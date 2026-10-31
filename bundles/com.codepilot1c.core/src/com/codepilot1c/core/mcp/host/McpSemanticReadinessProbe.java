/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp.host;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.tools.ToolRegistry;

/**
 * Staged semantic readiness for the MCP host.
 *
 * <p>The host publishes {@code ready=true} only once every stage below holds, in order:</p>
 *
 * <ol>
 * <li>{@code workspace} - the Eclipse workspace is open;</li>
 * <li>{@code edtServices} - the named global 1C:EDT services are registered;</li>
 * <li>{@code metadataRead} - a real no-write 1C:EDT metadata read answers;</li>
 * <li>{@code import} - the {@code workspace_import_project} entry point exists.</li>
 * </ol>
 *
 * <p>Mutation is deliberately not a stage. 1C:EDT creates
 * {@code IBmPlatformGlobalEditingContext} inside {@code BmModelManager.link()}, a
 * {@code LifecyclePhase.LINKING} participant that only runs when
 * {@code IServicesOrchestrator.startServices} is invoked for a <em>project</em> context. On the
 * separate empty workspace WP-N0 requires, no project context is ever started, so gating the host
 * on the mutation runtime keeps it at HTTP 503 forever. Mutation-dependent state is therefore
 * reported per project through {@link ProjectReadiness} instead, and the host answers honestly
 * for the operations it can actually serve - starting with importing a project.</p>
 *
 * <p>Workspace presence alone is equally wrong in the other direction: it reports ready while no
 * 1C:EDT service is up at all.</p>
 */
public final class McpSemanticReadinessProbe {

    /** MCP entry point a WP-N0 client needs before anything else can happen. */
    static final String IMPORT_ENTRY_POINT = "workspace_import_project"; //$NON-NLS-1$

    private static final String DEGRADED_REASON = "EDT runtime services failed readiness probe"; //$NON-NLS-1$

    private final EdtMetadataGateway edtGateway;
    private final BooleanSupplier importEntryPointAvailable;
    private final Supplier<List<ProjectReadiness>> projectStates;

    /** Creates the production probe backed by the live 1C:EDT runtime and tool registry. */
    public McpSemanticReadinessProbe(EdtMetadataGateway edtGateway) {
        this(edtGateway,
                McpSemanticReadinessProbe::isImportEntryPointRegistered,
                new EdtWorkspaceProjectStates(edtGateway));
    }

    /**
     * @param edtGateway 1C:EDT runtime boundary, never {@code null}
     * @param importEntryPointAvailable whether the import entry point is registered
     * @param projectStates truthful per-project states; failures are treated as "unknown"
     */
    public McpSemanticReadinessProbe(
            EdtMetadataGateway edtGateway,
            BooleanSupplier importEntryPointAvailable,
            Supplier<List<ProjectReadiness>> projectStates) {
        this.edtGateway = edtGateway != null ? edtGateway : new EdtMetadataGateway();
        this.importEntryPointAvailable = importEntryPointAvailable != null
                ? importEntryPointAvailable
                : McpSemanticReadinessProbe::isImportEntryPointRegistered;
        this.projectStates = projectStates != null ? projectStates : List::of;
    }

    /**
     * Evaluates the stages in order and stops at the first one that does not hold.
     *
     * @return the readiness snapshot, never {@code null}
     */
    public McpReadiness evaluate() {
        ProjectSnapshot projectSnapshot = projects();
        List<ProjectReadiness> projects = projectSnapshot.projects();
        try {
            edtGateway.ensureWorkspaceRuntimeAvailable();
        } catch (MetadataOperationException e) {
            return McpReadiness.starting(stageReason("workspace", e), projects); //$NON-NLS-1$
        } catch (RuntimeException e) {
            return McpReadiness.notReady(DEGRADED_REASON, projects);
        }

        try {
            edtGateway.ensureEdtServicesAvailable();
        } catch (MetadataOperationException e) {
            return McpReadiness.starting(stageReason("edtServices", e), projects); //$NON-NLS-1$
        } catch (RuntimeException e) {
            return McpReadiness.notReady(DEGRADED_REASON, projects);
        }

        try {
            edtGateway.ensureMetadataReadCapability();
        } catch (MetadataOperationException e) {
            return McpReadiness.starting(stageReason("metadataRead", e), projects); //$NON-NLS-1$
        } catch (RuntimeException e) {
            return McpReadiness.notReady(DEGRADED_REASON, projects);
        }

        boolean importAvailable;
        try {
            importAvailable = importEntryPointAvailable.getAsBoolean();
        } catch (RuntimeException e) {
            return McpReadiness.notReady(DEGRADED_REASON, projects);
        }
        if (!importAvailable) {
            return McpReadiness.starting(
                    "import: MCP entry point " + IMPORT_ENTRY_POINT + " is not registered", //$NON-NLS-1$ //$NON-NLS-2$
                    projects);
        }

        if (!projectSnapshot.complete()) {
            return McpReadiness.notReady("projectStatus: EDT project readiness lookup failed", projects); //$NON-NLS-1$
        }
        if (projects.stream().anyMatch(project -> !"ready".equals(project.state()))) { //$NON-NLS-1$
            return McpReadiness.starting("projects: semantic project readiness is still pending", projects); //$NON-NLS-1$
        }

        return McpReadiness.available(projects);
    }

    private ProjectSnapshot projects() {
        try {
            List<ProjectReadiness> states = projectStates.get();
            return new ProjectSnapshot(states != null ? states : List.of(), true);
        } catch (RuntimeException e) {
            return new ProjectSnapshot(List.of(), false);
        }
    }

    private record ProjectSnapshot(List<ProjectReadiness> projects, boolean complete) { }

    private static String stageReason(String stage, MetadataOperationException failure) {
        String detail = failure.getMessage() != null && !failure.getMessage().isBlank()
                ? failure.getMessage()
                : failure.getCode().name();
        return stage + ": " + detail; //$NON-NLS-1$
    }

    private static boolean isImportEntryPointRegistered() {
        ToolRegistry.ToolResolution resolution = ToolRegistry.getInstance().resolveTool(IMPORT_ENTRY_POINT);
        return resolution != null && resolution.tool() != null;
    }
}
