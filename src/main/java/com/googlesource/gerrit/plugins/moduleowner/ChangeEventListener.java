package com.googlesource.gerrit.plugins.moduleowner;

import com.google.common.util.concurrent.CheckedFuture;
import com.google.gerrit.common.EventListener;
import com.google.gerrit.common.TimeUtil;
import com.google.gerrit.common.data.LabelType;
import com.google.gerrit.common.data.LabelTypes;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.PatchSetApproval;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.AccountResolver;
import com.google.gerrit.server.events.CommentAddedEvent;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.PatchSetCreatedEvent;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.index.ChangeIndexer;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
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
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static com.googlesource.gerrit.plugins.moduleowner.ModuleOwnerConfig.CODE_REVIEW_LABEL;
import static com.googlesource.gerrit.plugins.moduleowner.ModuleOwnerConfig.MODULE_OWNER_LABEL;
import static com.googlesource.gerrit.plugins.moduleowner.ModuleOwnerUtils.getAccountFromAttribute;

/**
 * Listener for change events, specifically patch set created events.
 */
class ChangeEventListener implements EventListener {
    private static final Logger log = LoggerFactory
            .getLogger(ChangeEventListener.class);

    private final GitRepositoryManager repoManager;
    private final WorkQueue workQueue;

    private final IdentifiedUser.GenericFactory userFactory;
    private final ReviewersByOwnership.Factory reviewersFactory;

    private final ChangeIndexer indexer;
    private final ThreadLocalRequestContext tl;
    private final SchemaFactory<ReviewDb> schemaFactory;
    private final AccountCache accountCache;
    private final AccountResolver accountResolver;

    private final ModuleOwnerConfigCache moduleOwnerConfigCache;
    private final ProjectCache projectCache;
    private ReviewDb db;

    @Inject
    ChangeEventListener(
            final GitRepositoryManager repoManager,
            final WorkQueue workQueue,
            final IdentifiedUser.GenericFactory userFactory,
            final ChangeIndexer indexer,
            final ThreadLocalRequestContext tl,
            final AccountCache accountCache,
            final AccountResolver accountResolver,
            final SchemaFactory<ReviewDb> schemaFactory,
            final ModuleOwnerConfigCache moduleOwnerConfigCache,
            final ProjectCache projectCache,
            final ReviewersByOwnership.Factory reviewersFactory) {
        this.repoManager = repoManager;
        this.workQueue = workQueue;
        this.userFactory = userFactory;
        this.accountCache = accountCache;
        this.accountResolver = accountResolver;
        this.indexer = indexer;
        this.tl = tl;
        this.schemaFactory = schemaFactory;
        this.moduleOwnerConfigCache = moduleOwnerConfigCache;
        this.reviewersFactory = reviewersFactory;
        this.projectCache = projectCache;
    }

    @Override
    public void onEvent(Event event) {
        //FIXME skip changes on refs/meta/config

        if (event instanceof PatchSetCreatedEvent) {
            // TODO filter drafts
            // New patch set available, automatically add module owners as reviewers
            addReviewers((PatchSetCreatedEvent) event);
            // TODO update labels
            // TODO add DraftPublished listener
        } else if (event instanceof CommentAddedEvent) {
            // New review available, add owner label if appropriate
            updateLabels((CommentAddedEvent) event);
        }
        // else, dropping event
    }

    void addReviewers(final PatchSetCreatedEvent event) {
        ModuleOwnerConfig config = moduleOwnerConfigCache.get(event.getProjectNameKey());
        if (config == null || !config.isEnabled() || config.getMaxReviewers() <= 0) {
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
            runTaskOnWorkQueue(reviewersFactory.create(projectName, commit, change, repo),
                               change);

        } catch (RepositoryNotFoundException e) {
            log.warn("Repo not found: {}", projectName.get(), e);
        } catch (IOException e) {
            log.warn("IO Exception trying to get repo: {}", projectName.get(), e);
        } catch (OrmException e) {
            log.warn("OrmException while adding reviewers for: {}", projectName.get(), e);
        }
    }

    private void updateLabels(CommentAddedEvent event) {
        Project.NameKey projectName = event.getProjectNameKey();
        ModuleOwnerConfig config = moduleOwnerConfigCache.get(projectName);
        if (config == null || !config.isEnabled()) {
            return;
        }

        Account account = getAccountFromAttribute(event.author, accountCache, accountResolver);
        if (account == null && event.author != null) {
            log.warn("Could not find account for user: {} ({})",
                     event.author.name, event.author.email);
            return;
        }

        try (Repository repo = repoManager.openRepository(projectName);
             ReviewDb reviewDb = schemaFactory.open()) {
            Change.Id changeId = new Change.Id(Integer.parseInt(event.change.number));
            final Change change = reviewDb.changes().get(changeId);
            if (change == null) {
                log.warn("Change {} not found.", changeId.get());
                return;
            }

            PatchSet.Id psId = new PatchSet.Id(changeId, Integer.parseInt(event.patchSet.number));
            PatchSet patchSet = reviewDb.patchSets().get(psId);
            if (patchSet == null) {
                log.warn("Patch set " + psId.get() + " not found.");
                return;
            }

            final RevWalk rw = new RevWalk(repo);
            final RevCommit commit =
                    rw.parseCommit(ObjectId.fromString(event.patchSet.revision));

            syncModuleOwnerLabel(projectName, account, change, psId, commit,
                                 config, reviewDb, repo);
        } catch (OrmException | IOException e) {
            log.error("Exception while updating lables for change: {} in project: {}",
                      event.change.id, event.getProjectNameKey().get(), e);
        }
    }

