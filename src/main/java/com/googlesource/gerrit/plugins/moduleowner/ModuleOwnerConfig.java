package com.googlesource.gerrit.plugins.moduleowner;

import com.google.common.base.MoreObjects;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.gerrit.common.data.LabelTypes;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.AccountGroup;
import com.google.gerrit.reviewdb.client.AccountGroupMember;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.AccountLoader;
import com.google.gerrit.server.account.AccountResolver;
import com.google.gerrit.server.account.AccountState;
import com.google.gerrit.server.account.GroupCache;
import com.google.gerrit.server.account.GroupDetailFactory;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.SchemaFactory;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import static com.googlesource.gerrit.plugins.moduleowner.ModuleOwnerUtils.getFilesInCommit;

/**
 * Snapshot of the module owner configuration created at object construction time.
 */
public class ModuleOwnerConfig {
    private static final Logger log = LoggerFactory.getLogger(ModuleOwnerConfig.class);

    public static final String CODE_REVIEW_LABEL = "Code-Review";
    public static final String MODULE_OWNER_LABEL = "Module-Owner";
    private static final String PLUGIN_NAME = "moduleowner";
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
    private final boolean enabled;

    private final PluginConfigFactory configFactory;
    private final AccountResolver accountResolver;
    private final AccountCache accountCache;
    private final GroupCache groupCache;
    private final ProjectCache projectCache;
    private final GroupDetailFactory.Factory groupDetailFactory;
    private final AccountLoader.Factory accountLoader;
    private final SchemaFactory<ReviewDb> schemaFactory;


    @Inject
    ModuleOwnerConfig(PluginConfigFactory configFactory,
                      AccountResolver accountResolver,
                      AccountCache accountCache,
                      GroupCache groupCache,
                      ProjectCache projectCache,
                      GroupDetailFactory.Factory groupDetailFactory,
                      AccountLoader.Factory accountLoader,
                      SchemaFactory<ReviewDb> schemaFactory,
                      @Assisted Project.NameKey projectName) {
        this.projectName = projectName;
        log.info("Initializing module owner config for {}", projectName);

        this.configFactory = configFactory;
        this.accountResolver = accountResolver;
        this.accountCache = accountCache;
        this.groupCache = groupCache;
        this.projectCache = projectCache;
        this.groupDetailFactory = groupDetailFactory;
        this.accountLoader = accountLoader;
        this.schemaFactory = schemaFactory;

        initConfig();

        allPatterns = Lists.newArrayList(patternToId.keySet());
        sortPatterns(allPatterns);

        maxReviewers = 2; // TODO make this configurable
        enabled = checkEnabled();
    }

