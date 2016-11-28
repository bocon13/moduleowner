package com.googlesource.gerrit.plugins.moduleowner;

import com.google.gerrit.common.EventListener;
import com.google.gerrit.extensions.events.UsageDataPublishedListener;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.restapi.RestApiModule;
import com.google.gerrit.extensions.webui.TopMenu;
import com.google.gerrit.extensions.config.FactoryModule;
import com.google.gerrit.server.git.validators.MergeValidationListener;

import static com.google.gerrit.server.change.RevisionResource.REVISION_KIND;

class Module extends FactoryModule {
  @Override
  protected void configure() {
      DynamicSet.bind(binder(), MergeValidationListener.class).to(MergeUserValidator.class);
      DynamicSet.bind(binder(), EventListener.class).to(ChangeEventListener.class);
      DynamicSet.bind(binder(), UsageDataPublishedListener.class).to(UsageDataListener.class);
      DynamicSet.bind(binder(), TopMenu.class).to(OwnersTopMenu.class);

      // TODO finish implementation of DynamicSubmit and re-enable
//      bind(CapabilityDefinition.class)
//              .annotatedWith(Exports.named(DynamicSubmitCapability.DYANMIC_SUBMIT))
//              .to(DynamicSubmitCapability.class);

      install(new RestApiModule() {
          @Override
          protected void configure() {
              get(REVISION_KIND, "moduleowner").to(GetModuleOwner.class);
          }
      });
      install(ModuleOwnerConfigCacheImpl.module());

      factory(ModuleOwnerConfig.Factory.class);
      factory(ReviewersByOwnership.Factory.class);
  }
}
