# Provider-Neutral Tool Surface Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Удалить Qwen/CodePilot-conditioned tool surface и оставить один provider-neutral model-facing contract для ChatView, AgentRunner, `discover_tools` и MCP host без изменения исполнения tools.

**Architecture:** `ToolSurfaceAugmentor` остаётся общей точкой сборки effective `ToolDefinition`, но его context и contributors больше не получают provider state. Восемь централизованных schema overrides сверяются с raw/runtime contracts, после чего `ToolRegistry` строит surface только из profile, category и built-in/dynamic provenance; generic OpenAI compatibility parsers и внешние Qwen workflows сохраняются.

**Tech Stack:** Java 17, Eclipse RCP/OSGi, Gson, JUnit 4, Maven/Tycho, Python 3 inventory generator.

## Global Constraints

- Выполнять этот cleanup отдельной серией изменений до M002; `docs/superpowers/specs/2026-07-16-unified-agent-runtime-design.md` не изменять.
- `com.codepilot1c.core` не должен зависеть от UI workbench APIs; UI-only tools остаются dynamic contributions из `com.codepilot1c.ui`.
- Не менять `ToolRegistry.registerDefaultTools()`, built-in-over-dynamic precedence, profile allowlists, permissions, `ToolContextGate`, `ToolGraph`, deferred loading или `ToolExecutionService`.
- Сохранить `ContentToolCallFallbackParser`, `JsonRepairUtil`, `OpenAiStreamingToolCallParser`, `OpenAiStreamingSession`, `OpenAiCompatibilityProfile*` и `OpenAiModelCompatibilityPolicy`.
- Сохранить `evals/qwen/`, Qwen CLI/MCP runners, qwen-codex queue, README-инструкции и исторические release notes/plans.
- `ProviderSelectionGate` сохранить для prompts, filesystem prompt overrides и skills; tool-surface assembly не должен его читать.
- Не добавлять compatibility flag и не оставлять параллельную backend-gated surface.
- `AGENTS.md` игнорируется `.gitignore`; обновить только текущую working copy и не использовать `git add -f` без отдельного решения пользователя.
- Для release/update delivery допустим только полный reactor build `mvn -DskipTests package`; публикацию update site в этой задаче не выполнять.
- Все production changes выполнять через failing test → минимальная реализация → passing test → отдельный commit.

---

## File Map

### Production files

- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/surface/ProviderNeutralToolSurfaceRewriteContributor.java` — provider-neutral overrides описаний и built-in schema normalization; заменяет `BackendToolSurfaceRewriteContributor.java`.
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/surface/ToolRoutingSurfaceContributor.java` — общие category routing hints без provider gate.
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/surface/DynamicToolSurfaceContributor.java` — общая external/dynamic guidance и schema hardening.
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/surface/ToolSurfaceSchemaNormalizer.java` — восемь explicit schema overrides и recursive hardening.
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/surface/ToolSurfaceContext.java` — только profile, category и built-in provenance.
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/ToolRegistry.java` — локально создаёт provider-neutral context.
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/ActiveProviderConfigResolver.java` — сохраняет только read-only lookup active config, нужный `resolve_web_client_url`; не участвует в tool surface.
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/ProviderContextResolver.java` — удалить.
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/diagnostics/ResolveWebClientUrlTool.java` — перевести с surface resolver на узкий active-config resolver.
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/ProviderCapabilities.java` и `ProviderUtils.java` — удалить dead `backendOptimizations` contract.
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/metadata/EdtValidateRequestTool.java` — raw schema публикует полный runtime operation set.
- `tools/generate_tool_prompt_inventory.py` — фактический OpenAI-compatible provider flow без удалённых Qwen классов.
- `AGENTS.md` — локальные provider-neutral правила вместо устаревшего Qwen checklist; ignored file не входит в commit.

### Test files

- `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/surface/ToolSurfaceSchemaParityTest.java` — property/required/enum parity восьми overrides.
- `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/surface/ProviderNeutralToolSurfaceContributorTest.java` — replacement для backend-named contributor test.
- `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/surface/ToolSurfaceSnapshotTest.java` — provider invariance и size envelope.
- `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/surface/ToolSurfaceContextTest.java` — provider-neutral context state.
- `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/surface/ToolRegistryAugmentorRuntimeTest.java` — registry/MCP consumer parity.
- `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/meta/DiscoverToolsToolSurfaceTest.java` — `discover_tools` consumer parity.
- `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/surface/QwenRuntimeReferenceGuardTest.java` — live-source/reference guard с allowlist через ограниченный scope.
- Existing provider, AgentRunner и LangGraph tests — удалить wiring к исчезающим APIs и сохранить transport/filtering assertions.

---

### Task 1: Reconcile the Centralized Schema and Description Contracts

**Files:**

- Create: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/surface/ToolSurfaceSchemaParityTest.java`
- Modify: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/EdtValidateRequestToolSchemaTest.java:1-24`
- Modify: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/surface/BackendToolSurfaceContributorTest.java:15-88`
- Modify: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/metadata/EdtValidateRequestTool.java:27-48`
- Modify: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/surface/ToolSurfaceSchemaNormalizer.java:20-236`
- Modify: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/surface/BackendToolSurfaceRewriteContributor.java:29-120`

**Interfaces:**

- Consumes: `ValidationOperation.values()`, `ValidationOperation.getToolName()`, raw `ITool.getParameterSchema()`.
- Produces: identical primary property/required sets for raw and normalized schemas; public validation operations = enum operations plus `external_manage`, `extension_manage`, `dcs_manage`.

- [ ] **Step 1: Replace substring checks with a parsed runtime-operation contract**

Use Gson in `EdtValidateRequestToolSchemaTest` and compare the raw schema to the actual validation enum plus the three accepted composite entry points:

```java
private static Set<String> runtimeOperationNames() {
    Set<String> names = Arrays.stream(ValidationOperation.values())
            .map(ValidationOperation::getToolName)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    names.addAll(Set.of("external_manage", "extension_manage", "dcs_manage")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    return names;
}

@Test
public void schemaListsEveryPublicValidationOperation() {
    JsonObject schema = JsonParser.parseString(new EdtValidateRequestTool().getParameterSchema())
            .getAsJsonObject();
    JsonArray values = schema.getAsJsonObject("properties") //$NON-NLS-1$
            .getAsJsonObject("operation") //$NON-NLS-1$
            .getAsJsonArray("enum"); //$NON-NLS-1$
    Set<String> actual = StreamSupport.stream(values.spliterator(), false)
            .map(JsonElement::getAsString)
            .collect(Collectors.toCollection(LinkedHashSet::new));

    assertEquals(runtimeOperationNames(), actual);
    assertTrue(new EdtValidateRequestTool().getDescription().contains("template")); //$NON-NLS-1$
}
```

- [ ] **Step 2: Add failing parity tests for all eight explicit overrides**

Create `ToolSurfaceSchemaParityTest` in the `surface` package so it can call package-private `ToolSurfaceSchemaNormalizer`:

```java
public class ToolSurfaceSchemaParityTest {
    private static final Map<String, ITool> RAW_TOOLS = Map.of(
            "read_file", new ReadFileTool(), //$NON-NLS-1$
            "list_files", new ListFilesTool(), //$NON-NLS-1$
            "glob", new GlobTool(), //$NON-NLS-1$
            "grep", new GrepTool(), //$NON-NLS-1$
            "edit_file", new EditFileTool(), //$NON-NLS-1$
            "write_file", new WriteTool(), //$NON-NLS-1$
            "edt_validate_request", new EdtValidateRequestTool(), //$NON-NLS-1$
            "ensure_module_artifact", new EnsureModuleArtifactTool()); //$NON-NLS-1$

    @Test
    public void explicitOverridesPreservePrimaryPropertiesAndRequiredFields() {
        for (Map.Entry<String, ITool> entry : RAW_TOOLS.entrySet()) {
            JsonObject raw = parse(entry.getValue().getParameterSchema());
            JsonObject effective = parse(ToolSurfaceSchemaNormalizer.normalizeBuiltIn(
                    entry.getKey(), entry.getValue().getParameterSchema()));

            assertEquals(entry.getKey(), propertyNames(raw), propertyNames(effective));
            assertEquals(entry.getKey(), requiredNames(raw), requiredNames(effective));
            assertFalse(entry.getKey(), effective.get("additionalProperties").getAsBoolean()); //$NON-NLS-1$
        }
    }

    @Test
    public void validationOverrideListsEveryPublicRuntimeOperation() {
        JsonObject effective = parse(ToolSurfaceSchemaNormalizer.normalizeBuiltIn(
                "edt_validate_request", new EdtValidateRequestTool().getParameterSchema())); //$NON-NLS-1$
        assertEquals(runtimeOperationNames(), enumValues(effective, "operation")); //$NON-NLS-1$
    }

    @Test
    public void writeFileOverrideAdvertisesDocumentationCreation() {
        JsonObject effective = parse(ToolSurfaceSchemaNormalizer.normalizeBuiltIn(
                "write_file", new WriteTool().getParameterSchema())); //$NON-NLS-1$
        String pathDescription = effective.getAsJsonObject("properties") //$NON-NLS-1$
                .getAsJsonObject("path").get("description").getAsString(); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(pathDescription.contains("*.md")); //$NON-NLS-1$
        assertTrue(pathDescription.contains("*.txt")); //$NON-NLS-1$
    }

    @Test
    public void explicitNumericRangesMatchRuntimeLimits() {
        JsonObject read = parse(ToolSurfaceSchemaNormalizer.normalizeBuiltIn(
                "read_file", new ReadFileTool().getParameterSchema())); //$NON-NLS-1$
        assertEquals(1, property(read, "start_line").get("minimum").getAsInt()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, property(read, "end_line").get("minimum").getAsInt()); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject glob = parse(ToolSurfaceSchemaNormalizer.normalizeBuiltIn(
                "glob", new GlobTool().getParameterSchema())); //$NON-NLS-1$
        assertEquals(500, property(glob, "max_results").get("maximum").getAsInt()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(property(glob, "max_results").has("minimum")); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject grep = parse(ToolSurfaceSchemaNormalizer.normalizeBuiltIn(
                "grep", new GrepTool().getParameterSchema())); //$NON-NLS-1$
        assertFalse(property(grep, "context_lines").has("minimum")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static JsonObject parse(String schema) {
        return JsonParser.parseString(schema).getAsJsonObject();
    }

    private static Set<String> propertyNames(JsonObject schema) {
        return new LinkedHashSet<>(schema.getAsJsonObject("properties").keySet()); //$NON-NLS-1$
    }

    private static JsonObject property(JsonObject schema, String name) {
        return schema.getAsJsonObject("properties").getAsJsonObject(name); //$NON-NLS-1$
    }

    private static Set<String> requiredNames(JsonObject schema) {
        if (!schema.has("required")) { //$NON-NLS-1$
            return Set.of();
        }
        return StreamSupport.stream(schema.getAsJsonArray("required").spliterator(), false) //$NON-NLS-1$
                .map(JsonElement::getAsString)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> enumValues(JsonObject schema, String property) {
        JsonArray values = schema.getAsJsonObject("properties") //$NON-NLS-1$
                .getAsJsonObject(property)
                .getAsJsonArray("enum"); //$NON-NLS-1$
        return StreamSupport.stream(values.spliterator(), false)
                .map(JsonElement::getAsString)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> runtimeOperationNames() {
        Set<String> names = Arrays.stream(ValidationOperation.values())
                .map(ValidationOperation::getToolName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        names.addAll(Set.of("external_manage", "extension_manage", "dcs_manage")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return names;
    }
}
```

- [ ] **Step 3: Run the contract tests and verify the known drift is exposed**

Run:

```bash
mvn -pl bundles/com.codepilot1c.core.tests -am \
  -Dtest=EdtValidateRequestToolSchemaTest,ToolSurfaceSchemaParityTest,BackendToolSurfaceContributorTest test
```

Expected: FAIL because raw schema omits `render_template`, normalized validation schema omits composite operations, `mutate_role_rights` and `render_template`, normalized `write_file` text omits `*.md`/`*.txt`, and two normalized lower bounds are not enforced by runtime.

- [ ] **Step 4: Publish the complete operation set in both raw and normalized schemas**

Use this exact ordered enum in both `EdtValidateRequestTool.SCHEMA` and the `edt_validate_request` override:

```json
["create_metadata", "create_form", "apply_form_recipe", "external_manage", "external_create_report", "external_create_processing", "extension_manage", "extension_create_project", "extension_adopt_object", "extension_set_property_state", "dcs_manage", "dcs_create_main_schema", "dcs_upsert_query_dataset", "dcs_upsert_parameter", "dcs_upsert_calculated_field", "add_metadata_child", "ensure_module_artifact", "update_metadata", "delete_metadata", "mutate_form_model", "mutate_role_rights", "render_template"]
```

Keep the raw description explicit that `command` for composite tools belongs only in `payload.command`.

Update the raw and centralized tool descriptions so the newly published `render_template` contract is not contradicted:

```java
return "Проверяет запрос на изменение метаданных и выдаёт одноразовый validation_token. Обязателен перед metadata/forms/DCS/template/extension/external мутациями. Не используй для read-only tools."; //$NON-NLS-1$
```

```java
case "edt_validate_request" -> "Проверяет запрос на изменение метаданных и выдаёт ОДНОРАЗОВЫЙ validation_token. Каждый токен может быть использован ТОЛЬКО ОДИН РАЗ — для каждой новой мутации запрашивай НОВЫЙ токен. Обязателен перед metadata/forms/DCS/template/extension/external мутациями."; //$NON-NLS-1$
```

- [ ] **Step 5: Correct the centralized `write_file` contract**

Replace only the centralized override text; retain the runtime guard and raw tool implementation:

```java
case "write_file" -> "Overwrite existing workspace text files; may create project-root Code.md and documentation (*.md, *.txt). Never write .mdo/.form/.mxl/DCS artifacts directly; use semantic EDT tools."; //$NON-NLS-1$
```

Use these two schema descriptions:

```json
"path": {
  "type": "string",
  "description": "Workspace-relative file path. Existing files are overwritten; new files may be created only for project-root Code.md or documentation (*.md, *.txt)."
},
"overwrite": {
  "type": "boolean",
  "description": "Must be true. Existing files are overwritten; project-root Code.md and documentation (*.md, *.txt) may be created."
}
```

- [ ] **Step 6: Remove model-only lower bounds not enforced by runtime**

In `ToolSurfaceSchemaNormalizer`, retain `maximum: 500` for `glob.max_results`, because `GlobTool` clamps to that upper limit, but remove `minimum: 1`. Remove `minimum: 0` from `grep.context_lines`, because `GrepTool` currently accepts the integer without a lower-bound validation. Keep `minimum: 1` for `read_file.start_line` and `end_line`, because `ReadFileTool` normalizes the effective start to at least line 1.

- [ ] **Step 7: Run the contract tests and the existing validation suite**

Run:

```bash
mvn -pl bundles/com.codepilot1c.core.tests -am \
  -Dtest=EdtValidateRequestToolSchemaTest,ToolSurfaceSchemaParityTest,BackendToolSurfaceContributorTest,ValidationOperationTest,EdtValidateRequestToolTest test
```

Expected: PASS; the eight overrides parse as JSON, property/required sets match raw primary contracts, and all 22 public validation operation names are visible.

- [ ] **Step 8: Commit the audited contracts**

```bash
git add bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/metadata/EdtValidateRequestTool.java \
  bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/surface/BackendToolSurfaceRewriteContributor.java \
  bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/surface/ToolSurfaceSchemaNormalizer.java \
  bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/EdtValidateRequestToolSchemaTest.java \
  bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/surface/BackendToolSurfaceContributorTest.java \
  bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/surface/ToolSurfaceSchemaParityTest.java
git commit -m "fix: reconcile tool surface contracts"
```

---

### Task 2: Make All Surface Contributors Provider-Neutral

**Files:**

- Move/Modify: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/surface/BackendToolSurfaceRewriteContributor.java` → `ProviderNeutralToolSurfaceRewriteContributor.java`
- Modify: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/surface/ToolSurfaceAugmentor.java:33-39`
- Modify: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/surface/ToolRoutingSurfaceContributor.java:11-27`
- Modify: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/surface/DynamicToolSurfaceContributor.java:10-34`
- Move/Modify: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/surface/BackendToolSurfaceContributorTest.java` → `ProviderNeutralToolSurfaceContributorTest.java`
- Modify: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/surface/ToolSurfaceSnapshotTest.java:1-188`

**Interfaces:**

- Consumes: `ToolSurfaceContext.isBuiltIn()`, `ToolSurfaceContext.getCategory()`.
- Produces: `ProviderNeutralToolSurfaceRewriteContributor`; identical name/description/schema for the same tool/profile across CodePilot, OpenAI-compatible, Ollama and no active provider.

- [ ] **Step 1: Rewrite the snapshot test as a provider-invariance test**

Retain the isolated registry and provider-store fixture, remove the provider label from the serialized contract and compare all four selections:

```java
@Test
public void effectiveToolSurfaceIsIdenticalAcrossProviderSelections() throws Exception {
    ToolRegistry registry = createIsolatedRegistry();
    registerSnapshotTools(registry);

    String codePilot = surfaceSnapshotFor(registry, "backend", ProviderType.CODEPILOT_BACKEND, "backend-coder"); //$NON-NLS-1$ //$NON-NLS-2$
    String openAi = surfaceSnapshotFor(registry, "openai-local", ProviderType.OPENAI_COMPATIBLE, "gpt-5"); //$NON-NLS-1$ //$NON-NLS-2$
    String ollama = surfaceSnapshotFor(registry, "ollama", ProviderType.OLLAMA, "llama3.2"); //$NON-NLS-1$ //$NON-NLS-2$
    setStoreState(List.of(), null);
    String noProvider = surfaceSnapshot(registry);

    assertEquals(codePilot, openAi);
    assertEquals(codePilot, ollama);
    assertEquals(codePilot, noProvider);
    assertEquals(EXPECTED_PROVIDER_NEUTRAL_DESCRIPTIONS, descriptionSnapshot(registry));
}
```

`surfaceSnapshot(ToolRegistry)` must include each definition's `name`, whitespace-normalized `description`, and parsed/re-serialized schema via `JsonParser.parseString(schema).toString()`. Define the exact description snapshot as:

```java
private static final String EXPECTED_PROVIDER_NEUTRAL_DESCRIPTIONS = """
        read_file=Read an existing workspace file or line range. Bare Code.md resolves to current project root; otherwise use workspace-relative paths. Tool routing: prefer read/search before mutation, keep paths workspace-relative, and switch to EDT semantic tools for platform/model questions.
        edit_file=Edit existing workspace text files; create only project-root Code.md. Never edit .mdo/.form/.mxl/DCS artifacts directly; use metadata/form/dcs/template tools. Tool routing: read before edit, patch the smallest necessary region, and do not mutate EDT metadata files directly when a semantic tool exists.
        create_metadata=Создаёт метаданный объект через BM API. Свойства: COMMON_MODULE — clientManagedApplication/server/global. DOCUMENT — useStandardCommands. CATALOG — hierarchical+hierarchyType. После создания запусти диагностику. Tool routing: enforce edt_validate_request -> validation_token -> mutation -> diagnostics. Do not skip validation or diagnose success without re-running diagnostics.
        qa_inspect=Читает состояние QA без изменений файлов: объясняет qa-config, проверяет окружение и ищет доступные шаги Vanessa Automation. Tool routing: follow the QA pipeline in order, treat generated context as ephemeral, and use steps search only as fallback support for scenario authoring.
        skill=Показывает доступные skills и загружает инструкцию выбранного skill по имени. Используй для подключения специализированного workflow.
        """; //$NON-NLS-1$
```

All providers must receive this safety-rich contract; schema equality is enforced by the full serialized `surfaceSnapshot` comparison.

- [ ] **Step 2: Add a representative size envelope**

Use the same five-tool fixture (`read_file`, `edit_file`, `create_metadata`, `qa_inspect`, `skill`) and lock these upper bounds, rounded above the existing backend snapshot:

```java
@Test
public void providerNeutralSurfaceStaysWithinFormerBackendEnvelope() throws Exception {
    ToolRegistry registry = createIsolatedRegistry();
    registerSnapshotTools(registry);
    List<ToolDefinition> definitions = registry.getToolDefinitions(
            registry.createRuntimeSurfaceContext(new BuildAgentProfile()));

    assertEquals(5, definitions.size());
    assertTrue(totalDescriptionChars(definitions) <= 1_600);
    assertTrue(totalSchemaChars(definitions) <= 3_500);

    List<LlmMessage> rendered = ToolPromptRenderer.applyToMessages(
            List.of(LlmMessage.system("BASE")), definitions); //$NON-NLS-1$
    assertTrue(rendered.get(0).getContent().length() <= 2_100);
}
```

Keep the exact snapshot assertion from Step 1 alongside these aggregate ceilings so shortening one description cannot silently hide growth in another contract.

- [ ] **Step 3: Run the invariance tests and verify non-backend cases fail**

Run:

```bash
mvn -pl bundles/com.codepilot1c.core.tests -am \
  -Dtest=ToolSurfaceSnapshotTest,BackendToolSurfaceContributorTest test
```

Expected: FAIL because OpenAI-compatible, Ollama and no-provider contexts still bypass all three contributors.

- [ ] **Step 4: Rename the built-in rewrite contributor and remove provider gates**

Use `apply_patch` with `*** Move to:` for both Java files. Apply these exact semantic changes:

```diff
-public final class BackendToolSurfaceRewriteContributor implements ToolSurfaceContributor {
+public final class ProviderNeutralToolSurfaceRewriteContributor implements ToolSurfaceContributor {
@@
-        return context != null && context.isBuiltIn() && context.isBackendSelectedInUi();
+        return context != null && context.isBuiltIn();
```

```diff
-                new BackendToolSurfaceRewriteContributor(),
+                new ProviderNeutralToolSurfaceRewriteContributor(),
```

```diff
- * Adds backend-specific execution discipline for built-in tools.
+ * Adds provider-neutral execution discipline for built-in tools.
@@
-                && context.isBuiltIn()
-                && context.isBackendSelectedInUi()
+                && context.isBuiltIn()
                 && context.getCategory() != ToolCategory.DYNAMIC;
```

- [ ] **Step 5: Make dynamic/MCP guidance universal and idempotent**

Use this exact constant and support predicate:

```java
private static final String MCP_GUIDANCE =
        "External tool note: this tool is provided by an MCP/dynamic source. Follow its schema exactly, " //$NON-NLS-1$
        + "do not assume EDT/file semantics, and rely on returned machine-readable errors."; //$NON-NLS-1$

@Override
public boolean supports(ToolSurfaceContext context) {
    return context != null && !context.isBuiltIn();
}
```

Rename the test class to `ProviderNeutralToolSurfaceContributorTest`, remove every `.backendSelectedInUi(true)` call, replace `Backend note:` with `External tool note:`, and keep assertions for schema hardening, optional-only `required: []`, structured EDT write warnings and duplicate-guidance prevention.

Add a reflection-backed catalog audit to the renamed test so all 56 overrides are exercised against registered tools:

```java
@Test
public void overrideCatalogContainsOnlyRegisteredProviderNeutralTools() throws Exception {
    ProviderNeutralToolSurfaceRewriteContributor contributor =
            new ProviderNeutralToolSurfaceRewriteContributor();
    Method method = ProviderNeutralToolSurfaceRewriteContributor.class
            .getDeclaredMethod("overrideDescription", String.class); //$NON-NLS-1$
    method.setAccessible(true);

    List<String> overridden = new ArrayList<>();
    for (ITool tool : ToolRegistry.getInstance().getAllTools()) {
        String description = (String) method.invoke(contributor, tool.getName());
        if (description == null) {
            continue;
        }
        overridden.add(tool.getName());
        String lower = description.toLowerCase(Locale.ROOT);
        assertFalse(tool.getName(), lower.contains("qwen")); //$NON-NLS-1$
        assertFalse(tool.getName(), lower.contains("backend")); //$NON-NLS-1$
    }
    assertEquals(56, overridden.size());
}
```

If this count fails because an override names a tool no longer registered, remove or correct that stale override; do not lower the expected count without documenting the actual catalog change in the same commit.

Also preserve the documented third-party fallback with this test:

```java
@Test
public void malformedDynamicSchemaRemainsUnchanged() {
    String malformed = "{not-json"; //$NON-NLS-1$
    ToolDefinition.Builder builder = ToolDefinition.builder()
            .name("dynamic_tool") //$NON-NLS-1$
            .description("External") //$NON-NLS-1$
            .parametersSchema(malformed);

    new DynamicToolSurfaceContributor().contribute(
            ToolSurfaceContext.builder().builtIn(false).build(), builder);

    assertEquals(malformed, builder.build().getParametersSchema());
}
```

- [ ] **Step 6: Run surface tests**

Run:

```bash
mvn -pl bundles/com.codepilot1c.core.tests -am \
  -Dtest=ProviderNeutralToolSurfaceContributorTest,ToolSurfaceSnapshotTest,ToolSurfaceAugmentorTest,ToolDescriptionCoverageTest test
```

Expected: PASS; all four provider selections serialize to the same snapshot, dynamic schemas are hardened for every provider, and the size envelope passes.

- [ ] **Step 7: Commit the provider-neutral contributors**

```bash
git add -A -- \
  bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/surface/BackendToolSurfaceRewriteContributor.java \
  bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/surface/ProviderNeutralToolSurfaceRewriteContributor.java \
  bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/surface/ToolSurfaceAugmentor.java \
  bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/surface/ToolRoutingSurfaceContributor.java \
  bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/surface/DynamicToolSurfaceContributor.java \
  bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/surface/BackendToolSurfaceContributorTest.java \
  bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/surface/ProviderNeutralToolSurfaceContributorTest.java \
  bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/surface/ToolSurfaceSnapshotTest.java
git commit -m "refactor: make tool surface provider neutral"
```

---

### Task 3: Remove Provider State from Tool-Surface Assembly

**Files:**

- Modify: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/surface/ToolSurfaceContext.java:11-145`
- Modify: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/ToolRegistry.java:60-80,320-405`
- Create: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/ActiveProviderConfigResolver.java`
- Delete: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/ProviderContextResolver.java`
- Modify: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/diagnostics/ResolveWebClientUrlTool.java:9-55,120-130`
- Modify: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/surface/ToolSurfaceContextTest.java:1-111`
- Modify: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/surface/ToolRegistryAugmentorRuntimeTest.java:1-151`
- Create: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/meta/DiscoverToolsToolSurfaceTest.java`
- Modify: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/agent/AgentRunnerBuildRequestTest.java:1-175`
- Modify: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/agent/langgraph/LangGraphAgentRunnerTest.java:20-100`

**Interfaces:**

- Consumes: `ToolSurfaceContext.builder().profile(AgentProfile).category(ToolCategory).builtIn(boolean).build()`.
- Produces: `ToolRegistry.createRuntimeSurfaceContext(AgentProfile)` with no provider/preferences lookup; `ActiveProviderConfigResolver.resolve(): LlmProviderConfig` only for unrelated active-model diagnostics.

- [ ] **Step 1: Replace provider-copy tests with a provider-neutral state contract**

Replace `ToolSurfaceContextTest` with tests for exact non-static state and `toBuilder()` preservation:

```java
@Test
public void contextContainsOnlyProviderNeutralInstanceState() {
    Set<String> instanceFields = Arrays.stream(ToolSurfaceContext.class.getDeclaredFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()))
            .map(Field::getName)
            .collect(Collectors.toSet());

    assertEquals(Set.of("profile", "category", "builtIn"), instanceFields); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
}

@Test
public void toBuilderPreservesProfileCategoryAndProvenance() {
    ToolSurfaceContext copy = ToolSurfaceContext.builder()
            .profile(STUB_PROFILE)
            .category(ToolCategory.FILES_READ_SEARCH)
            .builtIn(true)
            .build()
            .toBuilder()
            .build();

    assertSame(STUB_PROFILE, copy.getProfile());
    assertEquals(ToolCategory.FILES_READ_SEARCH, copy.getCategory());
    assertTrue(copy.isBuiltIn());
}
```

- [ ] **Step 2: Add registry, MCP, discover-tools and AgentRunner consumer parity assertions**

Rewrite `ToolRegistryAugmentorRuntimeTest` so a custom contributor appends the exact current profile id, for example `[profile=fallback]` when the test passes `null`. Do not read or mutate `LlmProviderConfigStore`. Compare the registry definition with reflected MCP `tools/list` as follows:

```java
ToolDefinition expected = toolRegistry.getToolDefinitions(
        toolRegistry.createRuntimeSurfaceContext(null)).get(0);
Map<String, Object> published = tools.stream()
        .filter(item -> expected.getName().equals(item.get("name"))) //$NON-NLS-1$
        .findFirst()
        .orElseThrow();
assertEquals(expected.getDescription(), published.get("description")); //$NON-NLS-1$
Map<?, ?> expectedSchema = new Gson().fromJson(expected.getParametersSchema(), Map.class);
assertEquals(expectedSchema, published.get("inputSchema")); //$NON-NLS-1$
```

Create `DiscoverToolsToolSurfaceTest` with an isolated registry containing a built-in file-category stub. Set an augmentor that appends ` [effective]`, call:

```java
ToolResult result = new DiscoverToolsTool(registry)
        .execute(Map.of("category", "workspace")) //$NON-NLS-1$ //$NON-NLS-2$
        .join();
JsonObject payload = JsonParser.parseString(result.getContent()).getAsJsonObject();
JsonObject discovered = payload.getAsJsonArray("tools").get(0).getAsJsonObject(); //$NON-NLS-1$
assertEquals("Raw description [effective]", discovered.get("description").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
```

The stub must return `getCategory() == "workspace"`, so `BuiltinToolTaxonomy` maps it to the requested category.

Add an AgentRunner request test that sets `registry.setAugmentor(ToolSurfaceAugmentor.defaultAugmentor())`, builds a request with `read_file`, and uses field-wise comparison because `ToolDefinition` has no value-based `equals`:

```java
ToolDefinition expected = registry.getToolDefinition(
        registry.getTool("read_file"), //$NON-NLS-1$
        registry.createRuntimeSurfaceContext(new ExploreAgentProfile()));
ToolDefinition actual = request.getTools().stream()
        .filter(definition -> "read_file".equals(definition.getName())) //$NON-NLS-1$
        .findFirst()
        .orElseThrow();
assertEquals(expected.getDescription(), actual.getDescription());
assertEquals(JsonParser.parseString(expected.getParametersSchema()),
        JsonParser.parseString(actual.getParametersSchema()));
String compactDescription = expected.getDescription()
        .replace('\n', ' ')
        .replaceAll("\\s+", " ") //$NON-NLS-1$ //$NON-NLS-2$
        .strip();
assertTrue(systemPrompt.contains(compactDescription.substring(
        0, Math.min(359, compactDescription.length()))));
```

- [ ] **Step 3: Run tests and verify the context-state test fails**

Run:

```bash
mvn -pl bundles/com.codepilot1c.core.tests -am \
  -Dtest=ToolSurfaceContextTest,ToolRegistryAugmentorRuntimeTest,DiscoverToolsToolSurfaceTest,AgentRunnerBuildRequestTest test
```

Expected: FAIL because `ToolSurfaceContext` still contains provider snapshot/id/backend fields. Consumer tests may already pass and therefore characterize the assembly path before deletion.

- [ ] **Step 4: Reduce `ToolSurfaceContext` to three instance fields**

Remove the provider import, fields, getters and builder methods. The resulting state logic must be:

```java
private final AgentProfile profile;
private final ToolCategory category;
private final boolean builtIn;

private ToolSurfaceContext(Builder builder) {
    this.profile = builder.profile != null ? builder.profile : defaultProfile();
    this.category = builder.category != null ? builder.category : ToolCategory.DYNAMIC;
    this.builtIn = builder.builtIn;
}

public Builder toBuilder() {
    return builder()
            .profile(profile)
            .category(category)
            .builtIn(builtIn);
}

public static final class Builder {
    private AgentProfile profile;
    private ToolCategory category;
    private boolean builtIn;

    public Builder profile(AgentProfile profile) {
        this.profile = profile;
        return this;
    }

    public Builder category(ToolCategory category) {
        this.category = category;
        return this;
    }

    public Builder builtIn(boolean builtIn) {
        this.builtIn = builtIn;
        return this;
    }

    public ToolSurfaceContext build() {
        return new ToolSurfaceContext(this);
    }
}
```

Keep `FALLBACK_PROFILE`, `passthrough()`, `builder()`, `defaultProfile()`, `getProfile()`, `getCategory()` and `isBuiltIn()`.

- [ ] **Step 5: Make `ToolRegistry` build context locally**

Delete field initialization and lazy accessor for `ProviderContextResolver`. Use:

```java
public ToolSurfaceContext createRuntimeSurfaceContext(AgentProfile profile) {
    return ToolSurfaceContext.builder()
            .profile(profile != null ? profile : ToolSurfaceContext.defaultProfile())
            .build();
}
```

Remove test reflection writes for `providerContextResolver` from `AgentRunnerBuildRequestTest` and `LangGraphAgentRunnerTest`; no replacement field is needed.

- [ ] **Step 6: Preserve the unrelated active-model lookup outside surface assembly**

Create `ActiveProviderConfigResolver` in `com.codepilot1c.core.provider` with this public contract:

```java
public final class ActiveProviderConfigResolver {
    public LlmProviderConfig resolve() {
        try {
            LlmProviderRegistry registry = LlmProviderRegistry.getInstance();
            LlmProviderConfigStore store = registry.getConfigStore();
            if (store == null) {
                store = LlmProviderConfigStore.getInstance();
            }
            String activeProviderId = store.getActiveProviderId();
            if (activeProviderId != null && !activeProviderId.isBlank()) {
                if ("backend".equals(activeProviderId) //$NON-NLS-1$
                        && registry.getBackendProvider() instanceof DynamicLlmProvider backend) {
                    return backend.getConfig().copy();
                }
                Optional<LlmProviderConfig> active = store.getProvider(activeProviderId);
                if (active.isPresent()) {
                    return active.get().copy();
                }
            }
            return store.getProviders().stream()
                    .filter(LlmProviderConfig::isConfigured)
                    .findFirst()
                    .map(LlmProviderConfig::copy)
                    .orElseGet(LlmProviderConfig::new);
        } catch (RuntimeException e) {
            return new LlmProviderConfig();
        }
    }
}
```

Update `ResolveWebClientUrlTool` field/constructor/import to `ActiveProviderConfigResolver` and replace `providerContextResolver.resolveActiveProviderConfig()` with `activeProviderConfigResolver.resolve()`. Then delete `ProviderContextResolver.java` completely. This preserves its only non-surface production use without letting provider data return to `ToolSurfaceContext`.

- [ ] **Step 7: Run all affected assembly tests**

Run:

```bash
mvn -pl bundles/com.codepilot1c.core.tests -am \
  -Dtest=ToolSurfaceContextTest,ToolRegistryAugmentorRuntimeTest,DiscoverToolsToolSurfaceTest,ToolSurfaceSnapshotTest,AgentRunnerBuildRequestTest,LangGraphAgentRunnerTest test
```

Expected: PASS; registry/MCP/discovery/AgentRunner all observe effective definitions, and context has exactly three non-static fields.

- [ ] **Step 8: Commit provider-state removal**

```bash
git add -A -- \
  bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/surface/ToolSurfaceContext.java \
  bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/ToolRegistry.java \
  bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/ProviderContextResolver.java \
  bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/ActiveProviderConfigResolver.java \
  bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/diagnostics/ResolveWebClientUrlTool.java \
  bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/surface/ToolSurfaceContextTest.java \
  bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/surface/ToolRegistryAugmentorRuntimeTest.java \
  bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/meta/DiscoverToolsToolSurfaceTest.java \
  bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/agent/AgentRunnerBuildRequestTest.java \
  bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/agent/langgraph/LangGraphAgentRunnerTest.java
git commit -m "refactor: remove provider state from tool assembly"
```

---

### Task 4: Remove the Dead Backend-Optimization Capability

**Files:**

- Modify: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/ProviderCapabilities.java:15-70,175-205`
- Modify: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/ProviderUtils.java:38-80`
- Modify: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/provider/ProviderCapabilitiesTest.java`
- Modify: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/provider/ProviderUtilsTest.java:14-45`
- Modify: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/TaskToolTest.java:200-211`
- Modify: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/DelegateToAgentToolTest.java:178-190`

**Interfaces:**

- Consumes: all remaining `ProviderCapabilities` flags with real runtime effects.
- Produces: no field, getter, builder method or `ProviderUtils` helper named `backendOptimizations`/`supportsBackendOptimizations`.

- [ ] **Step 1: Add an absence contract to `ProviderCapabilitiesTest`**

```java
@Test
public void backendOptimizationCapabilityIsNotPartOfPublicApi() {
    String getter = "supportsBackend" + "Optimizations"; //$NON-NLS-1$ //$NON-NLS-2$
    String builder = "backend" + "Optimizations"; //$NON-NLS-1$ //$NON-NLS-2$
    assertFalse(Arrays.stream(ProviderCapabilities.class.getDeclaredMethods())
            .map(Method::getName)
            .anyMatch(name -> name.equals(getter)));
    assertFalse(Arrays.stream(ProviderCapabilities.Builder.class.getDeclaredMethods())
            .map(Method::getName)
            .anyMatch(name -> name.equals(builder)));
    assertFalse(Arrays.stream(ProviderUtils.class.getDeclaredMethods())
            .map(Method::getName)
            .anyMatch(name -> name.equals(getter)));
}
```

- [ ] **Step 2: Run the provider test and verify it fails**

Run:

```bash
mvn -pl bundles/com.codepilot1c.core.tests -am -Dtest=ProviderCapabilitiesTest test
```

Expected: FAIL because all three dead API forms still exist.

- [ ] **Step 3: Delete the dead field and helpers**

Remove these exact members/usages from `ProviderCapabilities`: field `private final boolean backendOptimizations`, constructor assignment `this.backendOptimizations = builder.backendOptimizations`, getter `supportsBackendOptimizations()`, builder field `private boolean backendOptimizations`, and builder method `backendOptimizations(boolean backendOptimizations)`.

Remove `.backendOptimizations(true)`, `ProviderUtils.supportsBackendOptimizations(ILlmProvider)` and `ProviderUtils.supportsBackendOptimizations(LlmProviderConfig)`. Keep CodePilot capabilities exactly:

```java
return base
        .codePilotBackend(true)
        .promptCacheHeaders(true)
        .resolvedModel(true)
        .textToolCallFallback(true)
        .streamUsage(true)
        .build();
```

Delete backend-optimization assertions from `ProviderUtilsTest`, but preserve checks for `isCodePilotBackend`, prompt-cache headers, resolved model and text fallback. Remove only `.backendOptimizations(true)` from the fake backend providers in `TaskToolTest` and `DelegateToAgentToolTest`.

- [ ] **Step 4: Run provider and delegation tests**

Run:

```bash
mvn -pl bundles/com.codepilot1c.core.tests -am \
  -Dtest=ProviderCapabilitiesTest,ProviderUtilsTest,TaskToolTest,DelegateToAgentToolTest test
```

Expected: PASS; all remaining CodePilot runtime capabilities retain their previous values.

- [ ] **Step 5: Verify there are no dead API references**

Run:

```bash
rg -n "backendOptimizations|supportsBackendOptimizations" \
  bundles/com.codepilot1c.core/src bundles/com.codepilot1c.core.tests/src
```

Expected: no matches.

- [ ] **Step 6: Commit capability cleanup**

```bash
git add bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/ProviderCapabilities.java \
  bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/ProviderUtils.java \
  bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/provider/ProviderCapabilitiesTest.java \
  bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/provider/ProviderUtilsTest.java \
  bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/TaskToolTest.java \
  bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/DelegateToAgentToolTest.java
git commit -m "refactor: remove dead backend optimization capability"
```

---

### Task 5: Remove Stale Qwen Runtime References from Live Instructions and Inventory

**Files:**

- Create: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/surface/QwenRuntimeReferenceGuardTest.java`
- Modify: `tools/generate_tool_prompt_inventory.py:470-585`
- Modify working copy only: `AGENTS.md:145-213`

**Interfaces:**

- Consumes: repository root and a deliberately restricted live-file scope.
- Produces: guard against deleted Qwen runtime APIs and backend-named universal surface; historical docs and external Qwen assets remain out of scope.

- [ ] **Step 1: Add a live-reference guard that does not match its own literals**

Create the test with concatenated fragments:

```java
public class QwenRuntimeReferenceGuardTest {
    private static final List<String> FORBIDDEN = List.of(
            "QwenFunction" + "CallingTransport", //$NON-NLS-1$ //$NON-NLS-2$
            "QwenToolCall" + "Examples", //$NON-NLS-1$ //$NON-NLS-2$
            "QwenContent" + "ToolCallParser", //$NON-NLS-1$ //$NON-NLS-2$
            "QwenStreaming" + "ToolCallParser", //$NON-NLS-1$ //$NON-NLS-2$
            "isQwen" + "Native", //$NON-NLS-1$ //$NON-NLS-2$
            "getResolved" + "ModelFamily", //$NON-NLS-1$ //$NON-NLS-2$
            "resolve" + "ModelFamily", //$NON-NLS-1$ //$NON-NLS-2$
            "BackendTool" + "Surface", //$NON-NLS-1$ //$NON-NLS-2$
            "Backend" + " note:", //$NON-NLS-1$ //$NON-NLS-2$
            "ProviderContext" + "Resolver", //$NON-NLS-1$ //$NON-NLS-2$
            "backend" + "Optimizations", //$NON-NLS-1$ //$NON-NLS-2$
            "supportsBackend" + "Optimizations"); //$NON-NLS-1$ //$NON-NLS-2$

    @Test
    public void liveRuntimeAndInstructionsDoNotReferenceRemovedApis() throws Exception {
        Path root = repositoryRoot();
        List<Path> files = new ArrayList<>();
        collectJava(files, root.resolve("bundles/com.codepilot1c.core/src")); //$NON-NLS-1$
        collectJava(files, root.resolve("bundles/com.codepilot1c.core.tests/src")); //$NON-NLS-1$
        collectJava(files, root.resolve("bundles/com.codepilot1c.ui/src")); //$NON-NLS-1$
        files.add(root.resolve("tools/generate_tool_prompt_inventory.py")); //$NON-NLS-1$
        if (Files.isRegularFile(root.resolve("AGENTS.md"))) { //$NON-NLS-1$
            files.add(root.resolve("AGENTS.md")); //$NON-NLS-1$
        }

        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            for (String forbidden : FORBIDDEN) {
                if (content.contains(forbidden)) {
                    violations.add(root.relativize(file) + " -> " + forbidden); //$NON-NLS-1$
                }
            }
        }
        assertTrue(String.join("\n", violations), violations.isEmpty()); //$NON-NLS-1$

        String chatView = Files.readString(
                root.resolve("bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/views/ChatView.java"), //$NON-NLS-1$
                StandardCharsets.UTF_8);
        assertTrue(chatView.contains("ToolRegistry.getInstance().getToolDefinitions()")); //$NON-NLS-1$
        assertFalse(chatView.contains("ProviderSelectionGate")); //$NON-NLS-1$
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath(); //$NON-NLS-1$
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("pom.xml")) //$NON-NLS-1$
                    && Files.isDirectory(candidate.resolve("bundles/com.codepilot1c.core"))) { //$NON-NLS-1$
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Repository root not found"); //$NON-NLS-1$
    }

    private static void collectJava(List<Path> files, Path directory) throws IOException {
        try (Stream<Path> stream = Files.walk(directory)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java")) //$NON-NLS-1$
                    .forEach(files::add);
        }
    }
}
```

Do not add `docs/`, `evals/`, README files or external runners to the scanned paths.

- [ ] **Step 2: Run the guard and verify it reports the live stale references**

Run:

```bash
mvn -pl bundles/com.codepilot1c.core.tests -am -Dtest=QwenRuntimeReferenceGuardTest test
```

Expected: FAIL on `tools/generate_tool_prompt_inventory.py` and local `AGENTS.md`; if Tasks 2–4 were not applied correctly, it also reports backend surface class/text or deleted capability-era names.

- [ ] **Step 3: Correct the inventory call hierarchy**

Replace the desktop provider role and final provider-transport row with:

```python
{
    "flow": "desktop_chat_direct",
    "stage_order": "2",
    "component": "ILlmProvider.complete|streamComplete",
    "source_file": str(CORE_SRC / "com/codepilot1c/core/provider"),
    "role": "Provider receives messages and structured tool definitions; compatibility policy controls request fields and streaming behavior.",
    "next_stage": "LlmResponse.toolCalls",
},
```

```python
{
    "flow": "provider_transport",
    "stage_order": "1",
    "component": "DynamicLlmProvider/OpenAI-compatible transport",
    "source_file": str(CORE_SRC / "com/codepilot1c/core/provider/config/DynamicLlmProvider.java"),
    "role": "Builds the structured OpenAI-compatible request used by CodePilot backend and generic OpenAI endpoints; provider-neutral compatibility policy supplies request overrides.",
    "next_stage": "OpenAiStreamingSession/ContentToolCallFallbackParser",
},
```

- [ ] **Step 4: Replace the ignored local Qwen rules with provider-neutral rules**

In the current `AGENTS.md` working copy, remove the complete `## Qwen Optimization Rules` section and the three Qwen checklist items. Insert:

```markdown
## Provider-Neutral Tool Contract Rules (Mandatory for all new code)

- Build model-facing definitions only through `ToolRegistry` -> `ToolSurfaceAugmentor`.
- Keep effective tool descriptions and schemas independent of provider type, model name, and global UI provider selection.
- For new tools, use a valid JSON object schema, list every required primary parameter in `required`, and keep runtime parsing aligned with the published schema.
- Keep tool descriptions concise and provider-neutral; put shared routing/safety guidance in the surface contributors, not provider transports.
- Preserve structured tool calling as the primary OpenAI-compatible path and keep `ContentToolCallFallbackParser` only as a safety net.
- When changing request/stream parsing, run CodePilot, generic OpenAI-compatible, GLM, MiniMax and Kimi regression tests.
```

Add these provider-neutral lines to `Checklist: New/Changed Tool`:

```markdown
- [ ] Effective description/schema is provider-neutral and contains no model/provider gate.
- [ ] Schema parses as JSON and matches the primary runtime parameter contract.
- [ ] If transport parsing changed, generic structured/streaming/content-fallback tests pass.
```

Do not stage this ignored file.

- [ ] **Step 5: Verify the generator and guard**

Run:

```bash
python3 -m py_compile tools/generate_tool_prompt_inventory.py
python3 -c 'from pathlib import Path; from tools.generate_tool_prompt_inventory import collect_call_hierarchy_rows; rows = collect_call_hierarchy_rows(); assert any(r["component"] == "DynamicLlmProvider/OpenAI-compatible transport" for r in rows); assert all(Path(r["source_file"]).exists() for r in rows)'
mvn -pl bundles/com.codepilot1c.core.tests -am -Dtest=QwenRuntimeReferenceGuardTest test
git check-ignore AGENTS.md
```

Expected: Python commands exit 0, guard PASS, and `git check-ignore` prints `AGENTS.md`.

- [ ] **Step 6: Commit only tracked cleanup**

```bash
git add tools/generate_tool_prompt_inventory.py \
  bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/surface/QwenRuntimeReferenceGuardTest.java
git status --short
git commit -m "test: guard provider-neutral tool surface"
```

Expected before commit: only the generator and guard test are staged; local `AGENTS.md` is not shown because it is ignored.

---

### Task 6: Run Transport, Consumer, and Full Reactor Verification

**Files:**

- Verify only: all files changed in Tasks 1–5.
- Preserve unchanged: `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config/ContentToolCallFallbackParser.java`, `JsonRepairUtil.java`, `OpenAiStreamingToolCallParser.java`, `OpenAiStreamingSession.java`, compatibility profiles/policy, `evals/qwen/`, external Qwen runners and M002 design.

**Interfaces:**

- Consumes: final provider-neutral tool surface and existing generic transport suite.
- Produces: evidence for QRM-01 through QRM-10 and a clean, packageable Tycho reactor.

- [ ] **Step 1: Verify provider invariance, schema parity and all consumers together**

Run:

```bash
mvn -pl bundles/com.codepilot1c.core.tests -am \
  -Dtest=ProviderNeutralToolSurfaceContributorTest,ToolSurfaceSchemaParityTest,ToolSurfaceSnapshotTest,ToolSurfaceContextTest,ToolRegistryAugmentorRuntimeTest,DiscoverToolsToolSurfaceTest,AgentRunnerBuildRequestTest,QwenRuntimeReferenceGuardTest test
```

Expected: PASS with zero failures/errors; the snapshot matrix covers CodePilot, OpenAI-compatible, Ollama and no provider.

- [ ] **Step 2: Verify generic request, streaming and content-fallback behavior**

Run:

```bash
mvn -pl bundles/com.codepilot1c.core.tests -am \
  -Dtest=DynamicLlmProviderRequestBodyTest,OpenAiStreamingSessionTest,DynamicLlmProviderStreamingTest,ContentToolCallFallbackParserTest,OpenAiModelCompatibilityPolicyTest,OpenAiCompatibilityProfileResolverTest test
```

Expected: PASS, including `codePilotBackendUsesSingleOpenAiToolPathWithoutXmlPriming`, structured fragment accumulation, pending-call completion/repair, GLM, MiniMax and Kimi cases.

- [ ] **Step 3: Verify the intended removals and explicit preservation scopes**

Run:

```bash
test ! -e bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/ProviderContextResolver.java
test -e bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config/ContentToolCallFallbackParser.java
test -e bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config/JsonRepairUtil.java
test -d evals/qwen
rg -n "ProviderSelectionGate" bundles/com.codepilot1c.core/src/com/codepilot1c/core/{agent,skills,settings,tools/SkillTool.java}
rg -n "ProviderSelectionGate" bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/surface bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/ToolRegistry.java || true
```

Expected: first four checks exit 0; first `rg` shows legitimate prompt/skill uses; second `rg` prints no tool-surface/registry matches.

- [ ] **Step 4: Run the complete core test module**

Run:

```bash
mvn -pl bundles/com.codepilot1c.core.tests -am test
```

Expected: BUILD SUCCESS with zero test failures/errors.

- [ ] **Step 5: Run the mandatory full Tycho reactor package build**

Run:

```bash
mvn -DskipTests package
```

Expected: BUILD SUCCESS for targets, bundles, features and `repositories/com.codepilot1c.update`; do not publish the produced update site.

- [ ] **Step 6: Inspect final diff and repository state**

Run:

```bash
git diff --check
git status --short --branch
git log --oneline -7
```

Expected: `git diff --check` exits 0; no uncommitted tracked runtime/test changes remain; local ignored `AGENTS.md` is intentionally absent from status; recent history contains the five implementation commits after the design/plan commits. If a command fails, stop completion, diagnose that exact failure, apply a scoped test-first fix, rerun the failed command and repeat this final inspection.

---

## Requirement Traceability

| Requirement | Implemented by | Proof |
|---|---|---|
| QRM-01 provider-independent effective contract | Tasks 2–3 | Four-provider snapshot equality |
| QRM-02 four consumer paths share assembly | Task 3 | Registry/MCP/discover/AgentRunner tests plus ChatView source guard scope |
| QRM-03 built-in and dynamic normalization | Task 2 | Contributor tests for both provenances |
| QRM-04 eight overrides match runtime contracts | Task 1 | `ToolSurfaceSchemaParityTest` |
| QRM-05 complete validation operation enum | Task 1 | Raw and effective enum equality to runtime operations |
| QRM-06 execution/profile/permission/graph/deferred unchanged | Tasks 3 and 6 | Existing AgentRunner/LangGraph/full core suite |
| QRM-07 generic transport preserved | Task 6 | Request/streaming/fallback compatibility suite |
| QRM-08 external Qwen assets preserved | Task 6 | Explicit filesystem checks and restricted guard scope |
| QRM-09 live instructions/generator cleaned | Task 5 | Reference guard and generator assertion |
| QRM-10 separate from M002 | Global constraints and Task 6 | M002 design remains untouched |

## Execution Notes

- Baseline characterization already verified before implementation: 15 targeted tests, zero failures/errors, Maven BUILD SUCCESS.
- The plan intentionally does not canonicalize all 56 descriptions into individual tool classes; the approved common overlay remains.
- The plan intentionally moves active-provider lookup needed by `resolve_web_client_url` to a narrow provider helper. Deleting that unrelated read-only behavior would be a regression, while keeping it in `ToolSurfaceContext` would violate QRM-01.
- Public MCP descriptions/schemas for non-CodePilot clients change atomically at Task 2; do not add a rollout flag.
