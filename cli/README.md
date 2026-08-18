# CodePilot CLI harness

This Java 17 module is the platform-neutral command surface for running and
inspecting CodePilot integrations outside the EDT UI. It includes a CLI process
supervisor for long-lived, CLI-owned headless EDT processes. It does not
install an operating-system service, provide automatic restart, or claim a
background daemon beyond the child process that the command actually starts.

Build and run:

```shell
mvn -DskipTests package
mvn -pl cli/codepilot-cli -am test
java -jar cli/codepilot-cli/target/codepilot-cli-1.0.0-SNAPSHOT-all.jar version
java -jar cli/codepilot-cli/target/codepilot-cli-1.0.0-SNAPSHOT-all.jar --output json doctor
java -jar cli/codepilot-cli/target/codepilot-cli-1.0.0-SNAPSHOT-all.jar edt installations
java -jar cli/codepilot-cli/target/codepilot-cli-1.0.0-SNAPSHOT-all.jar edt status
java -jar cli/codepilot-cli/target/codepilot-cli-1.0.0-SNAPSHOT-all.jar edt start \
  --workspace /absolute/path/to/workspace --edt-home /absolute/path/to/edt/eclipse \
  --port 8765 --timeout 120
java -jar cli/codepilot-cli/target/codepilot-cli-1.0.0-SNAPSHOT-all.jar edt status --all
java -jar cli/codepilot-cli/target/codepilot-cli-1.0.0-SNAPSHOT-all.jar edt stop --id INSTANCE_UUID
java -jar cli/codepilot-cli/target/codepilot-cli-1.0.0-SNAPSHOT-all.jar edt stop --all --force
java -jar cli/codepilot-cli/target/codepilot-cli-1.0.0-SNAPSHOT-all.jar --output json mcp health
java -jar cli/codepilot-cli/target/codepilot-cli-1.0.0-SNAPSHOT-all.jar --output json mcp tools
java -jar cli/codepilot-cli/target/codepilot-cli-1.0.0-SNAPSHOT-all.jar --output json mcp call get_diagnostics \
  --args '{"workspace":"/absolute/path/to/workspace"}'
```

Run these commands from the repository root: the standalone MCP client is a
reactor dependency of the CLI.

Configuration precedence is a Java system property, then an environment
variable, then a safe local default:

| Purpose | System property | Environment |
|---|---|---|
| EDT Eclipse home | `edt.home` | `EDT_HOME` |
| MCP endpoint | `codepilot.endpoint` | `CODEPILOT_ENDPOINT` |
| Optional config file check | `codepilot.config` | `CODEPILOT_CONFIG` |
| MCP bearer token | `codepilot.mcp.bearerToken` | `CODEPILOT_MCP_BEARER_TOKEN` |

Discovery checks the explicit EDT home, conventional 1C installation roots,
and `PATH` on macOS, Linux, and Windows. A directory is reported only when it
contains a platform launcher (`1cedtcli`, `1cedt`, or their Windows `.exe`
variants). Discovery never starts EDT and contains no user-specific hardcoded
paths. `edt start --edt-home` validates exactly the supplied Eclipse home;
otherwise the first deterministically sorted discovered installation is used.

`doctor` reports independent `java`, `edt`, `config`, and `endpoint` checks in
text or deterministic JSON.

## EDT supervisor

`edt start` requires an existing workspace. Before launch it canonicalizes the
workspace, rejects an existing `.metadata/.lock`, verifies that the requested
loopback port can be bound, and selects a validated `1cedtcli`/`1cedt` launcher.
It then starts:

```text
1cedtcli -nosplash -application com.codepilot1c.core.headless -data WORKSPACE -vmargs ...
```

The process receives MCP bind/port properties plus a UUID instance ID, owner
`cli`, and registry directory. Standard output and error are appended to the
instance log. The start command polls `/health/ready`; early exit or readiness
timeout terminates the process it just created and returns exit code `4`.

