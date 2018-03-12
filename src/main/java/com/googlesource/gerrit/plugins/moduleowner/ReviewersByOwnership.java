package com.googlesource.gerrit.plugins.moduleowner;

import com.google.gerrit.extensions.api.changes.AddReviewerInput;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.PatchSetApproval;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.change.ChangesCollection;
import com.google.gerrit.server.change.PostReviewers;
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.SchemaFactory;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.assistedinject.Assisted;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Computes the Module Owners for a patch set and assigns them as reviewers.
 */
public class ReviewersByOwnership implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ReviewersByOwnership.class);

    private final Project.NameKey projectName;
    private final RevCommit commit;
    private final Change change;
    private final Repository repo;

    private final Provider<PostReviewers> reviewersProvider;
    private final ChangesCollection changes;
    private final SchemaFactory<ReviewDb> schemaFactory;
    private final ModuleOwnerConfigCache configCache;


    public interface Factory {
        ReviewersByOwnership create(Project.NameKey projectName, RevCommit commit,
                                    Change change, Repository repo);
    }

    @Inject
    public ReviewersByOwnership(final ChangesCollection changes,
                                final Provider<PostReviewers> reviewersProvider,
                                final SchemaFactory<ReviewDb> schemaFactory,
                                final ModuleOwnerConfigCache configCache,
                                @Assisted final Project.NameKey projectName,
                                @Assisted final RevCommit commit,
                                @Assisted final Change change,
                                @Assisted final Repository repo) {
        this.changes = changes;
        this.reviewersProvider = reviewersProvider;
        this.schemaFactory = schemaFactory;
        this.configCache = configCache;

        this.projectName = projectName;
        this.commit = commit;
        this.change = change;
        this.repo = repo;
    }

    @Override
    public void run() {
        ModuleOwnerConfig config = configCache.get(projectName);
        if (config == null) {
            return;
        }
        List<Account.Id> moduleOwners = config.getModuleOwners(repo, commit, change);
        addReviewers(change, moduleOwners, config.getMaxReviewers());
    }

    /**
     * Append the reviewers to change#{@link Change}
     *
     * @param change {@link Change} to add the reviewers to
     * @param moduleOwners List of module owners, sorted by relevance
     * @param numReviewers number of module owners to assign as reviewers
     */
    private void addReviewers(Change change, List<Account.Id> moduleOwners, int numReviewers) {
        try {
            ChangeResource changeResource = changes.parse(change.getId());

            // scan existing reviewers for module owners
            /* FIXME notes does not appear to contain any reviewers
            Multimap<ReviewerState, Account.Id> existingReviewers = changeResource.getNotes().getReviewers();
            if (existingReviewers != null) {
                for (Map.Entry<ReviewerState, Account.Id> entry : existingReviewers.entries()) {
                    Account.Id reviewer = entry.getValue();
                    if (moduleOwners.contains(reviewer)) {
                        switch (entry.getKey()) {
                            case REVIEWER:
                            case CC:
                                numReviewers--;
                                break;
                            case REMOVED:
                            default:
                                // don't count removed reviewers, but still remove them
                                break;
                        }
                        moduleOwners.remove(reviewer);
                    }
                    if (numReviewers <= 0) {
                        return;
                    }
                }
            } ... alternate implementation follows: */
            try (ReviewDb reviewDb = schemaFactory.open()) {
                List<PatchSetApproval> existingApprovals =
                        reviewDb.patchSetApprovals().byChange(change.getId()).toList();
                for (PatchSetApproval approval : existingApprovals) {
                    Account.Id reviewer = approval.getAccountId();
                    if (moduleOwners.contains(reviewer)) {
                        numReviewers--;
                        moduleOwners.remove(reviewer);
                    }
                    if (numReviewers <= 0) {
                        log.debug("Already enough module owners assigned to change: {} in project: {}",
                                 change.getId(), change.getProject().get());
                        return;
                    }
                }
            } catch (OrmException e) {
                log.error("Exception while reading reviewers for change: {} in project: {}",
                          change.getId(), change.getProject().get(), e);
            }

            // select remaining module owners to be reviewers by relevance
            moduleOwners = moduleOwners.subList(0, numReviewers <= moduleOwners.size() ?
                                                    numReviewers : moduleOwners.size());

            // add module owners as reviewers
            PostReviewers post = reviewersProvider.get();
            for (Account.Id accountId : moduleOwners) {
                AddReviewerInput input = new AddReviewerInput();
                input.reviewer = accountId.toString();
                post.apply(changeResource, input);
            }
            log.debug("Adding reviewers to change {}: {}", change, moduleOwners);
        } catch (Exception ex) {
            log.error("Couldn't add reviewers to the change", ex);
        }
    }
}
