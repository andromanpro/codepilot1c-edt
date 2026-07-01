<!-- refreshed: 2026-07-01 -->
# Architecture

**Analysis Date:** 2026-07-01

## System Overview

```text
codepilot1c-oss/
+---------------------------- Tycho Reactor -----------------------------+
| `pom.xml` -> `targets/` -> `bundles/` -> `features/` -> `repositories/` |
+------------------------------+-----------------------------------------+
                               |
               +---------------+---------------+
               |                               |
               v                               v
   +---------------------------+   +-------------------------------+
   | Core OSGi bundle          |   | UI OSGi bundle                |
   | `bundles/com.codepilot1c.core` | `bundles/com.codepilot1c.ui` |
   | - agent loop/providers    |   | - ChatView/workbench UI       |
   | - tools and permissions   |   | - dynamic UI tools            |
   | - EDT service gateways    |   | - diagnostics collector       |
   | - MCP client and host     |   | - remote workbench bridge     |
   | - memory/search seams     |   | - preferences/views/handlers  |
   +-------------+-------------+   +----------------+--------------+
                 |                                  |
                 v                                  v
   +---------------------------+   +-------------------------------+
   | EDT runtime services      |   | Eclipse workbench services    |
   | `com._1c.g5.v8.*` via     |   | `PlatformUI`, SWT/JFace,      |
   | `EdtMetadataGateway`      |   | markers, editors, commands    |
   +-------------+-------------+   +-------------------------------+
                 |
                 v
   +---------------------------+
   | Project filesystem output |
   | EDT BM commit plus export |
   | `src/Configuration/...`   |
   +---------------------------+
```

## Component Responsibilities

| Component | Responsibility | File |
|-----------|----------------|------|
| Root reactor | Defines Tycho module order and release packaging flow. | `pom.xml` |
| Bundle reactor | Lists active bundles: `com.codepilot1c.core`, `com.codepilot1c.core.tests`, `com.codepilot1c.ui`. | `bundles/pom.xml` |
| Core activator | Starts logging, memory, provider registry, MCP client/host, EDT service trackers, and shutdown cleanup. | `bundles/com.codepilot1c.core/src/com/codepilot1c/core/internal/VibeCorePlugin.java` |
| UI activator | Runs workbench-thread initialization, registers UI-only dynamic tools, and publishes the remote workbench bridge OSGi service. | `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/internal/VibeUiPlugin.java` |
| Chat view | Primary Eclipse view for direct chat, streaming, tool cards, diff preview, session persistence, attachments, and model selection. | `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/views/ChatView.java` |
| Agent runner | Generic agent loop: prompt assembly, provider calls, streaming, tool execution, cancellation, tracing, and max-step handling. | `bundles/com.codepilot1c.core/src/com/codepilot1c/core/agent/AgentRunner.java` |
| LangGraph runner | Wraps `AgentRunner` behind a minimal LangGraph graph for shared desktop/remote sessions. | `bundles/com.codepilot1c.core/src/com/codepilot1c/core/agent/langgraph/LangGraphAgentRunner.java` |
| Agent session controller | Shared desktop and remote controller for agent sessions, leases, confirmations, remote events, and workbench bridge commands. | `bundles/com.codepilot1c.core/src/com/codepilot1c/core/remote/AgentSessionController.java` |
| Profile registry | Registers profile-driven tool allowlists, permissions, prompts, limits, and GSD mode. | `bundles/com.codepilot1c.core/src/com/codepilot1c/core/agent/profiles/AgentProfileRegistry.java` |
| Tool registry | Registers built-in tools, extension-point tools, dynamic tools, descriptors, tool surface augmentors, and built-in-over-dynamic precedence. | `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/ToolRegistry.java` |
| Tool execution service | Parses arguments, blocks repaired mutating calls, logs/traces tool calls, and returns deterministic `ToolResult` objects. | `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/ToolExecutionService.java` |
| Provider registry | Loads legacy extension-point LLM providers and dynamic preference-backed providers, then resolves the active provider. | `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/LlmProviderRegistry.java` |
| EDT metadata gateway | Central access point for EDT OSGi services and runtime readiness checks. | `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataGateway.java` |
| EDT metadata service | Owns BM metadata/form mutation, transaction, export, readiness, and reserved-name guardrails. | `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java` |
| UI diagnostics tool | UI-only dynamic tool exposing live workbench diagnostics to the agent/tool surface. | `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/tools/GetDiagnosticsTool.java` |
| MCP client manager | Starts configured external MCP servers and registers their tools as dynamic tools. | `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/McpServerManager.java` |
| MCP host router | Exposes CodePilot tools/resources/prompts over inbound MCP JSON-RPC with exposure and mutation policy checks. | `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/host/McpHostRequestRouter.java` |
| Memory/search seam | Initializes prompt memory contributors and allows a replaceable memory search index. | `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory/MemoryService.java` |

