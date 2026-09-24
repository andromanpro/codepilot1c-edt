/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.gsd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.codepilot1c.core.agent.profiles.AgentCapability;
import com.codepilot1c.core.gsd.GsdFeatureGate;
import com.codepilot1c.core.model.ToolCall;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolExecutionContext;
import com.codepilot1c.core.tools.ToolExecutionService;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonObject;

import sun.misc.Unsafe;

/** Route-level coverage for trusted ChatView/agent GSD execution identities. */
public class GsdRoutedCapturedIdentityTest {

    private static final Path ARTEL_CAPTURED =
            Path.of("/Users/alex/repo/artel/exten_artel/ДО.Артель"); //$NON-NLS-1$
    private static final Path ARTEL_REPOSITORY_PARENT =
            Path.of("/Users/alex/repo/artel/exten_artel"); //$NON-NLS-1$

    private static final List<String> GSD_TOOL_NAMES = List.of(
            "gsd_get_state", //$NON-NLS-1$
            "gsd_record_decision", //$NON-NLS-1$
            "gsd_create_plan", //$NON-NLS-1$
            "gsd_update_task", //$NON-NLS-1$
            "gsd_record_evidence", //$NON-NLS-1$
            "gsd_record_verification_outcome", //$NON-NLS-1$
            "gsd_record_shipment", //$NON-NLS-1$
            "gsd_transition"); //$NON-NLS-1$

    @Test
    public void naturalProjectHintUsesTrustedCapturedIdentity() throws Exception {
        ToolResult result = executeGetState(
                Map.of("project_path", "artel"), ARTEL_CAPTURED).join(); //$NON-NLS-1$ //$NON-NLS-2$

        assertIdentitySuccess(result, ARTEL_CAPTURED);
    }

    @Test
    public void omittedProjectPathUsesTrustedCapturedIdentity() throws Exception {
        ToolResult result = executeGetState(Map.of(), ARTEL_CAPTURED).join();

        assertIdentitySuccess(result, ARTEL_CAPTURED);
    }

    @Test
    public void exactCapturedProjectPathStillSucceeds() throws Exception {
        ToolResult result = executeGetState(
                Map.of("project_path", ARTEL_CAPTURED.toString()), //$NON-NLS-1$
                ARTEL_CAPTURED).join();

        assertIdentitySuccess(result, ARTEL_CAPTURED);
    }

    @Test
    public void explicitMismatchedAbsoluteProjectPathStillFailsClosed() throws Exception {
        ToolResult result = executeGetState(
                Map.of("project_path", ARTEL_REPOSITORY_PARENT.toString()), //$NON-NLS-1$
                ARTEL_CAPTURED).join();

        assertIdentityFailure(result,
                "project_path does not match the captured execution identity"); //$NON-NLS-1$
    }

    @Test
    public void rawDotAndDotDotTraversalFailBeforeNormalization() throws Exception {
        for (String requested : List.of(
                ARTEL_CAPTURED + "/.", //$NON-NLS-1$
                ARTEL_CAPTURED + "/child/..", //$NON-NLS-1$
                ARTEL_CAPTURED + "\\child\\..", //$NON-NLS-1$
                ".", //$NON-NLS-1$
                "..")) { //$NON-NLS-1$
            ToolResult result = executeGetState(
                    Map.of("project_path", requested), ARTEL_CAPTURED).join(); //$NON-NLS-1$
            assertIdentityFailure(result, "project_path traversal is not allowed"); //$NON-NLS-1$
        }
    }

