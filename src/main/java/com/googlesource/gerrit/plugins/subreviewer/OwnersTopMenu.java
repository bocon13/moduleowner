package com.googlesource.gerrit.plugins.subreviewer;

import com.google.common.collect.Lists;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.client.GerritTopMenu;
import com.google.gerrit.extensions.webui.TopMenu;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.account.CapabilityControl;
import com.google.inject.Inject;
import com.google.inject.Provider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class OwnersTopMenu implements TopMenu {
    private final String pluginName;
    private final Provider<CurrentUser> userProvider;
    private final List<MenuEntry> menuEntries;

    @Inject
    public OwnersTopMenu(@PluginName String pluginName,
                         Provider<CurrentUser> userProvider) {
        this.pluginName = pluginName;
        this.userProvider = userProvider;
        menuEntries = Lists.newArrayListWithCapacity(1);

        String baseUrl = "/plugins/" + pluginName + "/";

        // add menu entry that is only visible to users with a certain capability
        if (canSeeMenuEntry()) {
            menuEntries.add(new MenuEntry(GerritTopMenu.PROJECTS,
                  Collections.singletonList(new MenuItem("Module Owners",
                                 baseUrl + "owners/${projectName}"))));
        }
    }

    private boolean canSeeMenuEntry() {
        if (userProvider.get().isIdentifiedUser()) {
            // TODO we might want to do something more intelligent
//            CapabilityControl ctl = userProvider.get().getCapabilities();
//            return ctl.canPerform(pluginName + "-" + MyCapability.ID)
//                    || ctl.canAdministrateServer();
            return true;
        } else {
            return false;
        }
    }

    @Override
    public List<MenuEntry> getEntries() {
        return menuEntries;
    }
}