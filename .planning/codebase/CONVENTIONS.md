# Coding Conventions

**Analysis Date:** 2026-07-01

## Naming Patterns

**Files:**
- Use one public Java type per file, with the filename matching the type name: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/ToolRegistry.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config/OpenAiModelCompatibilityPolicy.java`.
- Keep production Java under bundle-local `src/` folders: `bundles/com.codepilot1c.core/src`, `bundles/com.codepilot1c.ui/src`.
- Keep tests under test bundles with mirrored package paths: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/ToolResultTest.java`, `bundles/com.codepilot1c.ui.tests/src/com/codepilot1c/ui/views/BrowserChatPanelToolCallTest.java`.
- Use `*Tool.java` for agent tools: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/metadata/AddMetadataChildTool.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/edt/EdtDiagnosticsTool.java`.
- Use `*Provider.java`, `*Registry.java`, `*Service.java`, `*Gateway.java`, and `*Policy.java` suffixes for their roles: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/DynamicLlmProvider.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/ToolRegistry.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/EdtMetadataGateway.java`.
- Use `*Test.java` for JUnit tests: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/provider/config/OpenAiStreamingToolCallParserRepairFlagTest.java`.

**Packages:**
- Use bundle-rooted package names beginning with `com.codepilot1c.core` or `com.codepilot1c.ui`.
- Keep workbench/SWT/JFace code in `com.codepilot1c.ui`: `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/views/ChatView.java`, `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/tools/DiagnosticsToolProvider.java`.
- Keep provider, agent, EDT gateway/service, MCP, memory, evaluation, and built-in tools in `com.codepilot1c.core`: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/agent`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools`.
- Do not introduce UI dependencies into `com.codepilot1c.core`; `bundles/com.codepilot1c.ui/META-INF/MANIFEST.MF` depends on `com.codepilot1c.core`, not the reverse.

**Classes:**
- Use role-specific names instead of generic manager names when a clearer role exists: `MetadataRequestValidationService`, `OpenAiCompatibilityProfileResolver`, `DynamicToolSurfaceContributor`, `ToolPromptRenderer`.
- Use nested `record` types for immutable internal data where compact value objects are enough: `ProviderCapabilities`, `OpenAiCompatibilityProfileResolver.Profile`, `ToolParameters`, `ValidationTokenStore.TokenRecord`.
- Use package-private helper classes for provider/tool internals that should not become API: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config/ContentToolCallFallbackParser.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config/JsonRepairUtil.java`.

**Functions:**
- Use lowerCamelCase Java method names: `registerDefaultTools()`, `registerDynamicTool()`, `buildRequestBody()`, `validateAndIssueToken()`, `safeTopFqn()`.
- Use verb-first names for operations and predicates: `consumeToken()`, `normalizePayload()`, `isReadOnly()`, `canExecuteShell()`, `supportsStreamUsageReporting()`.
- Use explicit operation names for tool execution methods: `doExecute()`, `createMetadata()`, `forceExportTopLevelObject()`, `verifyTopLevelPersisted()`.

**Variables:**
- Use lowerCamelCase for locals and fields: `validationToken`, `normalizedPayload`, `toolName`, `opId`.
- Use uppercase snake case for constants: `LARGE_TOOL_RESULT_CHARS`, `MAX_DESCRIPTION_CHARS`, `WAIT_TIMEOUT_MS`, `TOKEN_TTL`.
- Use `opId` consistently for operation identifiers in long-running or multi-step tool/provider flows: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/metadata/AddMetadataChildTool.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/DynamicLlmProvider.java`.

**Types:**
- Use enums for closed command/operation sets: `ValidationOperation`, `ToolCategory`, `ToolPermission`, `ToolDescriptor.Mutability`.
- Use structured result/envelope types instead of ad hoc maps when a payload is consumed by code: `ToolResult`, `ValidationTokenStore.ValidationToken`, `MetadataOperationException`.

## Code Style

**Formatting:**
- Use Java 17 source and target compatibility from `bom/pom.xml`.
- Use UTF-8 for source and reporting from `bom/pom.xml`.
- Follow the existing Java style: 4-space indentation, braces on the same line, descriptive local names, early returns for invalid states, and compact private helpers near their call sites.
- Preserve Eclipse-generated NLS comments when editing existing code: `//$NON-NLS-1$` appears throughout `bundles/com.codepilot1c.core/src` and `bundles/com.codepilot1c.ui/src`.
- Do not rely on a repository-wide formatter file; no Java formatter, Checkstyle, PMD, or SpotBugs config is detected in this worktree.

