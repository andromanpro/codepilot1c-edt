# Packaged CLI E2E harness

`../run-cli-e2e.sh` is an explicit, opt-in check for the packaged CLI. It
drives the public command surface through `edt start`, readiness/status,
authenticated MCP `initialize`, `tools/list`, `tools/call`, `ping`, and
session DELETE, then `edt stop`. In fake mode it also stages the production
POSIX distribution launcher and shaded jar under a path containing spaces.
Two dumb-terminal/stdin shell invocations exercise connected broker discovery,
an annotated mutating tool approval (`y`), scripted SSE tool/result turns,
`/sessions`, `/resume`, a second user turn that continues the complete saved
transcript, `/status`, and `/exit`. It is not a unit test and is not wired into
any Maven lifecycle.

```sh
# Build first, if the shaded jar is not already present.
mvn -pl cli/codepilot-cli -am -DskipTests package
tools/run-cli-e2e.sh --jar cli/codepilot-cli/target/codepilot-cli-1.0.0-SNAPSHOT-all.jar
```

The harness creates a unique temporary root containing `home/`,
`workspace/`, and a fake EDT home. Every CLI invocation receives
`-Duser.home=<temporary home>` and `HOME=<temporary home>`, so the registry is
isolated from the operator's real `~/.codepilot1c`. It selects a loopback
ephemeral port, passes the exact instance UUID to each MCP command, checks the
reported PID command line before any fallback signal, and only removes its
own validated temporary path. Normal cleanup uses `edt stop --id`; the
fallback is limited to the same PID plus the same instance marker. No
port-wide or name-wide process termination is used. Failed runs print the
fake-host log tail and remove the temporary root too; set
`CODEPILOT_E2E_KEEP_ARTIFACTS=true` when preserving it for investigation.
The fake-host helper requires Python 3.10+; the packaged CLI itself remains
Java 17-only.

The fake is deliberately strict. It accepts only the exact headless EDT
argument vector built by the supervisor, MCP protocol `2025-11-25`, the
harness-private bearer, correct session/protocol headers, the scripted tool
arguments, broker turn two only after the exact approved MCP result, and the
resumed turn only after the complete prior user/tool-call/tool-result/final
assistant transcript plus the new user message. Frozen JSON-string tool
arguments, assistant reasoning, message order, both deterministic final texts,
and the byte-for-byte append boundary are checked. Each successful initialize
must be paired with authenticated DELETE. The harness parses the private
schema-v1 session metadata and JSONL transcript, verifies the second turn and
six-message count plus `/sessions` and `/status`, checks POSIX `0700`/`0600`
permissions, scans runtime artifacts and process command lines for the test
bearer, and verifies no temporary process remains.

The start command is a tracked background process, so an interrupt during
readiness does not lose ownership before the registry record is available.
Cleanup reconciles only records in this run's temporary registry, checks the
record UUID/PID command marker, stops the exact instance, and then terminates
the exact start PID if still needed. It preserves the root rather than
deleting it whenever a live or mismatched process cannot be proven safe.
`TMPDIR`, `TMP`, and `TEMP` are ignored for allocation; OS-default `mktemp`
is canonicalized and marked with an ownership file before cleanup.
Cleanup and interruption traps are installed before allocation. An internal
`CODEPILOT_E2E_TEST_PRE_PID_DELAY=<seconds>` seam exists only for safety tests:
it proves SIGINT/SIGTERM cleanup can recover and terminate the exact start
child from the private PID file before normal PID publication.
`CODEPILOT_E2E_TEST_BIND_FAILURE_ONCE=true` makes the first fake process exit
at bind time so the bounded fresh-port retry and stale-record reconciliation
can be tested deterministically.

## Explicit real EDT smoke

Real EDT is never selected implicitly. Point the harness at a known installed
EDT and keep the workspace temporary:

```sh
tools/run-cli-e2e.sh --mode real --edt-home "/absolute/path/to/1C/EDT/eclipse" \
  --jar "/absolute/path/to/codepilot-cli-1.0.0-SNAPSHOT-all.jar"
```

