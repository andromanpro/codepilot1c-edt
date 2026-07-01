# External Integrations

**Analysis Date:** 2026-07-01

## APIs & External Services

**LLM Provider APIs:**
- OpenAI-compatible chat completions - Generic provider type for OpenAI-like gateways and the CodePilot backend wire format.
  - SDK/Client: Java `HttpClient` through `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config/DynamicLlmProvider.java` and `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config/ProviderHttpTransport.java`.
  - Config model: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config/LlmProviderConfig.java` and `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config/ProviderType.java`.
  - Auth: `Authorization: Bearer <apiKey>` from provider config in `DynamicLlmProvider.buildHttpRequest`.
  - Endpoints: `/chat/completions` and `/models` from `ProviderType.OPENAI_COMPATIBLE`.
  - Tool support: Structured `tools` payloads plus content fallback via `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config/ContentToolCallFallbackParser.java`, JSON repair via `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config/JsonRepairUtil.java`, and streaming tool-call parsing via `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config/OpenAiStreamingToolCallParser.java`.
- Anthropic Claude API - Native Anthropic provider path for Claude-style `/messages` requests.
  - SDK/Client: Java `HttpClient` through `DynamicLlmProvider.java`; legacy extension-point provider is `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/claude/ClaudeProvider.java`.
  - Auth: `x-api-key` plus `anthropic-version: 2023-06-01` in `DynamicLlmProvider.buildHttpRequest`; legacy preference keys are in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/settings/VibePreferenceConstants.java`.
  - Endpoints: `/messages` and `/v1/models` from `ProviderType.ANTHROPIC`.
- Ollama local API - Local model runtime provider.
  - SDK/Client: Java `HttpClient` through `DynamicLlmProvider.java`; legacy extension-point provider is `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/ollama/OllamaProvider.java`.
  - Auth: No API key required by `LlmProviderConfig.isConfigured()`.
  - Endpoints: `/api/chat` and `/api/tags` from `ProviderType.OLLAMA`.
- 1C CodePilot hosted backend - Plugin account, usage, key rotation, and LiteLLM/OpenAI-compatible model backend.
  - SDK/Client: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/backend/BackendService.java` and `DynamicLlmProvider.java`.
  - Base URLs: `https://api.codepilot1c.ru` and `https://codepilot1c.ru` defaults in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/backend/BackendConfig.java`, overridable with `vibe.backend.url` and `vibe.auth.url`.
  - Auth: Backend API key and user id stored through Eclipse Secure Storage by `BackendService.storeCredentials`; model requests use `ProviderType.CODEPILOT_BACKEND`.
  - Endpoints: `/api/plugin/auth/signup/start`, `/api/plugin/auth/signup/confirm`, `/api/plugin/auth/login`, `/api/v1/usage`, `/api/v1/rotate-key`, and LiteLLM base `/v1`.
  - Provider routing: Runtime backend provider is injected by `VibeCorePlugin.initializeLlmProvider` in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/internal/VibeCorePlugin.java`; selection checks use `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/ProviderSelectionGate.java`.
- OpenAI Codex (ChatGPT subscription) - OAuth-authenticated OpenAI Responses backend served from ChatGPT, separate from the standard OpenAI API.
  - SDK/Client: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/codex/CodexProvider.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/codex/CodexResponsesRequestBuilder.java`, and `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/codex/CodexResponsesStreamParser.java`.
  - Auth: OAuth 2.0 Authorization Code + PKCE in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/codex/CodexOAuthService.java`; tokens stored through `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/auth/SecureTokenStore.java`.
  - OAuth endpoints: `https://auth.openai.com/oauth/authorize` and `https://auth.openai.com/oauth/token` in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/codex/CodexOAuthConstants.java`.
  - Callback: `http://localhost:1455/auth/callback` handled by the Codex OAuth service; user-facing docs are in `docs/codex-oauth.md`.
  - API endpoint: `https://chatgpt.com/backend-api/codex/responses`; requests include `Authorization: Bearer`, `chatgpt-account-id`, `originator`, and `OpenAI-Beta: responses=experimental` headers in `CodexProvider.java`.
