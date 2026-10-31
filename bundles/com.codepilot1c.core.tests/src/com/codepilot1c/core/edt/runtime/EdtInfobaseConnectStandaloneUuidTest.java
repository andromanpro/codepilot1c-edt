/*******************************************************************************
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Copyright (C) 2026 codepilot1c-edt contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License v3.0 as published by the
 * Free Software Foundation.
 ******************************************************************************/
package com.codepilot1c.core.edt.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.wst.server.core.IRuntime;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.codepilot1c.core.edt.runtime.EdtInfobaseConnectService.ConnectRequest;
import com.codepilot1c.core.edt.runtime.EdtInfobaseConnectService.ConnectionKind;
import com.e1c.g5.v8.dt.platform.standaloneserver.wst.core.IStandaloneServerService;

/**
 * Regression test for the {@code connect_infobase(standalone)} fix
 * (commit "Fix connect_infobase(standalone): assign infobase UUID before EDT call").
 *
 * <p>Before the fix, {@link EdtInfobaseConnectService#connectStandalone} created an
 * {@link InfobaseReference} via {@code InfobaseReferences.newFileInfobaseReference(...)}
 * and handed it straight to EDT's {@code IStandaloneServerService.createServerWithInfobase(...)}
 * without ever assigning a UUID. EDT then constructed a
 * {@link com.e1c.g5.v8.dt.platform.standaloneserver.wst.core.StandaloneServerInfobase} from it,
 * whose constructor enforces {@code Preconditions.checkArgument(uuid != null)} — every
 * standalone connect failed with a bare, message-less {@code IllegalArgumentException} that
 * the outer handler mislabelled as {@code EDT_SERVICE_UNAVAILABLE}.</p>
 *
 * <p>The fix sets {@code reference.setUuid(UUID.randomUUID())} before the EDT call, symmetric to
 * what {@code persistReference()} does in the file branch. This test guards that behaviour by
 * intercepting the InfobaseReference at the EDT-call boundary and asserting its UUID is
 * non-null.</p>
 */
public class EdtInfobaseConnectStandaloneUuidTest {

    /**
     * Drives {@link EdtInfobaseConnectService#connectStandalone} end-to-end with stubbed
     * collaborators and asserts that the {@link InfobaseReference} reaching the EDT standalone
     * API carries a non-null UUID.
     */
    @Test
    public void connectStandaloneAssignsUuidBeforeEdtCall() throws Exception {
        // Create the temp dir inside user.home so EdtInfobaseConnectService.validateAndNormalizePath
        // accepts it (it rejects paths outside the Eclipse workspace or user home).
        Path home = Path.of(System.getProperty("user.home")); //$NON-NLS-1$
        Path databasePath = Files.createTempDirectory(home, "edt-standalone-uuid-regression"); //$NON-NLS-1$
        try {
            AtomicReference<InfobaseReference> captor = new AtomicReference<>();
            TestableConnectService service = new TestableConnectService(
                    new StubGateway(newCapturingStandaloneServerService(captor)));
            IProject project = newProjectProxy("Demo"); //$NON-NLS-1$

            ConnectRequest request = new ConnectRequest(
                    "Demo",                     // project_name //$NON-NLS-1$
                    databasePath.toString(),    // database_path (already an absolute temp dir under home)
                    ConnectionKind.STANDALONE,  // kind
                    null,                       // login (use OS auth so storeAccessSettings is harmless)
                    null,                       // password
                    false,                      // set_primary=false skips checkExistingPrimary
                    Integer.valueOf(1545),      // server_port
                    "");                       // runtime_version (forces findRuntime -> getRuntimes()) //$NON-NLS-1$

            try {
                service.invokeConnectStandalone(project, request);
                fail("expected EdtToolException because the capturing service aborts the EDT call"); //$NON-NLS-1$
            } catch (EdtToolException expected) {
                // The captor throws after recording the reference, so the service wraps it as
                // STANDALONE_SERVER_CREATE_FAILED (defense-in-depth catch in connectStandalone).
                assertEquals("captured exception must be wrapped as a typed EDT tool error", //$NON-NLS-1$
                        EdtToolErrorCode.STANDALONE_SERVER_CREATE_FAILED, expected.getCode());
            }

            InfobaseReference captured = captor.get();
            assertNotNull("EDT's createServerWithInfobase must have been invoked", captured); //$NON-NLS-1$
            assertNotNull("REGRESSION: standalone connect must assign a non-null UUID to the " //$NON-NLS-1$
                    + "InfobaseReference before handing it to EDT's StandaloneServerService — " //$NON-NLS-1$
                    + "otherwise EDT's StandaloneServerInfobase constructor fails its " //$NON-NLS-1$
                    + "Preconditions.checkArgument and the connect aborts with a bare " //$NON-NLS-1$
                    + "IllegalArgumentException.", //$NON-NLS-1$
                    captured.getUuid());
        } finally {
            deleteRecursively(databasePath.toFile());
        }
    }

    // ---- support stubs -----------------------------------------------------------------------