**Linting:**
- Treat Maven/Tycho compilation as the primary Java quality gate: root `pom.xml`, `bom/pom.xml`, `bundles/pom.xml`.
- Tycho compiler settings live in `bom/pom.xml`; keep new bundle code compatible with JavaSE-17 and Tycho 4.0.4.
- The `qwen-code/` subtree has its own Node/TypeScript tooling in `qwen-code/package.json` and is separate from the Java/OSGi conventions in `bundles/`.

## Import Organization

**Order:**
1. Java standard library imports such as `java.util.*`, `java.nio.file.*`, and `java.util.concurrent.*`.
2. Third-party and platform imports such as `org.eclipse.*`, `org.osgi.*`, and `com.google.gson.*`.
3. Project imports under `com.codepilot1c.*`.

**Path Aliases:**
- Java code does not use path aliases; packages are resolved through OSGi manifests and Maven/Tycho.
- Bundle dependencies are declared in `META-INF/MANIFEST.MF`, for example `bundles/com.codepilot1c.core/META-INF/MANIFEST.MF` and `bundles/com.codepilot1c.ui/META-INF/MANIFEST.MF`.

**Guidance:**
- Prefer explicit imports for new code. Avoid adding wildcard imports except where an existing file already groups many sibling tool classes, such as `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/ToolRegistry.java`.
- Keep optional EDT APIs behind service/gateway classes so import churn remains isolated in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt`.

## OSGi And Tycho Conventions

**Bundle layout:**
- Core bundle: `bundles/com.codepilot1c.core`.
- UI bundle: `bundles/com.codepilot1c.ui`.
- Core unit/contract tests: `bundles/com.codepilot1c.core.tests`.
- UI PDE tests: `bundles/com.codepilot1c.ui.tests`.
- Reactor modules are declared in root `pom.xml`, `bundles/pom.xml`, and `tests/pom.xml`.

**Manifest rules:**
- Keep Java execution environment aligned with `JavaSE-17` in `bundles/com.codepilot1c.core/META-INF/MANIFEST.MF` and `bundles/com.codepilot1c.ui/META-INF/MANIFEST.MF`.
- Export only packages intended for bundle consumers or friend bundles. `bundles/com.codepilot1c.core/META-INF/MANIFEST.MF` exports selected API packages and marks internal packages with `x-friends`.
- UI bundle code may require `com.codepilot1c.core`; core bundle code must not require `com.codepilot1c.ui`.

**Extension points:**
- Contribute provider/tool/prompt functionality through extension points declared in `bundles/com.codepilot1c.core/plugin.xml`.
- Current extension schemas are `bundles/com.codepilot1c.core/schema/toolProvider.exsd`, `bundles/com.codepilot1c.core/schema/promptProvider.exsd`, and `bundles/com.codepilot1c.core/schema/llmProvider.exsd`.
- Use runtime dynamic tools for MCP/UI contributions instead of hardcoding them into startup paths: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/ToolRegistry.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/agent/DynamicToolSurfaceContributor.java`.

## Layer Boundaries

**Core layer:**
- Put agent loop, provider integration, tool execution, metadata services, MCP client/host support, memory, and evaluation in `bundles/com.codepilot1c.core/src/com/codepilot1c/core`.
- Access EDT runtime through gateway/service classes such as `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/EdtMetadataGateway.java` and `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java`.
- Do not call EDT runtime services directly from tool code when an EDT service/gateway exists.

**UI layer:**
- Put workbench-specific APIs, SWT/JFace/browser code, views, widgets, and UI tool providers in `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui`.
- Register UI-only tools dynamically from the UI bundle. The diagnostics tool provider belongs in `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/tools/DiagnosticsToolProvider.java`.
- Keep dynamic UI registration lifecycle-safe with workbench/UI thread constraints; do not move UI registration into `bundles/com.codepilot1c.core`.