- Model listing APIs - Provider model picker integration.
  - SDK/Client: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config/ModelFetchService.java`.
  - Endpoints: Provider-specific `/models`, `/v1/models`, `/api/tags`, or curated Codex model list from `CodexOAuthConstants.KNOWN_MODELS`.

**1C:EDT / 1C Platform APIs:**
- EDT BM metadata services - Metadata creation, update, deletion, forms, DCS, rights, external objects, extensions, AST, and platform docs.
  - SDK/Client: 1C:EDT OSGi services imported in `bundles/com.codepilot1c.core/META-INF/MANIFEST.MF`.
  - Service gateway: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataGateway.java`.
  - Mutation service: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java`.
  - Validation token flow: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/validation/MetadataRequestValidationService.java` and `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/validation/ValidationTokenStore.java`.
  - BM object safety: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/BmObjectHelper.java`; always normalize to top object before `bmGetFqn()`.
  - Runbooks: `docs/reports/edt-api-patterns-retrospective-2026-02-14.md`, `docs/reports/edt-bm-model-investigation-2026-02-13.md`, and `docs/reports/edt-metadata-uuid-export-runbook.md`.
- EDT runtime/infobase services - Infobase association/access, standalone server, runtime launch, synchronization, and 1C process inspection.
  - SDK/Client: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/runtime/EdtRuntimeGateway.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/runtime/EdtRuntimeService.java`, and `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/observability`.
  - Imported services: `IInfobaseAssociationManager`, `IInfobaseAccessManager`, `IInfobaseManager`, `IRuntimeComponentManager`, and `IStandaloneServerService`.
- EDT diagnostics and marker APIs - Live diagnostics from markers and dirty editor annotations.
  - SDK/Client: UI collector `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/diagnostics/EdtDiagnosticsCollector.java`.
  - Tool: `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/tools/GetDiagnosticsTool.java`.
  - Registration: dynamic tool registration in `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/internal/VibeUiPlugin.java`.
  - Research: `docs/reports/edt-diagnostics-research-2026-02-15.md`.

**MCP Protocol:**
- Outbound MCP client - Connects to user-configured MCP servers and contributes their tools dynamically.
  - SDK/Client: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/McpServerManager.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/client/McpClient.java`, and `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/McpToolAdapter.java`.
  - Transports: STDIO in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/transport/McpStdioTransport.java`, streamable HTTP in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/transport/McpStreamableHttpTransport.java`, legacy SSE in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/transport/McpLegacySseTransport.java`, and fallback transport in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/transport/FallbackMcpTransport.java`.
  - Auth: none, static headers, or OAuth2 from `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/transport/McpTransportFactory.java`; OAuth tokens are stored by `SecureTokenStore.java`.
  - Config: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/config/McpServerConfig.java` and `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/config/McpServerConfigStore.java`.
  - Tool registration: `McpServerManager.registerToolsFromServer` calls `ToolRegistry.registerDynamicTool` in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/ToolRegistry.java`.
- Inbound MCP host - Exposes CodePilot tools, resources, prompts, and remote web UI over local HTTP.
  - SDK/Client: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/host/McpHostManager.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/host/McpHostServer.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/host/McpHostRequestRouter.java`, and `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/host/transport/McpHostHttpTransport.java`.
  - Bind/default port: `127.0.0.1` and available port `8765-8799` from `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/host/McpHostConfig.java`.
  - Endpoints: `/mcp`, `/health`, `/.well-known/oauth-authorization-server`, `/.well-known/openid-configuration`, `/.well-known/oauth-protected-resource`, `/oauth/register`, `/oauth/authorize`, `/oauth/token`, `/remote/api`, and `/remote/` in `McpHostHttpTransport.java`.
  - Auth: `OAUTH_OR_BEARER`, `OAUTH_ONLY`, `BEARER_ONLY`, or `NONE` from `McpHostConfig.java`; bearer token stored by `McpHostConfigStore.java` through Eclipse Secure Storage.
  - Exposure policy: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/host/DefaultMcpToolExposurePolicy.java`, with wildcard allow and `-tool_name` deny tokens.
  - Resources: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/host/resource/DiagnosticsResourceProvider.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/host/resource/StateResourceProvider.java`, and `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/host/resource/WorkspaceResourceProvider.java`.
  - Prompts: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/host/prompt/PromptTemplateProvider.java`.

**Eclipse Extension Points:**
- LLM providers - `com.codepilot1c.core.llmProvider` declared in `bundles/com.codepilot1c.core/plugin.xml` and schema `bundles/com.codepilot1c.core/schema/llmProvider.exsd`.
  - Built-ins: `ClaudeProvider`, `OpenAiProvider`, and `OllamaProvider` contributed in `bundles/com.codepilot1c.core/plugin.xml`.
  - Registry: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/LlmProviderRegistry.java`.
