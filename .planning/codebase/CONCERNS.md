# Codebase Concerns

**Analysis Date:** 2026-07-01

## Tech Debt

**Monolithic EDT metadata mutation service:**
- Issue: `EdtMetadataService` is 9,605 lines and owns top-level metadata creation, form creation/mutation, UUID repair, type assignment, export scheduling, serialization polling, delete cleanup, and assorted EDT service lookups.
- Files: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java`
- Impact: Small EDT metadata changes can accidentally affect export timing, UUID repair, reserved-name checks, delete cleanup, forms, DCS, extensions, or external objects. Regression localization is hard because many responsibilities share private helpers and constants.
- Fix approach: Keep new EDT mutation behavior behind small service methods or new helper classes called from `EdtMetadataService`; preserve `executeWrite` + export + post-check ordering; add focused tests around the public tool/service contract before touching shared private helpers.

**Export and serialization are coupled in one large post-mutation path:**
- Issue: BM write commits, `forceExport(...)`, derived-data waits, `waitModelSynchronization(...)`, and `Configuration.mdo` polling are all coordinated manually inside `forceExportTopLevelObject()` and `verifyConfigurationEntryPersisted()`.
- Files: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java`
- Impact: `forceExport(...)` scheduling and `waitModelSynchronization(...)` completion can still leave filesystem serialization lagging; a successful BM mutation can be reported while `src/Configuration/Configuration.mdo` is not yet updated.
- Fix approach: Preserve the current two-phase mental model: BM commit is authoritative, filesystem serialization is a separate observed side effect. Any new mutation path must force export, wait derived data, poll `src/Configuration/Configuration.mdo` when top-level links change, refresh resources, and leave trace diagnostics with `opId`.

**Provider/Qwen compatibility instructions do not match current source shape:**
- Issue: Current provider code uses generic CodePilot backend capabilities and OpenAI-compatible profiles; Qwen-specific classes referenced by project constraints are not present under `bundles/`.
- Files: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/ProviderCapabilities.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config/DynamicLlmProvider.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config/OpenAiCompatibilityProfileResolver.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config/ContentToolCallFallbackParser.java`
- Impact: A future Qwen optimization can easily be added to the wrong layer by modifying `buildOpenAiRequestBody()` or by applying Qwen behavior to all OpenAI-compatible providers.
- Fix approach: Add an explicit provider-gated Qwen capability surface before adding Qwen-specific request shaping. Keep structured tools as the primary channel, content/XML parsing as fallback, and do not alter generic OpenAI-compatible behavior for model-family quirks.

**RAG/indexing bundle and extension points are absent from the current bundle set:**
- Issue: The repository currently contains `com.codepilot1c.core`, `com.codepilot1c.ui`, and test bundles only; `com.codepilot1c.rag`, `com.codepilot1c.rag.codeChunker`, and `com.codepilot1c.core.embeddingProvider` are not declared in the active `bundles/` tree.
- Files: `bundles/com.codepilot1c.core/plugin.xml`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory/search/InMemorySearchIndex.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory/MemoryService.java`
- Impact: New indexing or embedding work can drift into hardcoded core logic instead of an extension/overlay model. Current memory search is in-process Jaccard search, not a scalable RAG pipeline.
- Fix approach: Introduce chunkers and embedding providers through extension points before adding language-specific indexing. Keep `MemoryService.setSearchIndex()` as the narrow replacement seam for a future BM25/vector index.

