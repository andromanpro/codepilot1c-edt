# Codebase Structure

**Analysis Date:** 2026-07-01

## Directory Layout

```text
codepilot1c-oss/
|-- pom.xml                         # Root Tycho reactor
|-- bom/                            # Parent/BOM Maven configuration
|-- targets/                        # Eclipse/EDT target platform definitions
|-- bundles/                        # OSGi plugin bundles and bundle tests
|   |-- com.codepilot1c.core/       # Agent runtime, tools, EDT services, providers, MCP, memory
|   |-- com.codepilot1c.core.tests/ # Core unit/contract tests
|   |-- com.codepilot1c.ui/         # Eclipse workbench UI bundle
|   `-- com.codepilot1c.ui.tests/   # UI-focused tests
|-- features/                       # Eclipse feature packaging
|   `-- com.codepilot1c.feature/    # Feature containing core and UI plugins
|-- repositories/                   # p2 update site packaging
|   `-- com.codepilot1c.update/     # Update repository/category
|-- tests/                          # Optional desktop UI test reactor module
|-- docs/                           # Architecture notes, reports, runbooks, plans
|-- e2e/                            # End-to-end fixtures and remote-web tests
|-- site/                           # Site/static assets
|-- .codex/                         # GSD workflow skills/templates/agents for this repo
|-- .planning/codebase/             # Generated codebase map documents
|-- .runs/                          # Local run outputs/workspaces
|-- output/                         # Local generated test/output artifacts
|-- scratchpad/                     # Local scratch files
`-- qwen-code/                      # Embedded separate repository/vendor workspace
```

## Directory Purposes

**`pom.xml`:**
- Purpose: Root Tycho reactor for the product.
- Contains: Modules `targets`, `bundles`, `features`, `repositories`; root build plugin configuration.
- Key files: `pom.xml`.

**`bom/`:**
- Purpose: Parent/BOM configuration used by root `pom.xml`.
- Contains: Maven dependency/plugin management.
- Key files: `bom/pom.xml`.

**`targets/`:**
- Purpose: Target platform resolution for Eclipse, Xtext, terminal/CDT native, Gson, and local 1C:EDT.
- Contains: Target module and `.target` files.
- Key files: `targets/default/default.target`, `targets/pom.xml`.

**`bundles/`:**
- Purpose: All OSGi runtime bundles and bundle-level tests.
- Contains: Bundle projects, `META-INF/MANIFEST.MF`, `plugin.xml`, schemas, Java source, resources, tests.
- Key files: `bundles/pom.xml`.

**`bundles/com.codepilot1c.core/`:**
- Purpose: Core runtime bundle.
- Contains: `META-INF/`, `OSGI-INF/`, `schema/`, `src/`, `resources/knowledge/`, `skills/`, `web/remote/`, bundled libraries under `lib/`.
- Key files: `bundles/com.codepilot1c.core/META-INF/MANIFEST.MF`, `bundles/com.codepilot1c.core/plugin.xml`, `bundles/com.codepilot1c.core/build.properties`.

**`bundles/com.codepilot1c.core/src/com/codepilot1c/core/`:**
- Purpose: Core Java source root.
- Contains: Agent loop, providers, tools, EDT integrations, MCP, memory, QA, tracing, session/state/settings, utilities.
- Key files: `agent/AgentRunner.java`, `tools/ToolRegistry.java`, `provider/LlmProviderRegistry.java`, `edt/metadata/EdtMetadataService.java`, `mcp/host/McpHostRequestRouter.java`.

**`bundles/com.codepilot1c.core/schema/`:**
- Purpose: Eclipse extension point schemas.
- Contains: `llmProvider.exsd`, `toolProvider.exsd`, `promptProvider.exsd`.
- Key files: `bundles/com.codepilot1c.core/schema/toolProvider.exsd`.

**`bundles/com.codepilot1c.core/resources/knowledge/`:**
- Purpose: Bundled domain knowledge used by prompts/tools.
- Contains: Markdown guidance for BSL, EDT gotchas, metadata operations, managed forms, extensions, query optimization.
- Key files: `bundles/com.codepilot1c.core/resources/knowledge/edt-metadata-operations.md`.

