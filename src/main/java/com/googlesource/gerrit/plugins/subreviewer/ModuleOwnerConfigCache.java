package com.googlesource.gerrit.plugins.subreviewer;

import com.google.gerrit.reviewdb.client.Project;

/**
 * Cache of ModuleOwnerConfig.
 */
public interface ModuleOwnerConfigCache {

    /**
     * Returns the module owner config for a given project.
     * @param projectName project name
     * @return module owner config
     */
    ModuleOwnerConfig get(Project.NameKey projectName);

    /**
     * Evicts the module owner config for a given project.
     * @param projectName project name
     */
    void evict(Project.NameKey projectName);
}