**Memory extraction retry path is declared but not wired to startup:**
- Issue: `MemoryExtractionListener` writes `.extraction-pending` files and logs that failed LLM extraction retries on next startup, but the only reference to `processPendingRequests(...)` is the method definition itself.
- Files: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory/extraction/MemoryExtractionListener.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory/extraction/LlmMemoryExtractor.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory/MemoryService.java`
- Impact: Failed or interrupted LLM memory extraction can leave pending files stranded until a caller is added. Operators may believe the queue is crash-safe while retry is inactive.
- Fix approach: Wire pending processing into memory initialization or plugin startup with a bounded executor and tests that verify a saved pending file is retried or expired.

**Dynamic UI tool registration has no matching unregister on UI plugin stop:**
- Issue: `VibeUiPlugin` registers `get_diagnostics` as a dynamic tool after workbench startup and unregisters the remote workbench bridge on stop, but does not unregister `get_diagnostics`.
- Files: `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/internal/VibeUiPlugin.java`, `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/tools/GetDiagnosticsTool.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/ToolRegistry.java`
- Impact: A stopped/restarted UI bundle can leave a workbench-dependent tool visible through the core registry while UI services are unavailable or stale.
- Fix approach: Call `ToolRegistry.getInstance().unregisterDynamicTool("get_diagnostics")` during UI plugin stop, and add a lifecycle test around dynamic tool registration.

**Large UI and provider classes concentrate unrelated workflow logic:**
- Issue: `ChatView` is 3,662 lines, `BrowserChatPanel` is 1,413 lines, `EdtDiagnosticsCollector` is 1,328 lines, and `DynamicLlmProvider` is 1,235 lines.
- Files: `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/views/ChatView.java`, `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/views/BrowserChatPanel.java`, `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/diagnostics/EdtDiagnosticsCollector.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config/DynamicLlmProvider.java`
- Impact: UI thread behavior, browser rendering, diagnostics collection, provider request construction, streaming, and fallback parsing are difficult to change independently.
- Fix approach: Keep new code in small collaborators with tests, and avoid adding more workflow branches directly to these classes.

## Known Bugs

**Browser apply-code action is a stub:**
- Symptoms: Browser-rendered chat can emit an apply-code callback, but `ChatView` only logs the request and does not apply code to a file.
- Files: `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/views/ChatView.java`
- Trigger: Click an apply-code action from `BrowserChatPanel`.
- Workaround: Use file tools or manual editor operations instead of browser apply-code.

**Regenerate action is a visible but unimplemented UI affordance:**
- Symptoms: Assistant message bubbles create a regenerate button without a handler.
- Files: `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/views/MessageBubbleComposite.java`
- Trigger: Use message actions for an assistant response.
- Workaround: Send a new prompt manually.

**Profiling tools return placeholder behavior:**
- Symptoms: `startProfiling()` reports enabled/disabled status without EDT debug integration, and `getProfilingResults()` returns an empty result list.
- Files: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/ast/EdtAstService.java`
- Trigger: Use profiling tools against an EDT project.
- Workaround: Use EDT-native profiling/debug tooling until service integration is implemented.

**Diff calculation replaces the full content:**
- Symptoms: `CodeDiffUtils.calculateDiff(...)` returns one replacement for the entire text when any change exists.
- Files: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/diff/CodeDiffUtils.java`
- Trigger: Generate a diff for partially changed content.
- Workaround: Use targeted SEARCH/REPLACE editing paths where possible.

**Pending memory extraction files have no active startup consumer:**
- Symptoms: `.extraction-pending` files remain after failed LLM memory extraction because no startup caller invokes `LlmMemoryExtractor.processPendingRequests(...)`.
- Files: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory/extraction/MemoryExtractionListener.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory/extraction/LlmMemoryExtractor.java`
- Trigger: Session completion with LLM extraction failure or plugin shutdown during extraction.
- Workaround: Re-run extraction through a new session, or add the missing startup retry hook.

## Security Considerations

**Validation tokens are short-lived but logged too verbosely:**
- Risk: `consumeToken(...)` and `ValidationTokenStore` log validation token values with `LogSanitizer.truncate(token, 80)`. UUID tokens are shorter than 80 characters, so logs can contain the whole token.
- Files: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/validation/MetadataRequestValidationService.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/validation/ValidationTokenStore.java`
- Current mitigation: Tokens are in-memory, one-time use, scoped to operation/project, and expire after 5 minutes.
- Recommendations: Log a hash or fixed short prefix only. Keep raw/normalized payload logging behind secret redaction and bounded truncation.

**MCP host defaults expose broad local tool surface:**
- Risk: MCP host defaults enable HTTP, bind to `127.0.0.1`, expose `*`, and set mutation policy to `ALLOW`.
- Files: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/host/McpHostConfig.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/host/DefaultMcpToolExposurePolicy.java`, `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/preferences/McpHostPreferencePage.java`
- Current mitigation: Default bind address is local, auth defaults to OAuth-or-bearer, non-local binding shows a warning, and individual tools can require confirmation or mark themselves destructive.
- Recommendations: Prefer deny/ask defaults for mutating tools when binding is not loopback; keep `*, -edit_file, -write_file` style filters visible and tested; treat non-local `NONE` auth as invalid, not just warned.

**Bearer token is intentionally visible in preferences and install hints:**
- Risk: The preference page renders the bearer token in a normal `Text` widget and embeds it in generated MCP client configuration snippets.
- Files: `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/preferences/McpHostPreferencePage.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/host/McpHostConfigStore.java`
- Current mitigation: Token storage uses secure storage in `McpHostConfigStore`; UI supports token rotation.
- Recommendations: Mask token display by default, provide explicit reveal/copy actions, and avoid including tokens in screenshots or persisted diagnostics.

