/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.Test;

import com.codepilot1c.core.agent.events.AgentEvent;
import com.codepilot1c.core.agent.events.ConfirmationRequiredEvent;
import com.codepilot1c.core.agent.events.IAgentEventListener;
import com.codepilot1c.core.agent.events.ToolCallEvent;
import com.codepilot1c.core.agent.events.ToolResultEvent;
import com.codepilot1c.core.agent.profiles.AgentProfile;
import com.codepilot1c.core.agent.profiles.AgentProfileRegistry;
import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.model.LlmRequest;
import com.codepilot1c.core.model.LlmResponse;
import com.codepilot1c.core.model.LlmStreamChunk;
import com.codepilot1c.core.model.ToolCall;
import com.codepilot1c.core.permissions.PermissionRule;
import com.codepilot1c.core.provider.ILlmProvider;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.surface.ToolSurfaceAugmentor;
import com.google.gson.Gson;

import sun.misc.Unsafe;

public class AgentRunnerAskGateTest {

    @Test
    public void askDecisionRequestsConfirmationEvenWhenToolDoesNotRequireIt() throws Exception {
        CountingTool editFile = new CountingTool("edit_file", false, true); //$NON-NLS-1$
        AgentRunner runner = runnerWith(editFile);
        AutoConfirmListener confirmations = new AutoConfirmListener(Resolution.CONFIRM);
        runner.addListener(confirmations);

        invokeExecute(runner, call("call-1", "src/Main.bsl"), gsdExecuteConfig()); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(1, confirmations.confirmations.size());
        assertEquals(1, editFile.executions.get());
    }