    private void syncModuleOwnerLabel(Project.NameKey projectName, Account user,
                                      Change change, PatchSet.Id psId, RevCommit commit,
                                      ModuleOwnerConfig config,
                                      ReviewDb reviewDb, Repository repo)
            throws OrmException, IOException {
        // FIXME run in the background???
        ProjectState projectState = projectCache.get(projectName);
        LabelTypes labelTypes = projectState.getLabelTypes();
        LabelType codeReviewLabel = labelTypes.byLabel(CODE_REVIEW_LABEL);
        LabelType moduleOwnerLabel = labelTypes.byLabel(MODULE_OWNER_LABEL);
        if (codeReviewLabel == null || moduleOwnerLabel == null) {
            return;
        }

        List<PatchSetApproval> existingApprovals =
                reviewDb.patchSetApprovals().byChange(change.getId()).toList();
        PatchSetApproval existingCodeReviewApproval = null;
        PatchSetApproval existingModuleOwnerApproval = null;
        for (PatchSetApproval approval : existingApprovals) {
            if (approval.isSubmit()) {
                continue;
            }
            if (!approval.getAccountId().equals(user.getId())) {
                continue;
            }
            if (!approval.getPatchSetId().equals(psId)) {
                // old approval
                continue;
            }
            if (Objects.equals(approval.getLabel(), codeReviewLabel.getName())) {
                existingCodeReviewApproval = approval;
            } else if (Objects.equals(approval.getLabel(), moduleOwnerLabel.getName())) {
                existingModuleOwnerApproval = approval;
            }
        }

        if (config.isModuleOwner(user.getId(), repo, commit)) {
            if (existingCodeReviewApproval != null && existingModuleOwnerApproval != null) {
                if (existingCodeReviewApproval.getValue() != existingModuleOwnerApproval.getValue()) {
                    // Update module owner approval
                    existingModuleOwnerApproval.setValue(existingCodeReviewApproval.getValue());
                    existingModuleOwnerApproval.setGranted(TimeUtil.nowTs());
                    updatePatchSetApproval(reviewDb, change.getId(),
                                           existingModuleOwnerApproval, ChangeType.UPDATE);

                } // else, nothing to be done; module owner approval is up to date
            } else if (existingCodeReviewApproval != null) {
                // Create module owner approval
                PatchSetApproval moduleOwnerApproval = new PatchSetApproval(
                        new PatchSetApproval.Key(
                                existingCodeReviewApproval.getPatchSetId(),
                                existingCodeReviewApproval.getAccountId(),
                                moduleOwnerLabel.getLabelId()),
                        existingCodeReviewApproval.getValue(),
                        TimeUtil.nowTs());
                updatePatchSetApproval(reviewDb, change.getId(),
                                       moduleOwnerApproval, ChangeType.INSERT);

            } else if (existingModuleOwnerApproval != null) {
                // Delete module owner approval (no matching code review approval)
                updatePatchSetApproval(reviewDb, change.getId(),
                                       existingModuleOwnerApproval, ChangeType.DELETE);

            } // else, nothing to be done
        } else if (existingModuleOwnerApproval != null) {
            // Delete module owner approval (not a module owner)
            updatePatchSetApproval(reviewDb, change.getId(),
                                   existingModuleOwnerApproval, ChangeType.DELETE);
        } // else, not module owner and no existing approval; nothing to do
    }

    private enum ChangeType {
        INSERT {
            @Override
            void apply(ReviewDb reviewDb, PatchSetApproval approval)
                    throws OrmException{
                reviewDb.patchSetApprovals().insert(Collections.singleton(approval));
            }
        },
        UPDATE {
            @Override
            void apply(ReviewDb reviewDb, PatchSetApproval approval)
                    throws OrmException{
                reviewDb.patchSetApprovals().update(Collections.singleton(approval));
            }
        },
        DELETE {
            @Override
            void apply(ReviewDb reviewDb, PatchSetApproval approval)
                    throws OrmException{
                reviewDb.patchSetApprovals().delete(Collections.singleton(approval));
            }
        };

        abstract void apply(ReviewDb reviewDb, PatchSetApproval approval)
                throws OrmException;
    }

    private void updatePatchSetApproval(ReviewDb reviewDb, Change.Id changeId,
                                        PatchSetApproval approval, ChangeType type)
            throws OrmException, IOException {
        reviewDb.changes().beginTransaction(changeId);
        try {
            type.apply(reviewDb, approval);
            reviewDb.commit();
            log.info("Change in module owner approval: {} {}", type, approval);
        } catch (Exception e) {
            log.error("Exception adding reviewer to change {}", changeId, e);
        } finally {
            reviewDb.rollback();
        }
        CheckedFuture<?, IOException> indexWrite = indexer.indexAsync(changeId);
        indexWrite.checkedGet();
    }

    private void runTaskOnWorkQueue(final Runnable task, final Change change) {
        workQueue.getDefaultQueue().submit(new Runnable() {
            @Override
            public void run() {
            RequestContext old = tl.setContext(new RequestContext() {

                @Override
                public CurrentUser getCurrentUser() {
                    return userFactory.create(change.getOwner());
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
    }
}