**Remote web session cookie lacks `Secure`:**
- Risk: Remote web login creates an `HttpOnly; SameSite=Lax` cookie without `Secure`, and endpoints are built as `http://...`.
- Files: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/host/transport/RemoteWebController.java`, `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/mcp/host/transport/RemoteWebControllerTest.java`
- Current mitigation: Default host binding is loopback and cookie path is limited to `/remote/api`.
- Recommendations: Keep remote web strictly local unless TLS is introduced; add configuration validation that blocks non-loopback remote UI with cookie auth over plain HTTP.

**Emergency `.mdo` edit override bypasses the BM mutation path:**
- Risk: `edit_file` can edit `.mdo` descriptors when `allow_metadata_descriptor_edit=true`, bypassing validation tokens, BM APIs, export scheduling, and diagnostics.
- Files: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/file/EditFileTool.java`
- Current mitigation: `.mdo` edits are blocked by default and structured `.form`, `.mxl`, and DCS artifacts remain blocked.
- Recommendations: Keep this override reserved for explicit emergency recovery. Do not expose it in normal prompts or provider examples.

**Memory secret filtering is regex-based and lossy:**
- Risk: `SecretGuard` can miss context-specific secrets and can also redact long base64-like non-secrets, affecting persisted memory quality.
- Files: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory/extraction/SecretGuard.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory/extraction/LlmMemoryExtractor.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory/extraction/MemoryExtractor.java`
- Current mitigation: Both rule-based and LLM memory extraction apply secret filtering before persistence.
- Recommendations: Add test fixtures for 1C connection strings, bearer tokens, SSH/private key fragments, and benign long identifiers. Treat memory writes as untrusted until filtered.

## Performance Bottlenecks

**EDT export waits can block long operations for minutes:**
- Problem: Export-derived data wait defaults to 120 seconds, configuration serialization polls for 30 seconds, and the service also calls important-data waits and model synchronization.
- Files: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java`
- Cause: BM commit, derived data, filesystem serialization, and resource refresh are separate EDT phases.
- Improvement path: Keep mutation tools asynchronous and always include `opId` logging. Add timing metrics around export phases before changing wait constants.

**In-memory memory search scales linearly and uses CopyOnWrite lists:**
- Problem: Search and duplicate detection iterate all indexed memory entries for a project and compute Jaccard similarity in memory.
- Files: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory/search/InMemorySearchIndex.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory/MemoryService.java`
- Cause: The current implementation is intentionally lightweight and documented as suitable for hundreds of entries.
- Improvement path: Replace through `IMemorySearchIndex` and `MemoryService.setSearchIndex(...)` with a BM25/vector implementation before relying on project-scale recall.

**LLM memory extraction blocks worker threads up to 45 seconds per session:**
- Problem: `LlmMemoryExtractor.extract(...)` builds a transcript and waits synchronously on `provider.complete(...).get(...)`.
- Files: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory/extraction/LlmMemoryExtractor.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory/extraction/MemoryExtractionListener.java`
- Cause: Extraction is wrapped in `CompletableFuture.runAsync(...)` without an explicit bounded executor.
- Improvement path: Use a named bounded executor for extraction, enforce queue limits, and make pending retry processing backoff-aware.

**Diagnostics collection can scan broad workspace/project state:**
- Problem: `get_diagnostics` defaults to project/workspace diagnostics when no file or project is specified and can include runtime markers.
- Files: `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/tools/GetDiagnosticsTool.java`, `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/diagnostics/EdtDiagnosticsCollector.java`
- Cause: The UI diagnostic tool is designed for live workbench diagnostics and broad auto-fix workflows.
- Improvement path: Prefer `scope=file`, `scope=active_editor`, `project_name`, and `object` filters in prompts and tests. Keep `wait_ms` bounded.