**RAG/indexing layer:**
- The requested RAG bundle path `bundles/com.codepilot1c.rag` is not present in this worktree.
- If RAG/indexing code is added, use a chunker registry/extension point pattern rather than hardcoded language handling, keep indexing as cancellable background jobs, batch work, and expose embedding-provider initialization failures as explicit user-visible status.

## Tool System

**Registration:**
- Register built-in tools only in `ToolRegistry.registerDefaultTools()` at `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/ToolRegistry.java`.
- Use `ToolRegistry.registerDynamicTool()` and `ToolRegistry.unregisterDynamicTool()` for runtime contributions from MCP and UI.
- Preserve built-in-over-dynamic precedence: `ToolRegistry.getTool()` checks built-ins before dynamic tools, and `ToolRegistry.getAllTools()` lets built-ins override dynamic names.
- Register tool descriptors through `ToolDescriptorRegistry` via `ToolRegistry` rather than scattering descriptor setup through startup code.

**Tool metadata:**
- Add `@ToolMeta` to concrete tools to declare `name`, `category`, `mutating`, `requiresValidationToken`, `tags`, and `surfaceCategory`: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/ToolMeta.java`.
- Make mutating metadata/form/DCS/extension tools declare `requiresValidationToken=true`, as in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/metadata/AddMetadataChildTool.java` and `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/metadata/MutateFormModelTool.java`.
- Keep tool names stable snake_case because prompts, profiles, descriptors, tests, and E2E scenarios depend on exact names.