## Pattern Overview

**Overall:** Eclipse RCP/OSGi plugin suite with a Tycho reactor, bundle-level extension points, profile-driven agent orchestration, and EDT runtime access through service gateways.

**Key Characteristics:**
- Use Tycho packaging boundaries in `pom.xml`, `bundles/pom.xml`, `features/com.codepilot1c.feature/feature.xml`, and `repositories/com.codepilot1c.update/category.xml`.
- Keep built-in tool registration centralized in `ToolRegistry.registerDefaultTools()` in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/ToolRegistry.java`.
- Keep UI workbench-only functionality in `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui` and register it dynamically through `ToolRegistry.registerDynamicTool(...)`.
- Access EDT BM/runtime services through `EdtMetadataGateway` and domain services such as `EdtMetadataService`, not directly from UI or random tool code.
- Keep provider behavior behind `ILlmProvider`, `ProviderCapabilities`, and `LlmProviderRegistry`; model-specific transport behavior belongs in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config`.
- Use extension points in `bundles/com.codepilot1c.core/plugin.xml` and schemas in `bundles/com.codepilot1c.core/schema`.

## Layers

**Tycho Reactor Layer:**
- Purpose: Builds target platform, bundles, features, and the p2 update site.
- Location: `pom.xml`, `targets/pom.xml`, `bundles/pom.xml`, `features/pom.xml`, `repositories/pom.xml`.
- Contains: Maven module topology, target platform, Eclipse feature metadata, p2 category.
- Depends on: Tycho target at `targets/default/default.target`.
- Used by: Local release/update builds and CI-like full reactor builds.

**Core Bundle Layer:**
- Purpose: Owns agent runtime, provider abstractions, tool registry/execution, EDT services, MCP client/host, memory, QA, tracing, and shared model classes.
- Location: `bundles/com.codepilot1c.core/src/com/codepilot1c/core`.
- Contains: `agent`, `tools`, `provider`, `edt`, `mcp`, `memory`, `remote`, `session`, `settings`, `qa`, `evaluation`.
- Depends on: Eclipse runtime/resources, EDT packages imported in `bundles/com.codepilot1c.core/META-INF/MANIFEST.MF`, bundled libraries in `bundles/com.codepilot1c.core/lib`.
- Used by: `com.codepilot1c.ui`, MCP host, tests, and extension overlays.

**UI Bundle Layer:**
- Purpose: Owns workbench integration, views, handlers, preferences, live diagnostics, markdown/browser rendering, and UI thread work.
- Location: `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui`.
- Contains: `views`, `chat`, `diagnostics`, `handlers`, `preferences`, `tools`, `remote`, `theme`, `markdown`, `editor`, `diff`.
- Depends on: `com.codepilot1c.core`, SWT/JFace/Eclipse UI, workbench APIs from `bundles/com.codepilot1c.ui/META-INF/MANIFEST.MF`.
- Used by: Eclipse workbench extension declarations in `bundles/com.codepilot1c.ui/plugin.xml`.

**Agent Orchestration Layer:**
- Purpose: Turn user prompts into provider requests, tool calls, tool results, and final agent results.
- Location: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/agent`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/remote`.
- Contains: `AgentRunner`, `AgentConfig`, `AgentResult`, profile classes, prompt assembly, tool graph router, LangGraph adapter, remote session controller.
- Depends on: `ILlmProvider`, `ToolRegistry`, profile permissions, prompt providers, tracing.
- Used by: `AgentViewAdapter`, remote web controller, MCP remote companion.

