package com.googlesource.gerrit.plugins.subreviewer;

import autovalue.shaded.com.google.common.common.collect.Lists;
import com.google.gerrit.common.EventListener;
import com.google.gerrit.common.TimeUtil;
import com.google.gerrit.common.data.LabelType;
import com.google.gerrit.common.data.LabelTypes;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.PatchSetApproval;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.ApprovalsUtil;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.data.ApprovalAttribute;
import com.google.gerrit.server.events.CommentAddedEvent;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.PatchSetCreatedEvent;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.notedb.ChangeUpdate;
import com.google.gerrit.server.project.ChangeControl;
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
import java.util.List;
import java.util.Objects;

/**
 * Listener for change events, specifically patch set created events.
 */
class ChangeEventListener implements EventListener {
    private static final Logger log = LoggerFactory
            .getLogger(ChangeEventListener.class);

    private final GitRepositoryManager repoManager;
    private final WorkQueue workQueue;

    private final ChangeControl.GenericFactory changeControlFactory;
    private final IdentifiedUser.GenericFactory userFactory;
    private final ReviewersByOwnership.Factory reviewersFactory;
    private final ChangeUpdate.Factory changeFactory;

    private final ApprovalsUtil approvalsUtil;
    private final ThreadLocalRequestContext tl;
    private final SchemaFactory<ReviewDb> schemaFactory;

    private final ModuleOwnerConfigCache moduleOwnerConfigCache;
    private final ProjectCache projectCache;
    private ReviewDb db;

    @Inject
    ChangeEventListener(
            final GitRepositoryManager repoManager,
            final WorkQueue workQueue,
            final IdentifiedUser.GenericFactory userFactory,
            final ApprovalsUtil approvalsUtil,
            final ChangeControl.GenericFactory changeControlFactory,
            final ThreadLocalRequestContext tl,
            final SchemaFactory<ReviewDb> schemaFactory,
            final ModuleOwnerConfigCache moduleOwnerConfigCache,
            final ProjectCache projectCache,
            final ReviewersByOwnership.Factory reviewersFactory,
            final ChangeUpdate.Factory changeFactory) {
        this.repoManager = repoManager;
        this.workQueue = workQueue;
        this.userFactory = userFactory;
        this.approvalsUtil = approvalsUtil;
        this.changeControlFactory = changeControlFactory;
        this.tl = tl;
        this.schemaFactory = schemaFactory;
        this.moduleOwnerConfigCache = moduleOwnerConfigCache;
        this.reviewersFactory = reviewersFactory;
        this.projectCache = projectCache;
        this.changeFactory = changeFactory;
    }

    @Override
    public void onEvent(Event event) {
        if (event instanceof PatchSetCreatedEvent) {
            // New patch set available, automatically add module owners as reviewers
            addReviewers((PatchSetCreatedEvent) event);
        } else if (event instanceof CommentAddedEvent) {
            // TODO New review available, add owner label if appropriate
            updateLabels((CommentAddedEvent) event);
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
        log.info("comment {} by {} with {}", event.comment, event.author.name, event.approvals);
        if (event.approvals != null) {
            for (ApprovalAttribute a : event.approvals) {
                log.info("approval of type {} with desc {}/ value {}",
                         a.type, a.description, a.value);
            }
        }

        String user = null;
        if (event.author != null) {
            if (event.author.username != null) {
                user = event.author.username;
            } else if (event.author.email != null) {
                user = event.author.email;
            } else if (event.author.name != null) {
                user = event.author.name;
            }
        }

        // ^^^^^^ user filter

        ModuleOwnerConfig moduleOwnerConfig = moduleOwnerConfigCache.get(event.getProjectNameKey());

        try (Repository repo = repoManager.openRepository(event.getProjectNameKey());
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


            ProjectState projectState = projectCache.get(change.getProject());
            LabelTypes labelTypes = projectState.getLabelTypes();
            log.info("labels: {}", labelTypes);
            LabelType codeReviewLabel = labelTypes.byLabel("Code-Review");
            LabelType moduleOwnerLabel = labelTypes.byLabel("Module-Owner");

            List<PatchSetApproval> existingApprovals = reviewDb.patchSetApprovals()
                    .byChange(changeId).toList();
            List<PatchSetApproval> moduleOwnerApprovals = Lists.newArrayList();
            for (PatchSetApproval approval : existingApprovals) {
                if (approval.isSubmit()) {
                    continue;
                }

                // FIXME need to clear if CR == 0
                // FIXME run in the background???

                if (Objects.equals(codeReviewLabel.getName(), approval.getLabel()) &&
                    moduleOwnerConfig.isModuleOwner(approval.getAccountId(), repo, commit)) {

                    log.info("found approval:" + approval);
                    PatchSetApproval psa = new PatchSetApproval(
                            new PatchSetApproval.Key(
                                    approval.getPatchSetId(),
                                    approval.getAccountId(),
                                    labelTypes.byLabel("Module-Owner").getLabelId()),
                            approval.getValue(),
                            TimeUtil.nowTs());
                    moduleOwnerApprovals.add(psa);
                }
            }

            reviewDb.changes().beginTransaction(change.getId());
            try {
                // FIXME conditional update / delete
                reviewDb.patchSetApprovals().upsert(moduleOwnerApprovals);
                reviewDb.commit();
                log.info("success {}", moduleOwnerApprovals);
            } catch (Exception e) {
                log.warn("db write exception", e);
            } finally {
                reviewDb.rollback();
            }
        } catch (OrmException e) {
            log.warn("OrmException while updating labels", e);
        } catch (RepositoryNotFoundException e) {
            e.printStackTrace(); //FIXME
        } catch (IOException e) {
            e.printStackTrace(); //FIXME
        }
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
