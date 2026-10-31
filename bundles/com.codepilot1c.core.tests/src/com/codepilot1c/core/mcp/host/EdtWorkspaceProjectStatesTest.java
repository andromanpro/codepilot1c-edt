/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp.host;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;

public class EdtWorkspaceProjectStatesTest {

    @Test
    public void activeProjectRemainsBuildingUntilTheSemanticDerivedDataPredicateIsReady() {
        AtomicBoolean derivedDataReady = new AtomicBoolean(false);
        IProject project = proxy(IProject.class, (object, method, args) -> switch (method.getName()) {
            case "isOpen" -> Boolean.TRUE; //$NON-NLS-1$
            case "getName" -> "DemoConfDT"; //$NON-NLS-1$ //$NON-NLS-2$
            default -> defaultValue(method.getReturnType());
        });
        IDtProject dtProject = proxy(IDtProject.class,
                (object, method, args) -> defaultValue(method.getReturnType()));
        IDtProjectManager dtProjects = proxy(IDtProjectManager.class, (object, method, args) -> switch (method.getName()) {
            case "getDtProject" -> dtProject; //$NON-NLS-1$
            case "isProjectActive" -> Boolean.TRUE; //$NON-NLS-1$
            default -> defaultValue(method.getReturnType());
        });
        IV8ProjectManager v8Projects = proxy(IV8ProjectManager.class, (object, method, args) ->
            "isServiceContextActive".equals(method.getName()) //$NON-NLS-1$
                    ? Boolean.TRUE : defaultValue(method.getReturnType()));
        IDerivedDataManager derivedData = proxy(IDerivedDataManager.class, (object, method, args) ->
            ("isIdle".equals(method.getName()) || "isAllComputed".equals(method.getName())) //$NON-NLS-1$ //$NON-NLS-2$
                    ? Boolean.valueOf(derivedDataReady.get()) : defaultValue(method.getReturnType()));
        IDerivedDataManagerProvider derivedDataProvider = proxy(IDerivedDataManagerProvider.class,
                (object, method, args) -> "get".equals(method.getName()) //$NON-NLS-1$
                        ? derivedData : defaultValue(method.getReturnType()));
        EdtMetadataGateway gateway = new EdtMetadataGateway() {
            @Override
            public IDtProjectManager getDtProjectManager() {
                return dtProjects;
            }

            @Override
            public IV8ProjectManager getV8ProjectManager() {
                return v8Projects;
            }

            @Override
            public IDerivedDataManagerProvider getDerivedDataManagerProvider() {
                return derivedDataProvider;
            }
        };
        EdtWorkspaceProjectStates states = new EdtWorkspaceProjectStates(gateway, () -> new IProject[] {project});

        assertEquals(List.of(new ProjectReadiness("DemoConfDT", "building")), states.get()); //$NON-NLS-1$ //$NON-NLS-2$

        derivedDataReady.set(true);

        assertEquals(List.of(new ProjectReadiness("DemoConfDT", "ready")), states.get()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (returnType == Integer.TYPE) {
            return Integer.valueOf(0);
        }
        if (returnType == Long.TYPE) {
            return Long.valueOf(0L);
        }
        return null;
    }
}