- Tool providers - `com.codepilot1c.core.toolProvider` declared in `bundles/com.codepilot1c.core/plugin.xml` and schema `bundles/com.codepilot1c.core/schema/toolProvider.exsd`.
  - Built-in registration: `ToolRegistry.registerDefaultTools()` in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/ToolRegistry.java`.
  - Runtime/dynamic registration: MCP and UI call `ToolRegistry.registerDynamicTool`.
- Prompt providers - `com.codepilot1c.core.promptProvider` declared in `bundles/com.codepilot1c.core/plugin.xml` and schema `bundles/com.codepilot1c.core/schema/promptProvider.exsd`.
  - Registry: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/agent/prompts/PromptProviderRegistry.java`.
- RAG/embedding/chunker extension points - `com.codepilot1c.rag.codeChunker`, `com.codepilot1c.core.embeddingProvider`, and a dedicated `bundles/com.codepilot1c.rag` bundle are not present in the current reactor; future RAG work must add the bundle, extension declarations, and feature/update-site entries rather than hardcoding language handling in core.

**Remote Web/UI Surfaces:**
- Remote web UI - Static assets under `bundles/com.codepilot1c.core/web/remote` served by `McpHostHttpTransport.java` through `RemoteWebController`.
  - E2E tests: `e2e/remote-web/tests/remote-ui.spec.mjs`, `e2e/remote-web/tests/remote-ui.live.spec.mjs`, and `e2e/remote-web/playwright.config.mjs`.
- Eclipse UI contributions - Preferences, commands, handlers, views, keybindings, startup, and property pages are declared in `bundles/com.codepilot1c.ui/plugin.xml`.
  - Chat view: `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/views/ChatView.java`.
  - Graph studio view: `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/views/GraphStudioView.java`.
  - Memory inspector view: `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/views/MemoryInspectorView.java`.
  - Remote workbench bridge: `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/remote/RemoteWorkbenchBridge.java` registered as `IRemoteWorkbenchBridge` by `VibeUiPlugin.java`.

## Data Storage

**Databases:**
- Not detected - No database server, JDBC datasource, ORM, or migration stack is present in the Maven reactor.

**File Storage:**
- Eclipse plugin state - Session JSON files are stored under `{plugin-state}/sessions/` by `bundles/com.codepilot1c.core/src/com/codepilot1c/core/session/FileSessionStore.java`; fallback path is `~/.vibe-sessions`.
- Project memory files - Project memory is stored under `{project}/.codepilot1c/memory/` by `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory/MemoryService.java` and `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory/store/MarkdownMemoryStore.java`.
  - Curated file: `.codepilot1c/memory/project.md`.
  - Machine file: `.codepilot1c/memory/.auto-memory.md`.
- Agent traces - JSON/JSONL trace artifacts are stored under `{plugin-state}/agent-runs/runs/{runId}/` or a `codepilot1c.agent.trace.dir` override by `bundles/com.codepilot1c.core/src/com/codepilot1c/core/evaluation/trace/ArtifactLayout.java`.
  - Files: `run.json`, `events.jsonl`, `llm.jsonl`, `tools.jsonl`, and `mcp.jsonl`.
- Remote UI/update-site static files - `bundles/com.codepilot1c.core/web/remote`, `bundles/com.codepilot1c.ui/web`, and `site/`.