    @Test
    public void everyGsdToolRouteReceivesCapturedIdentityForNaturalAndOmittedArgs()
            throws Exception {
        ToolExecutionContext context = context(ARTEL_CAPTURED);

        for (String toolName : GSD_TOOL_NAMES) {
            RecordingTool tool = new RecordingTool(toolName);
            RouteHarness harness = harness(tool);
            Map<String, Object> natural = new LinkedHashMap<>();
            natural.put("project_path", "artel"); //$NON-NLS-1$ //$NON-NLS-2$

            ToolResult naturalResult = executeCurrent(
                    harness, natural, context).join();
            assertTrue(toolName, naturalResult.isSuccess());
            assertEquals(toolName, ARTEL_CAPTURED.toString(),
                    tool.parameters.get().get("project_path")); //$NON-NLS-1$
            assertEquals(toolName, "artel", natural.get("project_path")); //$NON-NLS-1$ //$NON-NLS-2$
            assertSame(toolName, context, tool.context.get());

            tool.clear();
            Map<String, Object> omitted = new LinkedHashMap<>();
            ToolResult omittedResult = executeCurrent(
                    harness, omitted, context).join();
            assertTrue(toolName, omittedResult.isSuccess());
            assertEquals(toolName, ARTEL_CAPTURED.toString(),
                    tool.parameters.get().get("project_path")); //$NON-NLS-1$
            assertFalse(toolName, omitted.containsKey("project_path")); //$NON-NLS-1$
            assertSame(toolName, context, tool.context.get());
        }
    }

    @Test
    public void gsdOffBlocksBeforeCapturedIdentityRouting() throws Exception {
        String previous = System.getProperty(GsdFeatureGate.JVM_PROPERTY);
        try {
            System.setProperty(GsdFeatureGate.JVM_PROPERTY, "false"); //$NON-NLS-1$
            RecordingTool tool = new RecordingTool("gsd_get_state"); //$NON-NLS-1$

            ToolResult result = executeCurrent(
                    harness(tool), Map.of(), context(ARTEL_CAPTURED)).join();

            assertFalse(result.isSuccess());
            assertEquals(ToolExecutionService.GSD_DISABLED_ERROR,
                    result.getStructuredString("error_code")); //$NON-NLS-1$
            assertNull(tool.parameters.get());
        } finally {
            if (previous == null) {
                System.clearProperty(GsdFeatureGate.JVM_PROPERTY);
            } else {
                System.setProperty(GsdFeatureGate.JVM_PROPERTY, previous);
            }
        }
    }

    @Test
    public void incompleteExecutionContextDoesNotAuthorizeNaturalHint() throws Exception {
        IdentityProbeTool tool = new IdentityProbeTool();
        ToolExecutionContext incomplete = new ToolExecutionContext(
                "gsd-discuss", AgentCapability.READ_ONLY, 0, //$NON-NLS-1$
                ARTEL_CAPTURED.toString(), ""); //$NON-NLS-1$

        ToolResult result = executeCurrent(
                harness(tool), Map.of("project_path", "artel"), incomplete).join(); //$NON-NLS-1$ //$NON-NLS-2$

        assertIdentityFailure(result,
                "GSD execution requires scoped project_path and session identity"); //$NON-NLS-1$
    }

    private CompletableFuture<ToolResult> executeGetState(
            Map<String, Object> parameters, Path captured) throws Exception {
        IdentityProbeTool tool = new IdentityProbeTool();
        return executeCurrent(harness(tool), parameters, context(captured));
    }

    private static ToolExecutionContext context(Path captured) {
        return new ToolExecutionContext(
                "gsd-discuss", AgentCapability.READ_ONLY, 0, //$NON-NLS-1$
                captured.toString(), "chat-view-session"); //$NON-NLS-1$
    }

