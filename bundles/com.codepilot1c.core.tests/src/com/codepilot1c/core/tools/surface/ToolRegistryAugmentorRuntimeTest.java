package com.codepilot1c.core.tools.surface;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.Test;

import com.codepilot1c.core.mcp.host.McpHostConfig;
import com.codepilot1c.core.mcp.host.McpHostRequestRouter;
import com.codepilot1c.core.mcp.host.McpToolExposurePolicy;
import com.codepilot1c.core.mcp.host.prompt.IMcpPromptProvider;
import com.codepilot1c.core.mcp.model.McpPrompt;
import com.codepilot1c.core.mcp.model.McpPromptResult;
import com.codepilot1c.core.model.ToolDefinition;
import com.codepilot1c.core.tools.ToolRegistry;
import com.google.gson.Gson;

import sun.misc.Unsafe;

public class ToolRegistryAugmentorRuntimeTest {

    @Test
    public void effectiveProfileSurfaceReachesToolRegistryAndMcpHost() throws Exception {
        ToolRegistry toolRegistry = createIsolatedRegistry();
        toolRegistry.setAugmentor(new ToolSurfaceAugmentor(List.of(new ToolSurfaceContributor() {
            @Override
            public boolean supports(ToolSurfaceContext context) {
                return true;
            }

            @Override
            public void contribute(ToolSurfaceContext context, ToolDefinition.Builder builder) {
                builder.description(builder.getDescription() + " [profile=" //$NON-NLS-1$
                        + context.getProfile().getId() + "]"); //$NON-NLS-1$
            }
        })));
        toolRegistry.registerDynamicTool(new RuntimeTestTool());

        ToolDefinition expected = toolRegistry.getToolDefinitions(
                toolRegistry.createRuntimeSurfaceContext(null)).get(0);

        ToolRegistry previous = installSingleton(toolRegistry);
        List<Map<String, Object>> tools;
        try {
            McpHostRequestRouter router = new McpHostRequestRouter(
                    new AllowAllExposurePolicy(),
                    List.of(),
                    new EmptyPromptProvider(),
                    McpHostConfig.MutationPolicy.ALLOW);
            Method listTools = McpHostRequestRouter.class.getDeclaredMethod("listTools"); //$NON-NLS-1$
            listTools.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> effectiveTools = (List<Map<String, Object>>) listTools.invoke(router);
            tools = effectiveTools;
        } finally {
            installSingleton(previous);
        }

        assertFalse(tools.isEmpty());
        Map<String, Object> published = tools.stream()
                .filter(item -> expected.getName().equals(item.get("name"))) //$NON-NLS-1$
                .findFirst()
                .orElseThrow();
        assertEquals(expected.getDescription(), published.get("description")); //$NON-NLS-1$
        Map<?, ?> expectedSchema = new Gson().fromJson(expected.getParametersSchema(), Map.class);
        assertEquals(expectedSchema, published.get("inputSchema")); //$NON-NLS-1$
    }

    private static final class RuntimeTestTool implements com.codepilot1c.core.tools.ITool {
        @Override public String getName() { return "runtime_test_tool"; } //$NON-NLS-1$
        @Override public String getDescription() { return "Runtime tool"; } //$NON-NLS-1$
        @Override public String getParameterSchema() { return "{\"type\":\"object\"}"; } //$NON-NLS-1$
        @Override public CompletableFuture<com.codepilot1c.core.tools.ToolResult> execute(Map<String, Object> parameters) {
            return CompletableFuture.completedFuture(com.codepilot1c.core.tools.ToolResult.success("ok")); //$NON-NLS-1$
        }
    }

    private static final class AllowAllExposurePolicy implements McpToolExposurePolicy {
        @Override public boolean isExposed(String toolName) { return true; }
        @Override public boolean requiresConfirmation(String toolName, Map<String, Object> args) { return false; }
        @Override public boolean isDestructive(String toolName) { return false; }
    }

    private static final class EmptyPromptProvider implements IMcpPromptProvider {
        @Override public List<McpPrompt> listPrompts() { return Collections.emptyList(); }
        @Override public java.util.Optional<McpPromptResult> getPrompt(String name, Map<String, Object> arguments) {
            return java.util.Optional.empty();
        }
    }

    private static ToolRegistry createIsolatedRegistry() throws Exception {
        ToolRegistry registry = (ToolRegistry) unsafe().allocateInstance(ToolRegistry.class);
        setRegistryField(registry, "tools", new HashMap<String, com.codepilot1c.core.tools.ITool>()); //$NON-NLS-1$
        setRegistryField(registry, "dynamicTools", new ConcurrentHashMap<String, com.codepilot1c.core.tools.ITool>()); //$NON-NLS-1$
        setRegistryField(registry, "gson", new Gson()); //$NON-NLS-1$
        setRegistryField(registry, "augmentor", ToolSurfaceAugmentor.defaultAugmentor()); //$NON-NLS-1$
        return registry;
    }

    private static ToolRegistry installSingleton(ToolRegistry registry) throws Exception {
        Field field = ToolRegistry.class.getDeclaredField("instance"); //$NON-NLS-1$
        field.setAccessible(true);
        ToolRegistry previous = (ToolRegistry) field.get(null);
        field.set(null, registry);
        return previous;
    }

    private static void setRegistryField(ToolRegistry registry, String name, Object value) throws Exception {
        Field field = ToolRegistry.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(registry, value);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); //$NON-NLS-1$
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
