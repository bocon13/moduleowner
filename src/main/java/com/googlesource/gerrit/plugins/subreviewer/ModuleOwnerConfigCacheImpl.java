package com.googlesource.gerrit.plugins.subreviewer;

import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.RefNames;
import com.google.gerrit.server.cache.CacheModule;
import com.google.inject.Inject;
import com.google.inject.Module;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutionException;

/**
 * Cache ModuleOwnerConfig to avoid recomputations.
 */
@Singleton
public class ModuleOwnerConfigCacheImpl implements ModuleOwnerConfigCache {
    private static final Logger log =
            LoggerFactory.getLogger(ModuleOwnerConfigCacheImpl.class);

    private static final String BYID_NAME = "moduleowner";

    public static Module module() {
        return new CacheModule() {
            @Override
            protected void configure() {
            cache(BYID_NAME, Project.NameKey.class, ModuleOwnerConfig.class)
                    .loader(ConfigLoader.class);

            DynamicSet.bind(binder(), GitReferenceUpdatedListener.class)
                    .to(ModuleOwnerConfigCacheImpl.ConfigListener.class);

            bind(ModuleOwnerConfigCacheImpl.class);
            bind(ModuleOwnerConfigCache.class).to(ModuleOwnerConfigCacheImpl.class);
            }
        };
    }

    private LoadingCache<Project.NameKey, ModuleOwnerConfig> configCache;

    @Inject
    public ModuleOwnerConfigCacheImpl(
            @Named(BYID_NAME) LoadingCache<Project.NameKey, ModuleOwnerConfig> configCache) {
        log.debug("Initializing module owner config cache...");
        this.configCache = configCache;
    }

    @Override
    public ModuleOwnerConfig get(Project.NameKey projectName) {
        try {
            return configCache.get(projectName);
        } catch (ExecutionException e) {
            log.warn("Could not get config for project: {}", projectName.get());
            return null;
        }
    }

    @Override
    public void evict(Project.NameKey projectName) {
        configCache.invalidate(projectName);
    }

    @Singleton
    public static class ConfigListener implements GitReferenceUpdatedListener {
        private final ModuleOwnerConfigCache cache;

        @Inject
        public ConfigListener(ModuleOwnerConfigCache cache) {
            this.cache = cache;
        }

        @Override
        public void onGitReferenceUpdated(Event event) {
            if (event.getRefName().equals(RefNames.REFS_CONFIG)) {
                log.info("Updating module owner config for project: {}",
                         event.getProjectName());

                Project.NameKey project = new Project.NameKey(event.getProjectName());
                // TODO check to see if there are changes before evicting config
                cache.evict(project);
            }
        }
    }

    static class ConfigLoader extends CacheLoader<Project.NameKey, ModuleOwnerConfig> {
        ModuleOwnerConfig.Factory configFactory;

        @Inject
        public ConfigLoader(ModuleOwnerConfig.Factory configFactory) {
            this.configFactory = configFactory;
        }

        @Override
        public ModuleOwnerConfig load(Project.NameKey nameKey) throws Exception {
            return configFactory.create(nameKey);
        }
    }
}