    private static ToolCall call(String toolName) {
        return new ToolCall("call-1", toolName, "{}"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void assertIdentitySuccess(ToolResult result, Path captured) {
        assertTrue(result.getErrorMessage(), result.isSuccess());
        assertEquals(captured.toString(), result.getContent());
        assertEquals("success", result.getStructuredString("status")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("gsd_get_state", result.getStructuredString("operation")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, result.getStructuredInt("revision", -1)); //$NON-NLS-1$
    }

    private static void assertIdentityFailure(ToolResult result, String message) {
        assertFalse(result.isSuccess());
        assertEquals(message, result.getErrorMessage());
        assertEquals("error", result.getStructuredString("status")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("gsd_get_state", result.getStructuredString("operation")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("identity", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, result.getStructuredInt("revision", -1)); //$NON-NLS-1$
        assertEquals("", result.getStructuredString("phase")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static RouteHarness harness(ITool tool) throws Exception {
        ToolRegistry registry = isolatedRegistry(Map.of(tool.getName(), tool));
        return new RouteHarness(registry, new ToolExecutionService(registry), tool.getName());
    }

    private static CompletableFuture<ToolResult> executeCurrent(
            RouteHarness harness, Map<String, Object> parameters,
            ToolExecutionContext context) {
        ToolCall call = call(harness.toolName());
        return harness.service().executeIfCurrent(
                call, parameters, null, null, context,
                harness.registry().resolveTool(harness.toolName()))
                .orElseThrow(() -> new AssertionError("test registry resolution became stale")); //$NON-NLS-1$
    }

    private static ToolRegistry isolatedRegistry(Map<String, ITool> tools) throws Exception {
        ToolRegistry registry = (ToolRegistry) unsafe().allocateInstance(ToolRegistry.class);
        setField(registry, "tools", new HashMap<>(tools)); //$NON-NLS-1$
        setField(registry, "dynamicTools", new ConcurrentHashMap<String, ITool>()); //$NON-NLS-1$
        return registry;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = ToolRegistry.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); //$NON-NLS-1$
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private record RouteHarness(
            ToolRegistry registry, ToolExecutionService service, String toolName) {
    }

    private static final class RecordingTool implements ITool {
        private final String name;
        private final AtomicReference<Map<String, Object>> parameters = new AtomicReference<>();
        private final AtomicReference<ToolExecutionContext> context = new AtomicReference<>();

        private RecordingTool(String name) {
            this.name = name;
        }

        private void clear() {
            parameters.set(null);
            context.set(null);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "route probe"; //$NON-NLS-1$
        }

        @Override
        public String getParameterSchema() {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        @Override
        public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            throw new AssertionError("scoped route must use contextual execution"); //$NON-NLS-1$
        }

        @Override
        public CompletableFuture<ToolResult> execute(
                Map<String, Object> parameters, ToolExecutionContext context) {
            this.parameters.set(parameters);
            this.context.set(context);
            return CompletableFuture.completedFuture(ToolResult.success("ok")); //$NON-NLS-1$
        }
    }

    /** Executes the production GSD identity guard without touching the filesystem state layer. */
    private static final class IdentityProbeTool implements ITool {

        @Override
        public String getName() {
            return "gsd_get_state"; //$NON-NLS-1$
        }

        @Override
        public String getDescription() {
            return "identity route probe"; //$NON-NLS-1$
        }

        @Override
        public String getParameterSchema() {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        @Override
        public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            throw new AssertionError("scoped route must use contextual execution"); //$NON-NLS-1$
        }

        @Override
        public CompletableFuture<ToolResult> execute(
                Map<String, Object> parameters, ToolExecutionContext context) {
            try {
                String resolved = GsdToolSupport.requireProject(
                        new ToolParameters(parameters), context);
                JsonObject structured = new JsonObject();
                structured.addProperty("status", "success"); //$NON-NLS-1$ //$NON-NLS-2$
                structured.addProperty("operation", getName()); //$NON-NLS-1$
                structured.addProperty("revision", 0); //$NON-NLS-1$
                return CompletableFuture.completedFuture(
                        ToolResult.success(resolved, structured));
            } catch (GsdToolSupport.GsdToolIdentityException e) {
                return CompletableFuture.completedFuture(
                        GsdToolSupport.identityFailure(getName(), e));
            }
        }
    }
}
