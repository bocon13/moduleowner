package com.googlesource.gerrit.plugins.moduleowner;

import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.change.RevisionResource;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.patch.PatchList;
import com.google.gerrit.server.patch.PatchListCache;
import com.google.gerrit.server.patch.PatchListNotAvailableException;
import com.google.inject.Inject;
import com.google.inject.Provider;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * REST endpoint for client script to determine whether the current user is a
 * module owner.
 */
public class GetModuleOwner implements RestReadView<RevisionResource> {
    private static final Logger log = LoggerFactory.getLogger(GetModuleOwner.class);

    private final GitRepositoryManager gitManager;
    private final Provider<CurrentUser> currentUserProvider;
    private final PatchListCache patchListCache;
    private final ModuleOwnerConfigCache configCache;

    @Inject
    GetModuleOwner(Provider<CurrentUser> currentUserProvider,
                   GitRepositoryManager gitManager,
                   PatchListCache patchListCache,
                   ModuleOwnerConfigCache configCache) {
        this.currentUserProvider = currentUserProvider;
        this.gitManager = gitManager;
        this.patchListCache = patchListCache;
        this.configCache = configCache;
    }

    @Override
    public Response<Status> apply(RevisionResource rev) {

        CurrentUser submitter = currentUserProvider.get();
        if (!(submitter instanceof IdentifiedUser)) {
            // user is not identified, bailing...
            return Response.ok(Status.NONE);
        }
        IdentifiedUser submittingUser = (IdentifiedUser) submitter;

        /*
           FIXME consider using "dynamic-submit" capability instead because
              if the plugin is disabled, then module owners will be able to
              submit any change.
        */
        if (!rev.getControl().canSubmit()) {
            return Response.ok(Status.NONE);
        }

        Change change = rev.getChange();
        // Don't display status for DRAFT, MERGED, SUBMITTED, or ABANDONED changes
        if (change.getStatus() != Change.Status.NEW) {
            return Response.ok(Status.NONE);
        }

        ModuleOwnerConfig config = configCache.get(change.getProject());
        if (!config.isEnabled()) {
            return Response.ok(Status.NONE);
        }

        try (Repository repo = gitManager.openRepository(change.getProject())) {
            RevWalk rw = new RevWalk(repo.newObjectReader());
            PatchList curList = patchListCache.get(rev.getChange(), rev.getPatchSet());
            if (config.isModuleOwner(submittingUser.getAccountId(), repo,
                                     rw.parseCommit(curList.getNewId()))) {
                return Response.ok(Status.APPROVED);
            } else {
                return Response.ok(Status.DENIED);
            }
        } catch (RepositoryNotFoundException e) {
            log.error("Repo not found: {}", change.getProject(), e);
        } catch (IOException e) {
            log.error("IO Exception trying to get repo", e);
        } catch (PatchListNotAvailableException e) {
            log.error("Could not open revision", e);
        }
        return Response.ok(Status.NONE);
    }

    // TODO consider improving protocol for client communication
    enum Status {
        APPROVED,
        DENIED,
        NONE
    }
}
