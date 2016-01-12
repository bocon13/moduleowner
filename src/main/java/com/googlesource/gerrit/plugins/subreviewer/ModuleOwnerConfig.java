package com.googlesource.gerrit.plugins.subreviewer;

import com.google.common.base.MoreObjects;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gerrit.common.data.GroupDetail;
import com.google.gerrit.common.errors.NoSuchGroupException;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.AccountGroup;
import com.google.gerrit.reviewdb.client.AccountGroupMember;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.AccountLoader;
import com.google.gerrit.server.account.AccountResolver;
import com.google.gerrit.server.account.AccountState;
import com.google.gerrit.server.account.GroupCache;
import com.google.gerrit.server.account.GroupDetailFactory;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import static com.googlesource.gerrit.plugins.subreviewer.SubReviewerUtils.getFilesInCommit;

/**
 * Snapshot of the module owner configuration created at object construction time.
 */
public class ModuleOwnerConfig {
    private static final Logger log = LoggerFactory.getLogger(ModuleOwnerConfig.class);

    private static final String PLUGIN_NAME = "subreviewer";
    private static final String CONFIG_USER = "user";
    private static final String CONFIG_GROUP = "group";
    private static final String CONFIG_PATH = "path";

    public interface Factory {
        ModuleOwnerConfig create(Project.NameKey projectName);
    }

    private final Project.NameKey projectName;
    private final Map<Key, List<String>> idToPatterns = Maps.newHashMap();
    private final Map<String, Set<Key>> patternToId = Maps.newHashMap();
    private final List<String> allPatterns;
    private final int maxReviewers;

    private final PluginConfigFactory configFactory;
    private final AccountResolver accountResolver;
    private final AccountCache accountCache;
    private final GroupCache groupCache;
    private final GroupDetailFactory.Factory groupDetailFactory;
    private final AccountLoader.Factory accountLoader;

    @Inject
    ModuleOwnerConfig(PluginConfigFactory configFactory,
                             AccountResolver accountResolver,
                             AccountCache accountCache,
                             GroupCache groupCache,
                             GroupDetailFactory.Factory groupDetailFactory,
                             AccountLoader.Factory accountLoader,
                             @Assisted Project.NameKey projectName) {
        this.projectName = projectName;

        this.configFactory = configFactory;
        this.accountResolver = accountResolver;
        this.accountCache = accountCache;
        this.groupCache = groupCache;
        this.groupDetailFactory = groupDetailFactory;
        this.accountLoader = accountLoader;

        initConfig();

        allPatterns = Lists.newArrayList(patternToId.keySet());
        sortPatterns(allPatterns);

        maxReviewers = 3; //FIXME read from config
    }

    private void initConfig() {
        Config config = configFactory.getGlobalPluginConfig(PLUGIN_NAME);
        try {
            config = configFactory.getProjectPluginConfigWithInheritance(projectName, PLUGIN_NAME);
        } catch (NoSuchProjectException e) {
            log.warn("No such project {}", projectName, e);
        }

        for (String username : config.getSubsections(CONFIG_USER)) {
            Account account = accountCache.getByUsername(username).getAccount();
            if (account == null) {
                // Try to resolve account by name or email.
                try {
                    account = accountResolver.find(username);
                } catch (OrmException e) {
                    log.error("Exception processing user {}", username, e);
                }
            }
            if (account == null) {
                log.warn("Could not find account for: {}", username);
                continue;
            }

            List<String> pathPatterns = Lists.newArrayList(
                    config.getStringList(CONFIG_USER, username, CONFIG_PATH));
            addPatterns(Key.user(account.getId()), pathPatterns);
            log.info("Processing user: {} ({}) with patterns: {}",
                      username, account.getId(), pathPatterns);
        }

        for (String groupname : config.getSubsections(CONFIG_GROUP)) {
            AccountGroup group = groupCache.get(new AccountGroup.NameKey(groupname));
            if (group == null) {
                continue;
            }

            List<String> pathPatterns = Lists.newArrayList(
                    config.getStringList(CONFIG_GROUP, groupname, CONFIG_PATH));
            addPatterns(Key.group(group.getGroupUUID()), pathPatterns);
            log.info("Processing group: {} ({}) with patterns: {}",
                      groupname, group.getGroupUUID(), pathPatterns);
        }
    }