**Tool Layer:**
- Purpose: Exposes deterministic operations to LLMs and MCP clients.
- Location: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools`.
- Contains: built-in tool categories under `tools/file`, `tools/metadata`, `tools/forms`, `tools/bsl`, `tools/dcs`, `tools/debug`, `tools/diagnostics`, `tools/extension`, `tools/external`, `tools/qa`, `tools/workspace`, `tools/git`, `tools/meta`.
- Depends on: domain services in `core` and optional dynamic runtime contributions.
- Used by: `AgentRunner`, `ChatView`, `McpHostRequestRouter`, `McpServerManager`.

**Provider Layer:**
- Purpose: Abstracts LLM provider configuration, active provider selection, streaming, request/response serialization, model compatibility, and provider capabilities.
- Location: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider`.
- Contains: `ILlmProvider`, `LlmProviderRegistry`, `ProviderCapabilities`, `ProviderSelectionGate`, built-in Claude/OpenAI/Ollama providers, Codex provider, and dynamic provider config.
- Depends on: HTTP utilities, Eclipse preferences, secure storage, provider-specific serializers.
- Used by: `ChatView`, `AgentRunner`, `AgentSessionController`, preference pages.

**EDT Service Layer:**
- Purpose: Encapsulates 1C:EDT BM model access, metadata mutation, form mutation, DCS, rights, AST/read-only metadata inspection, diagnostics helpers, runtime/debug, and platform documentation.
- Location: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt`.
- Contains: `metadata`, `forms`, `dcs`, `extension`, `external`, `lang`, `ast`, `platformdoc`, `validation`, `runtime`, `debug`, `rights`.
- Depends on: `EdtMetadataGateway` and service trackers in `VibeCorePlugin`.
- Used by: EDT tools in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools`.

**MCP Layer:**
- Purpose: Connects to external MCP servers and exposes CodePilot itself as an MCP host.
- Location: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp`.
- Contains: client transports/config/model, host config/router/resources/prompts/session/transport, OAuth auth, HTTP/SSE/stdio transports.
- Depends on: `ToolRegistry`, `PermissionManager`, transport classes, host config store.
- Used by: core startup, UI startup, remote web companion, external MCP clients.

**Memory/RAG Seam Layer:**
- Purpose: Provides persistent project memory, prompt contributors, extraction listeners, and a replaceable search index.
- Location: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory`.
- Contains: `MemoryService`, `MarkdownMemoryStore`, prompt contributors, project metadata detection, `IMemorySearchIndex`, `InMemorySearchIndex`.
- Depends on: project filesystem `.codepilot1c/memory`, `SessionManager`, prompt assembly.
- Used by: prompt context assembly, `remember_fact`, project memory UI.
- Current RAG boundary: `bundles/com.codepilot1c.rag`, `com.codepilot1c.core.embeddingProvider`, and `com.codepilot1c.rag.codeChunker` are not present in `bundles/pom.xml`, `features/com.codepilot1c.feature/feature.xml`, or `bundles/com.codepilot1c.core/plugin.xml`. New indexing/chunking work must introduce or restore those extension declarations instead of hardcoding language handling into `core`.

## Data Flow

### Desktop Chat Request Path

