# Testing Patterns

**Analysis Date:** 2026-07-01

## Test Framework

**Runner:**
- JUnit 4.13.2 for core unit and contract tests.
- Maven Surefire 3.2.5 for `bundles/com.codepilot1c.core.tests`.
- Tycho Surefire 4.0.4 for OSGi/PDE UI tests in `bundles/com.codepilot1c.ui.tests`.
- Playwright 1.53.x for remote web UI E2E tests under `e2e/remote-web`.

**Assertion Library:**
- JUnit assertions through `org.junit.Assert.*`.
- Playwright assertions through `@playwright/test` in `e2e/remote-web/tests`.

**Run Commands:**
```bash
mvn test                                      # Run default reactor tests, including core tests
mvn -pl bundles/com.codepilot1c.core.tests -am test
                                             # Run core test bundle and required modules
mvn -Pdesktop-ui-tests test                  # Include desktop UI/PDE test module
mvn -DskipTests package                      # Full local deliverable/update-site build
cd e2e/remote-web && npm test                # Remote web Playwright harness
cd e2e/remote-web && npm run test:live       # Live remote web Playwright harness
```

**Config:**
- Root reactor: `pom.xml`.
- Shared build/test settings: `bom/pom.xml`.
- Core tests: `bundles/com.codepilot1c.core.tests/pom.xml`.
- UI tests: `bundles/com.codepilot1c.ui.tests/pom.xml`.
- Opt-in UI test parent: `tests/pom.xml`.
- Remote web E2E: `e2e/remote-web/playwright.config.mjs`.

## Test File Organization

**Location:**
- Core tests are in a separate plain Maven test bundle: `bundles/com.codepilot1c.core.tests/src`.
- UI/PDE tests are in a separate Eclipse test plugin: `bundles/com.codepilot1c.ui.tests/src`.
- Playwright tests are in `e2e/remote-web/tests`.
- Streaming fixtures are colocated with provider tests: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/provider/config/fixtures`.

**Naming:**
- Use `*Test.java` for Java tests.
- Use `*.spec.mjs` for Playwright tests.
- Keep Java test packages mirrored to production packages, for example `com.codepilot1c.core.tools` and `com.codepilot1c.core.provider.config`.

**Structure:**
```text
bundles/
├── com.codepilot1c.core/
│   └── src/com/codepilot1c/core/...          # Production core code
├── com.codepilot1c.core.tests/
│   └── src/com/codepilot1c/core/...          # Core JUnit tests
├── com.codepilot1c.ui/
│   └── src/com/codepilot1c/ui/...            # Production UI code
└── com.codepilot1c.ui.tests/
    └── src/com/codepilot1c/ui/...            # UI/PDE tests
