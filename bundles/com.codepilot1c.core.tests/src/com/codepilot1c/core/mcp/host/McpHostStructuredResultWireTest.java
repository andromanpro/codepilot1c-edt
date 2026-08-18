/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.codepilot1c.core.edt.forms.FormRecipePartialFailureException;
import com.codepilot1c.core.edt.forms.FormRecipePartialFailureException.BmState;
import com.codepilot1c.core.edt.forms.FormRecipePartialFailureException.FailurePhase;
import com.codepilot1c.core.edt.forms.FormRecipePartialFailureException.RollbackStatus;
import com.codepilot1c.core.edt.forms.FormRecipePartialFailureException.SerializedModelState;
import com.codepilot1c.core.edt.forms.FormRecipeResult;
import com.codepilot1c.core.edt.forms.HandlerStubReport;
import com.codepilot1c.core.edt.forms.UpdateFormModelResult;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.mcp.host.prompt.IMcpPromptProvider;
import com.codepilot1c.core.mcp.host.session.McpHostSession;
import com.codepilot1c.core.mcp.model.McpMessage;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import sun.misc.Unsafe;

/** Wire contract for structured MCP host tool results. */
public class McpHostStructuredResultWireTest {

    private static final Gson WIRE_GSON = new Gson();
    private static final String PARTIAL_ERROR =
            "[EDT_TRANSACTION_FAILED] Stub tail failed"; //$NON-NLS-1$

    private ToolRegistry registry;
    private ToolRegistry previousRegistry;

    @Before
    public void setUp() throws Exception {
        registry = isolatedRegistry();
        previousRegistry = installRegistry(registry);
    }

    @After
    public void tearDown() throws Exception {
        installRegistry(previousRegistry);
    }

