package com.googlesource.gerrit.plugins.moduleowner;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
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
import com.google.gerrit.server.events.DraftPublishedEvent;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.PatchSetCreatedEvent;
import com.google.gerrit.server.events.PatchSetEvent;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.index.change.ChangeIndexer;
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

import static com.googlesource.gerrit.plugins.moduleowner.ModuleOwnerConfig.CODE_REVIEW_LABEL;
import static com.googlesource.gerrit.plugins.moduleowner.ModuleOwnerConfig.MODULE_OWNER_LABEL;

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
            // New patch set available, automatically add module owners as reviewers
            PatchSetCreatedEvent psEvent = (PatchSetCreatedEvent) event;

            // Don't assign reviewers to drafts
            if (!psEvent.patchSet.get().isDraft) {
                addReviewers(psEvent);
                updateLabels(psEvent);
            }
        } else if (event instanceof DraftPublishedEvent) {
            addReviewers((PatchSetEvent) event);
            updateLabels((PatchSetEvent) event);
        } else if (event instanceof CommentAddedEvent) {
            // New review available, add owner label if appropriate
            updateLabels((CommentAddedEvent) event);
        }
        // else, dropping event
    }

    private void addReviewers(final PatchSetEvent event) {
        ModuleOwnerConfig config = moduleOwnerConfigCache.get(event.getProjectNameKey());
        if (config == null || !config.isEnabled() || config.getMaxReviewers() <= 0) {
            return;
        }

        Project.NameKey projectName = event.getProjectNameKey();
        try (Repository repo = repoManager.openRepository(projectName);
             ReviewDb reviewDb = schemaFactory.open()) {


            Change.Id changeId = new Change.Id(Integer.parseInt(event.change.get().number));
            final Change change = reviewDb.changes().get(changeId);
            if (change == null) {
                log.warn("Change {} not found.", changeId.get());
                return;
            }

            final RevWalk rw = new RevWalk(repo);
            final RevCommit commit =
                    rw.parseCommit(ObjectId.fromString(event.patchSet.get().revision));

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

    private void updateLabels(final PatchSetEvent event) {
        Project.NameKey projectName = event.getProjectNameKey();
        ModuleOwnerConfig config = moduleOwnerConfigCache.get(projectName);
        if (config == null || !config.isEnabled()) {
            return;
        }

        try (Repository repo = repoManager.openRepository(projectName);
             ReviewDb reviewDb = schemaFactory.open()) {
            Change.Id changeId = new Change.Id(Integer.parseInt(event.change.get().number));
            final Change change = reviewDb.changes().get(changeId);
            if (change == null) {
                log.warn("Change {} not found.", changeId.get());
                return;
            }

            PatchSet.Id psId = new PatchSet.Id(changeId, Integer.parseInt(event.patchSet.get().number));
            PatchSet patchSet = reviewDb.patchSets().get(psId);
            if (patchSet == null) {
                log.warn("Patch set " + psId.get() + " not found.");
                return;
            }

            final RevWalk rw = new RevWalk(repo);
            final RevCommit commit =
                    rw.parseCommit(ObjectId.fromString(event.patchSet.get().revision));

            syncModuleOwnerLabel(projectName, change, psId, commit,
                                 config, reviewDb, repo);
        } catch (OrmException | IOException e) {
            log.error("Exception while updating labels for change: {} in project: {}",
                      event.change.get().id, event.getProjectNameKey().get(), e);
        }
    }

    private void syncModuleOwnerLabel(Project.NameKey projectName,
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
        Multimap<Account.Id, PatchSetApproval> approvals = ArrayListMultimap.create();
        for (PatchSetApproval approval : existingApprovals) {
            //FIXME is this right?
            if (approval.isLegacySubmit()) {
                continue;
            }
            if (!approval.getPatchSetId().equals(psId)) {
                // old approval
                continue;
            }
            approvals.put(approval.getAccountId(), approval);
        }

        for (Account.Id account : approvals.keySet()) {
            PatchSetApproval existingModuleOwnerApproval = null;
            PatchSetApproval existingCodeReviewApproval = null;
            for (PatchSetApproval approval : approvals.get(account)) {
                if (CODE_REVIEW_LABEL.equals(approval.getLabel())) {
                    existingCodeReviewApproval = approval;
                } else if (MODULE_OWNER_LABEL.equals(approval.getLabel())) {
                    existingModuleOwnerApproval = approval;
                }
            }

            // Note: this is an optimization that bypasses isModuleOwner check
            if (existingCodeReviewApproval != null &&
                    existingModuleOwnerApproval != null &&
                    existingCodeReviewApproval.getValue() == existingModuleOwnerApproval.getValue()) {
                // If the MO and CR approvals match, we can skip the remaining...
                continue;
            }

            if (config.isModuleOwner(account, repo, commit)) {
                if (existingCodeReviewApproval != null && existingModuleOwnerApproval != null) {
                    if (existingCodeReviewApproval.getValue() != existingModuleOwnerApproval.getValue()) {
                        // Update module owner approval
                        existingModuleOwnerApproval.setValue(existingCodeReviewApproval.getValue());
                        existingModuleOwnerApproval.setGranted(TimeUtil.nowTs());
                        updatePatchSetApproval(reviewDb, projectName, change.getId(),
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
                    updatePatchSetApproval(reviewDb, projectName, change.getId(),
                                           moduleOwnerApproval, ChangeType.INSERT);

                } else if (existingModuleOwnerApproval != null) {
                    // Update module owner approval (no matching code review approval) to 0
                    // Note: Gerrit is probably using the module owner label to keep the user on the patchset,
                    //       thus it cannot be deleted.
                    existingModuleOwnerApproval.setValue((short) 0);
                    existingModuleOwnerApproval.setGranted(TimeUtil.nowTs());
                    updatePatchSetApproval(reviewDb, projectName, change.getId(),
                                           existingModuleOwnerApproval, ChangeType.UPDATE);
                } // else, nothing to be done
            } else if (existingModuleOwnerApproval != null) {
                // Delete module owner approval (not a module owner)
                log.info("REMOVING approval for non-module owner: {}", existingModuleOwnerApproval);
                updatePatchSetApproval(reviewDb, projectName, change.getId(),
                                       existingModuleOwnerApproval, ChangeType.DELETE);
                // If the module owner label was the only approval, inject CR +1
                // We need to ensure that at least one approval exists to keep user
                // on the review.
                if (approvals.get(account).size() == 1) {
                    PatchSetApproval codeReviewApproval = new PatchSetApproval(
                            new PatchSetApproval.Key(
                                    existingModuleOwnerApproval.getPatchSetId(),
                                    existingModuleOwnerApproval.getAccountId(),
                                    codeReviewLabel.getLabelId()),
                            (short) 0,
                            TimeUtil.nowTs());
                    log.info("INSERTING approval for non-module owner because last approval was removed: {}",
                             codeReviewApproval);
                    updatePatchSetApproval(reviewDb, projectName, change.getId(),
                                           codeReviewApproval, ChangeType.INSERT);
                }
            } // else, not module owner and no existing approval; nothing to do
        }
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

    private void updatePatchSetApproval(ReviewDb reviewDb,
                                        Project.NameKey project,
                                        Change.Id changeId,
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
        CheckedFuture<?, IOException> indexWrite = indexer.indexAsync(project, changeId);
        indexWrite.checkedGet();
    }

    private void runTaskOnWorkQueue(final Runnable task, final Change change) {
        workQueue.getDefaultQueue().submit(new Runnable() {
            @Override
            public void run() {
            RequestContext old = tl.setContext(new RequestContext() {

                @Override
                public CurrentUser getUser() {
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
