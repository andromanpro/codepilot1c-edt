package com.codepilot1c.core.tools.meta;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.Test;

import com.codepilot1c.core.model.ToolDefinition;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.surface.ToolSurfaceAugmentor;
import com.codepilot1c.core.tools.surface.ToolSurfaceContext;
import com.codepilot1c.core.tools.surface.ToolSurfaceContributor;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import sun.misc.Unsafe;

public class DiscoverToolsToolSurfaceTest {

    @Test
    public void discoveryPublishesEffectiveToolDescription() throws Exception {
        ToolRegistry registry = createIsolatedRegistry();
        registry.register(new WorkspaceTool());
        registry.setAugmentor(new ToolSurfaceAugmentor(List.of(new ToolSurfaceContributor() {
            @Override
            public boolean supports(ToolSurfaceContext context) {
                return true;
            }

            @Override
            public void contribute(ToolSurfaceContext context, ToolDefinition.Builder builder) {
                builder.description(builder.getDescription() + " [effective]"); //$NON-NLS-1$
            }
        })));

        ToolResult result = new DiscoverToolsTool(registry)
                .execute(Map.of("category", "workspace")) //$NON-NLS-1$ //$NON-NLS-2$
                .join();

        assertTrue(result.isSuccess());
        JsonObject payload = JsonParser.parseString(result.getContent()).getAsJsonObject();
        JsonObject discovered = payload.getAsJsonArray("tools").get(0).getAsJsonObject(); //$NON-NLS-1$
        assertEquals("Raw description [effective]", discovered.get("description").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static ToolRegistry createIsolatedRegistry() throws Exception {
        ToolRegistry registry = (ToolRegistry) unsafe().allocateInstance(ToolRegistry.class);
        setRegistryField(registry, "tools", new HashMap<String, ITool>()); //$NON-NLS-1$
        setRegistryField(registry, "dynamicTools", new ConcurrentHashMap<String, ITool>()); //$NON-NLS-1$
        setRegistryField(registry, "gson", new Gson()); //$NON-NLS-1$
        setRegistryField(registry, "augmentor", ToolSurfaceAugmentor.defaultAugmentor()); //$NON-NLS-1$
        return registry;
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

    private static final class WorkspaceTool implements ITool {
        @Override
        public String getName() {
            return "workspace_test_tool"; //$NON-NLS-1$
        }

        @Override
        public String getDescription() {
            return "Raw description"; //$NON-NLS-1$
        }

        @Override
        public String getParameterSchema() {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        @Override
        public String getCategory() {
            return "workspace"; //$NON-NLS-1$
        }

        @Override
        public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            return CompletableFuture.completedFuture(ToolResult.success("ok")); //$NON-NLS-1$
        }
    }
}
