/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp.host;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;
import com.codepilot1c.core.edt.metadata.MetadataProjectReadinessChecker;
import com._1c.g5.v8.derived.IDerivedDataManager;

/**
 * Truthful per-project readiness derived from the live 1C:EDT runtime.
 *
 * <p>The host reaches {@code ready=true} before any project is operational, so a client needs a
 * second axis to know whether a given project can already serve semantic work. The three states
 * are read from 1C:EDT itself rather than guessed:</p>
 *
 * <ul>
 * <li>{@code imported} - the project is open in the workspace but 1C:EDT has not adopted it as a
 *     {@link IDtProject} yet;</li>
 * <li>{@code building} - 1C:EDT adopted it, but its service context is not active, so its
 *     lifecycle (linking, storage initialization, or derived-data computation) is still
 *     running;</li>
 * <li>{@code ready} - the project is active, its service context is up, and the canonical
 *     derived-data predicate used by semantic metadata tools holds.</li>
 * </ul>
 */
public final class EdtWorkspaceProjectStates implements Supplier<List<ProjectReadiness>> {

    private final EdtMetadataGateway edtGateway;
    private final Supplier<IProject[]> workspaceProjects;

    public EdtWorkspaceProjectStates(EdtMetadataGateway edtGateway) {
        this(edtGateway, EdtWorkspaceProjectStates::workspaceProjects);
    }

    EdtWorkspaceProjectStates(EdtMetadataGateway edtGateway, Supplier<IProject[]> workspaceProjects) {
        this.edtGateway = edtGateway != null ? edtGateway : new EdtMetadataGateway();
        this.workspaceProjects = workspaceProjects != null ? workspaceProjects : () -> new IProject[0];
    }

    @Override
    public List<ProjectReadiness> get() {
        IProject[] projects = workspaceProjects.get();
        if (projects == null || projects.length == 0) {
            return List.of();
        }
        IDtProjectManager dtProjectManager = edtGateway.getDtProjectManager();
        IV8ProjectManager v8ProjectManager = edtGateway.getV8ProjectManager();
        IDerivedDataManagerProvider derivedDataManagerProvider = edtGateway.getDerivedDataManagerProvider();
        List<ProjectReadiness> states = new ArrayList<>(projects.length);
        for (IProject project : projects) {
            if (project == null || !project.isOpen()) {
                continue;
            }
            states.add(new ProjectReadiness(project.getName(), state(dtProjectManager, v8ProjectManager,
                    derivedDataManagerProvider, project)));
        }
        return List.copyOf(states);
    }

    private static IProject[] workspaceProjects() {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        return workspace == null || workspace.getRoot() == null ? new IProject[0] : workspace.getRoot().getProjects();
    }

    private static String state(IDtProjectManager dtProjectManager, IV8ProjectManager v8ProjectManager,
            IDerivedDataManagerProvider derivedDataManagerProvider, IProject project) {
        IDtProject dtProject = dtProjectManager.getDtProject(project);
        if (dtProject == null) {
            return "imported"; //$NON-NLS-1$
        }
        if (dtProjectManager.isProjectActive(dtProject) && v8ProjectManager.isServiceContextActive(project)) {
            IDerivedDataManager derivedDataManager = derivedDataManagerProvider.get(dtProject);
            if (derivedDataManager != null
                    && MetadataProjectReadinessChecker.isDerivedDataReady(derivedDataManager)) {
                return "ready"; //$NON-NLS-1$
            }
            return "building"; //$NON-NLS-1$
        }
        return "building"; //$NON-NLS-1$
    }
}
