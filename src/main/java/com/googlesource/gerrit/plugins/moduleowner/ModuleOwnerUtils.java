package com.googlesource.gerrit.plugins.moduleowner;

import com.google.common.collect.Lists;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.AccountResolver;
import com.google.gerrit.server.data.AccountAttribute;
import com.google.gwtorm.server.OrmException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Collection of utilities.
 */
public class ModuleOwnerUtils {
    private static final Logger log = LoggerFactory.getLogger(ModuleOwnerUtils.class);

    /**
     * Returns the list of files changed in a specified commit.
     * Adapted from gitblit:
     * https://github.com/gitblit/gitblit/blob/master/src/main/java/com/gitblit/utils/JGitUtils.java#L718
     *
     * @param repository
     * @param commit
     * @return list of files changed in a commit
     */
    public static List<String> getFilesInCommit(Repository repository, RevCommit commit) {
        List<String> files = Lists.newArrayList();
        RevWalk rw = new RevWalk(repository);
        try {

            if (commit.getParentCount() == 0) {
                TreeWalk tw = new TreeWalk(repository);
                tw.reset();
                tw.setRecursive(true);
                tw.addTree(commit.getTree());
                while (tw.next()) {
                    files.add(tw.getPathString());
                }
                tw.close();
            } else {
                RevCommit parent = rw.parseCommit(commit.getParent(0).getId());

                // adapted from https://github.com/centic9/jgit-cookbook/blob/master/src/main/java/org/dstadler/jgit/porcelain/ShowChangedFilesBetweenCommits.java
                try (ObjectReader reader = repository.newObjectReader()) {
                    CanonicalTreeParser oldTreeIter = new CanonicalTreeParser();
                    oldTreeIter.reset(reader, parent.getTree());
                    CanonicalTreeParser newTreeIter = new CanonicalTreeParser();
                    newTreeIter.reset(reader, commit.getTree());

                    // finally get the list of changed files
                    try (Git git = new Git(repository)) {
                        List<DiffEntry> diffs = git.diff()
                                .setNewTree(newTreeIter)
                                .setOldTree(oldTreeIter)
                                .call();
                        for (DiffEntry entry : diffs) {
                            switch(entry.getChangeType()) {
                                case ADD:
                                case MODIFY:
                                    files.add(entry.getNewPath());
                                    break;
                                case DELETE:
                                    files.add(entry.getOldPath());
                                    break;
                                case RENAME:
                                case COPY:
                                default:
                                    files.add(entry.getOldPath());
                                    files.add(entry.getNewPath());
                                    break;
                            }
                        }
                    }
                } // end adapted
            }
        } catch (Throwable t) {
            log.error("{} failed to determine files in commit!", repository, t);
        } finally {
            rw.dispose();
        }
        return files;
    }

    public static Account getAccountFromAttribute(ReviewDb reviewDb,
                                                  AccountAttribute attribute,
                                                  AccountCache cache,
                                                  AccountResolver resolver) {
        //TODO this could be greatly simplified by adding Account.Id to AccountAttribute
        Account account = null;
        if (attribute.username != null) {
            account = cache.getByUsername(attribute.username).getAccount();
        }
        if (account == null) {
            try {
                if (attribute.email != null) {
                    account = resolver.findByNameOrEmail(reviewDb, attribute.email);
                }
                if (account == null && attribute.name != null) {
                    account = resolver.findByNameOrEmail(reviewDb, attribute.name);
                }
            } catch (OrmException e) {
                log.error("Exception processing user {}", attribute.name, e);
            }
        }
        return account;
    }
}