**`bundles/com.codepilot1c.core/skills/`:**
- Purpose: Bundled CodePilot skill definitions exposed by the core skill catalog.
- Contains: Skill folders with `SKILL.md`.
- Key files: `bundles/com.codepilot1c.core/skills/review/SKILL.md`, `bundles/com.codepilot1c.core/skills/architect/SKILL.md`.

**`bundles/com.codepilot1c.core/web/remote/`:**
- Purpose: Remote web companion static UI served by the MCP host transport.
- Contains: HTML/CSS/JS assets.
- Key files: `bundles/com.codepilot1c.core/web/remote/index.html`, `bundles/com.codepilot1c.core/web/remote/app.js`.

**`bundles/com.codepilot1c.ui/`:**
- Purpose: Eclipse workbench UI bundle.
- Contains: `META-INF/`, `OSGI-INF/`, `src/`, `resources/`, `web/`, icons, bundled flexmark libraries.
- Key files: `bundles/com.codepilot1c.ui/META-INF/MANIFEST.MF`, `bundles/com.codepilot1c.ui/plugin.xml`, `bundles/com.codepilot1c.ui/build.properties`.

**`bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/`:**
- Purpose: UI Java source root.
- Contains: Chat UI, workbench handlers, preference pages, diagnostics, dynamic UI tools, remote bridge, rendering, themes.
- Key files: `views/ChatView.java`, `internal/VibeUiPlugin.java`, `tools/GetDiagnosticsTool.java`, `diagnostics/EdtDiagnosticsCollector.java`.

**`bundles/com.codepilot1c.core.tests/`:**
- Purpose: Core test bundle.
- Contains: Unit/contract tests mirroring core packages under `src/com/codepilot1c/core`.
- Key files: `bundles/com.codepilot1c.core.tests/pom.xml`, `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/ToolExecutionServiceRepairGateTest.java`.

**`bundles/com.codepilot1c.ui.tests/`:**
- Purpose: UI test bundle.
- Contains: UI tests under `src/com/codepilot1c/ui`.
- Key files: `bundles/com.codepilot1c.ui.tests/src/com/codepilot1c/ui/views/BrowserChatPanelToolCallTest.java`.

**`features/`:**
- Purpose: Eclipse feature definitions.
- Contains: Feature parent and product feature project.
- Key files: `features/com.codepilot1c.feature/feature.xml`.

**`repositories/`:**
- Purpose: p2 update site packaging.
- Contains: Repository module and category definition.
- Key files: `repositories/com.codepilot1c.update/category.xml`, `repositories/com.codepilot1c.update/pom.xml`.

**`docs/`:**
- Purpose: Developer notes, plans, reports, runbooks, release notes, E2E docs.
- Contains: `docs/reports/`, `docs/runbooks/`, `docs/plans/`, `docs/e2e/`, `docs/release-notes/`.
- Key files: `docs/reports/edt-metadata-uuid-export-runbook.md`, `docs/reports/edt-api-patterns-retrospective-2026-02-14.md`, `docs/reports/tool-graph-router-plan-2026-02-25.md`.

**`e2e/`:**
- Purpose: End-to-end test fixtures and remote web tests.
- Contains: `e2e/remote-web`.
- Key files: `e2e/remote-web`.

**`.codex/`:**
- Purpose: GSD/Codex workflow support checked into the repo.
- Contains: Local skills, agents, templates, workflows, hooks.
- Key files: `.codex/skills/gsd-map-codebase/SKILL.md`, `.codex/get-shit-done/templates/codebase/architecture.md`.

**`.planning/codebase/`:**
- Purpose: Generated codebase intelligence documents consumed by GSD planning/execution commands.
- Contains: `ARCHITECTURE.md`, `STRUCTURE.md`, and sibling map docs when generated.
- Key files: `.planning/codebase/ARCHITECTURE.md`, `.planning/codebase/STRUCTURE.md`.

## Key File Locations

**Entry Points:**
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/internal/VibeCorePlugin.java`: Core bundle activator and EDT service tracker owner.
- `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/internal/VibeUiPlugin.java`: UI bundle activator and dynamic UI tool registration.
- `bundles/com.codepilot1c.ui/plugin.xml`: Workbench views, commands, handlers, preference pages, startup, menus, key bindings.
- `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/views/ChatView.java`: Main chat view.
- `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/startup/McpHostStartup.java`: Workbench early-start hook for inbound MCP host.
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/remote/AgentSessionController.java`: Shared desktop/remote agent session entry point.