**Schemas:**
- Return strict JSON schemas from `ToolDefinition.getParameterSchema()` and parse them with Gson-compatible JSON.
- Include a complete `required` array for every required argument.
- Prefer flat parameter schemas for agent-facing tools. If an array/object is required, keep inner structures deterministic and documented in the schema, as in `mutate_form_model`.
- Set `additionalProperties:false` for validation tools and other tools where unknown parameters are unsafe, as in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/metadata/EdtValidateRequestTool.java`.

**Results:**
- Return deterministic `ToolResult` success/failure payloads from `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/ToolResult.java`.
- Prefer structured `JsonObject` payloads for machine-readable results and failures: `ToolResult.success(content, structured)` and `ToolResult.failure(errorMessage, structured)`.
- Use stable error envelopes with fields such as `status`, `tool`, `error_code`, `op_id`, `message`, `recoverable`, and `details` where tests or callers expect machine-readable errors. The contract is tested in `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/ToolPayloadContractTest.java`.
- Keep LLM-facing content concise; `ToolResult.getContentForLlm(maxChars)` already handles large-result truncation.

**Execution:**
- Route tool calls through `ToolExecutionService` at `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/ToolExecutionService.java`.
- Preserve rejection of repaired arguments for mutating tools. `ToolExecutionService` rejects repaired mutating calls to avoid data loss.
- Use `ToolArgumentParser` and `ToolParameters` instead of ad hoc argument parsing.

## Agent Profiles And Permissions

**Profiles:**
- Define capabilities in profile classes under `bundles/com.codepilot1c.core/src/com/codepilot1c/core/agent/profiles`.
- Keep read-only profiles read-only. `PlanAgentProfile` and `ExploreAgentProfile` expose read/search/inspection tools and return `isReadOnly() == true`.
- Keep mutating tools in build-capable profiles only. `BuildAgentProfile` includes write/edit/metadata/DCS/extension/external mutation tools and returns `isReadOnly() == false`.
- Keep `discover_tools` available across profiles so the agent can inspect runtime tool availability. This is asserted in `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/agent/profiles/AgentProfileRegistryTest.java`.

**Permissions:**
- Define default permissions in each profile class using `ToolPermission`.
- Ask before mutating and destructive operations by default in `BuildAgentProfile`.
- Do not grant metadata mutation tools to `PlanAgentProfile` or `ExploreAgentProfile`.

**Prompt alignment:**
- Keep prompt instructions aligned with actual available tools. `ToolPromptRenderer` at `bundles/com.codepilot1c.core/src/com/codepilot1c/core/agent/ToolPromptRenderer.java` treats tool definitions as the source of truth.
- If a tool is added to a profile, update profile prompts and tool-surface contributors only when that tool is actually available in the profile.
- Do not add prompt guidance for unavailable tools.

## Provider And Qwen-Compatible Conventions

**Current provider architecture:**
- Select provider behavior through capabilities and compatibility profiles, not direct string checks scattered through code.
- Use `ProviderCapabilities` at `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/ProviderCapabilities.java` and `OpenAiCompatibilityProfileResolver` at `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config/OpenAiCompatibilityProfileResolver.java`.
- Build OpenAI-compatible request bodies in `DynamicLlmProvider.buildOpenAiRequestBody()` at `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/DynamicLlmProvider.java`.
- Apply model/provider execution overrides through `OpenAiModelCompatibilityPolicy` at `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config/OpenAiModelCompatibilityPolicy.java`.

**Tool-call robustness:**
- Send tools through structured API request fields where supported.
- Keep content fallback parsing as a safety net only for providers that declare text fallback support: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config/ContentToolCallFallbackParser.java`.
- Use `JsonRepairUtil` for manual or fallback JSON repair, not raw `JsonParser.parseString()` without error handling: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config/JsonRepairUtil.java`.
- Preserve streaming parser behavior in `OpenAiStreamingToolCallParser` and its tests at `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/provider/config/OpenAiStreamingToolCallParserRepairFlagTest.java`.
- Add large-tool-result fallback policy in `OpenAiModelCompatibilityPolicy` when a tool can produce more than 50,000 characters.

**Qwen project constraints:**
- The named Qwen-specific classes from project guidance, such as `QwenFunctionCallingTransport.java` and `QwenToolCallExamples.java`, are not present in this worktree.
- If Qwen-native files are introduced, gate Qwen-specific behavior behind provider capabilities, keep it out of non-CodePilot providers, and do not regress the existing OpenAI-compatible path in `DynamicLlmProvider`.
- New tools must stay compatible with Qwen-style priming expectations: description under 200 characters, flat schema where possible, complete `required` array, deterministic example parameters, and valid JSON from `ToolDefinition.getParameterSchema()`.

## EDT BM/API Guardrails

**BM object access:**
- Always null-check BM objects before use.
- Normalize to the top object before reading FQN. Use `BmObjectHelper.safeTopObject()` and `BmObjectHelper.safeTopFqn()` from `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/BmObjectHelper.java`.
- Call `bmGetFqn()` only on top objects. Do not call it directly on arbitrary child/non-top BM objects.
- Keep BM access patterns covered by tests such as `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/edt/BmObjectHelperTest.java`.

**Metadata mutation flow:**
- Use `edt_validate_request` before every metadata/form/DCS/extension/external mutation. The validation tool is `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/metadata/EdtValidateRequestTool.java`.
- Pass the returned `validation_token` unchanged to the mutation tool.
- Consume tokens through `MetadataRequestValidationService.consumeToken()` and `ValidationTokenStore` at `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/MetadataRequestValidationService.java` and `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/ValidationTokenStore.java`.
- Treat tokens as one-time, operation-bound, project-bound, and payload-bound.
- Use EDT BM/service APIs as the source of truth. Do not make plain text `.mdo` edits as the primary mutation path.

**Reserved metadata names:**
- Before `add_metadata_child` for `child_kind=ATTRIBUTE`, check reserved standard attributes for the parent object type.
- Reject custom attribute names that collide case-insensitively with English standard names or Russian aliases.
- Use the guard in `EdtMetadataService.validateReservedChildName()` at `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java`.
- Suggest safe alternatives like `НаименованиеПользовательское` or `КомментарийПользовательский` when requested names are reserved.

**Export and diagnostics:**
- Treat BM commit and filesystem serialization as separate phases.
- Do not assume `forceExport(...)` is synchronous.
- Do not assume `waitModelSynchronization(...)` guarantees export completion.
- After metadata/form mutations, perform post-checks for persisted `.mdo` content and `src/Configuration/Configuration.mdo`.
- Use export trace diagnostics for export issues: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/edt/EdtTraceExportTool.java`.
- Re-run diagnostics after mutation through UI/dynamic diagnostics or EDT diagnostics tools, and treat persistent type warnings as model correctness issues.