Instances are stored using atomic replacement in:

```text
~/.codepilot1c/instances/<instanceId>.json
```

Registry schema version `1` contains only non-secret process metadata:
`instanceId`, `pid`, `port`, `baseUrl`, canonical `workspace`, `edtHome`,
`mode`, `owner`, `startedAt`, and optionally `pluginVersion`, `authMode`, and
`logFile`. The headless host may atomically enrich or replace the same record;
readers tolerate optional and unknown forward-compatible fields.

`edt status --all` combines the registry, PID identity, and readiness probe and
reports one of `starting`, `ready`, `degraded`, or `stale` for each instance.
Plain `edt status` retains the configured-endpoint probe.

`edt stop --id` first makes a best-effort `DELETE /mcp`, then requests normal
process destruction and waits for the bounded timeout. `--force` permits
forcible destruction after that wait. `edt stop --all` selects only records
whose owner is `cli`. A live PID is terminated only when its command line still
contains the matching instance UUID, which protects against PID reuse. External
owners and identity mismatches are never terminated. Dead CLI records are
removed as stale.

Paths are passed as individual `ProcessBuilder` arguments, not through a shell,
so spaces and platform separators on Windows, macOS, and Linux need no manual
quoting beyond the calling shell's normal argument rules.

## MCP client commands

The `mcp` group is a thin CLI surface over the standalone
`codepilot-runtime-mcp-client`; it does not load Eclipse, OSGi, SWT, or the
CodePilot UI. Its command grammar is:

```text
codepilot [--output text|json] mcp [--endpoint URL | --instance-id UUID]
  [--allow-insecure-http] [--bearer-token-file FILE]
  health | initialize | tools | ping | close

codepilot [--output text|json] mcp [common options] call TOOL
  [--args JSON | --args-file FILE | --args-stdin]
```

`--endpoint` and `--instance-id` are mutually exclusive. Without either, the
existing endpoint precedence remains `codepilot.endpoint`, then
`CODEPILOT_ENDPOINT`, then `http://127.0.0.1:8765`; a base URL is normalized to
its `/mcp` endpoint. `--instance-id` reads only the validated, loopback base URL
from `~/.codepilot1c/instances/<UUID>.json`, so it cannot redirect the CLI to an
arbitrary registry value. This path is built with Java `Path` APIs, so Windows,
macOS, and Linux separators (and spaces) need no shell-specific escaping beyond
normal quoting.

Every command except `health` creates a short-lived session: it performs MCP
`initialize` negotiation, runs the requested session method, and sends a
best-effort session DELETE. `initialize` and `close` are therefore useful
connectivity/lifecycle checks in a one-shot CLI process; a session cannot be
reused by a later process invocation. Protocol fallback is handled by the
runtime client.

`call` accepts exactly one JSON object source. With no source it sends `{}`;
`--args-file` reads UTF-8 (up to 1 MiB), and `--args-stdin` is the portable way
to pipe JSON on all three supported desktop platforms. Arrays, scalars,
malformed JSON, unreadable paths, and combinations of sources are usage errors.

All `--output json` responses are stable machine contracts. They include
`command`, normalized `endpoint`, `status`, negotiated `protocolVersion` for
session commands, and the relevant result payload. Credential-like fields in
server JSON are omitted and bearer values are redacted. The CLI never emits the
configured bearer token or an `Authorization` header. Prefer
`--bearer-token-file` over placing a token in a shell command or process list;
it has precedence over the property and environment variable.

Plain HTTP is accepted automatically only for loopback endpoints. Use
`--allow-insecure-http` only for a trusted non-loopback HTTP endpoint; HTTPS
needs no override.

Exit codes:

| Code | Meaning |
|---:|---|
| 0 | Success |
| 1 | Internal command failure |
| 2 | Invalid arguments or configuration |
| 3 | Authentication failure (reserved for provider commands) |
| 4 | EDT unavailable, not ready, or unable to perform the operation |
