package com.codepilot1c.core.mcp.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.Test;

import com.codepilot1c.core.mcp.host.prompt.IMcpPromptProvider;
import com.codepilot1c.core.mcp.host.resource.IMcpResourceProvider;
import com.codepilot1c.core.mcp.host.session.McpHostSession;
import com.codepilot1c.core.mcp.model.McpMessage;
import com.codepilot1c.core.mcp.model.McpPrompt;
import com.codepilot1c.core.mcp.model.McpPromptResult;
import com.codepilot1c.core.mcp.model.McpResource;
import com.codepilot1c.core.mcp.model.McpResourceContent;

public class McpHostRequestRouterContractTest {

    @Test
    public void preservesLegacyInitializeFieldsAndAddsOptionalExperimentalBlock() {
        McpContractMetadata metadata = new McpContractMetadata(
                1, "plugin", "2025.2", "gui", "/workspace", McpReadiness.available());
        McpHostRequestRouter router = new McpHostRequestRouter(
                new AllowAllExposurePolicy(),
                List.of(),
                new EmptyPromptProvider(),
                McpHostConfig.MutationPolicy.ALLOW,
                new McpContractMetadataService(() -> metadata));

        McpMessage request = new McpMessage();
        request.setId("initialize-1"); //$NON-NLS-1$
        request.setMethod("initialize"); //$NON-NLS-1$
        request.setParams(Map.of(
                "protocolVersion", "2025-06-18", //$NON-NLS-1$
                "clientInfo", Map.of("name", "legacy-client", "version", "1.0"))); //$NON-NLS-1$ //$NON-NLS-2$

        McpMessage response = router.route(request, new McpHostSession("contract-test"));
        assertFalse(response.isErrorResponse());
        assertNotNull(response.getResult());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        assertEquals("2025-06-18", result.get("protocolVersion")); //$NON-NLS-1$
        assertEquals(Map.of("name", "CodePilot1C MCP Host", "version", "1.3.0"), result.get("serverInfo")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull(result.get("capabilities")); //$NON-NLS-1$

        @SuppressWarnings("unchecked")
        Map<String, Object> capabilities = (Map<String, Object>) result.get("capabilities"); //$NON-NLS-1$
        assertEquals(Map.of("listChanged", true), capabilities.get("tools")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Map.of("listChanged", true), capabilities.get("resources")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Map.of("listChanged", true), capabilities.get("prompts")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Map.of(), capabilities.get("logging")); //$NON-NLS-1$
        @SuppressWarnings("unchecked")
        Map<String, Object> experimental = (Map<String, Object>) capabilities.get("experimental"); //$NON-NLS-1$
        @SuppressWarnings("unchecked")
        Map<String, Object> codepilot = (Map<String, Object>) experimental.get("codepilot"); //$NON-NLS-1$
        assertFalse(codepilot.get("contractVersion") instanceof String); //$NON-NLS-1$
        assertEquals(1, ((Number) codepilot.get("contractVersion")).intValue()); //$NON-NLS-1$
        assertEquals("plugin", codepilot.get("pluginVersion")); //$NON-NLS-1$
        assertEquals("2025.2", codepilot.get("edtVersion")); //$NON-NLS-1$
        assertEquals("gui", codepilot.get("mode")); //$NON-NLS-1$
        assertEquals("/workspace", codepilot.get("workspace")); //$NON-NLS-1$
    }

    private static final class AllowAllExposurePolicy implements McpToolExposurePolicy {
        @Override
        public boolean isExposed(String toolName) {
            return true;
        }

        @Override
        public boolean requiresConfirmation(String toolName, Map<String, Object> args) {
            return false;
        }

        @Override
        public boolean isDestructive(String toolName) {
            return false;
        }
    }

    private static final class EmptyPromptProvider implements IMcpPromptProvider {
        @Override
        public List<McpPrompt> listPrompts() {
            return List.of();
        }

        @Override
        public Optional<McpPromptResult> getPrompt(String name, Map<String, Object> arguments) {
            return Optional.empty();
        }
    }
}
