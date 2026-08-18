# Packaged CLI E2E harness

`../run-cli-e2e.sh` is an explicit, opt-in check for the packaged CLI. It
drives the public command surface through `edt start`, readiness/status,
`mcp health`, `initialize`, `tools`, and `ping`, then `edt stop`. The default
mode creates a local fake `1cedtcli` launcher and MCP host, so it is fast and
does not require an EDT installation. It is not a unit test and is not wired
into any Maven lifecycle.

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

The start command is a tracked background process, so an interrupt during
readiness does not lose ownership before the registry record is available.
Cleanup reconciles only records in this run's temporary registry, checks the
record UUID/PID command marker, stops the exact instance, and then terminates
the exact start PID if still needed. It preserves the root rather than
deleting it whenever a live or mismatched process cannot be proven safe.
`TMPDIR`, `TMP`, and `TEMP` are ignored for allocation; OS-default `mktemp`
is canonicalized and marked with an ownership file before cleanup.

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