The installed EDT must contain a validated `1cedtcli` or `1cedt` launcher and
the CodePilot headless application. This mode may take minutes and can fail
when the installed EDT does not contain the matching plugin/update site. The
harness does not install or modify EDT.

## PowerShell counterpart

On Windows, use the same command sequence from PowerShell with a temporary
`user.home`; the production CLI launcher already preserves argument boundaries.
The following is the supported counterpart pattern (replace paths and keep
`$EdtHome` explicit):

```powershell
$ErrorActionPreference = 'Stop'
$root = Join-Path ([IO.Path]::GetTempPath()) ('codepilot-cli-e2e-' + [guid]::NewGuid())
$home = Join-Path $root 'home'
$workspace = Join-Path $root 'workspace'
New-Item -ItemType Directory -Force -Path $home, $workspace | Out-Null
$jar = (Resolve-Path '.\cli\codepilot-cli\target\codepilot-cli-1.0.0-SNAPSHOT-all.jar').Path
$EdtHome = (Resolve-Path 'C:\Program Files\1C\EDT\eclipse').Path # explicit
$java = (Get-Command java).Source
$id = $null
try {
    # Reserve a loopback ephemeral port, then release it immediately for edt start.
    $probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $probe.Start(); $port = $probe.LocalEndpoint.Port; $probe.Stop()
    $common = @('-Duser.home=' + $home, '-jar', $jar)
    $start = & $java @common '--output' 'json' 'edt' 'start' '--workspace' $workspace `
        '--edt-home' $EdtHome '--port' $port '--timeout' 120
    if ($LASTEXITCODE -ne 0) { throw 'edt start failed' }
    $record = $start | ConvertFrom-Json
    $id = $record.instance.instanceId
    & $java @common '--output' 'json' 'edt' 'status' '--all' | Out-Host
    & $java @common '--output' 'json' 'mcp' 'health' '--instance-id' $id | Out-Host
    & $java @common '--output' 'json' 'mcp' 'initialize' '--instance-id' $id | Out-Host
    & $java @common '--output' 'json' 'mcp' 'tools' '--instance-id' $id | Out-Host
    & $java @common '--output' 'json' 'mcp' 'ping' '--instance-id' $id | Out-Host
    & $java @common '--output' 'json' 'edt' 'stop' '--id' $id | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'edt stop failed' }
    $id = $null
}
finally {
    if ($null -ne $id) {
        & $java @common '--output' 'json' 'edt' 'stop' '--id' $id '--force' | Out-Null
    }
    # Stop-Process is permitted only after querying the exact PID/instance
    # record; never use Get-Process | Stop-Process or a broad name match.
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
```

The PowerShell example is intentionally real-EDT-only: Windows validates
`1cedtcli.exe`/`1cedt.exe`, and the POSIX fake launcher is not portable to
Windows. For a no-EDT fast check on Windows, run the same POSIX harness from
WSL with the Linux packaged CLI jar. The real smoke remains opt-in because an
installed EDT may open or lock workspace state and must be explicitly chosen.

## Launcher smoke matrix

After building the shaded jar, run the host-aware packaging tests:

```sh
python3 -m unittest -v packaging.tests.test_distribution
```

They execute the POSIX launcher on macOS/Linux and execute `codepilot.ps1`
and `codepilot.cmd` when `pwsh`/Windows PowerShell and `cmd.exe` are available.
Unavailable runners are reported as skips. This is the manual Windows check
when the current host has no Windows runner:

```powershell
pwsh -NoLogo -NoProfile -File .\packaging\target\distribution-root\bin\codepilot.ps1 version
cmd.exe /d /s /c ".\packaging\target\distribution-root\bin\codepilot.cmd version"
```

Both commands must print a `codepilot <version>` line and exit zero. Run them
from a path containing spaces as an additional argument-boundary check.
