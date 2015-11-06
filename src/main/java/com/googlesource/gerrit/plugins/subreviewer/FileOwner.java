package com.googlesource.gerrit.plugins.subreviewer;

import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.change.RevisionResource;
import com.google.gerrit.server.config.PluginConfigFactory;
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

import static com.googlesource.gerrit.plugins.subreviewer.SubReviewerUtils.isPatchApproved;

/**
 * REST endpoint for client script to determine whether the current user is a
 * module owner.
 */
//FIXME requries dynamic submit
public class FileOwner implements RestReadView<RevisionResource> {
    private static final Logger log = LoggerFactory.getLogger(FileOwner.class);

    private final GitRepositoryManager gitManager;
    private final Provider<CurrentUser> currentUserProvider;
    private final PatchListCache patchListCache;
    private final PluginConfigFactory configFactory;

    @Inject
    FileOwner(Provider<CurrentUser> currentUserProvider,
              GitRepositoryManager gitManager,
              PatchListCache patchListCache,
              PluginConfigFactory configFactory) {
        this.currentUserProvider = currentUserProvider;
        this.gitManager = gitManager;
        this.patchListCache = patchListCache;
        this.configFactory = configFactory;
    }

    @Override
    public Response<Status> apply(RevisionResource rev) {

        CurrentUser submitter = currentUserProvider.get();
        if (!(submitter instanceof IdentifiedUser)) {
            // user is not identified, bailing...
            return Response.ok(Status.NONE);
        }

        // TODO verify "dynamic-submit" capability, bail early if not present
        //      this can also be done in utils

        Change change = rev.getChange();
        try (Repository repo = gitManager.openRepository(change.getProject())) {
            RevWalk rw = new RevWalk(repo.newObjectReader());
            PatchList curList = patchListCache.get(rev.getChange(), rev.getPatchSet());
            if (isPatchApproved(rw.parseCommit(curList.getNewId()),
                                submitter, repo, configFactory)) {
                return Response.ok(Status.APPROVED);
            }
            return Response.ok(Status.DENIED);
        } catch (RepositoryNotFoundException e) {
            log.warn("Repo not found: {}", change.getProject(), e);
        } catch (IOException e) {
            log.warn("IO Exception trying to get repo", e);
        } catch (PatchListNotAvailableException e) {
            log.warn("Could not open revision", e);
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




