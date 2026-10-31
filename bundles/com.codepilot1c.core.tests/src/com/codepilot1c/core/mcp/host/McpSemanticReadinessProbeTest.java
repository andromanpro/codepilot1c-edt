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

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * Contract for the semantic readiness published by {@code GET /health/ready} and by the MCP
 * {@code initialize} metadata.
 *
 * <p>WP-N0 requires a host on a separate, empty workspace to advertise readiness only when the
 * operations a client is about to issue can actually be served. Two failure modes are pinned
 * here because both were shipped before:</p>
 *
 * <ul>
 * <li>readiness derived from the mutation predicate
 *     ({@code IBmModelManager.getGlobalEditingContext()}), which 1C:EDT only creates during the
 *     per-project {@code LifecyclePhase.LINKING} run and therefore never on an empty workspace -
 *     the host stayed 503 forever; and</li>
 * <li>readiness derived from workspace presence alone
 *     ({@code ResourcesPlugin.getWorkspace() != null}), which reports ready while no 1C:EDT
 *     service is up at all.</li>
 * </ul>
 *
 * <p>The contract in between is staged: workspace, then the named global 1C:EDT services, then a
 * real no-write metadata read, then the import entry point. Mutation is reported per project
 * instead of gating the host.</p>
 */
public class McpSemanticReadinessProbeTest {

    private static final String MISSING_BM_MODEL_MANAGER = "IBmModelManager is unavailable in EDT runtime"; //$NON-NLS-1$

    @Test
    public void workspacePresenceAloneIsNeverSemanticReadiness() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.edtServicesFailure = MISSING_BM_MODEL_MANAGER;

        McpReadiness readiness = new McpSemanticReadinessProbe(gateway, () -> true, List::of).evaluate();