    private void initConfig() {
        Config config = configFactory.getGlobalPluginConfig(PLUGIN_NAME);
        try {
            config = configFactory.getProjectPluginConfigWithInheritance(projectName, PLUGIN_NAME);
        } catch (NoSuchProjectException e) {
            log.error("No such project {}", projectName, e);
        }

        for (String username : config.getSubsections(CONFIG_USER)) {
            Account account = getAccountFromName(username);
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

    private boolean checkEnabled() {
        ProjectState projectState = projectCache.get(projectName);
        LabelTypes labelTypes = projectState.getLabelTypes();
        log.info("Labels: {}", labelTypes);
        return labelTypes.byLabel(MODULE_OWNER_LABEL) != null;
    }

    private Account getAccountFromName(String name) {
        Account account = accountCache.getByUsername(name) == null ? 
                null : accountCache.getByUsername(name).getAccount();
        if (account == null) {
            // Try to resolve account by name or email.
            try {
                account = accountResolver.find(schemaFactory.open(), name);
            } catch (OrmException e) {
                log.error("Exception processing user {}", name, e);
            }
        }
        return account;
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

    public boolean isEnabled() {
        return enabled;
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

    public List<Account.Id> getModuleOwners(Repository repo, RevCommit commit,
                                           Change change) {
        List<String> files = getFilesInCommit(repo, commit);

        Map<Account.Id, Match> matchMap = getReviewersMap(files);
        filterMatches(matchMap, change);
        return sortReviewersByRelevance(matchMap, this.maxReviewers);
    }

    private Map<Account.Id, Match> getReviewersMap(List<String> files) {
        Map<Account.Id, Match> userToMatch = Maps.newHashMap();
        try (ReviewDb db = schemaFactory.open()){
            GroupDetailSnapshot groupToUser = new GroupDetailSnapshot(db);

            for (String file : files) {
                Map<Account.Id, String> patternMap = Maps.newHashMap();
                for (String pattern : allPatterns) {
                    if (Pattern.matches(pattern, file)) {
                        // found match
                        Set<Key> keys = patternToId.get(pattern);
                        for (Key k : keys) {
                            if (k.isUser() && !patternMap.containsKey(k.user)) {
                                patternMap.put(k.user, pattern);
                            } else {
                                for (Account.Id user : groupToUser.getUsers(k.group)) {
                                    if (!patternMap.containsKey(k.user)) {
                                        patternMap.put(user, pattern);
                                    }
                                }
                            }
                        }
                        // TODO do we want to check all patterns or break here?
                    }
                }
                for (Map.Entry<Account.Id, String> entry : patternMap.entrySet()) {
                    Match match = userToMatch.get(entry.getKey());
                    if (match == null) {
                        match = new Match(entry.getKey());
                        userToMatch.put(entry.getKey(), match);
                    }
                    match.addFile(entry.getValue());
                }
            }
        } catch (OrmException e) {
            log.error("Error getting reviewers for project {}", projectName, e);
        }

        return userToMatch;
    }

    private void filterMatches(Map<Account.Id, Match> matchMap, Change change) {
        if (change != null) {
            // remove the owner from the list of candidates
            matchMap.remove(change.getOwner());
        }

        // remove an inactive accounts from the list of candidates
        for(Iterator<Map.Entry<Account.Id, Match>> it = matchMap.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Account.Id, Match> entry = it.next();
            Account account = accountCache.get(entry.getKey()).getAccount();
            if (!account.isActive()) {
                it.remove();
            } else if (account.getStatus().equals("inactive")) { //FIXME
                log.info("skipping inactive user: {}", account.getFullName());
                it.remove();
            } else if (entry.getValue().sumPatternLength == 0) {
                log.info("skipping only super module owner matches for user: {}", account.getFullName());
                it.remove();
            }
        }
    }

    private static final Comparator<Map.Entry<Account.Id, Match>> MATCH_COMPARE =
            new Comparator<Map.Entry<Account.Id, Match>>() {
        @Override
        public int compare(Map.Entry<Account.Id, Match> o1, Map.Entry<Account.Id, Match> o2) {
            Match m1 = o1.getValue();
            Match m2 = o2.getValue();
            // reverse sort (high to low)
            if (m1.fileCount == m2.fileCount) {
                return m2.sumPatternLength - m1.sumPatternLength;
            }
            return m2.fileCount - m1.fileCount;
        }
    };

    private List<Account.Id> sortReviewersByRelevance(final Map<Account.Id, Match> reviewers, int max) {
        List<Map.Entry<Account.Id, Match>> entries =
                Ordering.from(MATCH_COMPARE).sortedCopy(reviewers.entrySet());

        if (entries.size() == 0) {
            return Collections.emptyList();
        }
        List<Account.Id> sortedReviewers = Lists.newArrayList();

        // randomize equivalent entries
        Map.Entry<Account.Id, Match> prev = entries.get(0);
        int start = 0;
        for (int i = 1; i < entries.size(); i++) {
            Map.Entry<Account.Id, Match> curr = entries.get(i);
            if (MATCH_COMPARE.compare(prev, curr) != 0) {
                shuffleAndAdd(entries.subList(start, i), sortedReviewers);
                start = i;
            }
            prev = curr;
        }
        shuffleAndAdd(entries.subList(start, entries.size()), sortedReviewers);

        return sortedReviewers;
    }

    private void shuffleAndAdd(List<Map.Entry<Account.Id, Match>> subList, List<Account.Id> destList) {
        if (subList.size() == 1) {
            destList.add(subList.get(0).getKey());
        } else {
            Collections.shuffle(subList);
            for (Map.Entry<Account.Id, Match> e : subList) {
                destList.add(e.getKey());
            }
        }
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

    public Map<Account, List<String>> getPatternMap() {
        Map<Account.Id, List<String>> idMap = Maps.newHashMap();

        try (ReviewDb db = schemaFactory.open()) {
            GroupDetailSnapshot groupToUser = new GroupDetailSnapshot(db);

            for (Map.Entry<Key, List<String>> entry : idToPatterns.entrySet()) {
                Key key = entry.getKey();
                if (key.isUser()) {
                    List<String> existingPatterns = idMap.get(key.user);
                    if (existingPatterns == null) {
                        existingPatterns = Lists.newArrayList(entry.getValue());
                        idMap.put(key.user, existingPatterns);
                    } else {
                        existingPatterns.addAll(entry.getValue());
                    }
                } else {
                    for (Account.Id user : groupToUser.getUsers(key.group)) {
                        List<String> existingPatterns = idMap.get(user);
                        if (existingPatterns == null) {
                            existingPatterns = Lists.newArrayList(entry.getValue());
                            idMap.put(user, existingPatterns);
                        } else {
                            existingPatterns.addAll(entry.getValue());
                        }
                    }
                }
            }
        } catch (OrmException e) {
            log.error("Error getting user to pattern map for project {}", projectName, e);
        }

        Map<Account, List<String>> userMap = Maps.newHashMapWithExpectedSize(idMap.size());
        for (Map.Entry<Account.Id, List<String>> entry : idMap.entrySet()) {
            // TODO remove duplicates in a better way
            List<String> patterns = Lists.newArrayList(Sets.newHashSet(entry.getValue()));
            userMap.put(accountCache.get(entry.getKey()).getAccount(), patterns);
        }
        return userMap;
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
        final Account.Id user;

        int fileCount;
        int sumPatternLength;

        Match(Account.Id user) {
            this.user = user;
        }

        void addFile(String pattern) {
            fileCount++;
            if (!".*".equals(pattern)) {
                // Don't count super module owner (".*") pattern length
                sumPatternLength += pattern.length();
            }
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("user", user)
                    .add("fileCount", fileCount)
                    .add("sumPatternLength", sumPatternLength)
                    .toString();
        }
    }

    private final class GroupDetailSnapshot {
        private final Map<AccountGroup.UUID, List<Account.Id>> groupToUser = Maps.newHashMap();
        private final ReviewDb db;

        GroupDetailSnapshot(ReviewDb db) {
            this.db = db;
        }

        List<Account.Id> getUsers(AccountGroup.UUID groupUUID) {
            List<Account.Id> users = groupToUser.get(groupUUID);
            if (users == null) {
                users = getUsersForSnapshot(groupUUID);
                groupToUser.put(groupUUID, users);
            }
            return users;
        }

        private List<Account.Id> getUsersForSnapshot(AccountGroup.UUID groupUUID) {
            AccountGroup group = groupCache.get(groupUUID);

            // Don't use groupDetailFactory because it only show current user's visible groups
            try {
                List<AccountGroupMember> members = db.accountGroupMembers().byGroup(group.getId()).toList();
                List<Account.Id> accountIds = Lists.newArrayListWithCapacity(members.size());
                for (AccountGroupMember member : members) {
                    accountIds.add(member.getAccountId());
                }
                Collections.shuffle(accountIds);
                log.debug("Loaded group {} with {}", group.getName(), accountIds);
                return accountIds;
            } catch (OrmException e) {
                log.error("Error loading group {}", group.getName(), e);
                return Collections.emptyList();
            }
        }

    }
}
