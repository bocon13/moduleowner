package com.googlesource.gerrit.plugins.subreviewer;

import com.google.gerrit.extensions.annotations.Exports;
import com.google.gerrit.extensions.config.CapabilityDefinition;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.restapi.RestApiModule;
import com.google.gerrit.server.git.validators.MergeValidationListener;
import com.google.inject.AbstractModule;

import static com.google.gerrit.server.change.RevisionResource.REVISION_KIND;

class Module extends AbstractModule {
  @Override
  protected void configure() {
      DynamicSet.bind(binder(), MergeValidationListener.class)
              .to(MergeUserValidator.class);

      bind(CapabilityDefinition.class)
              .annotatedWith(Exports.named(DynamicSubmitCapability.DYANMIC_SUBMIT))
              .to(DynamicSubmitCapability.class);

      install(new RestApiModule() {
          @Override
          protected void configure() {
              get(REVISION_KIND, "file-owner").to(FileOwner.class);
          }
      });
  }
}