**Large tool results need explicit provider policy review:**
- Problem: OpenAI-compatible policy switches streaming behavior when any tool result exceeds 50,000 characters or request text exceeds 120,000 characters.
- Files: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config/OpenAiModelCompatibilityPolicy.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config/DynamicLlmProvider.java`
- Cause: Some providers degrade or corrupt streaming tool calls with large contexts.
- Improvement path: Add new high-volume tools to compatibility tests and ensure results are truncated or paged before hitting provider-specific thresholds.

## Fragile Areas

**BM object FQN access must go through top-object normalization:**
- Files: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/BmObjectHelper.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/ast/EdtReferenceService.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/platformdoc/EdtPlatformDocumentationService.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java`
- Why fragile: Calling `bmGetFqn()` on non-top or transient BM objects throws in EDT runtime paths.
- Safe modification: Null-check BM objects, normalize to `bmGetTopObject()` when not top, and call `bmGetFqn()` only on the verified top object. Prefer `BmObjectHelper.safeTopFqn(...)`.
- Test coverage: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/edt/BmObjectHelperTest.java` covers helper behavior; new direct BM usages need code review.

**Reserved 1C standard attributes are guarded only in the child-attribute path:**
- Files: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java`
- Why fragile: `validateReservedChildName(...)` guards `MetadataChildKind.ATTRIBUTE` using live `standardAttributes` or fallback maps plus Russian aliases. New child kinds or parent classes can bypass this if not routed through the same check.
- Safe modification: Before adding any attribute-like child creation path, call the reserved-name guard or extend the fallback/alias maps for the new parent type.
- Test coverage: Schema tests exist for `add_metadata_child`, but direct reserved-name behavior needs focused service tests with English and Russian aliases.

**Filesystem cleanup after BM delete is destructive:**
- Files: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java`
- Why fragile: `cleanupRemovedFilesystemArtifacts(...)` deletes metadata folders, `.mdo`, `.form`, and `Module.bsl` artifacts after BM delete verification.
- Safe modification: Keep BM post-verify before cleanup, restrict paths to derived metadata artifact locations, refresh after deletion, and never reuse this helper for arbitrary workspace files.
- Test coverage: No direct test references were found for cleanup helpers or delete export post-checks.

**Tool registry precedence affects dynamic MCP/UI contributions:**
- Files: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/ToolRegistry.java`, `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/internal/VibeUiPlugin.java`
- Why fragile: Built-in tools take precedence over dynamic tools with the same name, while `getAllTools()` merges dynamic then built-in. A new dynamic tool name collision silently resolves to the built-in implementation.
- Safe modification: Register built-ins only in `ToolRegistry.registerDefaultTools()`, use dynamic tools for runtime/UI/MCP contributions, and add name-collision tests for new dynamic providers.
- Test coverage: Tool surface tests exist under `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/surface/`, but UI lifecycle unregister coverage is missing.

**Content-based tool-call fallback is regex-driven:**
- Files: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config/ContentToolCallFallbackParser.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config/OpenAiStreamingSession.java`, `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/provider/config/ContentToolCallFallbackParserTest.java`
- Why fragile: Fallback extraction supports XML, JSON-in-tool-call blocks, and Kimi/Moonshot markers. Small prompt or provider output changes can produce visible raw tool calls or missed tool execution.
- Safe modification: Keep structured API tool calls primary. Add parser fixtures for any new model-family marker format and keep mutating tools strict on malformed/repaired arguments.
- Test coverage: Parser and streaming tests exist, but every new provider family or tool priming format needs targeted cases.

**Release/update-site output depends on full reactor ordering:**
- Files: `pom.xml`, `bom/pom.xml`, `repositories/com.codepilot1c.update/pom.xml`, `targets/default/default.target`, `features/com.codepilot1c.feature/feature.xml`
- Why fragile: Tycho builds the target platform, bundles, features, and p2 repository as a reactor. Partial repository builds can produce unresolved or stale feature artifacts.
- Safe modification: For deliverables, run `mvn -DskipTests package` from the repository root and consume only `repositories/com.codepilot1c.update/target/repository` or the update ZIP. Verify `content.jar` and plugin qualifiers after build.
- Test coverage: Unit tests do not validate p2 repository completeness or qualifier freshness.

## Scaling Limits

**Memory subsystem is local and small-store oriented:**
- Current capacity: `InMemorySearchIndex` documents itself as suitable for hundreds of entries.
- Files: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory/search/InMemorySearchIndex.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory/store/MarkdownMemoryStore.java`
- Limit: Large project memory stores make search/dedup slower and less relevant because there is no BM25/vector retrieval in the active tree.
- Scaling path: Add a replaceable search/index implementation through OSGi or a future RAG bundle, then migrate `MemoryService` to discover it instead of relying on manual `setSearchIndex(...)`.