    /** Exposes the protected {@code connectStandalone} for direct invocation by the test. */
    private static final class TestableConnectService extends EdtInfobaseConnectService {
        TestableConnectService(EdtRuntimeGateway gateway) {
            super(gateway);
        }

        void invokeConnectStandalone(IProject project, ConnectRequest request) {
            connectStandalone(project, request);
        }
    }

    /** Gateway returning the captor as the EDT standalone-server service. */
    private static final class StubGateway extends EdtRuntimeGateway {
        private final IStandaloneServerService standaloneService;

        StubGateway(IStandaloneServerService standaloneService) {
            this.standaloneService = standaloneService;
        }

        @Override
        public IStandaloneServerService getStandaloneServerService() {
            return standaloneService;
        }

        @Override
        public IInfobaseAssociationManager getInfobaseAssociationManager() {
            // Not consulted because the test passes set_primary=false (checkExistingPrimary
            // short-circuits) and the captor throws before associate() runs.
            throw new UnsupportedOperationException("not used by this regression test"); //$NON-NLS-1$
        }

        @Override
        public IInfobaseAccessManager getInfobaseAccessManager() {
            throw new UnsupportedOperationException("not used by this regression test"); //$NON-NLS-1$
        }

        @Override
        public IRuntimeComponentManager getRuntimeComponentManager() {
            throw new UnsupportedOperationException("not used by this regression test"); //$NON-NLS-1$
        }
    }

    /**
     * Builds a dynamic {@link IStandaloneServerService} that captures the
     * {@link InfobaseReference} handed to {@code createServerWithInfobase} and then throws so the
     * production code never reaches the post-EDT bookkeeping (which would need more stubs).
     *
     * <p>A {@link Proxy} rather than a hand-written implementation: {@code IStandaloneServerService}
     * gains and renames methods between 1C:EDT releases, and this regression only depends on
     * {@code getRuntimes} and {@code createServerWithInfobase}.</p>
     *
     * @param captured sink for the intercepted reference, never {@code null}
     * @return the capturing service, never {@code null}
     */
    private static IStandaloneServerService newCapturingStandaloneServerService(
            AtomicReference<InfobaseReference> captured) {
        IRuntime runtime = newRuntimeProxy();
        return (IStandaloneServerService) Proxy.newProxyInstance(
                IStandaloneServerService.class.getClassLoader(),
                new Class<?>[] { IStandaloneServerService.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getRuntimes": //$NON-NLS-1$
                            // Must be non-empty so connectStandalone's findRuntime() does not bail
                            // with STANDALONE_RUNTIME_NOT_FOUND before the EDT call.
                            return List.of(runtime);
                        case "findRuntime": //$NON-NLS-1$
                            // Fall through to getRuntimes() so the test does not depend on
                            // version matching.
                            return Optional.empty();
                        case "createServerWithInfobase": //$NON-NLS-1$
                            captured.set((InfobaseReference) args[2]);
                            // Abort after capture - the test only cares about what reached EDT.
                            throw new RuntimeException("aborted-by-regression-captor"); //$NON-NLS-1$
                        case "getServers": //$NON-NLS-1$
                            return Collections.emptyList();
                        case "toString": //$NON-NLS-1$
                            return "CapturingStandaloneServerService"; //$NON-NLS-1$
                        default:
                            return defaultReturn(method);
                    }
                });
    }

    // ---- proxies -----------------------------------------------------------------------------

    private static IProject newProjectProxy(String projectName) {
        return (IProject) Proxy.newProxyInstance(
                IProject.class.getClassLoader(),
                new Class<?>[] { IProject.class },
                new ProjectHandler(projectName));
    }

    private static IRuntime newRuntimeProxy() {
        return (IRuntime) Proxy.newProxyInstance(
                IRuntime.class.getClassLoader(),
                new Class<?>[] { IRuntime.class },
                (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) { //$NON-NLS-1$
                        return "stub-runtime"; //$NON-NLS-1$
                    }
                    if ("toString".equals(method.getName())) { //$NON-NLS-1$
                        return "StubRuntime"; //$NON-NLS-1$
                    }
                    return defaultReturn(method);
                });
    }

    private static final class ProjectHandler implements InvocationHandler {
        private final String name;

        ProjectHandler(String name) {
            this.name = name;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getName" -> name; //$NON-NLS-1$
                case "exists" -> Boolean.TRUE; //$NON-NLS-1$
                case "isOpen" -> Boolean.TRUE; //$NON-NLS-1$
                case "equals" -> Boolean.valueOf(proxy == args[0]); //$NON-NLS-1$
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy)); //$NON-NLS-1$
                case "toString" -> "StubProject[" + name + "]"; //$NON-NLS-1$ //$NON-NLS-2$
                default -> defaultReturn(method);
            };
        }
    }

    private static Object defaultReturn(Method method) {
        Class<?> ret = method.getReturnType();
        if (ret == boolean.class) {
            return Boolean.FALSE;
        }
        if (ret.isPrimitive()) {
            return Integer.valueOf(0);
        }
        return null;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        assertTrue("failed to delete temp file " + file, file.delete() || !file.exists()); //$NON-NLS-1$
    }
}