    @Test
    public void askDeniedByUserProducesDeterministicCancelledResult() throws Exception {
        CountingTool editFile = new CountingTool("edit_file", false, true); //$NON-NLS-1$
        AgentRunner runner = runnerWith(editFile);
        RecordingListener events = new RecordingListener();
        runner.addListener(events);
        runner.addListener(new AutoConfirmListener(Resolution.DENY));

        invokeExecute(runner, call("call-1", "src/Main.bsl"), gsdExecuteConfig()); //$NON-NLS-1$ //$NON-NLS-2$
        invokeExecute(runner, call("call-2", "src/Main.bsl"), gsdExecuteConfig()); //$NON-NLS-1$ //$NON-NLS-2$

        List<ToolResult> results = toolResults(events.events);
        assertEquals(0, editFile.executions.get());
        assertEquals(2, results.size());
        assertEquals("confirmation_denied", results.get(0).getStructuredString("error")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("denied_by_user", results.get(0).getStructuredString("reason_code")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("user", results.get(0).getStructuredString("layer")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(results.get(0).getContentForLlm(), results.get(1).getContentForLlm());
        assertEquals(results.get(0).getStructuredData(), results.get(1).getStructuredData());
    }

    @Test
    public void toolCallEventCarriesEffectiveConfirmationFlag() throws Exception {
        CountingTool editFile = new CountingTool("edit_file", false, true); //$NON-NLS-1$
        AgentRunner runner = runnerWith(editFile);
        RecordingListener events = new RecordingListener();
        runner.addListener(events);
        runner.addListener(new AutoConfirmListener(Resolution.CONFIRM));

        invokeExecute(runner, call("call-1", "src/Main.bsl"), gsdExecuteConfig()); //$NON-NLS-1$ //$NON-NLS-2$

        ToolCallEvent toolCall = events.events.stream()
                .filter(ToolCallEvent.class::isInstance)
                .map(ToolCallEvent.class::cast)
                .findFirst()
                .orElse(null);
        assertNotNull(toolCall);
        assertTrue(toolCall.isRequiresConfirmation());
        assertFalse(editFile.requiresConfirmation());
    }

    @Test
    public void editFileAskInGsdExecuteProfileAsksForNonMdoResource() throws Exception {
        CountingTool editFile = new CountingTool("edit_file", false, true); //$NON-NLS-1$
        AgentRunner runner = runnerWith(editFile);
        AutoConfirmListener confirmations = new AutoConfirmListener(Resolution.CONFIRM);
        runner.addListener(confirmations);

        invokeExecute(runner, call("call-1", "src/Main.bsl"), gsdExecuteConfig()); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(1, confirmations.confirmations.size());
        assertEquals(editFile.isDestructive(), confirmations.confirmations.get(0).isDestructive());
    }

    @Test
    public void mdoDenyStillWinsOverAskInGsdExecuteProfile() throws Exception {
        CountingTool editFile = new CountingTool("edit_file", false, true); //$NON-NLS-1$
        AgentRunner runner = runnerWith(editFile);
        RecordingListener events = new RecordingListener();
        AutoConfirmListener confirmations = new AutoConfirmListener(Resolution.CONFIRM);
        runner.addListener(events);
        runner.addListener(confirmations);

        invokeExecute(runner, call("call-1", "Configuration.MDO"), gsdExecuteConfig()); //$NON-NLS-1$ //$NON-NLS-2$

        ToolResult result = toolResults(events.events).get(0);
        assertEquals(0, editFile.executions.get());
        assertEquals("permission_denied", result.getStructuredString("error")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("denied_by_profile_rule", result.getStructuredString("reason_code")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(confirmations.confirmations.isEmpty());
    }

    @Test
    public void askWithoutConfirmationSinkFailsClosed() throws Exception {
        CountingTool editFile = new CountingTool("edit_file", false, true); //$NON-NLS-1$
        AgentRunner runner = runnerWith(editFile);
        RecordingListener events = new RecordingListener();
        runner.addListener(events);

        invokeExecuteFuture(runner, call("call-1", "src/Main.bsl"), gsdExecuteConfig()) //$NON-NLS-1$ //$NON-NLS-2$
                .get(1, TimeUnit.SECONDS);

        ToolResult result = toolResults(events.events).get(0);
        assertEquals(0, editFile.executions.get());
        assertEquals("permission_denied", result.getStructuredString("error")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("confirmation_unavailable", result.getStructuredString("reason_code")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("profile", result.getStructuredString("layer")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("src/Main.bsl", result.getStructuredString("resource")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void confirmationRequiringToolWithoutSinkFailsClosed() throws Exception {
        AgentProfileRegistry profiles = AgentProfileRegistry.getInstance();
        profiles.register(new ConfirmationOnlyProfile());
        try {
            CountingTool mutation = new CountingTool("mutate_form_model", true, true); //$NON-NLS-1$
            AgentRunner runner = runnerWith(mutation);
            RecordingListener events = new RecordingListener();
            runner.addListener(events);
            AgentConfig config = AgentConfig.builder()
                    .profileName(ConfirmationOnlyProfile.ID)
                    .enableTool("mutate_form_model") //$NON-NLS-1$
                    .build();

            invokeExecuteFuture(runner, new ToolCall(
                    "call-1", "mutate_form_model", //$NON-NLS-1$ //$NON-NLS-2$
                    "{\"form_fqn\":\"Catalog.X.Form.Y\"}"), config) //$NON-NLS-1$
                    .get(1, TimeUnit.SECONDS);

            ToolResult result = toolResults(events.events).get(0);
            assertEquals(0, mutation.executions.get());
            assertEquals("confirmation_unavailable_tool_policy", //$NON-NLS-1$
                    result.getStructuredString("reason_code")); //$NON-NLS-1$
            assertEquals("tool", result.getStructuredString("layer")); //$NON-NLS-1$ //$NON-NLS-2$
        } finally {
            profiles.unregister(ConfirmationOnlyProfile.ID);
        }
    }

    @Test
    public void askDoesNotChangeDeniedPayloadKeySet() throws Exception {
        CountingTool editFile = new CountingTool("edit_file", false, true); //$NON-NLS-1$
        AgentRunner runner = runnerWith(editFile);
        RecordingListener events = new RecordingListener();
        runner.addListener(events);

        invokeExecute(runner, call("call-1", "Configuration.MDO"), gsdExecuteConfig()); //$NON-NLS-1$ //$NON-NLS-2$
        invokeExecute(runner, call("call-2", "src/Main.bsl"), gsdExecuteConfig()); //$NON-NLS-1$ //$NON-NLS-2$

        List<ToolResult> results = toolResults(events.events);
        assertEquals(2, results.size());
        assertEquals(results.get(0).getStructuredData().keySet(),
                results.get(1).getStructuredData().keySet());
    }

    private static ToolCall call(String id, String path) {
        return new ToolCall(id, "edit_file", //$NON-NLS-1$
                "{\"path\":\"" + path + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static AgentConfig gsdExecuteConfig() {
        return AgentConfig.builder()
                .profileName("gsd-execute") //$NON-NLS-1$
                .enableTool("edit_file") //$NON-NLS-1$
                .build();
    }

    private static AgentRunner runnerWith(ITool tool) throws Exception {
        AgentRunner runner = new AgentRunner(new NoopProvider(),
                isolatedRegistry(Map.of(tool.getName(), tool)), "system"); //$NON-NLS-1$
        Field field = AgentRunner.class.getDeclaredField("conversationHistory"); //$NON-NLS-1$
        field.setAccessible(true);
        field.set(runner, new ArrayList<>(List.of(LlmMessage.user("test")))); //$NON-NLS-1$
        return runner;
    }

    private static List<ToolResult> toolResults(List<AgentEvent> events) {
        return events.stream()
                .filter(ToolResultEvent.class::isInstance)
                .map(ToolResultEvent.class::cast)
                .map(ToolResultEvent::getResult)
                .toList();
    }

    private static void invokeExecute(AgentRunner runner, ToolCall call, AgentConfig config)
            throws Exception {
        invokeExecuteFuture(runner, call, config).join();
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<Void> invokeExecuteFuture(
            AgentRunner runner, ToolCall call, AgentConfig config) throws Exception {
        Method method = AgentRunner.class.getDeclaredMethod(
                "executeSingleToolCall", ToolCall.class, AgentConfig.class); //$NON-NLS-1$
        method.setAccessible(true);
        return (CompletableFuture<Void>) method.invoke(runner, call, config);
    }

    private static ToolRegistry isolatedRegistry(Map<String, ITool> tools) throws Exception {
        ToolRegistry registry = (ToolRegistry) unsafe().allocateInstance(ToolRegistry.class);
        setField(registry, "tools", new HashMap<>(tools)); //$NON-NLS-1$
        setField(registry, "dynamicTools", new ConcurrentHashMap<String, ITool>()); //$NON-NLS-1$
        setField(registry, "gson", new Gson()); //$NON-NLS-1$
        setField(registry, "augmentor", ToolSurfaceAugmentor.passthrough()); //$NON-NLS-1$
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

    private enum Resolution {
        CONFIRM,
        DENY,
        SKIP
    }

    private static final class RecordingListener implements IAgentEventListener {
        private final List<AgentEvent> events = new ArrayList<>();

        @Override
        public void onEvent(AgentEvent event) {
            events.add(event);
        }
    }

    private static final class AutoConfirmListener implements IAgentEventListener {
        private final Resolution resolution;
        private final List<ConfirmationRequiredEvent> confirmations = new ArrayList<>();

        private AutoConfirmListener(Resolution resolution) {
            this.resolution = resolution;
        }

        @Override
        public void onEvent(AgentEvent event) {
            if (!(event instanceof ConfirmationRequiredEvent confirmation)) {
                return;
            }
            confirmations.add(confirmation);
            switch (resolution) {
                case CONFIRM -> confirmation.confirm();
                case DENY -> confirmation.deny();
                case SKIP -> confirmation.skip();
            }
        }

        @Override
        public boolean handlesConfirmations() {
            return true;
        }
    }

    private static final class CountingTool implements ITool {
        private final String name;
        private final boolean requiresConfirmation;
        private final boolean destructive;
        private final AtomicInteger executions = new AtomicInteger();
        private final AtomicReference<Map<String, Object>> lastParameters = new AtomicReference<>();

        private CountingTool(String name, boolean requiresConfirmation, boolean destructive) {
            this.name = name;
            this.requiresConfirmation = requiresConfirmation;
            this.destructive = destructive;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "Test tool " + name; //$NON-NLS-1$
        }

        @Override
        public String getParameterSchema() {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        @Override
        public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            lastParameters.set(parameters);
            executions.incrementAndGet();
            return CompletableFuture.completedFuture(ToolResult.success("ok")); //$NON-NLS-1$
        }

        @Override
        public boolean requiresConfirmation() {
            return requiresConfirmation;
        }

        @Override
        public boolean isDestructive() {
            return destructive;
        }
    }

    private static final class ConfirmationOnlyProfile implements AgentProfile {
        private static final String ID = "w5-confirmation-only"; //$NON-NLS-1$

        @Override
        public String getId() {
            return ID;
        }

        @Override
        public String getName() {
            return ID;
        }

        @Override
        public String getDescription() {
            return ID;
        }

        @Override
        public Set<String> getAllowedTools() {
            return Set.of("mutate_form_model"); //$NON-NLS-1$
        }

        @Override
        public List<PermissionRule> getDefaultPermissions() {
            return List.of(PermissionRule.allow("mutate_form_model").forAllResources()); //$NON-NLS-1$
        }

        @Override
        public String getSystemPromptAddition() {
            return ""; //$NON-NLS-1$
        }

        @Override
        public int getMaxSteps() {
            return 1;
        }

        @Override
        public long getTimeoutMs() {
            return 1_000L;
        }

        @Override
        public boolean isReadOnly() {
            return false;
        }

        @Override
        public boolean canExecuteShell() {
            return false;
        }
    }

    private static final class NoopProvider implements ILlmProvider {
        @Override
        public String getId() {
            return "noop"; //$NON-NLS-1$
        }

        @Override
        public String getDisplayName() {
            return "Noop"; //$NON-NLS-1$
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public boolean supportsStreaming() {
            return false;
        }

        @Override
        public CompletableFuture<LlmResponse> complete(LlmRequest request) {
            return CompletableFuture.completedFuture(LlmResponse.of("ok")); //$NON-NLS-1$
        }

        @Override
        public void streamComplete(LlmRequest request, Consumer<LlmStreamChunk> consumer) {
            consumer.accept(LlmStreamChunk.complete(LlmResponse.FINISH_REASON_STOP));
        }

        @Override
        public void cancel() {
        }

        @Override
        public void dispose() {
        }
    }
}
