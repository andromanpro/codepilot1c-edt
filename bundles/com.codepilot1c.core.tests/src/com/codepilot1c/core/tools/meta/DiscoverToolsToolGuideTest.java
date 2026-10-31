package com.codepilot1c.core.tools.meta;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.Before;
import org.junit.Test;

import com.codepilot1c.core.model.ToolDefinition;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.surface.DeferredToolSession;
import com.codepilot1c.core.tools.surface.ToolSurfaceAugmentor;
import com.codepilot1c.core.tools.surface.ToolSurfaceContext;
import com.codepilot1c.core.tools.surface.ToolSurfaceContributor;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class DiscoverToolsToolGuideTest {
    private ToolRegistry registry;
    private DiscoverToolsTool discovery;
    private DeferredToolSession session;

    @Before
    public void setUp() {
        registry = ToolRegistry.createDetached();
        registry.register(new WorkspaceTool());
        discovery = new DiscoverToolsTool(registry);
        session = new DeferredToolSession(true);
        discovery.setSession(session);
    }

    @Test
    public void guideReturnsEffectiveDescriptionAndStructuredSchemaWithoutCallingTarget() {
        registry.setAugmentor(new ToolSurfaceAugmentor(List.of(new ToolSurfaceContributor() {
            @Override
            public boolean supports(ToolSurfaceContext context) { return true; }
            @Override
            public void contribute(ToolSurfaceContext context, ToolDefinition.Builder builder) {
                builder.description("Effective description");
                builder.parametersSchema("{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}},\"required\":[\"value\"]}");
            }
        })));
        ToolResult result = discovery.execute(Map.of("tool_name", "workspace_test_tool")).join();
        assertTrue(result.getErrorMessage(), result.isSuccess());
        JsonObject guide = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertEquals("documented", guide.get("status").getAsString());
        assertEquals("Effective description", guide.get("description").getAsString());
        assertEquals("string", guide.getAsJsonObject("input_schema").getAsJsonObject("properties")
                .getAsJsonObject("value").get("type").getAsString());
        assertEquals("value", guide.getAsJsonObject("input_schema").getAsJsonArray("required").get(0).getAsString());
        assertEquals(guide, result.getStructuredData());
        assertTrue(guide.get("mutating").getAsBoolean());
        assertTrue(guide.get("requires_validation_token").getAsBoolean());
        assertTrue(guide.get("requires_confirmation").getAsBoolean());
        assertFalse(guide.get("destructive").getAsBoolean());
        assertTrue(session.getDiscoveredCategories().isEmpty());
        assertFalse(discovery.requiresConfirmation());
        assertFalse(discovery.isMutating());
    }

    @Test
    public void categoryDiscoveryRetainsExistingResultAndSessionEffect() {
        ToolResult result = discovery.execute(Map.of("category", "workspace")).join();
        assertTrue(result.getErrorMessage(), result.isSuccess());
        JsonObject payload = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertEquals("discovered", payload.get("status").getAsString());
        assertEquals(1, payload.get("tools_count").getAsInt());
        assertEquals(1, session.getDiscoveredCategories().size());
    }

    @Test
    public void rejectsUnknownToolWithoutChangingSession() {
        ToolResult result = discovery.execute(Map.of("tool_name", "missing_tool")).join();
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("Unknown tool: missing_tool"));
        assertTrue(session.getDiscoveredCategories().isEmpty());
    }

    @Test
    public void rejectsAmbiguousAndMissingSelectors() {
        for (Map<String, Object> parameters : List.<Map<String, Object>>of(Map.of(),
                Map.of("category", "workspace", "tool_name", "workspace_test_tool"))) {
            ToolResult result = discovery.execute(parameters).join();
            assertFalse(result.isSuccess());
            assertTrue(result.getErrorMessage().contains("exactly one"));
        }
        assertTrue(session.getDiscoveredCategories().isEmpty());
    }

    @Test
    public void rejectsBlankAndNonStringToolNames() {
        assertFalse(discovery.execute(Map.of("tool_name", " ")).join().isSuccess());
        assertFalse(discovery.execute(Map.of("tool_name", 12)).join().isSuccess());
        assertTrue(session.getDiscoveredCategories().isEmpty());
    }

    @Test
    public void schemaAdvertisesBothSelectorsAndDoesNotRequireCategoryForGuide() {
        JsonObject schema = JsonParser.parseString(discovery.getParameterSchema()).getAsJsonObject();
        assertTrue(schema.getAsJsonObject("properties").has("tool_name"));
        assertTrue(schema.getAsJsonObject("properties").has("category"));
        assertFalse(schema.has("required"));
    }

    private static final class WorkspaceTool implements ITool {
        @Override public String getName() { return "workspace_test_tool"; }
        @Override public String getDescription() { return "Raw description"; }
        @Override public String getParameterSchema() { return "{\"type\":\"object\"}"; }
        @Override public String getCategory() { return "workspace"; }
        @Override public boolean isMutating() { return true; }
        @Override public boolean requiresConfirmation() { return true; }
        @Override public boolean requiresValidationToken() { return true; }
        @Override public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            throw new AssertionError("Documentation must never execute its target");
        }
    }
}
