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
java -jar cli/codepilot-cli/target/codepilot-cli-1.0.0-SNAPSHOT-all.jar --output json agent run \
  --prompt "Inspect the workspace diagnostics" \
  --provider-endpoint https://provider.example/v1 --model example-model \
  --provider-api-key-file /private/path/provider-token \
  --instance-id EDT_INSTANCE_UUID --mcp-bearer-token-file /private/path/mcp-token
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
| Agent provider endpoint | `codepilot.provider.endpoint` | `CODEPILOT_PROVIDER_ENDPOINT` |
| Agent provider model | `codepilot.provider.model` | `CODEPILOT_PROVIDER_MODEL` |
| Agent provider API key | `codepilot.provider.apiKey` | `CODEPILOT_PROVIDER_API_KEY` |

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
session commands, and the relevant result payload. The `health` command reports
HTTP 401/403 as `status: "failed"` with `error: "authentication_failed"`, not
as readiness failure. Server JSON recursively omits credential keys such as
`apiKey`/`api-key`, API/client/consumer secrets, `secretKey`, `password`,
`passphrase`, `privateKey`, and auth/access/refresh/id token or authorization/
credential fields. The exact configured MCP bearer token is also replaced with
`<redacted>` wherever it occurs inside any emitted text or JSON string,
including benign server fields and embedded prefix/suffix text. The CLI does
not guess whether unrelated server text such as `password=hunter2` is secret;
such text remains observable unless it is under a structurally sensitive key.
The CLI never emits its configured bearer token or an `Authorization` header. Prefer
`--bearer-token-file` over placing a token in a shell command or process list;
it has precedence over the property and environment variable.

Bearer files are read as UTF-8, trimmed, limited to 64 KiB, must be regular
non-symlink files, and on POSIX systems must not be group/other readable or
writable (use mode `0600`). File bytes and decoder backing characters are
wiped after decoding; the returned character buffer is wiped after connection
setup. Windows filesystems do not expose POSIX mode bits through this check, so
the operator remains responsible for a restrictive Windows ACL.

Plain HTTP is accepted automatically only for loopback endpoints. Use
`--allow-insecure-http` only for a trusted non-loopback HTTP endpoint; HTTPS
needs no override.

## Connected shell LLM broker

The shell module contains a provider-neutral client for the authenticated EDT
LLM broker at `/llm/v1/capabilities` and `/llm/v1/chat`. This slice supplies
the transport and `StreamingAgentModel` adapter only; shell/controller mode
selection and root-command wiring are separate work.

`BrokerClient` is constructed with the MCP endpoint and bearer token already
selected by the MCP connection path. It deliberately defines no additional
property, environment, instance, or token-file precedence. It applies the same
`McpClientConfig` endpoint safety checks, derives the two broker paths from the
validated MCP origin, and sends the same bearer credential. Its private token
copy is wiped on close.

The client implements schema version 1 without exposing provider credentials:
the capability probe returns only `BrokerInfo`'s allowlisted provider fields,
and the chat adapter sends provider-neutral messages/tools without a client-side
model override. It consumes `delta`, `reasoning`, `tool_calls`, `usage`, `done`,
and `error` SSE events through the runtime-provider framing parser. SSE comments
and keepalives are ignored. Text/reasoning fragments are forwarded in order;
visible text and complete tool calls form the final agent `Assistant`.

HTTP 401/403 remain typed authentication failures, 409 identifies a retriable
single-flight busy response, and 503 instructs the caller to configure an
active provider in EDT. Schema/protocol failures and incomplete streams are
typed separately from transport failures. Response bodies, request bodies,
bearer values, and server diagnostics are never placed in exception messages.
Cancelling a completion cancels its own root HTTP request and closes its owned
response stream; closing the client cancels all of its remaining streams.

## Agent run

