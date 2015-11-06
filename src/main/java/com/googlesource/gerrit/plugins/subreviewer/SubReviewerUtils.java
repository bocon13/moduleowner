package com.googlesource.gerrit.plugins.subreviewer;

import autovalue.shaded.com.google.common.common.collect.Lists;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.config.PluginConfigFactory;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Collection of utilities.
 */
public class SubReviewerUtils {

    private static final String PLUGIN_NAME = "subreviewer";
    private static final String CONFIG_USER = "user";
    private static final String CONFIG_PATH = "path";

    private static final Logger log = LoggerFactory.getLogger(SubReviewerUtils.class);

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

    /**
     * Returns a list of file patterns that the user is allowed to submit.
     *
     * @param user
     * @param configFactory
     * @return list of file patterns
     */
    public static List<String> getFilesForUser(CurrentUser user,
                                               PluginConfigFactory configFactory) {
        // TODO verify "dynamic-submit" capability, bail early if not present
//        if (!submitter.getCapabilities().canAdministrateServer()) {
//            throw new MergeValidationException(CommitMergeStatus.PATH_CONFLICT);
//        }

        // FIXME move to project-based config (use destProject)
        Config config = configFactory.getGlobalPluginConfig(PLUGIN_NAME);
        return Arrays.asList(
                config.getStringList(CONFIG_USER, user.getUserName(), CONFIG_PATH));

    }

    /**
     * Ensures that every file matches at least one of the patterns.
     *
     * @param files list of files to check
     * @param patterns list of accepted patterns
     * @return true if every file matches, false otherwise
     */
    public static boolean isPatchApproved(List<String> files, List<String> patterns) {
        //TODO lower log levels in this function
        log.info("files: {}, patterns: {}", files, patterns);
        for (String file : files) {
            boolean match = false;
            for (String pattern : patterns) {
                if (Pattern.matches(pattern, file)) {
                    match = true;
                    break;
                }
            }
            if (!match) {
                log.info("file {} does not match any regex: {}", file, patterns);
                return false;
            }
        }
        log.info("patch approved");
        return true;
    }

    /**
     * Ensures that the supplied user has the file-based permissions to submit
     * the specified commit.
     *
     * @param commit
     * @param user
     * @param repository
     * @param configFactory
     * @return true if the user can submit, false otherwise
     */
    public static boolean isPatchApproved(RevCommit commit, CurrentUser user,
                                          Repository repository,
                                          PluginConfigFactory configFactory) {
        List<String> patterns = getFilesForUser(user, configFactory);
        if (patterns.isEmpty()) {
            return false;
        }
        List<String> files = getFilesInCommit(repository, commit);
        return isPatchApproved(files, patterns);
    }
}