    private void addPatterns(Key key, List<String> patterns) {
        sortPatterns(patterns);
        idToPatterns.put(key, patterns);
        for (String p : patterns) {
            Set<Key> keys = patternToId.get(p);
            if (keys == null) {
                patternToId.put(p, Sets.newHashSet(key));
            } else {
                keys.add(key);
            }
        }
    }

    public int getMaxReviewers() {
        return maxReviewers;
    }

    public boolean isModuleOwner(Account.Id user, Repository repo, RevCommit commit) {
        List<String> files = getFilesInCommit(repo, commit);
        List<String> patterns = getEffectivePathPatternsForUser(user);
        boolean result = isPatchApproved(files, patterns);
        // TODO remove logs eventually
        if (result) {
            log.info("user {} is module owner for commit {}/{} with patterns {}",
                     accountCache.get(user).getUserName(), projectName.get(),
                     commit.getId().getName(), patterns);
        } else {
            log.info("user {} is not module owner for commit {}/{} with patterns {}",
                     accountCache.get(user).getUserName(), projectName.get(),
                     commit.getId().getName(), patterns);
        }
        return result;
    }

    public Set<Account.Id> getModuleOwners(Repository repo, RevCommit commit) {
        List<String> files = getFilesInCommit(repo, commit);

        Map<Key, Match> matchMap = Maps.newHashMap();

        for (String file : files) {
            for (String pattern : allPatterns) {
                if (Pattern.matches(pattern, file)) {
                    // found match
                    Set<Key> keys = patternToId.get(pattern);
                    for (Key k : keys) {
                        Match match = matchMap.get(k);
                        if (match == null) {
                            match = new Match(k);
                            matchMap.put(k, match);
                        }
                        match.addFile(pattern);
                    }
                    // TODO do we want to check all patterns or break here
            }
            }
        }

        return findTopReviewers(matchMap);
    }

    private Set<Account.Id> findTopReviewers(final Map<Key, Match> reviewers) {

        List<Map.Entry<Key, Match>> entries = Lists.newArrayList(reviewers.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<Key, Match>>() {
            @Override
            public int compare(Map.Entry<Key, Match> o1, Map.Entry<Key, Match> o2) {
                Match m1 = o1.getValue();
                Match m2 = o2.getValue();
                // reverse sort
                if (m1.fileCount == m2.fileCount) {
                    return m2.sumPatternLength - m1.sumPatternLength;
                }
                return m2.fileCount - m1.fileCount;
            }
        });
        log.info("reviewers: {}", entries);

        Set<Account.Id> topReviewers = Sets.newHashSet();
        for (Map.Entry<Key, Match> entry : entries) {
            Key key = entry.getKey();
            if (key.isUser()) {
                log.info("adding user: {}", key.user);
                topReviewers.add(key.user);
            } else {
                List<Account.Id> users = getUsersForGroup(key);
                for (Account.Id user : users) {
                    topReviewers.add(user);
                    log.info("adding user: {}", user);
                    if (topReviewers.size() >= maxReviewers) {
                        break;
                    }
                }
            }
            if (topReviewers.size() >= maxReviewers) {
                break;
            }
        }
        // TODO should we skip owner?

//        for (Account.Id id : ids) {
//            Account account = accountCache.get(id).getAccount();
//            if (account.isActive() && !change.getOwner().equals(account.getId())) {
//                Integer count = reviewers.get(account);
//                reviewers.put(account, count == null ? 1 : count.intValue() + 1);
//            }
//        }


        return topReviewers;
    }

