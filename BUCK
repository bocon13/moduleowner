include_defs('//bucklets/gerrit_plugin.bucklet')

gerrit_plugin(
  name = 'subreviewer',
  srcs = glob(['src/main/java/**/*.java']),
  resources = glob(['src/main/resources/**/*']),
  manifest_entries = [
    'Gerrit-PluginName: subreviewer',
    'Gerrit-ApiType: plugin',
    'Gerrit-ApiVersion: 2.11.5',
    'Gerrit-Module: com.googlesource.gerrit.plugins.subreviewer.Module',
    'Gerrit-HttpModule: com.googlesource.gerrit.plugins.subreviewer.HttpModule',
  ],
)

# this is required for bucklets/tools/eclipse/project.py to work
java_library(
  name = 'classpath',
  deps = [':subreviewer__plugin'],
)