e2e/
└── remote-web/
    ├── playwright.config.mjs
    └── tests/*.spec.mjs
```

## Test Structure

**Suite Organization:**
```java
public class ToolResultTest {
    @Test
    public void successWithStructuredDataReportsStructuredType() {
        JsonObject structured = new JsonObject();
        structured.addProperty("value", 42);

        ToolResult result = ToolResult.success("ok", structured);

        assertTrue(result.isSuccess());
        assertEquals(ToolResult.ResultType.STRUCTURED, result.getType());
    }
}
```

**Patterns:**
- Use small deterministic unit tests for value objects and pure helpers: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/ToolResultTest.java`, `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/file/FileEditApplierTest.java`.
- Use contract tests for agent/tool-facing JSON shapes: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/ToolPayloadContractTest.java`.
- Use schema tests for tool definitions when a schema is externally consumed: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/metadata/AddMetadataChildToolSchemaTest.java`.
- Use profile invariant tests for profile permissions, allowlists, and prompt/tool alignment: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/agent/profiles/AgentProfileRegistryTest.java`, `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/ToolDescriptionCoverageTest.java`.
- Use fixture replay tests for provider streaming and compatibility behavior: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/provider/config/OpenAiStreamingSessionTest.java`.
- Use SWT event-loop polling for UI tests: `bundles/com.codepilot1c.ui.tests/src/com/codepilot1c/ui/views/BrowserChatPanelToolCallTest.java`.

## Mocking

**Framework:** Manual fakes, dynamic proxies, and reflection. Mockito is not detected in the Java test dependencies.

**Patterns:**
```java
private static EdtMetadataGateway failingGateway(String message) {
    return new EdtMetadataGateway() {
        @Override
        public Optional<IProject> findProject(String projectName) {
            return Optional.empty();
        }
    };
}
```

**Dynamic proxy pattern:**
```java
IBmObject object = (IBmObject) Proxy.newProxyInstance(
        IBmObject.class.getClassLoader(),
        new Class<?>[] { IBmObject.class },
        (proxy, method, args) -> {
            if ("bmIsTop".equals(method.getName())) {
                return Boolean.TRUE;
            }
            if ("bmGetFqn".equals(method.getName())) {
                return "Catalog.Products";
            }
            return null;
        });
```

**What to Mock:**
- EDT interfaces that are unavailable outside an EDT runtime: `IBmObject`, `IProject`, `IBmModelManager`, project gateway/service collaborators.
- Provider responses and streaming chunks when testing parser/session behavior.
- Tool gateway/service dependencies when asserting payload contracts.
- UI browser interactions at DOM boundary in PDE tests.

**What NOT to Mock:**
- JSON schema strings for tool definitions; parse the real schema from `ToolDefinition`.
- Profile allowlists and permissions; instantiate the real profile/registry classes.
- Tool result envelopes; call the real tool or service boundary where practical.
- Pure transformation helpers such as `FileEditApplier`, `JsonRepairUtil`, and compatibility profile resolution.

**Reflection and singleton isolation:**
- Use reflection only when the production singleton has no test seam and restore state in `@After`.
- `ToolRegistryTestSupport` at `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/ToolRegistryTestSupport.java` uses `Unsafe.allocateInstance` and reflection to isolate `ToolRegistry`; prefer it over duplicating singleton hacks.

## Fixtures and Factories

**Test Data:**
```text
bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/provider/config/fixtures/
├── glm5_reasoning_then_toolcall.sse
├── minimax_reasoning_then_toolcall.sse
└── structured_toolcall_clean.sse
```

**Location:**
- Provider stream fixtures: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/provider/config/fixtures`.
- Temporary file fixtures: created per test with `Files.createTempDirectory(...)`, `Files.createTempFile(...)`, or JUnit `TemporaryFolder`.
- Playwright output: `output/playwright/remote-web` configured by `e2e/remote-web/playwright.config.mjs`.

**Guidance:**
- Keep fixtures small and specific to one parser/session behavior.
- Prefer in-test builders/fakes for JSON request payloads, validation payloads, and tool calls.
- Use real Russian 1C names in metadata/naming tests when validating reserved aliases and user-facing behavior.

## Coverage

**Requirements:** No coverage threshold is enforced in Maven/Tycho configuration.

**View Coverage:**
```bash
# Not configured in this worktree.
```

**Practical expectation:**
- Add targeted tests for every new tool schema, result payload, permission/profile exposure, provider compatibility branch, and EDT mutation guard.
- For behavior that requires live EDT, add a narrow unit/contract test plus a runbook/E2E verification entry.

## Test Types

**Unit Tests:**
- Pure Java helpers and value types: `ToolResultTest`, `FileEditApplierTest`, `JsonRepairUtilTest`, `OpenAiCompatibilityProfileResolverTest`.
- BM/API safety helpers: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/edt/BmObjectHelperTest.java`.
- Validation operation and token behavior: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/edt/metadata/ValidationOperationTest.java`, `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/edt/metadata/MetadataRequestValidationServiceExtensionTest.java`.

**Contract Tests:**
- Tool payload envelopes: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/ToolPayloadContractTest.java`.
- Tool schema parsing and required fields: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/metadata/AddMetadataChildToolSchemaTest.java`.
- Tool descriptions/profile visibility: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/ToolDescriptionCoverageTest.java`.
- Prompt/profile invariants: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/agent/PromptSnapshotTest.java`, `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/agent/profiles/AgentProfileRegistryTest.java`.

**Provider Compatibility Tests:**
- Compatibility profile resolution: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/provider/config/OpenAiCompatibilityProfileResolverTest.java`.
- Content fallback parsing: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/provider/config/ContentToolCallFallbackParserTest.java`.
- Streaming parser repair semantics: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/provider/config/OpenAiStreamingToolCallParserRepairFlagTest.java`.
- Streaming fixture replay: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/provider/config/OpenAiStreamingSessionTest.java`.

**Integration Tests:**
- Core integration tests are mostly contract-style tests against real registries/services with fake EDT/provider collaborators.
- UI integration tests run as an Eclipse test plugin via `bundles/com.codepilot1c.ui.tests/pom.xml`.

**E2E Tests:**
- Scenario catalog: `docs/E2E_TEST_SCENARIOS.md`.
- Live result examples: `docs/E2E_TEST_RESULTS_2026-04-08.md`.
- Remote web harness: `e2e/remote-web/tests/remote-ui.spec.mjs`, `e2e/remote-web/tests/remote-ui.live.spec.mjs`.
- EDT UI emulation runbook: `docs/runbooks/edt-ui-emulation-runbook.md`.

## Common Patterns

**Async Testing:**
```java
ToolResult result = tool.execute(args).join();
assertTrue(result.isSuccess());
```

**Error Testing:**
```java
try {
    service.validateAndIssueToken(request);
    fail("Expected MetadataOperationException");
} catch (MetadataOperationException ex) {
    assertEquals(MetadataOperationCode.INVALID_REQUEST, ex.getCode());
    assertTrue(ex.isRecoverable());
}
```

**JSON Contract Testing:**
```java
ToolResult result = tool.execute(args).join();
JsonObject structured = result.getStructuredData();

assertEquals("error", structured.get("status").getAsString());
assertTrue(structured.has("error_code"));
assertTrue(structured.has("op_id"));
```

**Schema Testing:**
```java
JsonObject schema = JsonParser.parseString(tool.getDefinition().getParameterSchema()).getAsJsonObject();
JsonArray required = schema.getAsJsonArray("required");

assertTrue(required.contains(new JsonPrimitive("validation_token")));
```

**UI Event Loop Testing:**
```java
long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
while (System.currentTimeMillis() < deadline) {
    if (condition.getAsBoolean()) {
        return;
    }
    while (display.readAndDispatch()) {
        // drain SWT events
    }
    Thread.sleep(50);
}
fail("Timed out waiting for UI condition");
```

## New Tool Test Checklist

**Required tests:**
- Add or update schema tests under `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/...`.
- Assert `ToolDefinition.getParameterSchema()` parses as JSON and has the expected `required` fields.
- Assert deterministic success and failure `ToolResult` payloads, including machine-readable error fields.
- Update profile/description coverage tests when the tool becomes visible through a profile: `ToolDescriptionCoverageTest`, `AgentProfileRegistryTest`.
- Test mutating tools with missing/invalid/reused `validation_token` when `@ToolMeta(requiresValidationToken=true)`.
- Add provider compatibility/streaming tests when tool output can be large, nested, or likely to trigger fallback parsing.

**Qwen/OpenAI-compatible expectations:**
- Test that new tool descriptions stay concise enough for provider prompt surfaces.
- Test or inspect generated examples if Qwen-native priming files are introduced.
- Add large-tool-result compatibility policy tests when output can exceed 50,000 characters.
- Preserve tests for repaired mutating call rejection in `ToolExecutionService`.

## Metadata Mutation Test Checklist

**Required tests:**
- Test `edt_validate_request -> validation_token -> mutation` flow with the real `MetadataRequestValidationService` where possible.
- Assert tokens are one-time, operation-bound, project-bound, and payload-bound through `ValidationTokenStore`.
- Test reserved standard attribute rejection for Catalog, Document, InformationRegister, and AccumulationRegister names, including Russian aliases.
- Test BM top-object normalization and `bmGetFqn()` guard behavior through `BmObjectHelperTest`.
- Test export/diagnostics payload structure for tools that trace or mutate EDT artifacts.

**Live verification:**
- Re-run diagnostics after metadata/form mutation using the dynamic UI diagnostics tool or EDT diagnostics tools.
- For export incidents, use `docs/reports/edt-metadata-uuid-export-runbook.md` and `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/edt/EdtTraceExportTool.java`.
- Verify `src/Configuration/Configuration.mdo` after mutation/export when the workflow changes metadata files.

## UI Test Checklist

**Required tests:**
- Keep UI-only logic tests in `bundles/com.codepilot1c.ui.tests/src`.
- Use Tycho UI harness settings in `bundles/com.codepilot1c.ui.tests/pom.xml`: `useUIHarness=true`, `useUIThread=true`.
- Test dynamic UI tool registration lifecycle where UI tools are introduced or changed.
- Poll the SWT event loop instead of using fixed sleeps only.
- Keep UI tests resilient to SWT browser timing by waiting on DOM-visible state.

## RAG/Indexer Test Checklist

**Current state:**
- The requested `bundles/com.codepilot1c.rag` bundle and `CodeChunkerRegistry` are not present in this worktree.

**When indexing code exists:**
- Add unit tests for chunker selection by extension/registry.
- Add cancellation tests for background indexing jobs.
- Add batching tests for large project/file sets.
- Add explicit failure tests for missing embedding-provider configuration.
- Add commit/optimize tests for index lifecycle behavior.

## E2E And Local EDT Runbooks

**Scenario conventions:**
- Use exact tool IDs from `ToolRegistry.registerDefaultTools()` in E2E scenarios.
- Use composite names like `edt_diagnostics(command)` only when the tool schema uses a `command` dispatcher.
- Include Preconditions, User action, Assertions, and Recovery in scenario documentation.
- Require a fresh one-time `validation_token` for every mutating metadata/form/DCS/extension/external operation.

**Runbook references:**
- Metadata UUID/export incident: `docs/reports/edt-metadata-uuid-export-runbook.md`.
- EDT BM/API retrospective: `docs/reports/edt-api-patterns-retrospective-2026-02-14.md`.
- EDT BM model investigation: `docs/reports/edt-bm-model-investigation-2026-02-13.md`.
- EDT diagnostics research: `docs/reports/edt-diagnostics-research-2026-02-15.md`.
- EDT UI emulation: `docs/runbooks/edt-ui-emulation-runbook.md`.
- Tool Graph Router plan: `docs/reports/tool-graph-router-plan-2026-02-25.md`.

**Local scripts:**
- The project guidance references local scripts such as `tools/publish-p2-local.sh`, `tools/run-edt-e2e-local.sh`, `tools/run-qwen-local-edt-suite.sh`, and `tools/run-qwen-mcp-suite.py`; these paths are not present in this worktree.
- Do not replace full release/update verification with partial Tycho builds. Use `mvn -DskipTests package` from the repository root for deliverable builds.

## Build Verification Pattern

**Default quality loop:**
```bash
mvn test
```

**Core-only focused loop:**
```bash
mvn -pl bundles/com.codepilot1c.core.tests -am test
```

**UI/PDE loop:**
```bash
mvn -Pdesktop-ui-tests test
```

**Release/update-site loop:**
```bash
mvn -DskipTests package
```

**Artifact verification:**
- Publish/install only from `repositories/com.codepilot1c.update/target/repository`.
- The update ZIP path is `repositories/com.codepilot1c.update/target/com.codepilot1c.update-1.3.0-SNAPSHOT.zip`.
- After a deliverable build, inspect produced p2 content and plugin qualifiers before asking a user to update.

---

*Testing analysis: 2026-07-01*
