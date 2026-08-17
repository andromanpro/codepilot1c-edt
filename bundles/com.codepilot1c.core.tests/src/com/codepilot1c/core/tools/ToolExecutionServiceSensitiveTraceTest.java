package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.junit.Test;

import com.codepilot1c.core.agent.profiles.ExploreAgentProfile;
import com.codepilot1c.core.evaluation.trace.AgentTraceSession;
import com.codepilot1c.core.evaluation.trace.tests.AbstractTraceTest;
import com.codepilot1c.core.model.ToolCall;
import com.google.gson.JsonObject;

/**
 * Integration guards for sensitive trace redaction on the pre-parsed execution
 * path used by the permission gate and delegation context propagation.
 */
public class ToolExecutionServiceSensitiveTraceTest extends AbstractTraceTest {

    @Test
    public void sensitiveResultIsOmittedOnGatedPreParsedExecutePath() throws Exception {
        String secret = "stored-secret"; //$NON-NLS-1$
        FixedResultTool tool = new FixedResultTool("gated_sensitive_trace", secret, true); //$NON-NLS-1$

        JsonObject payload = executeAndReadResult(tool, ToolExecutionContext.unscoped());

        assertRedacted(payload, secret);
    }

    @Test
    public void nonSensitiveResultPreservesTraceContractOnGatedPreParsedExecutePath() throws Exception {
        String content = "ordinary-content"; //$NON-NLS-1$
        FixedResultTool tool = new FixedResultTool("gated_regular_trace", content, false); //$NON-NLS-1$

        JsonObject payload = executeAndReadResult(tool, ToolExecutionContext.unscoped());
        Map<String, Object> contractPayload = ToolExecutionService.buildToolResultTracePayload(
                new ToolCall("call-1", tool.getName(), "{}"), ToolResult.success(content), 1L, null, false); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(tool.getName(), payload.get("tool_name").getAsString()); //$NON-NLS-1$
        assertEquals("call-1", payload.get("call_id").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(payload.has("duration_ms")); //$NON-NLS-1$
        assertTrue(payload.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("TEXT", payload.get("result_type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(content, payload.get("content").getAsString()); //$NON-NLS-1$
        assertFalse(payload.has("content_omitted")); //$NON-NLS-1$
        assertTrue(contractPayload.containsKey("content")); //$NON-NLS-1$
        assertTrue(contractPayload.containsKey("error_message")); //$NON-NLS-1$
    }

    @Test
    public void sensitiveResultIsOmittedOnScopedGatedPreParsedExecutePath() throws Exception {
        String secret = "scoped-secret"; //$NON-NLS-1$
        FixedResultTool tool = new FixedResultTool("scoped_sensitive_trace", secret, true); //$NON-NLS-1$
        ToolExecutionContext context = ToolExecutionContext.of(new ExploreAgentProfile(), 2);

        JsonObject payload = executeAndReadResult(tool, context);

        assertSame(context, tool.observedContext);
        assertRedacted(payload, secret);
    }

    private JsonObject executeAndReadResult(FixedResultTool tool, ToolExecutionContext context) throws Exception {
        ToolRegistry registry = ToolRegistry.getInstance();
        registry.registerDynamicTool(tool);
        try {
            AgentTraceSession session = AgentTraceSession.startMcpSession(
                    "sensitive-trace-session", "test", "local", "/tools/call"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            ToolExecutionService service = registry.getExecutionService();
            ToolCall call = new ToolCall("call-1", tool.getName(), "{\"approved\":true}"); //$NON-NLS-1$ //$NON-NLS-2$

            ToolResult result = service.execute(call, Map.of("approved", Boolean.TRUE), //$NON-NLS-1$
                    session, "permission-gate", context).join(); //$NON-NLS-1$

            assertTrue(result.isSuccess());
            assertEquals(tool.content, result.getContent());
            List<JsonObject> events = readJsonLines(session.getLayout().getToolsFile());
            assertEquals(2, events.size());
            assertEquals("TOOL_RESULT", events.get(1).get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
            return events.get(1).getAsJsonObject("data"); //$NON-NLS-1$
        } finally {
            registry.unregisterDynamicTool(tool.getName());
        }
    }

    private static void assertRedacted(JsonObject payload, String secret) {
        assertEquals(Boolean.TRUE, Boolean.valueOf(payload.get("content_omitted").getAsBoolean())); //$NON-NLS-1$
        assertEquals(secret.length(), payload.get("content_length").getAsInt()); //$NON-NLS-1$
        assertFalse(payload.has("content")); //$NON-NLS-1$
        assertFalse(payload.has("error_message")); //$NON-NLS-1$
        assertFalse(payload.has("exception_type")); //$NON-NLS-1$
        assertFalse(payload.has("exception_message")); //$NON-NLS-1$
        assertFalse(payload.toString().contains(secret));
    }

    private static final class FixedResultTool implements ITool {

        private final String name;
        private final String content;
        private final boolean sensitive;
        private ToolExecutionContext observedContext;

        private FixedResultTool(String name, String content, boolean sensitive) {
            this.name = name;
            this.content = content;
            this.sensitive = sensitive;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "Trace integration guard"; //$NON-NLS-1$
        }

        @Override
        public String getParameterSchema() {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        @Override
        public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            return CompletableFuture.completedFuture(ToolResult.success(content));
        }

        @Override
        public CompletableFuture<ToolResult> execute(
                Map<String, Object> parameters, ToolExecutionContext context) {
            observedContext = context;
            return execute(parameters);
        }

        @Override
        public Set<String> getTags() {
            return sensitive ? Set.of("sensitive") : Set.of(); //$NON-NLS-1$
        }
    }
}