## RAG And Indexing Conventions

**Current state:**
- The requested bundle `bundles/com.codepilot1c.rag` and a `CodeChunkerRegistry` implementation are not present in this worktree.
- Search/memory helpers exist in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/search` and `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory`.

**When adding indexing code:**
- Add language support through a chunker registry/extension point rather than branching in a monolithic indexer.
- Run indexing as background jobs with batching and cancellation support.
- Fail fast with clear status when an embedding provider is unavailable.
- Keep index commit/optimize behavior explicit and testable.

## Error Handling

**Patterns:**
- Throw domain exceptions for domain failures and convert them at tool boundaries: `MetadataOperationException` in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata`.
- Include recoverability and stable error codes for tool-facing failures.
- Catch `JsonSyntaxException`/runtime parsing failures near JSON boundaries and return structured failures.
- Preserve interrupted status when catching `InterruptedException`.
- Do not swallow persistent diagnostics or export warnings; return them in structured payloads when they affect correctness.

## Logging

**Framework:** `VibeLogger` and Eclipse `ILog`

**Patterns:**
- Use `private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(CurrentClass.class)` in long-lived services/tools/providers.
- Generate operation/correlation IDs with `LogSanitizer.newId(...)` or `LogSanitizer.newCorrelationId()`.
- Include `opId` in START/SUCCESS/FAILED log messages for long operations, provider calls, validation, export tracing, and metadata mutations.
- Redact secrets and truncate large payloads through `LogSanitizer` before logging: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/logging/LogSanitizer.java`.
- Use `AbstractTool` logging integration for normal tool execution and `ToolExecutionService` trace events for tool-call auditing.

## Comments

**When to Comment:**
- Add comments where the code enforces a non-obvious safety contract, such as repaired mutating tool-call rejection in `ToolExecutionService` or form materialization/export behavior in `EdtMetadataService`.
- Keep comments short and operational. Explain why a guard exists, not what each line does.
- Preserve existing runbook references in code when they point to operational recovery material.

**JSDoc/TSDoc:**
- Not applicable for Java code.
- Use JavaDoc on public extension/API surfaces and behavior contracts: `Tool`, `ToolDefinition`, `AgentProfile`, `ProviderCapabilities`.

## Function Design

**Size:** Keep methods focused around one operation or validation boundary. Split schema creation, payload normalization, token consumption, BM mutation, export verification, and result construction into separate helpers when a tool grows.

**Parameters:** Use typed services and value objects where possible. Avoid long parameter lists for new service methods; prefer request objects when operation input has many fields.

**Return Values:** Return typed results internally and deterministic `ToolResult` at tool boundaries.

**Async:** Use `CompletableFuture` for provider/tool async boundaries consistently with `Tool.execute()` and `ToolExecutionService.executeTool()`.

## Module Design

**Exports:**
- Keep exported package lists in `META-INF/MANIFEST.MF` deliberate and minimal.
- Use `x-friends` for internal packages that are only consumed by companion bundles/tests, as in `bundles/com.codepilot1c.core/META-INF/MANIFEST.MF`.

**Barrel Files:**
- Java does not use barrel files.
- Use registries instead of barrel aggregation: `ToolRegistry`, `AgentProfileRegistry`, `OpenAiCompatibilityProfileResolver`.

**Where to add new code:**
- Built-in agent tools: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/...`, registered in `ToolRegistry.registerDefaultTools()`.
- UI-only dynamic tools: `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/tools/...`.
- Metadata services/gateways: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/...` and `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/...`.
- Provider compatibility behavior: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config/...`.
- Agent profiles and routing: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/agent/profiles/...`.
- Core tests: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/...`.
- UI tests: `bundles/com.codepilot1c.ui.tests/src/com/codepilot1c/ui/...`.

---

*Convention analysis: 2026-07-01*
