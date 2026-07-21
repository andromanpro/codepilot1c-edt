package com.codepilot1c.core.tools.surface;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.Test;

import com.codepilot1c.core.model.ToolDefinition;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolResult;

public class BackendToolSurfaceContributorTest {

    @Test
    public void backendRewriteOverridesDescriptionAndSchemaForPriorityTools() {
        ToolDefinition definition = ToolSurfaceAugmentor.defaultAugmentor().augment(
                new StubTool("edt_validate_request", "raw", "{\"type\":\"object\",\"properties\":{\"payload\":{\"type\":\"object\"}}}"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                ToolSurfaceContext.builder()
                        .builtIn(true)
                        .backendSelectedInUi(true)
                        .category(ToolCategory.METADATA_MUTATION)
                        .profile(ToolSurfaceContext.defaultProfile())
                        .build());

        assertTrue(definition.getDescription().contains("validation_token")); //$NON-NLS-1$
        assertTrue(definition.getDescription().contains("DCS/template/extension/external")); //$NON-NLS-1$
        assertTrue(definition.getDescription().contains("Tool routing: enforce edt_validate_request -> validation_token -> mutation -> diagnostics.")); //$NON-NLS-1$
        assertTrue(definition.getParametersSchema().contains("\"ensure_module_artifact\"")); //$NON-NLS-1$
        assertTrue(definition.getParametersSchema().contains("\"render_template\"")); //$NON-NLS-1$
    }

    @Test
    public void dynamicSchemasAreHardenedWithoutChangingDescriptionWhenAlreadyAnnotated() {
        ToolDefinition.Builder builder = ToolDefinition.builder()
                .name("dynamic_tool") //$NON-NLS-1$
                .description("Existing\n\nBackend note: this tool is provided by an MCP/dynamic source. Follow its schema exactly, do not assume EDT/file semantics, and rely on returned machine-readable errors.") //$NON-NLS-1$
                .parametersSchema("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}"); //$NON-NLS-1$

        new DynamicToolSurfaceContributor().contribute(
                ToolSurfaceContext.builder().builtIn(false).backendSelectedInUi(true).build(),
                builder);

        ToolDefinition definition = builder.build();
        assertEquals("dynamic_tool", definition.getName()); //$NON-NLS-1$
        assertTrue(definition.getParametersSchema().contains("\"additionalProperties\":false")); //$NON-NLS-1$
        assertTrue(definition.getParametersSchema().contains("\"required\":[]")); //$NON-NLS-1$
    }

    @Test
    public void backendRewriteKeepsExplicitEmptyRequiredArrayForOptionalOnlySchemas() {
        ToolDefinition definition = ToolSurfaceAugmentor.defaultAugmentor().augment(
                new StubTool("list_files", "raw", "{\"type\":\"object\"}"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                ToolSurfaceContext.builder()
                        .builtIn(true)
                        .backendSelectedInUi(true)
                        .category(ToolCategory.FILES_READ_SEARCH)
                        .profile(ToolSurfaceContext.defaultProfile())
                        .build());

        assertTrue(definition.getParametersSchema().contains("\"required\": []") //$NON-NLS-1$
                || definition.getParametersSchema().contains("\"required\":[]")); //$NON-NLS-1$
    }

    @Test
    public void backendRewriteWarnsAgainstDirectStructuredEdtArtifactWrites() {
        ToolDefinition writeFile = ToolSurfaceAugmentor.defaultAugmentor().augment(
                new StubTool("write_file", "raw", "{\"type\":\"object\"}"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                ToolSurfaceContext.builder()
                        .builtIn(true)
                        .backendSelectedInUi(true)
                        .category(ToolCategory.FILES_WRITE_EDIT)
                        .profile(ToolSurfaceContext.defaultProfile())
                        .build());
        ToolDefinition dcsManage = ToolSurfaceAugmentor.defaultAugmentor().augment(
                new StubTool("dcs_manage", "raw", "{\"type\":\"object\"}"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                ToolSurfaceContext.builder()
                        .builtIn(true)
                        .backendSelectedInUi(true)
                        .category(ToolCategory.METADATA_MUTATION)
                        .profile(ToolSurfaceContext.defaultProfile())
                        .build());

        assertTrue(writeFile.getDescription().contains(".mxl")); //$NON-NLS-1$
        assertTrue(writeFile.getDescription().contains("*.md")); //$NON-NLS-1$
        assertTrue(writeFile.getDescription().contains("*.txt")); //$NON-NLS-1$
        assertTrue(writeFile.getDescription().contains("semantic EDT tools")); //$NON-NLS-1$
        assertTrue(dcsManage.getDescription().contains("Никогда не пиши DCS XML/MXL")); //$NON-NLS-1$
        assertTrue(dcsManage.getDescription().contains("edt_validate_request")); //$NON-NLS-1$
    }

    private static final class StubTool implements ITool {
        private final String name;
        private final String description;
        private final String schema;

        private StubTool(String name, String description, String schema) {
            this.name = name;
            this.description = description;
            this.schema = schema;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public String getParameterSchema() {
            return schema;
        }

        @Override
        public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            return CompletableFuture.completedFuture(ToolResult.success("ok")); //$NON-NLS-1$
        }
    }
}
