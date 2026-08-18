# CodePilot launcher prerequisites

`launchers/codepilot-edt-headless` and `launchers/codepilot-edt-headless.cmd` are deliberately small
cross-platform wrappers for the installed EDT launcher. They start the
headless Equinox application contributed by the CodePilot core bundle:
`com.codepilot1c.core.headless`.

The wrappers are packaging prerequisites, not the final command-line harness.
They do not embed a CLI jar and therefore do not add a dependency to the
normal Maven/Tycho reactor build. The future CLI distribution can replace the
application argument or place these wrappers next to a generated launcher.

Environment variables:

- `CODEPILOT_EDT_HOME` (or `EDT_HOME`) — EDT's Eclipse directory;
- `CODEPILOT_EDT_EXECUTABLE` — optional explicit `1cedt`/`eclipse` executable;
- `CODEPILOT_APPLICATION` — optional application id, defaulting to
  `com.codepilot1c.core.headless`.

Examples:

```sh
CODEPILOT_EDT_HOME=/opt/1cedt/eclipse ./packaging/launchers/codepilot-edt-headless --workspace /tmp/edt-ws
```

```bat
set CODEPILOT_EDT_HOME=C:\\Program Files\\1C\EDT\eclipse
packaging\launchers\codepilot-edt-headless.cmd --workspace C:\\workspaces\\edt
```

This wave does not claim `jlink` or `jpackage` support. Those should be added
after the final CLI runtime and native launcher contract are stable.
