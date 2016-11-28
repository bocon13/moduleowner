package com.googlesource.gerrit.plugins.moduleowner;

import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.PatchSetApproval;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.ApprovalsUtil;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.git.CodeReviewCommit;
import com.google.gerrit.server.git.strategy.CommitMergeStatus;
import com.google.gerrit.server.git.validators.MergeValidationException;
import com.google.gerrit.server.git.validators.MergeValidationListener;
import com.google.gerrit.server.project.ProjectState;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Merge validator that rejects merges (submits) if the user does not have the
 * appropriate file-based permissions.
 */
@Singleton
public class MergeUserValidator implements MergeValidationListener {

    //TODO create a better status (or pick a better one)
    private static final String DENY_STATUS =
            "Submit blocked due to missing module owner.";

    private static final Logger log =
            LoggerFactory.getLogger(MergeUserValidator.class);

    private final IdentifiedUser.GenericFactory identifiedUserFactory;
    private final Provider<ReviewDb> reviewDb;
    private final ApprovalsUtil approvalsUtil;
    private final ModuleOwnerConfigCache configFactory;

    // Because there is 'No user on merge thread' we need to get the
    // identified user from IdentifiedUser.GenericFactory, this is
    // normally not needed and you can, in many cases, just use
    // Provider<CurrentUser>, unfortunately not this one.
    @Inject
    MergeUserValidator(IdentifiedUser.GenericFactory identifiedUserFactory,
                       Provider<ReviewDb> reviewDb,
                       ApprovalsUtil approvalsUtil,
                       ModuleOwnerConfigCache configFactory) {
        this.identifiedUserFactory = identifiedUserFactory;
        this.reviewDb = reviewDb;
        this.approvalsUtil = approvalsUtil;
        this.configFactory = configFactory;
    }

    /**
     * Reject merges if the submitter does not have the appropriate file permissions.
     */
    @Override
    public void onPreMerge(Repository repo, CodeReviewCommit commit,
                           ProjectState destProject, Branch.NameKey destBranch,
                           PatchSet.Id patchSetId, IdentifiedUser caller)
            throws MergeValidationException {
        PatchSetApproval psa =
                approvalsUtil.getSubmitter(reviewDb.get(), commit.notes(), patchSetId);
        if (psa == null) {
            throw new MergeValidationException("no patchset found");
        }

        IdentifiedUser submitter =
                identifiedUserFactory.create(psa.getAccountId());

        ModuleOwnerConfig config = configFactory.get(destProject.getProject().getNameKey());
        if (config != null && config.isEnabled() &&
                !config.isModuleOwner(submitter.getAccountId(), repo, commit)) {
            throw new MergeValidationException(DENY_STATUS);
        }

        log.info("user {} submitted commit {}/{}",
                 submitter.getUserName(),
                 destProject.getProject().getNameKey(),
                 commit.getId().getName());
    }
}

