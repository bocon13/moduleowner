package com.googlesource.gerrit.plugins.subreviewer;

import com.google.gerrit.extensions.api.changes.AddReviewerInput;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.change.ChangesCollection;
import com.google.gerrit.server.change.PostReviewers;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.assistedinject.Assisted;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

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
    private final ModuleOwnerConfigCache configCache;

    public interface Factory {
        ReviewersByOwnership create(Project.NameKey projectName, RevCommit commit,
                                    Change change, Repository repo);
    }

    @Inject
    public ReviewersByOwnership(final ChangesCollection changes,
                                final Provider<PostReviewers> reviewersProvider,
                                final ModuleOwnerConfigCache configCache,
                                @Assisted final Project.NameKey projectName,
                                @Assisted final RevCommit commit,
                                @Assisted final Change change,
                                @Assisted final Repository repo) {
        this.changes = changes;
        this.reviewersProvider = reviewersProvider;
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
        Set<Account.Id> reviewers = config.getModuleOwners(repo, commit);
        addReviewers(reviewers, change);
    }

    /**
     * Append the reviewers to change#{@link Change}
     *
     * @param topReviewers Set of reviewers proposed
     * @param change {@link Change} to add the reviewers to
     */
    private void addReviewers(Set<Account.Id> topReviewers, Change change) {
        try {
            ChangeResource changeResource = changes.parse(change.getId());
            PostReviewers post = reviewersProvider.get();
            for (Account.Id accountId : topReviewers) {
                AddReviewerInput input = new AddReviewerInput();
                input.reviewer = accountId.toString();
                post.apply(changeResource, input);
            }
        } catch (Exception ex) {
            log.error("Couldn't add reviewers to the change", ex);
        }
    }
}
