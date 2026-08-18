# Standalone agent runtime

`codepilot-runtime-agent` is a provider-neutral, plain-Java 17 loop for a CLI
host. A run starts with immutable system/user messages, asks an `AgentModel`
for assistant text and/or tool calls, executes calls through `ToolRuntime`, and
feeds deterministic tool-result envelopes back into the next model turn.

Models may additionally implement `StreamingAgentModel`. The runtime then
passes a per-step `StreamObserver` and forwards its text and reasoning deltas
to an `AgentEventListener`. Buffered `AgentModel` implementations continue to
use the original completion method. Events are serialized per run in this
order: step start, zero or more deltas, the accepted assistant message, each
tool-call start/result pair, and one terminal turn-finished event. Late stream
deltas are detached after the model stage completes, and listener failures do
not affect control flow.

Safety bounds are explicit per runtime: `maxSteps` limits model/tool cycles,
`timeout` bounds the entire run, and a host `CancellationToken` can stop an
in-flight provider or tool future. Every terminal path returns `AgentResult`;
expected failures are represented by a stable `AgentError.Code`. A step
includes its requested tool executions, so a tool-producing final allowed
step is executed before the `STEP_LIMIT` result is returned.

An optional `ToolApprover` is called asynchronously before every valid tool
execution. Existing constructors install `ToolApprover.ALLOW`; overloads accept an
event listener and approver (and, when needed, a `LogSink`). A denial is fed
back to the model as a tool failure with code `CONFIRMATION_DENIED`, so the
agent can recover or choose another action. An approver failure terminates the
step with `FAILED/TOOL_APPROVAL`. Cancellation and `close()` cancel a pending
approval future when possible and otherwise detach it so a late decision
cannot execute the tool.

`ToolDefinition` retains its three-argument constructor and can now carry an
optional `ToolAnnotations` value containing a display title plus destructive,
read-only, and confirmation hints. These hints are metadata only: approval is
still invoked for every valid execution, regardless of annotation values.

OpenAI-compatible HTTP 401/403 responses are retained as the provider-neutral
`PROVIDER_AUTH` terminal code without retaining or exposing the response body.
Other provider HTTP failures remain `PROVIDER_HTTP`; malformed successful
responses remain `PROVIDER_RESPONSE`. This lets a standalone host map auth to
a distinct process result while keeping endpoint and credential policy outside
the runtime.

`AgentRuntime.close()` atomically stops admission, returns `CANCELLED/CLOSED`
for every active run, and propagates cancellation to the current provider or
tool request. Cancelling the `CompletableFuture` returned by `run(...)` also
cancels that run's current work. Tool-call IDs are unique for the entire run,
not only within one assistant response.

The returned `CompletableFuture` is a runtime-owned observation handle.
Callers may compose dependent stages or call `cancel(...)`, but cannot replace
the terminal value with `complete*`, `obtrude*`, or timeout-mutation methods.
This keeps lifecycle cleanup coupled to the actual run terminal state.

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
