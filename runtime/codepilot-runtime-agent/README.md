# Standalone agent runtime

`codepilot-runtime-agent` is a provider-neutral, plain-Java 17 loop for a CLI
host. A run starts with immutable system/user messages, asks an `AgentModel`
for assistant text and/or tool calls, executes calls through `ToolRuntime`, and
feeds deterministic tool-result envelopes back into the next model turn.

Safety bounds are explicit per runtime: `maxSteps` limits model/tool cycles,
`timeout` bounds the entire run, and a host `CancellationToken` can stop an
in-flight provider or tool future. Every terminal path returns `AgentResult`;
expected failures are represented by a stable `AgentError.Code`. A step
includes its requested tool executions, so a tool-producing final allowed
step is executed before the `STEP_LIMIT` result is returned.

`AgentRuntime.close()` atomically stops admission, returns `CANCELLED/CLOSED`
for every active run, and propagates cancellation to the current provider or
tool request. Cancelling the `CompletableFuture` returned by `run(...)` also
cancels that run's current work. Tool-call IDs are unique for the entire run,
not only within one assistant response.

The module contains two boundary adapters:

- `OpenAiCompatibleAgentModel` maps neutral messages and JSON schemas to the
  existing configured `/chat/completions` transport and parses assistant tool
  calls. It validates assistant roles and function call types, and it never
  selects an endpoint or authorization policy itself.
- `McpToolRuntime.connect(...)` initializes an existing `McpClient`, snapshots
  `tools/list`, and maps `tools/call` results into stable tool envelopes. A
  failed tool-list snapshot closes the newly initialized MCP session.

Logs intentionally contain only the caller-supplied operation ID, counters,
and terminal status. Message content, tool arguments/results, HTTP bodies,
credentials, and exception causes are never logged by this module.

This module is not an extraction of Eclipse `DynamicLlmProvider`; it has no
dependency on Eclipse, OSGi, EDT, the core bundle, or UI code.