        assertFalse("workspace presence must never publish semantic readiness", readiness.ready()); //$NON-NLS-1$
        assertEquals("starting", readiness.services()); //$NON-NLS-1$
        assertTrue("the reason must name the stage: " + readiness.reason(), //$NON-NLS-1$
                readiness.reason().contains("edtServices")); //$NON-NLS-1$
        assertTrue("the reason must name the missing service: " + readiness.reason(), //$NON-NLS-1$
                readiness.reason().contains("IBmModelManager")); //$NON-NLS-1$
    }

    @Test
    public void missingWorkspaceIsReportedBeforeAnyEdtServiceProbe() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.workspaceFailure = "ResourcesPlugin workspace is unavailable in EDT runtime"; //$NON-NLS-1$

        McpReadiness readiness = new McpSemanticReadinessProbe(gateway, () -> true, List::of).evaluate();

        assertFalse(readiness.ready());
        assertTrue(readiness.reason().contains("workspace")); //$NON-NLS-1$
        assertEquals("the workspace stage must short-circuit the probe", //$NON-NLS-1$
                List.of("workspace"), gateway.stages); //$NON-NLS-1$
    }

    @Test
    public void namedEdtServicesWithoutASafeMetadataReadAreNotReady() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.metadataReadFailure = "EDT project enumeration failed"; //$NON-NLS-1$

        McpReadiness readiness = new McpSemanticReadinessProbe(gateway, () -> true, List::of).evaluate();

        assertFalse("named services must not stand in for a real no-write capability", //$NON-NLS-1$
                readiness.ready());
        assertTrue(readiness.reason().contains("metadataRead")); //$NON-NLS-1$
        assertEquals(List.of("workspace", "edtServices", "metadataRead"), gateway.stages); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void readinessRequiresTheImportEntryPoint() {
        RecordingGateway gateway = new RecordingGateway();

        McpReadiness readiness = new McpSemanticReadinessProbe(gateway, () -> false, List::of).evaluate();

        assertFalse("an EDT runtime without the import entry point cannot serve WP-N0", //$NON-NLS-1$
                readiness.ready());
        assertTrue(readiness.reason().contains("import")); //$NON-NLS-1$
        assertTrue("the reason must name the entry point: " + readiness.reason(), //$NON-NLS-1$
                readiness.reason().contains("workspace_import_project")); //$NON-NLS-1$
    }

    @Test
    public void readinessIsPublishedOnlyWhenEveryStagePasses() {
        RecordingGateway gateway = new RecordingGateway();

        McpReadiness readiness = new McpSemanticReadinessProbe(gateway, () -> true, List::of).evaluate();

        assertTrue(readiness.ready());
        assertEquals("ready", readiness.services()); //$NON-NLS-1$
        assertEquals("", readiness.reason()); //$NON-NLS-1$
        assertEquals(List.of("workspace", "edtServices", "metadataRead"), gateway.stages); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void anEmptyWorkspaceWithoutMutationRuntimeStillReachesHostReadiness() {
        RecordingGateway gateway = new RecordingGateway();
        // Reproduces the live 1C:EDT 2026.2.0.289 headless state: every named global service is
        // registered, but IBmModelManager.getGlobalEditingContext() is still null because no
        // project context has run LifecyclePhase.LINKING yet.
        gateway.mutationFailure = "IBmPlatformGlobalEditingContext is unavailable in EDT runtime"; //$NON-NLS-1$

        McpReadiness readiness = new McpSemanticReadinessProbe(gateway, () -> true, List::of).evaluate();

        assertTrue("host readiness must not depend on the project-bound mutation runtime", //$NON-NLS-1$
                readiness.ready());
        assertFalse("the probe must not consult the mutation runtime at all", //$NON-NLS-1$
                gateway.stages.contains("mutation")); //$NON-NLS-1$
    }

    @Test
    public void mixedProjectStatesRemainVisibleWithoutPublishingAFalseOverallReady() {
        RecordingGateway gateway = new RecordingGateway();
        List<ProjectReadiness> projects = List.of(
                new ProjectReadiness("Imported", "imported"), //$NON-NLS-1$ //$NON-NLS-2$
                new ProjectReadiness("Building", "building"), //$NON-NLS-1$ //$NON-NLS-2$
                new ProjectReadiness("Ready", "ready")); //$NON-NLS-1$ //$NON-NLS-2$

        McpReadiness readiness = new McpSemanticReadinessProbe(gateway, () -> true, () -> projects).evaluate();

        assertFalse("imported or building projects cannot publish overall semantic readiness", readiness.ready()); //$NON-NLS-1$
        assertEquals("starting", readiness.services()); //$NON-NLS-1$
        assertEquals(projects, readiness.projects());
    }

    @Test
    public void aProjectStillBuildingDerivedDataCannotPublishReadyToHealthOrMcp() {
        RecordingGateway gateway = new RecordingGateway();
        List<ProjectReadiness> projects = List.of(new ProjectReadiness("DemoConfDT", "building")); //$NON-NLS-1$ //$NON-NLS-2$

        McpReadiness readiness = new McpSemanticReadinessProbe(gateway, () -> true, () -> projects).evaluate();

        assertFalse("a semantic tool would return PROJECT_NOT_READY while derived data builds", readiness.ready()); //$NON-NLS-1$
        assertEquals("starting", readiness.services()); //$NON-NLS-1$
        assertEquals("not_ready", readiness.asHealthResponse().get("status")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Boolean.FALSE, readiness.asMetadata().get("ready")); //$NON-NLS-1$
        assertEquals(projects, readiness.projects());
    }

    @Test
    public void projectStatesAreStillPublishedWhileTheHostIsNotReady() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.edtServicesFailure = MISSING_BM_MODEL_MANAGER;
        List<ProjectReadiness> projects = List.of(new ProjectReadiness("Imported", "imported")); //$NON-NLS-1$ //$NON-NLS-2$

        McpReadiness readiness = new McpSemanticReadinessProbe(gateway, () -> true, () -> projects).evaluate();

        assertFalse(readiness.ready());
        assertEquals("a client must see staged project state while the host is still starting", //$NON-NLS-1$
                projects, readiness.projects());
    }

    @Test
    public void anUnexpectedRuntimeFailureIsDegradedRatherThanStarting() {
        RecordingGateway gateway = new RecordingGateway() {
            @Override
            public void ensureEdtServicesAvailable() {
                throw new IllegalStateException("boom"); //$NON-NLS-1$
            }
        };

        McpReadiness readiness = new McpSemanticReadinessProbe(gateway, () -> true, List::of).evaluate();

        assertFalse(readiness.ready());
        assertEquals("degraded", readiness.services()); //$NON-NLS-1$
    }

    @Test
    public void aFailingProjectStateSupplierFailsClosedRatherThanPublishingReady() {
        RecordingGateway gateway = new RecordingGateway();

        McpReadiness readiness = new McpSemanticReadinessProbe(gateway, () -> true, () -> {
            throw new IllegalStateException("project enumeration exploded"); //$NON-NLS-1$
        }).evaluate();

        assertFalse(readiness.ready());
        assertEquals("degraded", readiness.services()); //$NON-NLS-1$
        assertEquals(List.of(), readiness.projects());
    }

    @Test
    public void healthResponseCarriesTheStageAndTheProjectStates() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.edtServicesFailure = MISSING_BM_MODEL_MANAGER;
        List<ProjectReadiness> projects = List.of(new ProjectReadiness("Imported", "imported")); //$NON-NLS-1$ //$NON-NLS-2$

        McpReadiness readiness = new McpSemanticReadinessProbe(gateway, () -> true, () -> projects).evaluate();

        var payload = readiness.asHealthResponse();
        assertEquals("not_ready", payload.get("status")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("starting", payload.get("services")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("a CLI must be able to read staged project state from /health/ready", //$NON-NLS-1$
                List.of(java.util.Map.of("name", "Imported", "state", "imported")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                payload.get("projects")); //$NON-NLS-1$
    }

    /** Gateway test double recording which readiness stages the probe actually consults. */
    private static class RecordingGateway extends EdtMetadataGateway {
        final List<String> stages = new ArrayList<>();
        String workspaceFailure;
        String edtServicesFailure;
        String metadataReadFailure;
        String mutationFailure;

        @Override
        public void ensureWorkspaceRuntimeAvailable() {
            stages.add("workspace"); //$NON-NLS-1$
            failIfConfigured(workspaceFailure);
        }

        @Override
        public void ensureEdtServicesAvailable() {
            stages.add("edtServices"); //$NON-NLS-1$
            failIfConfigured(edtServicesFailure);
        }

        @Override
        public void ensureMetadataReadCapability() {
            stages.add("metadataRead"); //$NON-NLS-1$
            failIfConfigured(metadataReadFailure);
        }

        @Override
        public void ensureMutationRuntimeAvailable() {
            stages.add("mutation"); //$NON-NLS-1$
            failIfConfigured(mutationFailure);
        }

        private static void failIfConfigured(String message) {
            if (message != null) {
                throw new MetadataOperationException(
                        MetadataOperationCode.EDT_SERVICE_UNAVAILABLE, message, false);
            }
        }
    }
}