1. `ChatView` builds a prompt, conversation history, optional model override, and tool definitions from `ToolRegistry.getToolDefinitions()` in `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/views/ChatView.java`.
2. `ChatView` resolves the active provider through `LlmProviderRegistry.getInstance().getActiveProvider()` in `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/views/ChatView.java`.
3. The provider returns streaming or non-streaming `LlmResponse` data through `ILlmProvider` in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/ILlmProvider.java`.
4. `ChatView.handleResponseWithTools(...)` appends assistant tool calls, dispatches tools through `ToolRegistry.execute(...)`, handles confirmation dialogs, and appends `LlmMessage.toolResult(...)` values.
5. `ChatView` continues the request loop until there are no tool calls or the tool iteration budget is exhausted.

### Shared Agent/Remote Path

1. `AgentViewAdapter.run(...)` resolves a profile with `ProfileRouter` and submits work through `AgentSessionController.submitFromDesktop(...)`.
2. `AgentSessionController.submitPrompt(...)` resolves `ILlmProvider`, creates `AgentConfig` from `AgentProfileRegistry`, and instantiates `LangGraphAgentRunner`.
3. `LangGraphAgentRunner` builds a one-node LangGraph in `LangGraphAgentGraphFactory` and delegates actual execution to `AgentRunner`.
4. `AgentRunner` assembles the system prompt via `SystemPromptAssembler`, initializes `ToolGraphRouter`, builds a filtered tool surface from profile/context/tool graph, and calls `ILlmProvider.complete(...)` or `streamComplete(...)`.
5. Tool calls are executed sequentially by `ToolExecutionService`; results are appended to history and emitted as agent events to UI/remote listeners.

### EDT Metadata Mutation Path

1. The agent calls `edt_validate_request` implemented by `EdtValidateRequestTool` in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/metadata/EdtValidateRequestTool.java`.
2. `MetadataRequestValidationService` validates and issues a short-lived token in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/validation/MetadataRequestValidationService.java`.
3. Mutation tools such as `CreateMetadataTool` consume the unchanged token, normalize payloads, and create request records in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/metadata/CreateMetadataTool.java`.
4. `EdtMetadataService` uses `EdtMetadataGateway` for EDT runtime services, opens BM write transactions, mutates the model, and calls export/post-check helpers.
5. Export and synchronization are separate phases in `EdtMetadataService`: BM write, `forceExportTopLevelObject(...)`, `waitModelSynchronization(...)`, derived-data flush/wait, and post-checks such as `src/Configuration/Configuration.mdo`.
6. UI diagnostics should be re-run through `get_diagnostics` in `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/tools/GetDiagnosticsTool.java`.

### MCP Host Tool Call Path

1. `VibeCorePlugin` and `McpHostStartup` start `McpHostManager` from `bundles/com.codepilot1c.core/src/com/codepilot1c/core/internal/VibeCorePlugin.java` and `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/startup/McpHostStartup.java`.
2. `McpHostServer` creates `McpHostRequestRouter` and `McpHostHttpTransport` in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/host`.
3. `McpHostRequestRouter` handles `initialize`, `tools/list`, `tools/call`, `resources/read`, `prompts/list`, and `prompts/get`.
4. `tools/call` checks exposure policy, resolves the tool through `ToolRegistry`, applies mutation policy through `PermissionManager`, executes with a bounded timeout, and returns MCP content blocks.

### External MCP Client Path

1. `McpServerManager.startEnabledServers()` loads enabled server configs from `McpServerConfigStore` in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/config`.
2. `McpServerManager` creates transports through `McpTransportFactory`, initializes `McpClient`, requests `tools/list`, and wraps each `McpTool` in `McpToolAdapter`.
3. Each adapter is registered as a dynamic tool through `ToolRegistry.registerDynamicTool(...)`.
4. Built-in tools retain precedence when a dynamic tool uses the same name.

**State Management:**
- Plugin lifecycle state lives in OSGi activators `VibeCorePlugin` and `VibeUiPlugin`.
- Provider config and active provider selection live in Eclipse preferences through `LlmProviderConfigStore`.
- Agent profile overrides live in Eclipse preferences through `ProfileConfigStore`.
- Long-lived chat sessions use `SessionManager` and `FileSessionStore` in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/session`.
- Project memory uses `.codepilot1c/memory` through `MarkdownMemoryStore`.
- Tool runtime state uses singleton registries: `ToolRegistry`, `ToolDescriptorRegistry`, `ToolInterceptorRegistry`, `PermissionManager`, `McpServerManager`, `McpHostManager`, `MemoryService`.

## Key Abstractions

**AgentProfile:**
- Purpose: Defines allowed tools, default permissions, prompt additions, limits, read-only behavior, shell capability, and GSD mode.
- Examples: `BuildAgentProfile`, `PlanAgentProfile`, `ExploreAgentProfile`, `MetadataBuildProfile`, `QABuildProfile` in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/agent/profiles`.
- Pattern: Registry plus immutable-ish profile classes with optional persisted overrides.

