# CodePilot CLI distribution

This directory produces a self-contained archive around the shaded CLI jar.
The packaging module is intentionally outside the normal Tycho reactor: it
does not require an EDT installation and it does not change CLI commands or
runtime/provider code.

## Build and verify

Build the shaded jar and then pass its exact path to the standalone assembly
module:

```sh
mvn -f cli/pom.xml clean verify
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

Both archives have the same platform-neutral layout:

```text
bin/codepilot             # Linux and macOS, executable
bin/codepilot.cmd         # Windows cmd.exe
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
   `bin\codepilot.cmd version` on Windows.
3. Optionally put the `bin` directory on `PATH`. The launchers resolve their
   own location (including a relative POSIX symlink), so the install may live
   in a path containing spaces.

The POSIX launcher uses `CODEPILOT_JAVA` when set, then `JAVA_HOME/bin/java`,
then `java` from `PATH`. Each value is one executable path; launcher arguments
are never interpreted as shell code. The Windows launcher applies the same
precedence with `java.exe` and quotes all distribution paths.

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
Windows launcher uses `%~dp0`, quoted paths, and `%*` argument forwarding; it
does not invoke a shell command string. The launcher tests exercise spaces in
the install path, symlink resolution, archive inventory, line endings, and
the executable bit without starting EDT or a GUI.