`agent run` is a one-shot host over the standalone `runtime-provider`,
`runtime-agent`, and `runtime-mcp-client` modules. It never loads Eclipse,
OSGi, EDT APIs, `com.codepilot1c.core`, or UI code. The grammar is:

```text
codepilot [--output text|json] agent run
  (--prompt TEXT | --prompt-file FILE | --prompt-stdin)
  [--provider-endpoint URL] [--model MODEL]
  [--provider-api-key-file FILE] [--provider-allow-insecure-http]
  [--max-steps N] [--timeout SECONDS]
  [--mcp-endpoint URL | --instance-id UUID]
  [--mcp-bearer-token-file FILE] [--allow-insecure-http]

codepilot [--output text|json] agent run [provider options]
  (--prompt TEXT | --prompt-file FILE | --prompt-stdin) --no-tools
```

Exactly one prompt source is required. Prompt files and standard input are
UTF-8 and limited to 1 MiB. The prompt is sent to the provider but never
printed in CLI diagnostics or runtime logs. `--no-tools` performs no MCP
discovery; otherwise the command reuses the MCP command's endpoint/instance
selection and token policy, negotiates `initialize`, snapshots `tools/list`,
executes model-requested `tools/call`, and closes the short-lived session.

Provider configuration precedence is explicit flag, then Java system
property, then environment variable. Endpoint and model must resolve to
non-blank values. The API key intentionally has no inline CLI option because
command arguments are commonly exposed through process listings and shell
history. Prefer `--provider-api-key-file`; it overrides the property and
environment and follows the same private-file checks as MCP bearer files.
`-Dcodepilot.provider.apiKey=...` may itself be visible in the process command
line, so the environment variable is safer when a file cannot be used.

Provider endpoints reject user info, query strings, and fragments. Plain HTTP
is accepted only on loopback unless `--provider-allow-insecure-http` is
explicitly supplied. This opt-in is independent from MCP's
`--allow-insecure-http`.

`--max-steps` defaults to 16 and bounds model/tool cycles. `--timeout` defaults
to 300 seconds and covers MCP initialization/listing plus the complete agent
run. Interruption cancels the in-flight provider or MCP future and the
production entry point installs a shutdown hook for Ctrl-C cancellation.

Text and JSON output always include terminal status/reason and completed step
count. JSON keys are stable: `command`, `status`, `terminalReason`, `steps`,
and `text` when a final assistant response exists. Prompts, transcripts, tool
arguments/results, and headers are not emitted. Exact configured provider and
MCP secrets are replaced recursively in every emitted text/JSON string,
including embedded final-model text. Structural credential-key filtering is
retained for MCP JSON. Arbitrary unknown strings are not heuristically
classified as credentials.

Exit codes:

| Code | Meaning |
|---:|---|
| 0 | Success |
| 1 | Agent/provider failure, cancellation, timeout, step limit, or internal command failure |
| 2 | Invalid arguments or configuration |
| 3 | Provider or MCP authentication failure |
| 4 | EDT/MCP unavailable, not ready, malformed, or unable to perform the operation |

## Controlled packaged-CLI E2E check

The opt-in harness at `tools/run-cli-e2e.sh` runs outside unit tests and is
not attached to the Maven reactor. Its default mode uses a local fake
headless launcher/server, isolates Java `user.home`, registry, and workspace
under a temporary directory, chooses a loopback ephemeral port, and verifies
the full start/readiness/status/MCP/stop/cleanup path:

```sh
mvn -pl cli/codepilot-cli -am -DskipTests package
tools/run-cli-e2e.sh --jar cli/codepilot-cli/target/codepilot-cli-1.0.0-SNAPSHOT-all.jar
```

Real EDT smoke is never implicit; pass `--mode real --edt-home` only for a
known installed EDT. See [`tools/cli-e2e/README.md`](../tools/cli-e2e/README.md)
for isolation guarantees, cleanup identity checks, and the PowerShell
counterpart pattern.