**Configuration:**
- `pom.xml`: Root reactor.
- `bundles/pom.xml`: Active bundle modules.
- `targets/default/default.target`: Eclipse/EDT target platform.
- `bundles/com.codepilot1c.core/META-INF/MANIFEST.MF`: Core bundle metadata, imports, exports, activator.
- `bundles/com.codepilot1c.ui/META-INF/MANIFEST.MF`: UI bundle metadata, imports, exports, activator.
- `bundles/com.codepilot1c.core/plugin.xml`: Core extension point declarations and built-in LLM providers.
- `bundles/com.codepilot1c.ui/plugin.xml`: UI extension declarations.
- `features/com.codepilot1c.feature/feature.xml`: Feature contents.
- `repositories/com.codepilot1c.update/category.xml`: p2 category.

**Core Logic:**
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/agent/AgentRunner.java`: Core agent loop.
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/agent/langgraph/LangGraphAgentRunner.java`: LangGraph wrapper around agent execution.
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/agent/profiles/AgentProfileRegistry.java`: Profile registration and config creation.
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/agent/prompts/SystemPromptAssembler.java`: System prompt assembly.
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/agent/graph/ToolGraphRouter.java`: Tool graph filtering/routing.
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/ToolRegistry.java`: Tool registration, lookup, definitions, dynamic tools.
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/ToolExecutionService.java`: Tool execution.
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/LlmProviderRegistry.java`: Provider registry and active provider resolution.
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config/DynamicLlmProvider.java`: Dynamic OpenAI-compatible/Anthropic/Ollama provider implementation.
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataGateway.java`: EDT runtime service access.
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java`: Metadata/form mutation service.
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/BmObjectHelper.java`: Safe BM object helpers.
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/McpServerManager.java`: External MCP client lifecycle.
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/host/McpHostRequestRouter.java`: Inbound MCP host JSON-RPC router.
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory/MemoryService.java`: Memory subsystem facade and search-index seam.

**UI Logic:**
- `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/views/ChatView.java`: Main direct chat workflow.
- `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/chat/AgentViewAdapter.java`: AgentRunner event adapter for UI.
- `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/views/BrowserChatPanel.java`: Browser-based chat rendering.
- `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/tools/GetDiagnosticsTool.java`: Dynamic UI diagnostics tool.
- `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/diagnostics/EdtDiagnosticsCollector.java`: Marker/annotation diagnostics collection.
- `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/remote/RemoteWorkbenchBridge.java`: Workbench bridge implementation for remote companion.
- `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/preferences`: Preference pages and provider/profile/MCP configuration UI.

**Testing:**
- `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core`: Core test source root.
- `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/edt/BmObjectHelperTest.java`: BM helper contract test.
- `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/ToolExecutionServiceRepairGateTest.java`: Mutating repaired tool-call guard test.
- `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/provider/config/OpenAiModelCompatibilityPolicyTest.java`: Provider compatibility policy test.
- `bundles/com.codepilot1c.ui.tests/src/com/codepilot1c/ui/views/BrowserChatPanelToolCallTest.java`: UI browser panel test.
- `tests/pom.xml`: Optional desktop UI test module under the `desktop-ui-tests` Maven profile.

**Documentation:**
- `README.md`: User-facing repository overview.
- `AGENTS.md`: Repository-specific agent rules.
- `docs/reports/edt-metadata-uuid-export-runbook.md`: EDT metadata UUID/export incident runbook.
- `docs/reports/edt-api-patterns-retrospective-2026-02-14.md`: EDT BM/API patterns.
- `docs/reports/edt-bm-model-investigation-2026-02-13.md`: EDT BM model investigation.
- `docs/reports/edt-diagnostics-research-2026-02-15.md`: Diagnostics research.
- `docs/reports/tool-graph-router-plan-2026-02-25.md`: Tool graph routing plan.
- `docs/MEMORY_ARCHITECTURE_PLAN.md`: Memory/RAG design notes.

## Naming Conventions

**Files:**
- Java classes use PascalCase with domain suffixes: `AgentRunner.java`, `EdtMetadataService.java`, `GetDiagnosticsTool.java`.
- Tool classes end with `Tool`: `CreateMetadataTool.java`, `EdtValidateRequestTool.java`, `QaRunTool.java`.
- Services end with `Service`: `EdtMetadataService.java`, `BackendService.java`, `MemoryService.java`.
- Registries end with `Registry`: `ToolRegistry.java`, `AgentProfileRegistry.java`, `LlmProviderRegistry.java`.
- Gateways end with `Gateway`: `EdtMetadataGateway.java`, `EdtProjectImportGateway.java`.
- Stores end with `Store`: `LlmProviderConfigStore.java`, `McpHostConfigStore.java`, `MarkdownMemoryStore.java`.
- Eclipse metadata files use PDE names: `MANIFEST.MF`, `plugin.xml`, `feature.xml`, `category.xml`, `build.properties`.
- Tests mirror source names with `Test`: `AgentRunnerBuildRequestTest.java`, `ToolSurfaceSnapshotTest.java`.

**Directories:**
- Bundle directories use Eclipse bundle IDs: `bundles/com.codepilot1c.core`, `bundles/com.codepilot1c.ui`.
- Java package directories mirror package names under `src/com/codepilot1c/...`.
- Core tool packages are grouped by domain: `tools/metadata`, `tools/forms`, `tools/bsl`, `tools/dcs`, `tools/qa`, `tools/workspace`.
- EDT service packages are grouped by EDT domain: `edt/metadata`, `edt/forms`, `edt/dcs`, `edt/extension`, `edt/external`, `edt/lang`, `edt/ast`, `edt/platformdoc`.
- UI packages are grouped by workbench concern: `views`, `handlers`, `preferences`, `diagnostics`, `tools`, `remote`.

**Special Patterns:**
- `@ToolMeta` annotates many `AbstractTool` implementations with name/category/mutation/tags.
- `ToolRegistry.registerDefaultTools()` is the central built-in registration list.
- `registerDynamicTool(...)` is used for runtime tools such as MCP tools and UI-only diagnostics.
- Extension point schemas live in `bundles/com.codepilot1c.core/schema/*.exsd`.
- Plugin localization lives under `OSGI-INF/l10n/`.
- Runtime resources bundled into plugins live under `resources/`, `skills/`, `web/`, `icons/`, and `lib/`.

## Where to Add New Code

**New Built-In Tool:**
- Implementation: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/<domain>/<Name>Tool.java`.
- Registration: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/ToolRegistry.java`.
- Schema/description contract tests: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools`.
- Profile exposure: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/agent/profiles`.
- Prompt alignment: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/agent/prompts`.
- Provider/tool-surface compatibility: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/surface`.

**New UI-Only Tool:**
- Implementation: `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/tools/<Name>Tool.java`.
- Workbench support code: `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/<domain>`.
- Dynamic registration: `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/internal/VibeUiPlugin.java`.
- Tests: `bundles/com.codepilot1c.ui.tests/src/com/codepilot1c/ui` or focused core contract tests if the logic is UI-free.

**New EDT Metadata Mutation:**
- Validation: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/validation`.
- Request/result records and service methods: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata`, `edt/forms`, `edt/dcs`, `edt/extension`, or `edt/external`.
- Tool wrapper: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/metadata`, `tools/forms`, `tools/dcs`, `tools/extension`, or `tools/external`.
- Tests: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/edt` and `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools`.
- Required flow: `edt_validate_request` -> unchanged `validation_token` -> mutation tool -> export/post-check -> diagnostics.

**New EDT Read-Only Analysis:**
- Service implementation: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/<domain>`.
- Tool wrapper: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/<domain>`.
- Safe BM helper usage: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/BmObjectHelper.java`.
- Tests: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/edt/<domain>`.

**New Agent Profile:**
- Profile class: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/agent/profiles/<Name>Profile.java`.
- Registry: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/agent/profiles/AgentProfileRegistry.java`.
- Routing: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/agent/profiles/ProfileRouter.java`.
- Prompt addition: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/agent/prompts/AgentPromptTemplates.java`.
- Tests: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/agent/profiles`.

**New Provider:**
- Built-in legacy provider: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/<provider>` and contribution in `bundles/com.codepilot1c.core/plugin.xml`.
- Dynamic provider behavior: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config`.
- Capability flags: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/ProviderCapabilities.java`.
- Preference UI: `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/preferences`.
- Tests: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/provider`.

**New Prompt Provider Overlay:**
- Interface: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/agent/prompts/IPromptProvider.java`.
- Extension schema: `bundles/com.codepilot1c.core/schema/promptProvider.exsd`.
- Registry behavior: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/agent/prompts/PromptProviderRegistry.java`.

**New LLM Provider Overlay:**
- Interface: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/ILlmProvider.java`.
- Extension schema: `bundles/com.codepilot1c.core/schema/llmProvider.exsd`.
- Registry behavior: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/LlmProviderRegistry.java`.

**New Tool Overlay:**
- Interface: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/ITool.java`.
- Extension schema: `bundles/com.codepilot1c.core/schema/toolProvider.exsd`.
- Runtime loading: `ToolRegistry.loadToolsFromExtensionPoint()` in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/ToolRegistry.java`.

**New UI View/Command/Preference:**
- View: `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/views`.
- Command handler: `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/handlers`.
- Preference page: `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/preferences`.
- Plugin registration: `bundles/com.codepilot1c.ui/plugin.xml`.
- Localization: `bundles/com.codepilot1c.ui/OSGI-INF/l10n`.

**New MCP Client Feature:**
- Config/model/transport: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/config`, `mcp/model`, `mcp/transport`.
- Tool adaptation: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/McpToolAdapter.java`.
- Lifecycle: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/McpServerManager.java`.
- UI preferences: `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/preferences/McpServersPreferencePage.java`.

**New MCP Host Feature:**
- Host config/server/router: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/host`.
- HTTP/OAuth/remote web transport: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/host/transport`.
- Resources: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/host/resource`.
- Prompts: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/host/prompt`.
- Static remote UI: `bundles/com.codepilot1c.core/web/remote`.

**New Memory/Search/RAG Code:**
- Core memory contract: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory`.
- Current replaceable search seam: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory/search/IMemorySearchIndex.java`.
- Default implementation: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory/search/InMemorySearchIndex.java`.
- RAG bundle: Not detected in the current reactor. Add `bundles/com.codepilot1c.rag`, include it in `bundles/pom.xml`, add feature packaging in `features/com.codepilot1c.feature/feature.xml`, and add extension schemas for `embeddingProvider` and `codeChunker` before adding indexing/chunking implementations.

**New QA/Test Automation Code:**
- Core QA services: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/qa`.
- QA tools: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/qa`.
- Runtime/debug tools: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/debug`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/runtime`.
- Tests: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/qa/tests`, `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools`.

**New Release/Packaging Code:**
- Core/UI bundle metadata: `bundles/<bundle>/META-INF/MANIFEST.MF`, `bundles/<bundle>/build.properties`, `bundles/<bundle>/plugin.xml`.
- Bundle reactor entry: `bundles/pom.xml`.
- Feature inclusion: `features/com.codepilot1c.feature/feature.xml`.
- Update site: `repositories/com.codepilot1c.update/category.xml`.
- Target additions: `targets/default/default.target`.

## Special Directories

**`bundles/*/target/`:**
- Purpose: Maven/Tycho build output.
- Generated: Yes.
- Committed: No.

**`.runs/`:**
- Purpose: Local run workspaces, smoke/e2e output, queue/plan worktrees, EDT workspaces.
- Generated: Yes.
- Committed: Local artifact directory; do not treat as source architecture.

**`output/`:**
- Purpose: Local generated artifacts such as Playwright output.
- Generated: Yes.
- Committed: No source ownership implied.

**`scratchpad/`:**
- Purpose: Local scratch files in this working tree.
- Generated: Manual/local.
- Committed: Not part of the Tycho product structure.

**`qwen-code/`:**
- Purpose: Embedded separate repository/vendor workspace.
- Generated: No.
- Committed: Treat as separate project boundary; do not place CodePilot Eclipse plugin source here.

**`.codex/`:**
- Purpose: Repository-local GSD/Codex workflow assets.
- Generated: No.
- Committed: Yes, but separate from Eclipse runtime bundles.

**`.planning/`:**
- Purpose: GSD planning/state/codebase intelligence artifacts.
- Generated: Yes.
- Committed: Workflow-owned; codebase mapper writes only `.planning/codebase/` for this task.

---

*Structure analysis: 2026-07-01*