    private List<Account.Id> getUsersForGroup(Key key) {
        AccountGroup.UUID groupUUID = key.group;

        AccountGroup group = groupCache.get(groupUUID);

        final GroupDetail groupDetail;
        try {
            groupDetail = groupDetailFactory.create(group.getId()).call();
        } catch (NoSuchGroupException e) {
            // the included group is not visible
            log.warn("Could not find group {}", group.getName(), e);
            return Collections.emptyList();
        } catch (OrmException e) {
            log.warn("OrmException reading group {}", group.getName(), e);
            //FIXME what to do?
            return Collections.emptyList();
        } catch (Exception e) {
            //FIXME remove
            log.warn("exception", e);
            return Collections.emptyList();
        }

        if (groupDetail.members != null) {
            List<Account.Id> users = Lists.newArrayListWithCapacity(
                    groupDetail.members.size());
            for (final AccountGroupMember m : groupDetail.members) {
                users.add(m.getAccountId());
            }
            // randomize the list order
            // FIXME use change-id or something as seed to avoid adding extra reviewers
            Collections.shuffle(users);
            return users;
        }
        return Collections.emptyList();
    }

    private List<String> getEffectivePathPatternsForUser(Account.Id user) {
        List<String> patterns = Lists.newArrayList();

        List<String> userSpecificPatterns = idToPatterns.get(Key.user(user));
        if (userSpecificPatterns != null) {
            patterns.addAll(userSpecificPatterns);
        }

        AccountState state = accountCache.get(user);
        if (state != null) {
            for (AccountGroup.UUID group : state.getInternalGroups()) {
                List<String> groupPatterns = idToPatterns.get(Key.group(group));
                if (groupPatterns != null) {
                    patterns.addAll(groupPatterns);
                }
            }
        }

        return patterns;
    }


    /**
     * Ensures that every file matches at least one of the patterns.
     *
     * @param files list of files to check
     * @param patterns list of accepted patterns
     * @return true if every file matches, false otherwise
     */
    private static boolean isPatchApproved(List<String> files, List<String> patterns) {
        log.debug("files: {}, patterns: {}", files, patterns);
        for (String file : files) {
            boolean match = false;
            for (String pattern : patterns) {
                if (Pattern.matches(pattern, file)) {
                    match = true;
                    break;
                }
            }
            if (!match) {
                log.debug("file {} does not match any regex: {}", file, patterns);
                return false;
            }
        }
        log.debug("patch approved");
        return true;
    }

    private static void sortPatterns(List<String> patterns) {
        // Sort from longest to shortest string
        Collections.sort(patterns, new Comparator<String>() {
            @Override
            public int compare(String o1, String o2) {
                return o2.length() - o1.length();
            }
        });
    }

    private static final class Key {
        final Account.Id user;
        final AccountGroup.UUID group;

        private Key(Account.Id user, AccountGroup.UUID group) {
            this.user = user;
            this.group = group;
        }

        static Key user(Account.Id user) {
            return new Key(user, null);
        }

        static Key group(AccountGroup.UUID group) {
            return new Key(null, group);
        }

        boolean isUser() {
            return this.user != null;
        }

        @Override
        public int hashCode() {
            return Objects.hash(user, group);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            final Key other = (Key) obj;
            return Objects.equals(this.user, other.user)
                    && Objects.equals(this.group, other.group);
        }

        @Override
        public String toString() {
            return isUser() ? "User:" + user.toString() : "Group:" + group.toString();
        }
    }

    private static final class Match {
        final Key key;

        int fileCount;
        int sumPatternLength;

        Match(Key key) {
            this.key = key;
        }

        void addFile(String pattern) {
            fileCount++;
            sumPatternLength += pattern.length();
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("key", key)
                    .add("fileCount", fileCount)
                    .add("sumPatternLength", sumPatternLength)
                    .toString();
        }
    }
}