    @Test
    public void applyFormRecipeSuccessIsProjectedToWire() throws Exception {
        HandlerStubReport report = new HandlerStubReport(
                List.of("АкцептФормаОбработчик"), List.of()); //$NON-NLS-1$
        FormRecipeResult recipe = recipe(report);
        ToolResult toolResult = ToolResult.success(
                recipe.formatForLlm(), formRecipePayload(recipe));

        JsonObject result = invoke("recipe_success", toolResult); //$NON-NLS-1$

        assertResultKeys(result, "isError", "content", "structuredContent"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertWireContent(toolResult, result);
        JsonObject structured = result.getAsJsonObject("structuredContent"); //$NON-NLS-1$
        assertEquals(List.of(
                "form_fqn", //$NON-NLS-1$
                "attributes_created", //$NON-NLS-1$
                "attributes_updated", //$NON-NLS-1$
                "attributes_removed", //$NON-NLS-1$
                "layout_operations_applied", //$NON-NLS-1$
                "handler_stubs_written", //$NON-NLS-1$
                "handler_stubs_skipped_existing", //$NON-NLS-1$
                "complete"), keys(structured)); //$NON-NLS-1$
        assertEquals(List.of("АкцептФормаОбработчик"), strings( //$NON-NLS-1$
                structured.get("handler_stubs_written"))); //$NON-NLS-1$
        assertEquals(List.of(), strings(structured.get("handler_stubs_skipped_existing"))); //$NON-NLS-1$
        assertTrue(structured.get("complete").getAsBoolean()); //$NON-NLS-1$
        assertEquals(toolResult.getStructuredData(), structured);
    }

    @Test
    public void mutateFormModelPartialSuccessIsProjectedToWire() throws Exception {
        HandlerStubReport report = new HandlerStubReport(
                List.of(), List.of("АкцептМодельОбработчик")); //$NON-NLS-1$
        UpdateFormModelResult model = model(report);
        ToolResult toolResult = ToolResult.success(
                model.formatForLlm(), formModelPayload(model));

        JsonObject result = invoke("model_partial_success", toolResult); //$NON-NLS-1$

        assertResultKeys(result, "isError", "content", "structuredContent"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertWireContent(toolResult, result);
        JsonObject structured = result.getAsJsonObject("structuredContent"); //$NON-NLS-1$
        assertEquals(List.of(
                "form_fqn", //$NON-NLS-1$
                "operations_applied", //$NON-NLS-1$
                "handler_stubs_written", //$NON-NLS-1$
                "handler_stubs_skipped_existing", //$NON-NLS-1$
                "complete"), keys(structured)); //$NON-NLS-1$
        assertEquals(List.of(), strings(structured.get("handler_stubs_written"))); //$NON-NLS-1$
        assertEquals(List.of("АкцептМодельОбработчик"), strings( //$NON-NLS-1$
                structured.get("handler_stubs_skipped_existing"))); //$NON-NLS-1$
        assertFalse(structured.get("complete").getAsBoolean()); //$NON-NLS-1$
        assertTrue(contentText(result).startsWith("⚠️")); //$NON-NLS-1$
        assertEquals(toolResult.getStructuredData(), structured);
    }

    @Test
    public void partialFailureKeepsStructuredDiagnosticsOnWire() throws Exception {
        JsonObject payload = partialPayload(partialFailure("Failed")); //$NON-NLS-1$
        ToolResult toolResult = ToolResult.failure(PARTIAL_ERROR, payload);

        JsonObject result = invoke("partial_failure", toolResult); //$NON-NLS-1$

        assertResultKeys(result, "isError", "content", "structuredContent"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertWireContent(toolResult, result);
        JsonObject structured = result.getAsJsonObject("structuredContent"); //$NON-NLS-1$
        assertEquals(partialKeys(), keys(structured));
        assertEquals(payload, structured);
        assertTrue(structured.get("partial").getAsBoolean()); //$NON-NLS-1$
        assertFalse(structured.get("complete").getAsBoolean()); //$NON-NLS-1$
        assertEquals("Error: " + PARTIAL_ERROR, contentText(result)); //$NON-NLS-1$
    }

    @Test
    public void defaultGsonOmitsJsonNullStructuredMember() throws Exception {
        JsonObject payload = partialPayload(partialFailure("Failed")); //$NON-NLS-1$
        payload.add("failed_handler", JsonNull.INSTANCE); //$NON-NLS-1$
        ToolResult toolResult = ToolResult.failure(PARTIAL_ERROR, payload);

        JsonObject result = invoke("partial_failure_null", toolResult); //$NON-NLS-1$

        assertResultKeys(result, "isError", "content", "structuredContent"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertWireContent(toolResult, result);
        JsonObject structured = result.getAsJsonObject("structuredContent"); //$NON-NLS-1$
        assertEquals(19, structured.size());
        assertFalse(structured.has("failed_handler")); //$NON-NLS-1$
        for (String key : requiredPartialKeys()) {
            assertTrue(key, structured.has(key));
            assertFalse(key, structured.get(key).isJsonNull());
        }
    }

    @Test
    public void genericErrorsKeepLegacyWireShape() {
        for (String error : List.of(
                "[METADATA_NOT_FOUND] Unknown event", //$NON-NLS-1$
                "[INVALID_VALIDATION_TOKEN] Token was already used", //$NON-NLS-1$
                "[METADATA_ALREADY_EXISTS] Command already exists")) { //$NON-NLS-1$
            ToolResult toolResult = ToolResult.failure(error);
            JsonObject result = invoke("generic_error_" + registry.getDynamicToolNames().size(), toolResult); //$NON-NLS-1$

            assertResultKeys(result, "isError", "content"); //$NON-NLS-1$ //$NON-NLS-2$
            assertWireContent(toolResult, result);
            assertFalse(result.has("structuredContent")); //$NON-NLS-1$
            assertEquals("Error: " + error, contentText(result)); //$NON-NLS-1$
        }
    }

    @Test
    public void emptyStructuredDataKeepsLegacyWireShape() {
        ToolResult toolResult = ToolResult.success("ok", new JsonObject()); //$NON-NLS-1$

        JsonObject result = invoke("empty_structured", toolResult); //$NON-NLS-1$

        assertResultKeys(result, "isError", "content"); //$NON-NLS-1$ //$NON-NLS-2$
        assertWireContent(toolResult, result);
        assertFalse(result.has("structuredContent")); //$NON-NLS-1$
    }

    @Test
    public void humanContentIsByteIdenticalForEveryStructuredResultShape() throws Exception {
        FormRecipeResult recipe = recipe(new HandlerStubReport(List.of("Written"), List.of())); //$NON-NLS-1$
        UpdateFormModelResult model = model(new HandlerStubReport(List.of(), List.of("Skipped"))); //$NON-NLS-1$
        JsonObject partial = partialPayload(partialFailure("Failed")); //$NON-NLS-1$
        JsonObject nullablePartial = partial.deepCopy();
        nullablePartial.add("failed_handler", JsonNull.INSTANCE); //$NON-NLS-1$
        List<ToolResult> cases = List.of(
                ToolResult.success(recipe.formatForLlm(), formRecipePayload(recipe)),
                ToolResult.success(model.formatForLlm(), formModelPayload(model)),
                ToolResult.failure(PARTIAL_ERROR, partial),
                ToolResult.failure(PARTIAL_ERROR, nullablePartial));

        for (int index = 0; index < cases.size(); index++) {
            ToolResult toolResult = cases.get(index);
            JsonObject result = invoke("content_identity_" + index, toolResult); //$NON-NLS-1$

            assertWireContent(toolResult, result);
            assertEquals(1, result.getAsJsonArray("content").size()); //$NON-NLS-1$
            assertFalse(contentText(result).contains(toolResult.getStructuredData().toString()));
        }
    }

    @Test
    public void hostGeneratedErrorsNeverExposeStructuredContent() {
        ToolResult structuredResult = ToolResult.success(
                "must not execute", simpleStructured()); //$NON-NLS-1$
        register(new ResultTool("not_exposed", structuredResult)); //$NON-NLS-1$
        JsonObject notExposed = wireResult(router(Set.of(), McpHostConfig.MutationPolicy.ALLOW, "") //$NON-NLS-1$
                .route(call("not_exposed"), session())); //$NON-NLS-1$

        JsonObject unknown = wireResult(router(Set.of("unknown"), //$NON-NLS-1$
                McpHostConfig.MutationPolicy.ALLOW, "") //$NON-NLS-1$
                .route(call("unknown"), session())); //$NON-NLS-1$

        register(new ResultTool("policy_denied", structuredResult)); //$NON-NLS-1$
        JsonObject denied = wireResult(router(Set.of("policy_denied"), //$NON-NLS-1$
                McpHostConfig.MutationPolicy.DENY, "") //$NON-NLS-1$
                .route(call("policy_denied"), session())); //$NON-NLS-1$

        register(new ResultTool("profile_denied", structuredResult)); //$NON-NLS-1$
        JsonObject profileDenied = wireResult(router(Set.of("profile_denied"), //$NON-NLS-1$
                McpHostConfig.MutationPolicy.ALLOW, "missing-test-profile") //$NON-NLS-1$ //$NON-NLS-2$
                .route(call("profile_denied"), session())); //$NON-NLS-1$

        for (JsonObject result : List.of(notExposed, unknown, denied, profileDenied)) {
            assertResultKeys(result, "isError", "content"); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(result.get("isError").getAsBoolean()); //$NON-NLS-1$
            assertFalse(result.has("structuredContent")); //$NON-NLS-1$
        }
    }

    @Test
    public void routedStructuredContentIsAnIndependentCopy() {
        JsonObject original = simpleStructured();
        ToolResult toolResult = ToolResult.success("ok", original); //$NON-NLS-1$
        register(new ResultTool("deep_copy", toolResult)); //$NON-NLS-1$
        McpMessage response = router(Set.of("deep_copy"), //$NON-NLS-1$
                McpHostConfig.MutationPolicy.ALLOW, "") //$NON-NLS-1$
                .route(call("deep_copy"), session()); //$NON-NLS-1$

        original.addProperty("added_after_route", true); //$NON-NLS-1$

        JsonObject structured = wireResult(response).getAsJsonObject("structuredContent"); //$NON-NLS-1$
        assertFalse(structured.has("added_after_route")); //$NON-NLS-1$
        assertEquals("value", structured.get("stable").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void toolsListDoesNotAcquireOutputSchema() {
        register(new ResultTool("listed", ToolResult.success("ok", simpleStructured()))); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject response = wire(router(Set.of("listed"), //$NON-NLS-1$
                McpHostConfig.MutationPolicy.ALLOW, "") //$NON-NLS-1$
                .route(request("tools/list", Map.of()), session())); //$NON-NLS-1$

        JsonObject tool = response.getAsJsonObject("result") //$NON-NLS-1$
                .getAsJsonArray("tools").get(0).getAsJsonObject(); //$NON-NLS-1$
        assertEquals(Set.of("name", "description", "inputSchema"), tool.keySet()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(tool.has("outputSchema")); //$NON-NLS-1$
    }

    @Test
    public void wireSerializerMatchesProductionDefaultGson() throws Exception {
        String source = Files.readString(locateHostTransportSource());

        assertTrue(source.contains("private final Gson gson = new Gson();")); //$NON-NLS-1$
        assertFalse(source.contains("serializeNulls")); //$NON-NLS-1$
    }

    @Test
    public void structuredResultIsProtocolVersionIndependent() {
        ToolResult toolResult = ToolResult.success("stable text", simpleStructured()); //$NON-NLS-1$
        register(new ResultTool("protocol_result", toolResult)); //$NON-NLS-1$
        List<String> results = new ArrayList<>();

        for (String protocol : List.of("2024-11-05", "2025-06-18", "2025-11-25")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            McpHostRequestRouter router = router(Set.of("protocol_result"), //$NON-NLS-1$
                    McpHostConfig.MutationPolicy.ALLOW, ""); //$NON-NLS-1$
            McpHostSession session = session();
            router.route(request("initialize", Map.of( //$NON-NLS-1$
                    "protocolVersion", protocol, //$NON-NLS-1$
                    "clientInfo", Map.of("name", "wire-test", "version", "1"))), session); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

            JsonObject result = wireResult(router.route(call("protocol_result"), session)); //$NON-NLS-1$
            assertResultKeys(result, "isError", "content", "structuredContent"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertWireContent(toolResult, result);
            results.add(result.toString());
        }

        assertEquals(results.get(0), results.get(1));
        assertEquals(results.get(0), results.get(2));
    }

    private JsonObject invoke(String toolName, ToolResult result) {
        register(new ResultTool(toolName, result));
        return wireResult(router(Set.of(toolName), McpHostConfig.MutationPolicy.ALLOW, "") //$NON-NLS-1$
                .route(call(toolName), session()));
    }

    private ResultTool register(ResultTool tool) {
        registry.registerDynamicTool(tool);
        return tool;
    }

    private McpHostRequestRouter router(
            Set<String> exposed, McpHostConfig.MutationPolicy policy, String profileId) {
        return new McpHostRequestRouter(
                new NamedExposurePolicy(exposed), List.of(), prompts(), policy, profileId);
    }

    private static IMcpPromptProvider prompts() {
        return new IMcpPromptProvider() {
            @Override
            public List<com.codepilot1c.core.mcp.model.McpPrompt> listPrompts() {
                return List.of();
            }

            @Override
            public java.util.Optional<com.codepilot1c.core.mcp.model.McpPromptResult> getPrompt(
                    String name, Map<String, Object> arguments) {
                return java.util.Optional.empty();
            }
        };
    }

    private static McpHostSession session() {
        McpHostSession session = new McpHostSession("mcp-structured-wire-test"); //$NON-NLS-1$
        session.setClientName("wire-test"); //$NON-NLS-1$
        return session;
    }

    private static McpMessage call(String toolName) {
        return request("tools/call", new LinkedHashMap<>(Map.of( //$NON-NLS-1$
                "name", toolName, "arguments", Map.of()))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static McpMessage request(String method, Object params) {
        McpMessage request = new McpMessage();
        request.setMethod(method);
        request.setRawId(method + "-id"); //$NON-NLS-1$
        request.setParams(params);
        return request;
    }

    private static JsonObject wire(McpMessage response) {
        return JsonParser.parseString(WIRE_GSON.toJson(response)).getAsJsonObject();
    }

    private static JsonObject wireResult(McpMessage response) {
        return wire(response).getAsJsonObject("result"); //$NON-NLS-1$
    }

    private static void assertWireContent(ToolResult expected, JsonObject result) {
        assertEquals(!expected.isSuccess(), result.get("isError").getAsBoolean()); //$NON-NLS-1$
        assertEquals(1, result.getAsJsonArray("content").size()); //$NON-NLS-1$
        JsonObject content = result.getAsJsonArray("content").get(0).getAsJsonObject(); //$NON-NLS-1$
        assertEquals("text", content.get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(expected.getContentForLlm(), content.get("text").getAsString()); //$NON-NLS-1$
    }

    private static String contentText(JsonObject result) {
        return result.getAsJsonArray("content").get(0).getAsJsonObject() //$NON-NLS-1$
                .get("text").getAsString(); //$NON-NLS-1$
    }

    private static void assertResultKeys(JsonObject result, String... expected) {
        assertEquals(List.of(expected), keys(result));
    }

    private static List<String> keys(JsonObject object) {
        return new ArrayList<>(object.keySet());
    }

    private static List<String> strings(JsonElement element) {
        List<String> values = new ArrayList<>();
        element.getAsJsonArray().forEach(value -> values.add(value.getAsString()));
        return values;
    }

    private static JsonObject simpleStructured() {
        JsonObject structured = new JsonObject();
        structured.addProperty("stable", "value"); //$NON-NLS-1$ //$NON-NLS-2$
        return structured;
    }

    private static FormRecipeResult recipe(HandlerStubReport report) {
        return new FormRecipeResult(
                "Project", "Catalog.C.Form.F", 1, 2, 3, 4, List.of("add_field[1]"), report); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static UpdateFormModelResult model(HandlerStubReport report) {
        return new UpdateFormModelResult(
                "Project", "Catalog.C.Form.F", 2, List.of("add_command[1]"), report); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static FormRecipePartialFailureException partialFailure(String failedHandler) {
        return new FormRecipePartialFailureException(
                MetadataOperationCode.EDT_TRANSACTION_FAILED,
                "Stub tail failed", //$NON-NLS-1$
                false,
                "Catalog.C.Form.F", //$NON-NLS-1$
                false,
                1,
                2,
                3,
                2,
                List.of("Written", "Skipped", "Failed"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                List.of("Written"), //$NON-NLS-1$
                List.of("Skipped"), //$NON-NLS-1$
                FailurePhase.WRITE_STUB,
                failedHandler,
                RollbackStatus.NOT_ATTEMPTED,
                BmState.INITIAL_COMMIT_REMAINS,
                SerializedModelState.INITIAL_EXPORT_VERIFIED,
                null);
    }

    private static JsonObject formRecipePayload(FormRecipeResult result) throws Exception {
        return invokePayload("formRecipeSuccess", FormRecipeResult.class, result); //$NON-NLS-1$
    }

    private static JsonObject formModelPayload(UpdateFormModelResult result) throws Exception {
        return invokePayload("formModelSuccess", UpdateFormModelResult.class, result); //$NON-NLS-1$
    }

    private static JsonObject partialPayload(FormRecipePartialFailureException failure) throws Exception {
        return invokePayload("partialFailure", FormRecipePartialFailureException.class, failure); //$NON-NLS-1$
    }

    private static JsonObject invokePayload(String methodName, Class<?> argumentType, Object argument)
            throws Exception {
        Class<?> payloads = Class.forName("com.codepilot1c.core.tools.forms.FormResultPayloads"); //$NON-NLS-1$
        Method method = payloads.getDeclaredMethod(methodName, argumentType);
        method.setAccessible(true);
        return (JsonObject) method.invoke(null, argument);
    }

    private static List<String> partialKeys() {
        return List.of(
                "code", //$NON-NLS-1$
                "recoverable", //$NON-NLS-1$
                "complete", //$NON-NLS-1$
                "partial", //$NON-NLS-1$
                "form_fqn", //$NON-NLS-1$
                "external_project", //$NON-NLS-1$
                "bm_export_verified_before_stub_phase", //$NON-NLS-1$
                "attributes_created", //$NON-NLS-1$
                "attributes_updated", //$NON-NLS-1$
                "attributes_removed", //$NON-NLS-1$
                "layout_operations_initially_committed", //$NON-NLS-1$
                "handler_slots_initially_committed", //$NON-NLS-1$
                "handler_stubs_written", //$NON-NLS-1$
                "handler_stubs_skipped_existing", //$NON-NLS-1$
                "handler_slots_without_written_stub", //$NON-NLS-1$
                "failure_phase", //$NON-NLS-1$
                "failed_handler", //$NON-NLS-1$
                "rollback_status", //$NON-NLS-1$
                "bm_state", //$NON-NLS-1$
                "serialized_model_state"); //$NON-NLS-1$
    }

    private static List<String> requiredPartialKeys() {
        return List.of(
                "code", //$NON-NLS-1$
                "recoverable", //$NON-NLS-1$
                "complete", //$NON-NLS-1$
                "partial", //$NON-NLS-1$
                "handler_stubs_written", //$NON-NLS-1$
                "handler_stubs_skipped_existing", //$NON-NLS-1$
                "failure_phase", //$NON-NLS-1$
                "rollback_status", //$NON-NLS-1$
                "bm_state", //$NON-NLS-1$
                "serialized_model_state"); //$NON-NLS-1$
    }

    private static Path locateHostTransportSource() {
        Path moduleRelative = Path.of("..", "com.codepilot1c.core", "src", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "com", "codepilot1c", "core", "mcp", "host", "transport", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                "McpHostHttpTransport.java"); //$NON-NLS-1$
        if (Files.isRegularFile(moduleRelative)) {
            return moduleRelative;
        }
        Path reactorRelative = Path.of("bundles", "com.codepilot1c.core", "src", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "com", "codepilot1c", "core", "mcp", "host", "transport", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                "McpHostHttpTransport.java"); //$NON-NLS-1$
        if (Files.isRegularFile(reactorRelative)) {
            return reactorRelative;
        }
        throw new AssertionError("Cannot locate McpHostHttpTransport.java from " //$NON-NLS-1$
                + Path.of("").toAbsolutePath()); //$NON-NLS-1$
    }

    private static ToolRegistry isolatedRegistry() throws Exception {
        ToolRegistry registry = (ToolRegistry) unsafe().allocateInstance(ToolRegistry.class);
        setField(registry, "tools", new HashMap<String, ITool>()); //$NON-NLS-1$
        setField(registry, "dynamicTools", new HashMap<String, ITool>()); //$NON-NLS-1$
        setField(registry, "gson", new Gson()); //$NON-NLS-1$
        return registry;
    }

    private static ToolRegistry installRegistry(ToolRegistry registry) throws Exception {
        Field field = ToolRegistry.class.getDeclaredField("instance"); //$NON-NLS-1$
        field.setAccessible(true);
        ToolRegistry previous = (ToolRegistry) field.get(null);
        field.set(null, registry);
        return previous;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); //$NON-NLS-1$
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static final class NamedExposurePolicy implements McpToolExposurePolicy {

        private final Set<String> exposed;

        private NamedExposurePolicy(Set<String> exposed) {
            this.exposed = exposed;
        }

        @Override
        public boolean isExposed(String toolName) {
            return exposed.contains(toolName);
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

    private static final class ResultTool implements ITool {

        private final String name;
        private final ToolResult result;

        private ResultTool(String name, ToolResult result) {
            this.name = name;
            this.result = result;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "MCP structured wire test tool"; //$NON-NLS-1$
        }

        @Override
        public String getParameterSchema() {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        @Override
        public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            return CompletableFuture.completedFuture(result);
        }
    }
}
