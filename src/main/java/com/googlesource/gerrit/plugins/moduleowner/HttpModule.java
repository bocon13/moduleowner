package com.googlesource.gerrit.plugins.moduleowner;

import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.webui.JavaScriptPlugin;
import com.google.gerrit.extensions.webui.WebUiPlugin;
import com.google.inject.servlet.ServletModule;

class HttpModule extends ServletModule {
  @Override
  protected void configureServlets() {
      DynamicSet.bind(binder(), WebUiPlugin.class)
              .toInstance(new JavaScriptPlugin("moduleowner.js"));
      // Serve Module Owners page
      serve("/owners/*").with(ModuleOwnersServlet.class);
  }
}
