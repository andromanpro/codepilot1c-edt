# CodePilot CLI distribution

This directory produces a self-contained archive around the shaded CLI jar.
The packaging module is intentionally outside the normal Tycho reactor: it
does not require an EDT installation and it does not change CLI commands or
runtime/provider code.

## Build and verify

Build the shaded jar and then pass its exact path to the standalone assembly
module:

```sh
mvn -pl cli/codepilot-cli -am clean verify
mvn -f packaging/pom.xml clean verify \
  -Dcli.jar="$PWD/cli/codepilot-cli/target/codepilot-cli-1.0.0-SNAPSHOT-all.jar"
```

The output is in `packaging/target/`:

```text
codepilot-cli-1.0.0-SNAPSHOT.zip
codepilot-cli-1.0.0-SNAPSHOT.tar.gz
codepilot-cli-1.0.0-SNAPSHOT.zip.sha256
codepilot-cli-1.0.0-SNAPSHOT.tar.gz.sha256
```

To prove clean-build reproducibility locally (the shaded jar and both archive
formats are compared byte-for-byte), run:

```sh
./packaging/tests/reproducibility.sh
```

This proves repeatability on the current host/JDK. It does not claim that ZIP
or TAR bytes produced on different operating systems or JDK distributions are
identical; verify the published checksums for the specific artifact.

Both archives have the same platform-neutral layout:

```text
bin/codepilot             # Linux and macOS, executable
bin/codepilot.ps1         # Windows PowerShell, canonical launcher
bin/codepilot.cmd         # Windows cmd.exe convenience wrapper
lib/codepilot-cli.jar
LICENSE
README.md
SHA256SUMS                # checksum of lib/codepilot-cli.jar
```

`SHA256SUMS` is generated from the staged jar, not from a user-provided
filename. Verify an archive checksum before unpacking, then verify the jar
after unpacking with the standard `sha256sum` (Linux) or `shasum -a 256`
(macOS). On Windows use `Get-FileHash`.

## Requirements and install

Java 17 or newer is required on all three supported desktop platforms. No
EDT installation is needed to install or run the CLI distribution itself.

1. Verify the archive checksum and extract the archive to a versioned
   directory. Keep `bin` and `lib` together.
2. Run `bin/codepilot version` on Linux/macOS or
   `pwsh -File .\bin\codepilot.ps1 version` on Windows.
3. Optionally put the `bin` directory on `PATH`. The launchers resolve their
   own location (including a relative POSIX symlink), so the install may live
   in a path containing spaces.

The POSIX launcher uses `CODEPILOT_JAVA` when set, then `JAVA_HOME/bin/java`,
then `java` from `PATH`. Each value is one executable path; launcher arguments
are never interpreted as shell code. On Windows, prefer
`pwsh -File .\bin\codepilot.ps1 version`: its `@CliArguments` array preserves
argument boundaries and does not reparse metacharacters. The `.cmd` launcher
uses the same Java-path precedence and is provided for convenience, but normal
`cmd.exe` `%*` forwarding has the platform's usual metacharacter/quoting
limitations; it is not the canonical arbitrary-argument interface.

## Start the interactive shell

From an unpacked distribution, use the platform launcher:

```sh
# macOS and Linux
bin/codepilot shell
```

```powershell
# Windows PowerShell (canonical Windows form)
pwsh -File .\bin\codepilot.ps1 shell
```

```bat
rem Windows cmd.exe convenience form
bin\codepilot.cmd shell
```

The equivalent direct jar invocations are:

```sh
java -jar lib/codepilot-cli.jar shell
# From a repository build:
java -jar cli/codepilot-cli/target/codepilot-cli-1.0.0-SNAPSHOT-all.jar shell
```

`codepilot` with no command enters the shell only when attached to an
interactive terminal. Use `agent run` rather than `shell` for redirected batch
input.

The default `--mode auto` first attempts connected mode against registered EDT
instances. Connected mode reuses EDT's active provider through the authenticated
LLM broker and never exports its API key. The plugin must advertise `llm.v1`;
enable the EDT instance preference `mcp.host.llm.enabled=true` (or start EDT
with `-Dmcp.host.llm.enabled=true`) and ensure an active provider is selected.
Use `--mode connected` to require this path.

`--mode standalone` instead requires `--provider-endpoint` and `--model` (or
their documented property/environment equivalents), plus an EDT MCP endpoint
for tools. Prefer `--provider-api-key-file` and
`--mcp-bearer-token-file`: each secret file overrides its corresponding Java
property and environment variable and avoids putting a secret in the CLI
argument list. See the repository's
[`cli/README.md`](https://github.com/ondysss/codepilot1c-edt/blob/main/cli/README.md#interactive-shell) for
all options, slash commands, approval prompts, Ctrl+C behavior, session paths,
broker diagnostics, and Secure Storage rollback limitations.

## Update and uninstall

For an update, download and verify the new archive, extract it beside the
current version, and switch the symlink or PATH entry atomically. On Windows,
close running CLI processes before replacing the old directory. Keep the old
version until `codepilot version` succeeds, then remove it.

To uninstall, stop any CLI-owned processes, remove the extracted versioned
directory, and remove its `bin` entry from `PATH` (or delete the POSIX symlink).
The distribution does not create services, registry entries, or hidden state;
any CLI instance registry created by commands is separate and is documented in
`cli/README.md`.

## Launcher safety

The POSIX launcher uses quoted arguments and `exec`, resolves symlinks without
`eval`, and rejects a missing jar or non-executable configured Java path. The
PowerShell launcher uses a call operator plus an argument array and rejects a
missing jar or configured Java executable. The `.cmd` launcher uses `%~dp0`
and quoted paths but retains the cmd.exe `%*` limitation described above. The
launcher tests exercise spaces in the install path, symlink resolution,
archive inventory, line endings, and executable bit without starting EDT or a
GUI.

After the shaded jar is built, the distribution tests also run `version`
through every launcher whose native runner is available on the current host:

```sh
python3 -m unittest -v packaging.tests.test_distribution
```

macOS/Linux runs `bin/codepilot`; PowerShell and cmd tests run when `pwsh` (or
Windows PowerShell) and `cmd.exe` are present and otherwise report skips. If no
Windows runner is available, perform this check on Windows after
`mvn -f packaging/pom.xml clean verify -Dcli.jar=...`:

```powershell
pwsh -NoLogo -NoProfile -File .\target\distribution-root\bin\codepilot.ps1 version
cmd.exe /d /s /c ".\target\distribution-root\bin\codepilot.cmd version"
```

Both commands must exit zero and print a `codepilot <version>` line; repeat
from an install directory containing spaces.
