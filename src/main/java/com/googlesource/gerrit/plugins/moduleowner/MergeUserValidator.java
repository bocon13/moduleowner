package com.googlesource.gerrit.plugins.moduleowner;

import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.git.CodeReviewCommit;
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

    private final Provider<ReviewDb> reviewDb;
    private final ModuleOwnerConfigCache configFactory;

    @Inject
    MergeUserValidator(Provider<ReviewDb> reviewDb,
                       ModuleOwnerConfigCache configFactory) {
        this.reviewDb = reviewDb;
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
        ModuleOwnerConfig config = configFactory.get(destProject.getProject().getNameKey());
        if (config != null && config.isEnabled() &&
                !config.isModuleOwner(caller.getAccountId(), repo, commit)) {
            throw new MergeValidationException(DENY_STATUS);
        }

        log.debug("user {} submitted commit {}/{}",
                 caller.getUserName(),
                 destProject.getProject().getNameKey(),
                 commit.getId().getName());
    }
}

