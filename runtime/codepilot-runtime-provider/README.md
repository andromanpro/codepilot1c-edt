# Runtime provider slice

`codepilot-runtime-provider` is the first executable, plain-Java provider
boundary for the standalone CLI harness. It deliberately supports one
wire-level path only: an OpenAI-compatible `POST /chat/completions` request.
It uses `java.net.http`, has no Eclipse, OSGi, SWT, EDT, or core-bundle
dependency, and does not log credentials or request bodies.

The public boundary consists of:

- `ProviderConfiguration` — immutable host-owned provider settings; API-key
  characters are copied and are not exposed by an accessor or `toString()`.
- `RuntimeProviderFactory` — registry-compatible factory currently offering
  the OpenAI-compatible transport.
- `OpenAiCompatibleProvider` — async HTTP execution of a typed chat request.
- `ChatCompletionRequest` and `ChatCompletionResponse` — minimal request and
  raw response contract, without freezing the existing agent/tool model.

The provider also accepts a Gson `JsonObject` through `completeRaw(...)` for
the standalone agent wire adapter. This remains a body-only boundary: endpoint,
timeout, configured headers, and authorization are applied by the same
transport path, and the JSON payload is never logged.

This is intentionally a prerequisite, not a second implementation of
`DynamicLlmProvider`. The existing core implementation is coupled to Eclipse
preferences, core model classes, provider-specific compatibility policies,
streaming parsers, and Codex OAuth delegation. Moving it wholesale would
duplicate or prematurely freeze these contracts.

## Next move-set

1. Add a core adapter that maps `LlmProviderConfig` and the existing request
   model into this boundary, while leaving configuration persistence in core.
2. Extract provider-neutral message/tool serialization behind a shared contract
   with request/stream regression snapshots for every supported transport.
3. Embed this jar in `com.codepilot1c.core` only when that adapter uses it;
   update the bundle classpath together with the adapter so the embedded jar is
   a production dependency rather than an unused copy.
4. Add streaming, model-listing, and non-OpenAI transports as separate
   vertical slices after their wire contracts are covered by tests.

The module's dependency-boundary test is intentionally strict: no production
source may import platform APIs or `com.codepilot1c.core`, and its POM may only
depend on the runtime kernel at compile scope.
