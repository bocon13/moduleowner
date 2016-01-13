include_defs('//bucklets/gerrit_plugin.bucklet')

gerrit_plugin(
  name = 'moduleowner',
  srcs = glob(['src/main/java/**/*.java']),
  resources = glob(['src/main/resources/**/*']),
  manifest_entries = [
    'Gerrit-PluginName: moduleowner',
    'Gerrit-ApiType: plugin',
    'Gerrit-ApiVersion: 2.11.5',
    'Gerrit-Module: com.googlesource.gerrit.plugins.moduleowner.Module',
    'Gerrit-HttpModule: com.googlesource.gerrit.plugins.moduleowner.HttpModule',
  ],
)

# this is required for bucklets/tools/eclipse/project.py to work
java_library(
  name = 'classpath',
  deps = [':moduleowner__plugin'],
)