**ITool and ToolResult:**
- Purpose: Defines tool name, description, JSON schema, execution, confirmation, mutation, validation-token, and tags.
- Examples: `ITool`, `AbstractTool`, `ToolResult`, `ToolMeta` in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools`.
- Pattern: Command objects registered centrally, returning structured deterministic result objects.

**ILlmProvider:**
- Purpose: Standardizes provider identity, configuration readiness, streaming, completion, cancellation, and disposal.
- Examples: `ClaudeProvider`, `OpenAiProvider`, `OllamaProvider`, `CodexProvider`, `DynamicLlmProvider`.
- Pattern: Registry-backed provider selection with capability metadata and transport-specific serializers.

**EdtMetadataGateway:**
- Purpose: Centralizes EDT OSGi service access and readiness checks.
- Examples: `EdtMetadataGateway`, `MetadataProjectReadinessChecker`, `EdtMetadataService`.
- Pattern: Gateway plus domain service. New EDT runtime access should route through a gateway/service class.

**BmObjectHelper:**
- Purpose: Safely normalizes BM objects before FQN access.
- Examples: `safeTopObject(...)`, `safeTopFqn(...)` in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/BmObjectHelper.java`.
- Pattern: Null-check, skip transient object, normalize non-top object with `bmGetTopObject()`, then call `bmGetFqn()` only on the top object.

**Extension Points:**
- Purpose: Allow overlays and runtime contributions without editing OSS built-ins.
- Examples: `com.codepilot1c.core.llmProvider`, `com.codepilot1c.core.toolProvider`, `com.codepilot1c.core.promptProvider` in `bundles/com.codepilot1c.core/plugin.xml`.
- Pattern: Eclipse extension point schemas under `bundles/com.codepilot1c.core/schema`.

## Entry Points

**Core Bundle Startup:**
- Location: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/internal/VibeCorePlugin.java`.
- Triggers: OSGi bundle activation from `bundles/com.codepilot1c.core/META-INF/MANIFEST.MF`.
- Responsibilities: Initialize logging, memory, provider registry, backend provider, MCP servers/host, EDT service trackers, and shutdown cleanup.

**UI Bundle Startup:**
- Location: `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/internal/VibeUiPlugin.java`.
- Triggers: OSGi bundle activation from `bundles/com.codepilot1c.ui/META-INF/MANIFEST.MF`.
- Responsibilities: Initialize themes, register `get_diagnostics` as a dynamic tool, and publish `IRemoteWorkbenchBridge`.

**Workbench Extension Startup:**
- Location: `bundles/com.codepilot1c.ui/plugin.xml`.
- Triggers: Eclipse `org.eclipse.ui.startup`, `org.eclipse.ui.views`, commands, handlers, menus, preference pages, and bindings.
- Responsibilities: Adds `ChatView`, `GraphStudioView`, `MemoryInspectorView`, command handlers, preference pages, and early MCP host startup.

**Update Site Packaging:**
- Location: `features/com.codepilot1c.feature/feature.xml`, `repositories/com.codepilot1c.update/category.xml`.
- Triggers: Tycho package build from root `pom.xml`.
- Responsibilities: Package core/ui plugins into the feature and publish the p2 repository category.

## Architectural Constraints

- **Bundle boundary:** Keep new workbench-specific code in `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui`; expose it to the agent as a dynamic tool or OSGi service. Do not add new workbench UI dependencies to domain services in `bundles/com.codepilot1c.core/src/com/codepilot1c/core`.
- **Current core manifest:** `bundles/com.codepilot1c.core/META-INF/MANIFEST.MF` currently requires `org.eclipse.ui`, `org.eclipse.ui.ide`, and JFace bundles. Treat that as existing surface, not permission to place new UI behavior in core.
- **EDT access:** New EDT code should use `EdtMetadataGateway` or a sibling gateway/service in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt`, not direct service tracker access from tool classes.
- **BM FQN access:** Use `BmObjectHelper.safeTopObject(...)` or `safeTopFqn(...)` in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/BmObjectHelper.java` before FQN reads.
- **Tool registration:** Add built-in tools only in `ToolRegistry.registerDefaultTools()`; add runtime/workbench/MCP tools through `registerDynamicTool(...)` or `com.codepilot1c.core.toolProvider`.
- **Tool precedence:** `ToolRegistry.getTool(...)` returns built-ins before dynamic tools. Do not rely on dynamic tools overriding built-ins.
- **Mutation validation:** EDT mutation tools must require and consume `validation_token` from `edt_validate_request`.
- **Export invariants:** BM write and filesystem export are separate phases. Keep explicit export/post-check code in `EdtMetadataService`.
- **RAG/indexing extension boundary:** The requested `embeddingProvider` and `rag codeChunker` extension points are not present in the checked-out reactor; add them through OSGi schema/plugin metadata and a `bundles/com.codepilot1c.rag` module before implementing provider-specific indexing behavior.
- **Global state:** Core runtime uses singleton services and registries. New long-running code must be lifecycle-safe and cleaned up from activators or registries.
- **Circular imports:** No Maven reactor cycle is present in `bundles/pom.xml`; `com.codepilot1c.ui` depends on `com.codepilot1c.core`, and feature packaging includes both.

## Anti-Patterns

### UI Tool In Core

**What happens:** A tool that needs active editors, SWT widgets, `PlatformUI`, markers, or workbench pages is implemented in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools`.

