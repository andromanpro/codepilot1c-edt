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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.codepilot1c.core.agent.profiles.AgentCapability;
import com.codepilot1c.core.agent.profiles.AgentProfile;
import com.codepilot1c.core.agent.profiles.AgentProfileRegistry;
import com.codepilot1c.core.agent.profiles.DynamicToolCapability;
import com.codepilot1c.core.evaluation.trace.AgentTraceSession;
import com.codepilot1c.core.evaluation.trace.ArtifactLayout;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.edt.validation.ValidationTokenStore;
import com.codepilot1c.core.mcp.host.prompt.IMcpPromptProvider;
import com.codepilot1c.core.mcp.host.session.McpHostSession;
import com.codepilot1c.core.mcp.model.McpContent;
import com.codepilot1c.core.mcp.model.McpMessage;
import com.codepilot1c.core.model.ToolCall;
import com.codepilot1c.core.permissions.PermissionManager;
import com.codepilot1c.core.permissions.PermissionRule;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.TaskTool;
import com.codepilot1c.core.tools.ToolExecutionContext;
import com.codepilot1c.core.tools.ToolExecutionService;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.tools.ToolRegistry.ToolResolution;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.diagnostics.EdtDiagnosticsTool;
import com.codepilot1c.core.tools.extension.ExtensionManageTool;
import com.codepilot1c.core.tools.workspace.WorkspaceImportProjectTool;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;


public class McpHostProfileGateTest {