**Caching:**
- Backend usage cache - In-memory 5-minute cache in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/backend/BackendService.java`.
- Provider/model state - In-memory registry caches in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/LlmProviderRegistry.java`.
- Memory search - In-memory per-project Jaccard index in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory/search/InMemorySearchIndex.java`; no external vector database is present.
- MCP server state - In-memory clients/states/errors in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/McpServerManager.java`.

## Authentication & Identity

**Auth Provider:**
- 1C CodePilot account - Custom backend login/signup flow implemented by `BackendService.java` with endpoints declared in `BackendConfig.java`.
  - Token storage: Eclipse Secure Storage keys managed by `BackendService.java`.
  - Provider injection: `VibeCorePlugin.initializeLlmProvider` creates the transient CodePilot backend provider.
- OpenAI Codex OAuth - OAuth 2.0 + PKCE through `CodexOAuthService.java`, header resolution through `CodexAuthProvider.java`, and token storage through `SecureTokenStore.java`.
  - Token refresh: `CodexAuthProvider` refreshes tokens before expiry and persists rotated refresh tokens.
- MCP host local auth - OAuth/Bearer modes in `McpHostConfig.java`, token persistence in `McpHostConfigStore.java`, OAuth handling in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/host/transport/McpHostOAuthService.java`.
- MCP outbound auth - Auth modes `NONE`, `STATIC_HEADERS`, and `OAUTH2` in `McpServerConfig.java`; request interception in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/auth/McpAuthHttpInterceptor.java`.

**Secrets location:**
- Eclipse Secure Storage - `SecureStorageUtil.java` is used by `BackendService.java`, `SecureTokenStore.java`, `McpHostConfigStore.java`, and `McpHostOAuthService.java`.
- Eclipse preferences - Dynamic provider configs, including `apiKey` field in `LlmProviderConfig`, are persisted as JSON by `LlmProviderConfigStore.java`; avoid logging or committing preference exports.
- Logs/traces sanitization - `bundles/com.codepilot1c.core/src/com/codepilot1c/core/logging/LogSanitizer.java` redacts API keys, bearer tokens, Authorization headers, and passwords; `bundles/com.codepilot1c.core/src/com/codepilot1c/core/evaluation/trace/TraceWriter.java` sanitizes trace payloads before writing JSON/JSONL.

## Monitoring & Observability

**Error Tracking:**
- External error tracking service: Not detected.

