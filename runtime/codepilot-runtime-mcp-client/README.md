# Standalone MCP client

`codepilot-runtime-mcp-client` is a platform-neutral Java 17 client for the
MCP Streamable HTTP contract used by the EDT CLI harness. It uses only
`java.net.http` and Gson at runtime; it has no dependency on Eclipse, OSGi,
SWT, EDT, or the core bundle.

The client performs readiness checks, MCP `initialize` negotiation, session
bound `ping`, `tools/list`, and `tools/call` requests, and session `DELETE`.
It preserves unknown server fields and raw JSON schemas so the CLI can expose
provider-neutral tool data without coupling to the core MCP model.

Security defaults reject URI user information, non-HTTP(S) endpoints, and
non-loopback plain HTTP. Plain HTTP can be explicitly enabled for a trusted
local network. Bearer credentials are copied into client-owned character
storage, are never included in `toString` or errors, and are wiped on close.

Limitations in this slice: server-sent event notifications are not consumed
or dispatched, and OAuth discovery/registration/token flows are not included.
The client accepts a single JSON response (and the first `data:` event when a
server wraps that response in SSE framing).

`closeAsync()` is the non-blocking shutdown API. `close()` implements
`AutoCloseable` and waits for the best-effort DELETE request, so callers that
must not block their event loop should use `closeAsync()`.