**Validation tokens are in-memory only:**
- Current capacity: Short-lived tokens are held in a singleton `ConcurrentHashMap`.
- Files: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/validation/ValidationTokenStore.java`
- Limit: Tokens vanish on plugin restart and cannot coordinate across multiple EDT/plugin processes.
- Scaling path: Keep this design for local single-process EDT safety; do not build multi-process mutation flows that assume token persistence.

**MCP remote host is local-workbench oriented:**
- Current capacity: Defaults bind to `127.0.0.1` and use local preferences/secure storage for auth settings.
- Files: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/host/McpHostConfig.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/host/transport/RemoteWebController.java`, `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/preferences/McpHostPreferencePage.java`
- Limit: Remote web and MCP host controls are not a hardened multi-user server boundary.
- Scaling path: Keep non-loopback deployments behind explicit auth, TLS, narrower tool filters, and mutation confirmation policy.

**EDT mutation throughput is bounded by EDT serialization and diagnostics:**
- Current capacity: Each high-level mutation can wait on BM transactions, derived-data export, filesystem serialization, project refresh, and follow-up diagnostics.
- Files: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java`, `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/tools/GetDiagnosticsTool.java`
- Limit: Batch mutation workflows can accumulate minutes of latency and leave stale diagnostics if checks are skipped.
- Scaling path: Batch only when the BM/export API supports it; otherwise preserve one mutation + export + diagnostics loop per user-visible change.

## Dependencies at Risk

**Tycho/Eclipse target platform version drift:**
- Risk: Build behavior depends on Tycho 4.0.4, Java 17, the `targets/default/default.target` platform, and Eclipse/EDT bundles.
- Impact: Updating Tycho, target definitions, or EDT bundle versions can break OSGi resolution, generated qualifiers, and update-site contents.
- Migration plan: Change target/build dependencies in a dedicated release-prep phase and verify with a full root reactor build plus p2 qualifier checks.
- Files: `bom/pom.xml`, `pom.xml`, `targets/default/default.target`, `repositories/com.codepilot1c.update/pom.xml`

**Provider behavior depends on model-name heuristics:**
- Risk: `OpenAiCompatibilityProfileResolver` selects streaming, reasoning, and temperature behavior from normalized model names.
- Impact: New CodePilot backend model names can fall into `codepilot-standard` and miss required non-streaming or reasoning-content behavior.
- Migration plan: Add explicit profile tests for every new backend model family before enabling it in UI/provider configuration.
- Files: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config/OpenAiCompatibilityProfileResolver.java`, `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/provider/config/OpenAiCompatibilityProfileResolverTest.java`

**Committed binary UI library requires supply-chain tracking:**
- Risk: The UI bundle carries a local `flexmark-util-builder-0.64.8.jar`.
- Impact: Security updates and license review are harder than Maven/Tycho-managed dependencies.
- Migration plan: Track the binary in release review, or move it into a managed target/dependency flow if Tycho packaging allows it.
- Files: `bundles/com.codepilot1c.ui/lib/flexmark-util-builder-0.64.8.jar`, `bundles/com.codepilot1c.ui/META-INF/MANIFEST.MF`

**EDT internal API reflective lookups are version-sensitive:**
- Risk: `EdtMetadataService` resolves form, platform, rights, generator, and Guice services using bundle/class names.
- Impact: EDT version changes can break form generation, rights handling, UUID repair, or metadata mutations at runtime while compilation still passes.
- Migration plan: Keep EDT baseline pinned for local/K8s tests and add smoke tests for form, rights, metadata, DCS, extension, and external-object flows before changing EDT versions.
- Files: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java`, `docs/reports/edt-bm-model-investigation-2026-02-13.md`, `docs/reports/edt-api-patterns-retrospective-2026-02-14.md`

## Missing Critical Features

**Active RAG bundle and chunker/embedding extension points are missing:**
- Problem: The current active source tree has no `bundles/com.codepilot1c.rag` bundle and core plugin XML declares only LLM, tool, and prompt provider extension points.
- Blocks: Scalable code search, language-specific chunker contribution, vector storage, and embedding-provider failure reporting.
- Files: `bundles/com.codepilot1c.core/plugin.xml`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory/search/InMemorySearchIndex.java`