**Logs:**
- Eclipse log and optional file log - Central logger in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/logging/VibeLogger.java`; default log path resolves to the core bundle state location as `vibe.log`.
- Structured operation IDs - `LogSanitizer.newId` and `LogSanitizer.newCorrelationId` are used by long-running tools such as `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/diagnostics/TailEdtLogsTool.java` and metadata validation in `MetadataRequestValidationService.java`.
- EDT/workspace log tailing - `tail_edt_logs` tool in `TailEdtLogsTool.java` uses `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/observability/EdtLogTailService.java`.
- Runtime observability tools - 1C process, infobase lock, standalone server, and log services live under `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/observability`.
- Agent/MCP traces - `AgentTraceSession.java`, `TracingLlmProvider.java`, and `TraceWriter.java` write structured run traces.
- Benchmark/evaluation artifacts - Model benchmark runner and reports live under `bundles/com.codepilot1c.core/src/com/codepilot1c/core/evaluation/benchmark`.

## CI/CD & Deployment

**Hosting:**
- Eclipse p2 update site - Built by `repositories/com.codepilot1c.update/pom.xml` and categorized by `repositories/com.codepilot1c.update/category.xml`.
  - Build command: `mvn -DskipTests package` from repository root.
  - Artifact location: `repositories/com.codepilot1c.update/target/repository`.
  - Feature: `features/com.codepilot1c.feature/feature.xml` includes `com.codepilot1c.core` and `com.codepilot1c.ui`.
- Static site - `site/index.html` and `site/root-index.html` provide static landing/update-site pages.

**CI Pipeline:**
- GitHub Actions build/release disabled - `.github/workflows/build.yml` and `.github/workflows/release.yml` expose manual workflows that only print that automated CI build/publish is disabled and point to `tools/publish-p2-local.sh`.
- Docker workflow present but inactive for current tree - `.github/workflows/docker.yml` references `docker/Dockerfile.plugin` and `releng/com.codepilot1c.update/target/...`, while `docker/` files are absent in the current working tree and the active update-site module is `repositories/com.codepilot1c.update`.
- Local publish script - `tools/publish-p2-local.sh` is the documented local publish path, but the tracked file is absent in the current working tree; restore it before publishing.

## Environment Configuration

**Required runtime configuration:**
- LLM provider config - Preferences `llm.providers`, `llm.activeProviderId`, and `llm.configVersion` in `VibePreferenceConstants.java`; user-edited in `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/preferences/ProvidersPreferencePage.java` and `ProviderEditDialog.java`.
- Provider credentials - Static provider API keys in `LlmProviderConfig`; CodePilot backend credentials in Eclipse Secure Storage via `BackendService.java`; Codex OAuth tokens in `SecureTokenStore.java`.
- MCP outbound servers - JSON preference key `mcp.servers` in `McpServerConfigStore.java`.
- MCP host - Preferences and system properties in `McpHostConfigStore.java`; defaults bind to local loopback.
- EDT target runtime - `targets/default/default.target` expects the local 1C:EDT 2025.1.5+34 install path for p2 resolution and development builds.

**System properties:**
- `vibe.backend.url`, `vibe.auth.url` - CodePilot backend/auth base URL overrides in `BackendConfig.java`.
- `codepilot.mcp.host.enabled`, `codepilot.mcp.host.http.enabled`, `codepilot.mcp.host.http.bindAddress`, `codepilot.mcp.host.http.port`, `codepilot.mcp.host.auth.mode`, `codepilot.mcp.host.policy.defaultMutationDecision`, `codepilot.mcp.host.policy.exposedTools`, `codepilot.mcp.host.http.bearerToken` - MCP host overrides in `McpHostConfigStore.java`.
- `codepilot.mcp.allowInsecureHttp` - Allows outbound MCP HTTP transport to use insecure HTTP in `McpTransportFactory.java`.
- `codepilot1c.agent.trace.enabled`, `codepilot1c.agent.trace.dir` - Trace capture controls in `AgentTraceSession.java` and `ArtifactLayout.java`.

**Secrets location:**
- Do not use `.env` files for this plugin runtime; no `.env` files were detected in the repository root during this scan.
- Do not commit Eclipse preference exports containing `llm.providers` because `LlmProviderConfigStore.java` persists dynamic provider JSON with `apiKey`.
- Do not commit trace artifacts containing raw prompts/tool results without relying on `TraceWriter.java` sanitization and a manual review.

## Webhooks & Callbacks

**Incoming:**
- OpenAI Codex OAuth callback - Local callback server receives `http://localhost:1455/auth/callback` in `CodexOAuthService.java`; user docs are in `docs/codex-oauth.md`.
- MCP host JSON-RPC - Local HTTP endpoint `/mcp` in `McpHostHttpTransport.java`.
- MCP host OAuth - Local endpoints `/oauth/register`, `/oauth/authorize`, `/oauth/token`, plus well-known metadata endpoints in `McpHostHttpTransport.java`.
- MCP host remote web - Local endpoints `/remote/api`, `/remote/`, and `/remote` in `McpHostHttpTransport.java`.

**Outgoing:**
- Provider inference requests - OpenAI-compatible, Anthropic, Ollama, CodePilot backend, and Codex requests from `DynamicLlmProvider.java` and `CodexProvider.java`.
- Provider model listing - Outgoing model list requests from `ModelFetchService.java`.
- CodePilot account requests - Signup, login, usage, and rotate-key requests from `BackendService.java`.
- MCP outbound client requests - STDIO process launches and remote HTTP/SSE requests from `McpTransportFactory.java` and `McpServerManager.java`.
- EDT runtime/tool operations - Local OSGi service calls into 1C:EDT via gateway classes under `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt`.

---

*Integration audit: 2026-07-01*