    private String previousTraceDir;
    private String previousTraceEnabled;
    private Path traceRoot;
    private ToolRegistry registry;
    private ToolRegistry.ScopedTestLease registryLease;
    private final List<String> registeredProfileIds = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        previousTraceDir = System.getProperty(ArtifactLayout.PROP_TRACE_DIR);
        previousTraceEnabled = System.getProperty(AgentTraceSession.PROP_TRACE_ENABLED);
        traceRoot = Files.createTempDirectory("mcp-profile-gate-"); //$NON-NLS-1$
        System.setProperty(ArtifactLayout.PROP_TRACE_DIR, traceRoot.toString());
        System.setProperty(AgentTraceSession.PROP_TRACE_ENABLED, Boolean.TRUE.toString());
        registry = isolatedRegistry();
        registryLease = ToolRegistry.installScopedForTesting(registry);
    }

    @After
    public void tearDown() throws Exception {
        for (String profileId : registeredProfileIds) {
            AgentProfileRegistry.getInstance().unregister(profileId);
        }
        registryLease.close();
        restoreProperty(ArtifactLayout.PROP_TRACE_DIR, previousTraceDir);
        restoreProperty(AgentTraceSession.PROP_TRACE_ENABLED, previousTraceEnabled);
    }

    @Test
    public void legacyModeDecisionMatrixMatchesBaseline() {
        for (McpHostConfig.MutationPolicy policy : McpHostConfig.MutationPolicy.values()) {
            for (boolean mutating : List.of(Boolean.FALSE, Boolean.TRUE)) {
                String name = "legacy_matrix_" + policy.name().toLowerCase() + "_" + mutating; //$NON-NLS-1$ //$NON-NLS-2$
                CapturingTool tool = register(new CapturingTool(name, mutating));
                McpMessage response;
                try {
                    response = router(policy, "") //$NON-NLS-1$
                            .route(call(name, new LinkedHashMap<>()), session());
                } catch (LinkageError unavailablePermissionManager) {
                    assertEquals(name, McpHostConfig.MutationPolicy.ASK, policy);
                    assertEquals(name, 0, tool.calls);
                    continue;
                }
                boolean expectedError = policy != McpHostConfig.MutationPolicy.ALLOW;
                assertEquals(name, expectedError, isToolError(response));
                assertEquals(name, expectedError ? 0 : 1, tool.calls);
                if (expectedError) {
                    assertEquals(name, "Tool execution denied by permission policy: " + policy, text(response)); //$NON-NLS-1$
                } else {
                    assertEquals(name, "ok", text(response)); //$NON-NLS-1$
                }
            }
        }
    }

    @Test
    public void legacyModeExecutesToolWhenGlobalRuleWouldAsk() {
        CapturingTool tool = register(new CapturingTool("write_file", true)); //$NON-NLS-1$

        McpMessage response = router(McpHostConfig.MutationPolicy.ALLOW, "") //$NON-NLS-1$
                .route(call(tool.getName(), Map.of("path", "file.txt")), session()); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(isToolError(response));
        assertEquals(1, tool.calls);
    }

    @Test
    public void legacyModeExecutesToolWhenGlobalRuleWouldDeny() {
        CapturingTool tool = register(new CapturingTool("shell", true)); //$NON-NLS-1$

        McpMessage response = router(McpHostConfig.MutationPolicy.ALLOW, "") //$NON-NLS-1$
                .route(call(tool.getName(), Map.of("command", "rm -rf /")), session()); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(isToolError(response));
        assertEquals(1, tool.calls);
    }

    @Test
    public void routerNeverConsultsGlobalPermissionRules() {
        assertFalse(List.of(McpHostRequestRouter.class.getDeclaredMethods()).stream()
                .anyMatch(method -> "globalRulesSafe".equals(method.getName()))); //$NON-NLS-1$
        assertFalse(List.of(McpHostRequestRouter.class.getDeclaredFields()).stream()
                .anyMatch(field -> field.getType() == PermissionManager.class));
        assertFalse(List.of(McpHostRequestRouter.class.getDeclaredConstructors()).stream()
                .flatMap(constructor -> List.of(constructor.getParameterTypes()).stream())
                .anyMatch(type -> type == PermissionManager.class));
    }

    @Test
    public void legacyModeKeepsUnfilteredToolList() {
        register(new CapturingTool("listed_a", false)); //$NON-NLS-1$
        register(new CapturingTool("listed_b", false)); //$NON-NLS-1$
        McpToolExposurePolicy exposure = new NamedExposurePolicy(Set.of("listed_a", "listed_b")); //$NON-NLS-1$ //$NON-NLS-2$

        McpMessage fourArgument = new McpHostRequestRouter(
                exposure, List.of(), prompts(), McpHostConfig.MutationPolicy.ALLOW)
                .route(request("tools/list", Map.of()), session()); //$NON-NLS-1$
        McpMessage explicitLegacy = new McpHostRequestRouter(
                exposure, List.of(), prompts(), McpHostConfig.MutationPolicy.ALLOW, "") //$NON-NLS-1$
                .route(request("tools/list", Map.of()), session()); //$NON-NLS-1$

        assertEquals(new Gson().toJson(fourArgument.getResult()),
                new Gson().toJson(explicitLegacy.getResult()));
        assertEquals(Set.of("listed_a", "listed_b"), listedNames(explicitLegacy)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void profilelessAllowListsAndCallsConfirmingCommands() {
        for (String name : List.of("edt_diagnostics", "workspace_import_project", //$NON-NLS-1$ //$NON-NLS-2$
                "extension_manage", "read_file")) { //$NON-NLS-1$ //$NON-NLS-2$
            boolean mutating = !"read_file".equals(name); //$NON-NLS-1$
            CapturingTool tool = register(new CapturingTool(
                    name, mutating, mutating, mutating));
            McpHostRequestRouter router = router(McpHostConfig.MutationPolicy.ALLOW, "  "); //$NON-NLS-1$
            Map<String, Object> listed = listedTool(router.route(
                    request("tools/list", Map.of()), session()), name); //$NON-NLS-1$

            assertFalse(name, listed.containsKey("_meta")); //$NON-NLS-1$
            if (mutating) {
                assertEquals(name, Boolean.TRUE, annotations(listed).get("destructiveHint")); //$NON-NLS-1$
            }
            McpMessage response = router.route(call(name, Map.of("command", "metadata_smoke")), //$NON-NLS-1$ //$NON-NLS-2$
                    session());
            assertFalse(name, isToolError(response));
            assertEquals(name, 1, tool.calls);
        }
    }

    @Test
    public void profilelessAllowListsAndCallsDynamicMutatingTool() {
        CapturingTool tool = new CapturingTool("mcp_external_mutation", true); //$NON-NLS-1$
        registry.registerDynamicTool(tool, DynamicToolCapability.MUTATING);
        McpHostRequestRouter router = router(McpHostConfig.MutationPolicy.ALLOW, ""); //$NON-NLS-1$

        Map<String, Object> listed = listedTool(router.route(
                request("tools/list", Map.of()), session()), tool.getName()); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, annotations(listed).get("destructiveHint")); //$NON-NLS-1$
        assertFalse(listed.containsKey("_meta")); //$NON-NLS-1$
        assertFalse(isToolError(router.route(call(tool.getName(), Map.of()), session())));
        assertEquals(1, tool.calls);
    }

    @Test
    public void localAllowWildcardListsAndCallsTaggedTools() {
        CapturingTool sensitive = register(new CapturingTool(
                "get_infobase_credentials", false) { //$NON-NLS-1$
            @Override
            public Set<String> getTags() {
                return Set.of("sensitive"); //$NON-NLS-1$
            }
        });
        CapturingTool localExec = register(new CapturingTool(
                "java_compile_probe", false) { //$NON-NLS-1$
            @Override
            public Set<String> getTags() {
                return Set.of("local-exec"); //$NON-NLS-1$
            }
        });
        McpHostConfig config = new McpHostConfig();
        config.setBindAddress("127.0.0.1"); //$NON-NLS-1$
        config.setMutationPolicy(McpHostConfig.MutationPolicy.ALLOW);
        config.setExposedToolsFilter("*"); //$NON-NLS-1$
        McpHostRequestRouter router = router(new DefaultMcpToolExposurePolicy(config),
                McpHostConfig.MutationPolicy.ALLOW, ""); //$NON-NLS-1$

        Set<String> listed = listedNames(router.route(request("tools/list", Map.of()), session())); //$NON-NLS-1$
        for (CapturingTool tool : List.of(sensitive, localExec)) {
            assertTrue(tool.getName(), listed.contains(tool.getName()));
            assertFalse(tool.getName(), isToolError(router.route(
                    call(tool.getName(), Map.of()), session())));
            assertEquals(tool.getName(), 1, tool.calls);
        }
    }

    @Test
    public void profilelessAllowStillRunsCommandValidators() {
        register(new EdtDiagnosticsTool());
        register(new WorkspaceImportProjectTool());
        register(new ExtensionManageTool());
        McpHostRequestRouter router = router(McpHostConfig.MutationPolicy.ALLOW, ""); //$NON-NLS-1$

        McpMessage diagnostics = router.route(call("edt_diagnostics", //$NON-NLS-1$
                Map.of("command", "metadata_smoke")), session()); //$NON-NLS-1$ //$NON-NLS-2$
        McpMessage workspace = router.route(call("workspace_import_project", Map.of()), session()); //$NON-NLS-1$
        McpMessage extension = router.route(call("extension_manage", //$NON-NLS-1$
                Map.of("command", "invalid")), session()); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(isToolError(diagnostics));
        assertTrue(text(diagnostics).contains("project")); //$NON-NLS-1$
        assertTrue(isToolError(workspace));
        assertTrue(text(workspace).contains("INVALID_ARGUMENT")); //$NON-NLS-1$
        assertTrue(isToolError(extension));
        assertTrue(text(extension).contains("Unknown command")); //$NON-NLS-1$
        for (McpMessage response : List.of(diagnostics, workspace, extension)) {
            assertFalse(text(response).contains("confirmation_unavailable_tool_policy")); //$NON-NLS-1$
        }
    }

    @Test
    public void profilelessAllowPassesTokenToInternalOneTimeValidator() {
        TokenCheckingTool tool = register(new TokenCheckingTool());
        McpHostRequestRouter router = router(McpHostConfig.MutationPolicy.ALLOW, ""); //$NON-NLS-1$
        String token = tool.issueToken();

        McpMessage missing = router.route(call(tool.getName(), Map.of()), session());
        McpMessage forged = router.route(call(tool.getName(),
                Map.of("validation_token", "forged")), session()); //$NON-NLS-1$ //$NON-NLS-2$
        McpMessage valid = router.route(call(tool.getName(),
                Map.of("validation_token", token)), session()); //$NON-NLS-1$
        McpMessage replay = router.route(call(tool.getName(),
                Map.of("validation_token", token)), session()); //$NON-NLS-1$

        assertTrue(text(missing), isToolError(missing));
        assertTrue(text(missing).contains("KNOWLEDGE_REQUIRED")); //$NON-NLS-1$
        assertTrue(text(forged), isToolError(forged));
        assertTrue(text(forged).contains("INVALID_VALIDATION_TOKEN")); //$NON-NLS-1$
        assertFalse(text(valid), isToolError(valid));
        assertTrue(text(replay), isToolError(replay));
        assertTrue(text(replay).contains("INVALID_VALIDATION_TOKEN")); //$NON-NLS-1$
        assertEquals(4, tool.calls);
    }

    @Test
    public void profilelessDenyAndAskRejectConfirmingCommands() {
        CapturingTool tool = register(new CapturingTool(
                "extension_manage", true, true, true)); //$NON-NLS-1$
        for (McpHostConfig.MutationPolicy policy : List.of(
                McpHostConfig.MutationPolicy.DENY, McpHostConfig.MutationPolicy.ASK)) {
            McpMessage response = router(policy, "") //$NON-NLS-1$
                    .route(call(tool.getName(), Map.of()), session());
            assertTrue(policy.name(), isToolError(response));
        }
        assertEquals(0, tool.calls);
    }

    @Test
    public void mcpRuntimeCapabilitiesRemainUsableRegardlessOfProfile() {
        CapturingTool read = new CapturingTool("mcp_tracker_search", false); //$NON-NLS-1$
        CapturingTool mutate = new CapturingTool("mcp_tracker_update", true); //$NON-NLS-1$
        CapturingTool unknown = new CapturingTool("mcp_tracker_unknown", false); //$NON-NLS-1$
        registry.registerDynamicTool(read, DynamicToolCapability.READ_ONLY);
        registry.registerDynamicTool(mutate, DynamicToolCapability.MUTATING);
        registry.registerDynamicTool(unknown);
        McpToolExposurePolicy exposure = new NamedExposurePolicy(Set.of(
                read.getName(), mutate.getName(), unknown.getName()));

        Set<String> gsdNames = listedNames(router(
                exposure, McpHostConfig.MutationPolicy.ALLOW, "gsd-discuss") //$NON-NLS-1$
                .route(request("tools/list", Map.of()), session())); //$NON-NLS-1$
        assertTrue(gsdNames.contains(read.getName()));
        assertTrue(gsdNames.contains(mutate.getName()));
        assertTrue(gsdNames.contains(unknown.getName()));

        Set<String> buildNames = listedNames(router(
                exposure, McpHostConfig.MutationPolicy.ALLOW, "build") //$NON-NLS-1$
                .route(request("tools/list", Map.of()), session())); //$NON-NLS-1$
        assertTrue(buildNames.contains(read.getName()));
        assertTrue(buildNames.contains(mutate.getName()));
        assertTrue(buildNames.contains(unknown.getName()));
    }

    @Test
    public void dynamicMutatingToolRunsUnderAllowRegardlessOfProfile() {
        CapturingTool tool = new CapturingTool("mcp_adversary_update", false); //$NON-NLS-1$
        registry.registerDynamicTool(tool, DynamicToolCapability.MUTATING);

        McpMessage response = router(
                new NamedExposurePolicy(Set.of(tool.getName())),
                McpHostConfig.MutationPolicy.ALLOW, "build") //$NON-NLS-1$
                .route(call(tool.getName(), Map.of("value", "changed")), session()); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(isToolError(response));
        assertEquals(1, tool.calls);
    }

    @Test
    public void trustedDynamicReadOnlyToolStillExecutesUnderAllow() {
        CapturingTool tool = new CapturingTool("mcp_tracker_search", false); //$NON-NLS-1$
        registry.registerDynamicTool(tool, DynamicToolCapability.READ_ONLY);

        McpMessage response = router(
                new NamedExposurePolicy(Set.of(tool.getName())),
                McpHostConfig.MutationPolicy.ALLOW, "build") //$NON-NLS-1$
                .route(call(tool.getName(), Map.of("query", "open")), session()); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(isToolError(response));
        assertEquals("ok", text(response)); //$NON-NLS-1$
        assertEquals(1, tool.calls);
    }

    @Test
    public void dynamicReplacementBetweenAuthorizationAndDispatchFailsClosed()
            throws Exception {
        String name = "mcp_race_replace"; //$NON-NLS-1$
        CapturingTool trusted = new CapturingTool(name, false);
        CapturingTool replacement = new CapturingTool(name, false);
        registry.registerDynamicTool(trusted, DynamicToolCapability.READ_ONLY);
        setField(registry, "executionService", new RegisteringExecutionService( //$NON-NLS-1$
                registry, replacement, DynamicToolCapability.MUTATING));

        McpMessage response = router(
                new NamedExposurePolicy(Set.of(name)),
                McpHostConfig.MutationPolicy.ALLOW, "build") //$NON-NLS-1$
                .route(call(name, Map.of()), session());

        assertTrue(isToolError(response));
        assertEquals("stale_tool_resolution", //$NON-NLS-1$
                structuredContent(response).get("reason_code").getAsString()); //$NON-NLS-1$
        assertEquals(0, trusted.calls);
        assertEquals(0, replacement.calls);
        assertSame(replacement, registry.getTool(name));
    }

    @Test
    public void unrelatedDynamicRefreshDoesNotInvalidateStableResolution()
            throws Exception {
        CapturingTool stable = new CapturingTool("mcp_stable_read", false); //$NON-NLS-1$
        CapturingTool unrelated = new CapturingTool("mcp_unrelated_update", false); //$NON-NLS-1$
        registry.registerDynamicTool(stable, DynamicToolCapability.READ_ONLY);
        setField(registry, "executionService", new RegisteringExecutionService( //$NON-NLS-1$
                registry, unrelated, DynamicToolCapability.MUTATING));

        McpMessage response = router(
                new NamedExposurePolicy(Set.of(stable.getName())),
                McpHostConfig.MutationPolicy.ALLOW, "build") //$NON-NLS-1$
                .route(call(stable.getName(), Map.of()), session());

        assertFalse(isToolError(response));
        assertEquals(1, stable.calls);
        assertEquals(0, unrelated.calls);
    }

    @Test
    public void confirmationSignalsDoNotVetoMcpAllow() {
        CapturingTool confirming = new CapturingTool(
                "mcp_local_confirming", false, true, false); //$NON-NLS-1$
        CapturingTool destructive = new CapturingTool(
                "mcp_local_destructive", false, false, true); //$NON-NLS-1$
        CapturingTool policyConfirmed = new CapturingTool(
                "mcp_policy_confirming", false); //$NON-NLS-1$
        registry.registerDynamicTool(confirming, DynamicToolCapability.READ_ONLY);
        registry.registerDynamicTool(destructive, DynamicToolCapability.READ_ONLY);
        registry.registerDynamicTool(policyConfirmed, DynamicToolCapability.READ_ONLY);

        McpMessage confirmingResponse = router(
                new NamedExposurePolicy(Set.of(confirming.getName())),
                McpHostConfig.MutationPolicy.ALLOW, "build") //$NON-NLS-1$
                .route(call(confirming.getName(), Map.of()), session());
        McpMessage destructiveResponse = router(
                new NamedExposurePolicy(Set.of(destructive.getName())),
                McpHostConfig.MutationPolicy.ALLOW, "build") //$NON-NLS-1$
                .route(call(destructive.getName(), Map.of()), session());
        McpHostRequestRouter policyRouter = router(
                new ConfirmingExposurePolicy(policyConfirmed.getName()),
                McpHostConfig.MutationPolicy.ALLOW, "build"); //$NON-NLS-1$
        Map<String, Object> policyMetadata = listedTool(policyRouter.route(
                request("tools/list", Map.of()), session()), policyConfirmed.getName()); //$NON-NLS-1$
        McpMessage policyResponse = policyRouter.route(
                call(policyConfirmed.getName(), Map.of()), session());

        for (McpMessage response : List.of(
                confirmingResponse, destructiveResponse, policyResponse)) {
            assertFalse(isToolError(response));
        }
        assertEquals(1, confirming.calls);
        assertEquals(1, destructive.calls);
        assertEquals(1, policyConfirmed.calls);
        assertFalse(policyMetadata.containsKey("_meta")); //$NON-NLS-1$
    }

    @Test
    public void toolListMetadataUsesEffectiveDynamicCapability() {
        CapturingTool read = new CapturingTool("mcp_metadata_read", false); //$NON-NLS-1$
        CapturingTool mutate = new CapturingTool("mcp_metadata_update", false); //$NON-NLS-1$
        registry.registerDynamicTool(read, DynamicToolCapability.READ_ONLY);
        registry.registerDynamicTool(mutate, DynamicToolCapability.MUTATING);
        McpToolExposurePolicy exposure = new NamedExposurePolicy(
                Set.of(read.getName(), mutate.getName()));

        McpMessage response = router(
                exposure, McpHostConfig.MutationPolicy.ALLOW, "build") //$NON-NLS-1$
                .route(request("tools/list", Map.of()), session()); //$NON-NLS-1$
        Map<String, Object> readMetadata = listedTool(response, read.getName());
        Map<String, Object> mutateMetadata = listedTool(response, mutate.getName());

        assertEquals(Boolean.TRUE, annotations(readMetadata).get("readOnlyHint")); //$NON-NLS-1$
        assertFalse(readMetadata.containsKey("_meta")); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, annotations(mutateMetadata).get("destructiveHint")); //$NON-NLS-1$
        assertFalse(mutateMetadata.containsKey("_meta")); //$NON-NLS-1$
    }

    @Test
    public void builtInCollisionKeepsBuiltInExecutionAndMetadata() throws Exception {
        String name = "mcp_collision"; //$NON-NLS-1$
        CapturingTool builtIn = register(new CapturingTool(name, false));
        CapturingTool dynamic = new CapturingTool(name, false);
        setField(registry, "executionService", new RegisteringExecutionService( //$NON-NLS-1$
                registry, dynamic, DynamicToolCapability.MUTATING));
        McpToolExposurePolicy exposure = new NamedExposurePolicy(Set.of(name));
        McpHostRequestRouter router = router(
                exposure, McpHostConfig.MutationPolicy.ALLOW, ""); //$NON-NLS-1$

        McpMessage response = router.route(call(name, Map.of()), session());
        Map<String, Object> listed = listedTool(router.route(
                request("tools/list", Map.of()), session()), name); //$NON-NLS-1$

        assertSame(builtIn, registry.getTool(name));
        assertFalse(listed.containsKey("annotations")); //$NON-NLS-1$
        assertFalse(listed.containsKey("_meta")); //$NON-NLS-1$
        assertFalse(isToolError(response));
        assertEquals(1, builtIn.calls);
        assertEquals(0, dynamic.calls);
    }

    @Test
    public void configuredAgentProfilesDoNotFilterMcpListOrCalls() {
        CapturingTool read = register(new CapturingTool("mcp_profile_read", false)); //$NON-NLS-1$
        CapturingTool mutate = register(new CapturingTool("mcp_profile_mutate", true, true, true)); //$NON-NLS-1$
        String profileId = registerProfile(Set.of(read.getName()),
                List.of(PermissionRule.deny(mutate.getName()).forAllResources()), true);
        for (String configuredId : List.of(profileId, "no-such-profile")) { //$NON-NLS-1$
            McpHostRequestRouter router = router(McpHostConfig.MutationPolicy.ALLOW, configuredId);
            Set<String> listed = listedNames(router.route(request("tools/list", Map.of()), session())); //$NON-NLS-1$
            assertTrue(configuredId, listed.contains(read.getName()));
            assertTrue(configuredId, listed.contains(mutate.getName()));
            assertFalse(configuredId, isToolError(router.route(call(mutate.getName(), Map.of()), session())));
        }
        assertEquals(2, mutate.calls);
    }

    @Test
    public void mcpMutationPolicyStillDeniesCallsWhenAgentProfileAllowsThem() {
        CapturingTool tool = register(new CapturingTool("mcp_profile_policy_deny", true)); //$NON-NLS-1$
        String profileId = registerProfile(Set.of(tool.getName()),
                List.of(PermissionRule.allow(tool.getName()).forAllResources()), false);
        for (McpHostConfig.MutationPolicy policy : List.of(
                McpHostConfig.MutationPolicy.DENY, McpHostConfig.MutationPolicy.ASK)) {
            McpMessage response = router(policy, profileId).route(call(tool.getName(), Map.of()), session());
            assertTrue(policy.name(), isToolError(response));
            assertEquals("Tool execution denied by permission policy: " + policy, text(response)); //$NON-NLS-1$
        }
        assertEquals(0, tool.calls);
    }

    @Test
    public void mcpExecutionCarriesScopedExecutionContext() {
        CapturingTool legacyTool = register(new CapturingTool("legacy_context", false)); //$NON-NLS-1$
        router(McpHostConfig.MutationPolicy.ALLOW, "") //$NON-NLS-1$
                .route(call(legacyTool.getName(), Map.of()), session());

        assertNotNull(legacyTool.context);
        assertTrue(legacyTool.context.isScoped());
        assertEquals("mcp-host", legacyTool.context.parentProfileId()); //$NON-NLS-1$
        assertEquals(AgentCapability.MUTATING, legacyTool.context.delegationCeiling());
        assertEquals(0, legacyTool.context.delegationDepth());

        CapturingTool profileTool = register(new CapturingTool("profile_context", false)); //$NON-NLS-1$
        String profileId = registerProfile(Set.of(profileTool.getName()), List.of(), true);
        router(McpHostConfig.MutationPolicy.ALLOW, profileId)
                .route(call(profileTool.getName(), Map.of()), session());

        assertEquals("mcp-host", profileTool.context.parentProfileId()); //$NON-NLS-1$
        assertEquals(AgentCapability.MUTATING, profileTool.context.delegationCeiling());
        assertEquals(0, profileTool.context.delegationDepth());
    }

    @Test
    public void gateAndExecutionShareOneParsedArgumentMap() {
        CapturingTool tool = register(new CapturingTool("same_map", false)); //$NON-NLS-1$
        LinkedHashMap<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("value", "same"); //$NON-NLS-1$ //$NON-NLS-2$
        String profileId = registerProfile(Set.of(tool.getName()),
                List.of(PermissionRule.allow(tool.getName()).forAllResources()), false);

        router(McpHostConfig.MutationPolicy.ALLOW, profileId)
                .route(call(tool.getName(), arguments), session());

        assertSame(arguments, tool.parameters);
    }

    @Test
    public void mcpToolTraceKeyVocabularyIsUnchanged() throws Exception {
        Set<String> vocabulary = Set.of(
                "method", "tool_name", "arguments", "permission_decision", "duration_ms", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "success", "result_type", "content", "error_message", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "exception_type", "exception_message"); //$NON-NLS-1$ //$NON-NLS-2$

        CapturingTool success = register(new CapturingTool("trace_success", false)); //$NON-NLS-1$
        CapturingTool failure = register(new CapturingTool("trace_failure", false)); //$NON-NLS-1$
        failure.result = ToolResult.failure("expected"); //$NON-NLS-1$
        CapturingTool throwing = register(new CapturingTool("trace_throwing", false)); //$NON-NLS-1$
        throwing.future = CompletableFuture.failedFuture(new IllegalStateException("boom")); //$NON-NLS-1$
        CapturingTool layerB = register(new CapturingTool("trace_layer_b", false)); //$NON-NLS-1$
        CapturingTool layerA = register(new CapturingTool("trace_layer_a", false)); //$NON-NLS-1$
        String profileId = registerProfile(Set.of(layerA.getName()),
                List.of(PermissionRule.deny(layerA.getName()).forAllResources()), false);

        List<TraceRun> runs = List.of(
                traceCall(router(McpHostConfig.MutationPolicy.ALLOW, ""), success), //$NON-NLS-1$
                traceCall(router(McpHostConfig.MutationPolicy.ALLOW, ""), failure), //$NON-NLS-1$
                traceCall(router(McpHostConfig.MutationPolicy.DENY, ""), layerB), //$NON-NLS-1$
                traceCall(router(McpHostConfig.MutationPolicy.ALLOW, profileId), layerA),
                traceCall(router(McpHostConfig.MutationPolicy.ALLOW, ""), throwing)); //$NON-NLS-1$

        for (TraceRun run : runs) {
            JsonObject data = toolTraceData(run.session(), run.toolName());
            assertTrue(run.toolName() + ": " + data.keySet(), //$NON-NLS-1$
                    vocabulary.containsAll(data.keySet()));
        }
    }

    @Test
    public void mcpToolCallWritesNoToolCallOrToolResultTraceEvents() throws Exception {
        CapturingTool tool = register(new CapturingTool("no_tool_events", false)); //$NON-NLS-1$
        McpHostSession session = tracedSession("no-tool-events"); //$NON-NLS-1$

        router(McpHostConfig.MutationPolicy.ALLOW, "") //$NON-NLS-1$
                .route(call(tool.getName(), Map.of()), session);

        Path toolsFile = session.getTraceSession().getLayout().getToolsFile();
        assertTrue(!Files.exists(toolsFile) || Files.readAllLines(toolsFile).isEmpty());
    }

    @Test
    public void legacyDenyEnvelopeIsByteIdentical() {
        CapturingTool tool = register(new CapturingTool("legacy_deny", true)); //$NON-NLS-1$

        McpMessage response = router(McpHostConfig.MutationPolicy.DENY, "") //$NON-NLS-1$
                .route(call(tool.getName(), Map.of()), session());

        assertEquals("Tool execution denied by permission policy: DENY", text(response)); //$NON-NLS-1$
    }

    @Test
    public void toolTimeoutStillProducesRouterExceptionEnvelope() throws Exception {
        CapturingTool tool = register(new CapturingTool("forced_timeout", false)); //$NON-NLS-1$
        setField(registry, "executionService", new TimeoutExecutionService(registry)); //$NON-NLS-1$
        McpHostSession session = tracedSession("forced-timeout"); //$NON-NLS-1$

        McpMessage response = router(McpHostConfig.MutationPolicy.ALLOW, "") //$NON-NLS-1$
                .route(call(tool.getName(), Map.of()), session);

        assertTrue(text(response).startsWith("Tool execution failed: ")); //$NON-NLS-1$
        JsonObject trace = toolTraceData(session, tool.getName());
        assertTrue(trace.has("exception_type")); //$NON-NLS-1$
        assertTrue(trace.has("exception_message")); //$NON-NLS-1$
    }

    @Test
    public void unresolvableDelegateTargetIsDeniedUnderScopedContext() {
        TaskTool task = new TaskTool(registry);
        registry.registerDynamicTool(task);

        McpMessage response = router(McpHostConfig.MutationPolicy.ALLOW, "") //$NON-NLS-1$
                .route(call(task.getName(), Map.of(
                        "prompt", "inspect", //$NON-NLS-1$ //$NON-NLS-2$
                        "profile", "missing-profile")), session()); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(text(response).contains("reason_code=delegation_target_unresolved")); //$NON-NLS-1$
        assertTrue(text(response).contains("parent=mcp-host")); //$NON-NLS-1$
    }

    @Test
    public void resolvableDelegateTargetIsUnaffectedByScopedContext() {
        TaskTool task = new TaskTool(registry);
        registry.registerDynamicTool(task);

        McpMessage response = router(McpHostConfig.MutationPolicy.ALLOW, "") //$NON-NLS-1$
                .route(call(task.getName(), Map.of(
                        "prompt", "inspect", //$NON-NLS-1$ //$NON-NLS-2$
                        "profile", "explore")), session()); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(text(response).contains("delegation_target_unresolved")); //$NON-NLS-1$
        assertFalse(text(response).contains("delegation_capability_exceeded")); //$NON-NLS-1$
    }

    private TraceRun traceCall(McpHostRequestRouter router, CapturingTool tool) {
        McpHostSession session = tracedSession(tool.getName());
        router.route(call(tool.getName(), Map.of()), session);
        return new TraceRun(session, tool.getName());
    }

    private JsonObject toolTraceData(McpHostSession session, String toolName) throws IOException {
        return readJsonLines(session.getTraceSession().getLayout().getMcpFile()).stream()
                .map(event -> event.getAsJsonObject("data")) //$NON-NLS-1$
                .filter(data -> data.has("tool_name")) //$NON-NLS-1$
                .filter(data -> toolName.equals(data.get("tool_name").getAsString())) //$NON-NLS-1$
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing tool trace: " + toolName)); //$NON-NLS-1$
    }

    private McpHostRequestRouter router(McpHostConfig.MutationPolicy policy, String profileId) {
        return router(new AllowAllExposurePolicy(), policy, profileId);
    }

    private McpHostRequestRouter router(
            McpToolExposurePolicy exposure, McpHostConfig.MutationPolicy policy,
            String profileId) {
        return new McpHostRequestRouter(exposure, List.of(), prompts(), policy, profileId);
    }

    private IMcpPromptProvider prompts() {
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

    private String registerProfile(
            Set<String> allowedTools, List<PermissionRule> rules, boolean readOnly) {
        return registerProfile(allowedTools, rules, readOnly, DynamicToolCapability.NONE);
    }

    private String registerProfile(
            Set<String> allowedTools, List<PermissionRule> rules, boolean readOnly,
            DynamicToolCapability dynamicGrant) {
        String id = "mcp-test-profile-" + registeredProfileIds.size(); //$NON-NLS-1$
        AgentProfileRegistry.getInstance().register(
                new StaticProfile(id, allowedTools, rules, readOnly, dynamicGrant));
        registeredProfileIds.add(id);
        return id;
    }

    private <T extends ITool> T register(T tool) {
        registry.register(tool);
        return tool;
    }

    private McpHostSession session() {
        McpHostSession session = new McpHostSession("mcp-profile-test"); //$NON-NLS-1$
        session.setClientName("test-client"); //$NON-NLS-1$
        return session;
    }

    private McpHostSession tracedSession(String id) {
        McpHostSession session = session();
        session.setTraceSession(AgentTraceSession.startMcpSession(
                id, "test", "local", "/mcp")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull(session.getTraceSession());
        return session;
    }

    private McpMessage call(String toolName, Map<String, Object> arguments) {
        return request("tools/call", Map.of("name", toolName, "arguments", arguments)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private McpMessage request(String method, Object params) {
        McpMessage request = new McpMessage();
        request.setMethod(method);
        request.setRawId(method + "-id"); //$NON-NLS-1$
        request.setParams(params);
        return request;
    }

    @SuppressWarnings("unchecked")
    private boolean isToolError(McpMessage response) {
        return Boolean.TRUE.equals(((Map<String, Object>) response.getResult()).get("isError")); //$NON-NLS-1$
    }

    @SuppressWarnings("unchecked")
    private String text(McpMessage response) {
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        List<McpContent> content = (List<McpContent>) result.get("content"); //$NON-NLS-1$
        return content.get(0).getText();
    }

    @SuppressWarnings("unchecked")
    private JsonObject structuredContent(McpMessage response) {
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        return (JsonObject) result.get("structuredContent"); //$NON-NLS-1$
    }

    @SuppressWarnings("unchecked")
    private Set<String> listedNames(McpMessage response) {
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools"); //$NON-NLS-1$
        Set<String> names = new HashSet<>();
        for (Map<String, Object> tool : tools) {
            names.add(String.valueOf(tool.get("name"))); //$NON-NLS-1$
        }
        return names;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> listedTool(McpMessage response, String name) {
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools"); //$NON-NLS-1$
        return tools.stream()
                .filter(tool -> name.equals(tool.get("name"))) //$NON-NLS-1$
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing listed tool: " + name)); //$NON-NLS-1$
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> annotations(Map<String, Object> tool) {
        return (Map<String, Object>) tool.get("annotations"); //$NON-NLS-1$
    }

    private List<JsonObject> readJsonLines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .map(line -> JsonParser.parseString(line).getAsJsonObject())
                .toList();
    }

    private void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static ToolRegistry isolatedRegistry() {
        return ToolRegistry.createDetached();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record TraceRun(McpHostSession session, String toolName) {
    }

    private static class CapturingTool implements ITool {

        private final String name;
        private final boolean mutating;
        private final boolean requiresConfirmation;
        private final boolean destructive;
        int calls;
        private Map<String, Object> parameters;
        private ToolExecutionContext context;
        private ToolResult result = ToolResult.success("ok"); //$NON-NLS-1$
        private CompletableFuture<ToolResult> future;

        private CapturingTool(String name, boolean mutating) {
            this(name, mutating, false, false);
        }

        private CapturingTool(
                String name, boolean mutating, boolean requiresConfirmation,
                boolean destructive) {
            this.name = name;
            this.mutating = mutating;
            this.requiresConfirmation = requiresConfirmation;
            this.destructive = destructive;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "MCP profile gate test tool"; //$NON-NLS-1$
        }

        @Override
        public String getParameterSchema() {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        @Override
        public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            return execute(parameters, ToolExecutionContext.unscoped());
        }

        @Override
        public CompletableFuture<ToolResult> execute(
                Map<String, Object> parameters, ToolExecutionContext context) {
            calls++;
            this.parameters = parameters;
            this.context = context;
            return future != null ? future : CompletableFuture.completedFuture(result);
        }

        @Override
        public boolean isMutating() {
            return mutating;
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

    @ToolMeta(name = "scoped_metadata_mutation", mutating = true,
            requiresValidationToken = true)
    private static final class ScopedValidationTokenTool extends CapturingTool {

        private ScopedValidationTokenTool(String name) {
            super(name, true, true, true);
        }
    }

    @ToolMeta(name = "token_checking_mutation", mutating = true,
            requiresValidationToken = true)
    private static final class TokenCheckingTool extends CapturingTool {

        private final ValidationTokenStore tokens = new ValidationTokenStore();

        private TokenCheckingTool() {
            super("token_checking_mutation", true, true, true); //$NON-NLS-1$
        }

        private String issueToken() {
            return tokens.issueToken(ValidationOperation.CREATE_METADATA,
                    "TestProject", Map.of("name", "TestObject")).token(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }

        @Override
        public CompletableFuture<ToolResult> execute(
                Map<String, Object> parameters, ToolExecutionContext context) {
            calls++;
            try {
                tokens.consumeToken((String) parameters.get("validation_token"), //$NON-NLS-1$
                        ValidationOperation.CREATE_METADATA, "TestProject", //$NON-NLS-1$
                        Map.of("name", "TestObject")); //$NON-NLS-1$ //$NON-NLS-2$
                return CompletableFuture.completedFuture(ToolResult.success("ok")); //$NON-NLS-1$
            } catch (MetadataOperationException e) {
                return CompletableFuture.completedFuture(
                        ToolResult.failure(e.getCode().name()));
            }
        }
    }

    private static final class StaticProfile implements AgentProfile {

        private final String id;
        private final Set<String> allowedTools;
        private final List<PermissionRule> rules;
        private final boolean readOnly;
        private final DynamicToolCapability dynamicGrant;

        private StaticProfile(
                String id, Set<String> allowedTools, List<PermissionRule> rules,
                boolean readOnly, DynamicToolCapability dynamicGrant) {
            this.id = id;
            this.allowedTools = allowedTools;
            this.rules = rules;
            this.readOnly = readOnly;
            this.dynamicGrant = dynamicGrant;
        }

        @Override
        public DynamicToolCapability getDynamicToolGrant() {
            return dynamicGrant;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public String getName() {
            return id;
        }

        @Override
        public String getDescription() {
            return "test profile"; //$NON-NLS-1$
        }

        @Override
        public Set<String> getAllowedTools() {
            return allowedTools;
        }

        @Override
        public List<PermissionRule> getDefaultPermissions() {
            return rules;
        }

        @Override
        public String getSystemPromptAddition() {
            return null;
        }

        @Override
        public int getMaxSteps() {
            return 1;
        }

        @Override
        public long getTimeoutMs() {
            return 1000;
        }

        @Override
        public boolean isReadOnly() {
            return readOnly;
        }

        @Override
        public boolean canExecuteShell() {
            return !readOnly;
        }

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

    private static final class ConfirmingExposurePolicy implements McpToolExposurePolicy {

        private final String exposedTool;

        private ConfirmingExposurePolicy(String exposedTool) {
            this.exposedTool = exposedTool;
        }

        @Override
        public boolean isExposed(String toolName) {
            return exposedTool.equals(toolName);
        }

        @Override
        public boolean requiresConfirmation(String toolName, Map<String, Object> args) {
            return exposedTool.equals(toolName);
        }

        @Override
        public boolean isDestructive(String toolName) {
            return false;
        }
    }

    private static final class TimeoutExecutionService extends ToolExecutionService {

        private TimeoutExecutionService(ToolRegistry registry) {
            super(registry);
        }

        @Override
        public Optional<CompletableFuture<ToolResult>> executeIfCurrent(
                ToolCall toolCall, Map<String, Object> parameters,
                AgentTraceSession traceSession, String parentEventId,
                ToolExecutionContext context, ToolResolution resolution) {
            return Optional.of(CompletableFuture.failedFuture(
                    new TimeoutException("forced timeout"))); //$NON-NLS-1$
        }
    }

    private static final class RegisteringExecutionService extends ToolExecutionService {

        private final ToolRegistry registry;
        private final ITool replacement;
        private final DynamicToolCapability capability;

        private RegisteringExecutionService(
                ToolRegistry registry, ITool replacement,
                DynamicToolCapability capability) {
            super(registry);
            this.registry = registry;
            this.replacement = replacement;
            this.capability = capability;
        }

        @Override
        public Optional<CompletableFuture<ToolResult>> executeIfCurrent(
                ToolCall toolCall, Map<String, Object> parameters,
                AgentTraceSession traceSession, String parentEventId,
                ToolExecutionContext context, ToolResolution resolution) {
            registry.registerDynamicTool(replacement, capability);
            return super.executeIfCurrent(toolCall, parameters, traceSession,
                    parentEventId, context, resolution);
        }
    }
}