**Qwen-specific compatibility surface is missing from source:**
- Problem: Project rules require Qwen-specific provider gating, model-family resolution, request transport, examples, XML content fallback, streaming parser behavior, and JSON repair policy, but current source exposes generic provider capabilities and content fallback.
- Blocks: Safe addition of Qwen-native optimizations without touching the generic OpenAI-compatible path.
- Files: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/ProviderCapabilities.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config/DynamicLlmProvider.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config/OpenAiModelCompatibilityPolicy.java`

**Automated release artifact qualifier sanity check is not represented in tests:**
- Problem: The release rule requires post-build verification of `content.jar` and plugin qualifiers, but no test or Maven verification step enforces it.
- Blocks: Confident update-site delivery from `repositories/com.codepilot1c.update/target/repository`.
- Files: `repositories/com.codepilot1c.update/pom.xml`, `pom.xml`

**Memory pending retry startup hook is missing:**
- Problem: `LlmMemoryExtractor.processPendingRequests(...)` exists but is not called.
- Blocks: Crash-safe automatic memory extraction retry.
- Files: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory/extraction/LlmMemoryExtractor.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory/MemoryService.java`

## Test Coverage Gaps

**Real EDT export/serialization integration is under-covered:**
- What's not tested: `forceExportTopLevelObject(...)`, delayed `Configuration.mdo` serialization, derived-data timeout handling, delete artifact cleanup, and post-mutation diagnostics against a live EDT workspace.
- Files: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java`, `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/edt/metadata/EdtMetadataFollowupContractTest.java`, `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/edt/metadata/EdtMetadataTypeSpecContractTest.java`
- Risk: Metadata mutation can pass unit/contract tests while failing to materialize correct `.mdo`, `.form`, or `Configuration.mdo` output.
- Priority: High

**Reserved attribute guard needs service-level cases:**
- What's not tested: Blocking standard attributes across Catalog, Document, InformationRegister, AccumulationRegister, tabular sections, and Russian aliases such as `Наименование`, `Номер`, `Период`, and `Активность`.
- Files: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java`, `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/metadata/AddMetadataChildToolSchemaTest.java`
- Risk: A new child creation path can create invalid/conflicting 1C metadata attributes.
- Priority: High

**Qwen/CodePilot backend streaming tool-call coverage is incomplete for future requirements:**
- What's not tested: Qwen-specific XML priming, model-family capability gating, fallback XML examples for every new tool, and finish-reason override behavior for pending tool calls.
- Files: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config/ContentToolCallFallbackParser.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config/OpenAiStreamingSession.java`, `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/provider/config/DynamicLlmProviderStreamingTest.java`, `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/provider/config/ContentToolCallFallbackParserTest.java`
- Risk: New tools can work with structured OpenAI calls but fail through CodePilot/Qwen content or streaming fallback paths.
- Priority: High

**MCP host security defaults need policy tests:**
- What's not tested: Non-loopback bind with `AuthMode.NONE`, broad `*` exposure with mutation policy `ALLOW`, visible bearer-token install hints, and destructive tool confirmation behavior under remote MCP calls.
- Files: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/host/McpHostConfig.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/host/DefaultMcpToolExposurePolicy.java`, `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/preferences/McpHostPreferencePage.java`, `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/mcp/host/transport/RemoteWebControllerTest.java`
- Risk: A configuration change can expose mutating tools beyond intended local trust boundaries.
- Priority: High

**UI dynamic tool lifecycle is untested:**
- What's not tested: Registration and unregistration of `get_diagnostics` across UI plugin start/stop and workbench availability.
- Files: `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/internal/VibeUiPlugin.java`, `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/tools/GetDiagnosticsTool.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/ToolRegistry.java`
- Risk: Core tool surfaces can retain UI-only tools after the UI bundle stops.
- Priority: Medium

**Memory extraction retry and scaling are under-tested:**
- What's not tested: Pending queue startup retry, retry-count updates, bounded concurrent extraction, SecretGuard false positives/negatives, and large memory stores.
- Files: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory/extraction/MemoryExtractionListener.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory/extraction/LlmMemoryExtractor.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory/extraction/SecretGuard.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory/search/InMemorySearchIndex.java`
- Risk: Memory can silently lose retry guarantees, store sensitive content, or become slow in real projects.
- Priority: Medium

**Release/update-site verification has no automated guard:**
- What's not tested: Full reactor-only delivery, update-site completeness, `content.jar` qualifier freshness, and plugin qualifier match against expected latest build.
- Files: `pom.xml`, `bom/pom.xml`, `repositories/com.codepilot1c.update/pom.xml`, `features/com.codepilot1c.feature/feature.xml`
- Risk: Partial/stale p2 repository content can be published even when the source tree builds in smaller scopes.
- Priority: High

---

*Concerns audit: 2026-07-01*
