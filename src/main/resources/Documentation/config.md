Configuration
=============

The configuration of the @PLUGIN@ plugin is done on project level in
the `moduleowner.config` file of the project. Missing values are inherited
from the parent projects. This means a global default configuration can
be done in the `moduleowner.config` file of the `All-Projects` root project.
Other projects can then override the configuration in their own
`moduleowner.config` file.

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

You will also need a `rules.pl` file with the following contents:

```
submit_rule(S) :-
    gerrit:default_submit(X),
    X =.. [submit | Ls],
    gerrit:remove_label(Ls, label('Module-Owner',_), NoMO),
    add_module_owner_approval(NoMO, Labels),
    S =.. [submit | Labels].

add_module_owner_approval(S1, S2) :-
    gerrit:current_user(U),
    gerrit:commit_label(label('Module-Owner', 2), U), !,
    S2 = [label('Module-Owner', ok(U)) | S1].
add_module_owner_approval(S1, [label('Module-Owner', need(_)) | S1]).
```

One-time Setup
--------------
```
mkdir onos-cfg
cd onos-cfg/
git init
git remote add origin ssh://gerrit.onosproject.org:29418/onos.git
git review -s -r origin
```

Adding/updating a module owner
------------------------------
```
git pull origin refs/meta/config
vi moduleowner.config
git commit -a -m"Adding/amending module owner"
git push origin HEAD:refs/for/refs/meta/config
```

If you are adding a new module owner, you will also need to add them to a group with the appropriate permissions.
Also, Gerrit seems to used cached group membership when displaying review options, so new reviewers may need to 
push a +0 review to force a cache refresh (or they can wait).
