package com.googlesource.gerrit.plugins.subreviewer;

import com.google.gerrit.common.EventListener;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.events.CommentAddedEvent;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.PatchSetCreatedEvent;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.util.RequestContext;
import com.google.gerrit.server.util.ThreadLocalRequestContext;
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.SchemaFactory;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.ProvisionException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Listener for change events, specifically patch set created events.
 */
class ChangeEventListener implements EventListener {
    private static final Logger log = LoggerFactory
            .getLogger(ChangeEventListener.class);

    private final GitRepositoryManager repoManager;
    private final WorkQueue workQueue;
    private final IdentifiedUser.GenericFactory identifiedUserFactory;
    private final ThreadLocalRequestContext tl;
    private final SchemaFactory<ReviewDb> schemaFactory;
    private final ModuleOwnerConfigCache moduleOwnerConfigCache;
    private final ReviewersByOwnership.Factory reviewersFactory;
    private ReviewDb db;

    @Inject
    ChangeEventListener(
            final GitRepositoryManager repoManager,
            final WorkQueue workQueue,
            final IdentifiedUser.GenericFactory identifiedUserFactory,
            final ThreadLocalRequestContext tl,
            final SchemaFactory<ReviewDb> schemaFactory,
            final ModuleOwnerConfigCache moduleOwnerConfigCache,
            final ReviewersByOwnership.Factory reviewersFactory) {
        this.repoManager = repoManager;
        this.workQueue = workQueue;
        this.identifiedUserFactory = identifiedUserFactory;
        this.tl = tl;
        this.schemaFactory = schemaFactory;
        this.moduleOwnerConfigCache = moduleOwnerConfigCache;
        this.reviewersFactory = reviewersFactory;
    }

    @Override
    public void onEvent(Event event) {
        if (event instanceof PatchSetCreatedEvent) {
            // New patch set available, automatically add module owners as reviewers
            addReviewers((PatchSetCreatedEvent) event);
        } else if (event instanceof CommentAddedEvent) {
            // TODO New review available, add owner label if appropriate
            return;
        }
        // else, dropping event
    }

    void addReviewers(final PatchSetCreatedEvent event) {
        ModuleOwnerConfig config = moduleOwnerConfigCache.get(event.getProjectNameKey());
        if (config == null) {
            return;
        }

        int maxReviewers = config.getMaxReviewers();
        if (maxReviewers <= 0) {
            return;
        }

        Project.NameKey projectName = event.getProjectNameKey();
        try (Repository repo = repoManager.openRepository(projectName);
             ReviewDb reviewDb = schemaFactory.open()) {


            Change.Id changeId = new Change.Id(Integer.parseInt(event.change.number));
            final Change change = reviewDb.changes().get(changeId);
            if (change == null) {
                log.warn("Change {} not found.", changeId.get());
                return;
            }

            final RevWalk rw = new RevWalk(repo);
            final RevCommit commit =
                    rw.parseCommit(ObjectId.fromString(event.patchSet.revision));

            //TODO consider moving to RequestScopePropagator
            final Runnable task =
                    reviewersFactory.create(projectName, commit, change, repo);
            workQueue.getDefaultQueue().submit(new Runnable() {
                @Override
                public void run() {
                RequestContext old = tl.setContext(new RequestContext() {

                    @Override
                    public CurrentUser getCurrentUser() {
                        return identifiedUserFactory.create(change.getOwner());
                    }

                    @Override
                    public Provider<ReviewDb> getReviewDbProvider() {
                        return new Provider<ReviewDb>() {
                            @Override
                            public ReviewDb get() {
                                if (db == null) {
                                    try {
                                        db = schemaFactory.open();
                                    } catch (OrmException e) {
                                        throw new ProvisionException("Cannot open ReviewDb", e);
                                    }
                                }
                                return db;
                            }
                        };
                    }
                });
                try {
                    task.run();
                } finally {
                    tl.setContext(old);
                    if (db != null) {
                        db.close();
                        db = null;
                    }
                }
                }
            });

        } catch (RepositoryNotFoundException e) {
            log.warn("Repo not found: {}", projectName.get(), e);
        } catch (IOException e) {
            log.warn("IO Exception trying to get repo: {}", projectName.get(), e);
        } catch (OrmException e) {
            log.warn("OrmException for repo: {}", projectName.get(), e);
        }
    }
}