**Why it's wrong:** It expands the core/workbench dependency surface and makes headless, remote, and MCP contexts harder to reason about.

**Do this instead:** Put the implementation in `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/tools` and register it with `ToolRegistry.registerDynamicTool(...)` from `VibeUiPlugin`.

### Direct EDT Runtime Calls From Tools

**What happens:** A tool reaches directly into EDT services or BM transaction APIs.

**Why it's wrong:** Readiness checks, service availability errors, transaction boundaries, export handling, and BM top-object normalization become inconsistent.

**Do this instead:** Put EDT runtime access in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/...` services that use `EdtMetadataGateway`, then call those services from the tool.

### Metadata Mutation Through Files

**What happens:** A feature edits `.mdo` files or `src/Configuration/Configuration.mdo` through `write_file`/`edit_file` as the primary mutation path.

**Why it's wrong:** EDT BM remains the source of truth, and direct file edits bypass validation, BM transactions, export sync, and diagnostics.

**Do this instead:** Use mutation tools and services under `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/metadata`, `tools/forms`, `tools/dcs`, `tools/extension`, and `tools/external`.

### Tool Prompt Drift

**What happens:** A prompt advertises a tool that is absent from a profile allowlist or runtime registry.

**Why it's wrong:** The model attempts unavailable calls and wastes turns.

**Do this instead:** Update profile allowlists in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/agent/profiles`, prompt additions in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/agent/prompts`, and registrations in `ToolRegistry` together.

## Error Handling

**Strategy:** Fail fast at boundaries, return structured `ToolResult` failures for tool calls, use typed EDT operation exceptions for EDT errors, and log/traces with operation context.

**Patterns:**
- `ToolExecutionService` returns `ToolResult.failure(...)` for unknown tools, parse failures, execution exceptions, and repaired mutating calls.
- EDT services throw `MetadataOperationException` with `MetadataOperationCode` from `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata`.
- Tools with long EDT operations create `opId` values with `LogSanitizer.newId(...)` and log start/success/failure.
- MCP host calls return JSON-RPC error objects or MCP `isError=true` tool results from `McpHostRequestRouter`.
- Provider failures use `LlmProviderException` and state updates through `VibeStateService`.

## Cross-Cutting Concerns

**Logging:** Use `VibeLogger` from `bundles/com.codepilot1c.core/src/com/codepilot1c/core/logging/VibeLogger.java`; use `LogSanitizer` before logging provider/tool/secret-adjacent data.

**Validation:** Use JSON schemas from each `ITool.getParameterSchema()`, metadata validation through `MetadataRequestValidationService`, and profile allowlists through `AgentConfig.isToolAllowed(...)`.

**Authentication:** LLM and backend secrets use Eclipse Secure Storage through `SecureStorageUtil`; MCP host auth is configured through `McpHostConfigStore` and OAuth/static bearer support under `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/host/transport`.

**Permissions:** Use `PermissionManager`, `PermissionRule`, tool confirmation flags, profile default permissions, and MCP host mutation policy.

**Tracing:** Agent/tool/MCP traces use `AgentTraceSession`, `TraceEventType`, and tracing wrappers in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/evaluation/trace`.

**Prompt Context:** `SystemPromptAssembler` combines base prompts, profile prompt additions, instruction context, requested skills, memory contributors, and filesystem prompt overrides.

**Diagnostics:** UI workbench diagnostics are collected in `EdtDiagnosticsCollector` and exposed through dynamic `get_diagnostics`.

---

*Architecture analysis: 2026-07-01*
