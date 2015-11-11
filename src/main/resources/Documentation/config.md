Configuration
=============

The configuration of the @PLUGIN@ plugin is done on project level in
the `subreviewer.config` file of the project. Missing values are inherited
from the parent projects. This means a global default configuration can
be done in the `subreviewer.config` file of the `All-Projects` root project.
Other projects can then override the configuration in their own
`subreviewer.config` file.

TODO: Currently, we use Java regex syntax. Should we switch to another?

```
  [user "john"]
    path = core/.*

  [user "jane"]
    path = api/.*
    path = README

  [group "Owners"]
    path = .*
```

user.&lt;user&gt;.path
:	A regex of an allowed file path. Multiple `path` occurrences are allowed.