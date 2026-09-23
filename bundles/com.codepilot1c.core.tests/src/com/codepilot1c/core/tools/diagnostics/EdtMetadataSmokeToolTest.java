package com.codepilot1c.core.tools.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IExternalObjectProject;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com.codepilot1c.core.edt.metadata.AddMetadataChildRequest;
import com.codepilot1c.core.edt.metadata.CreateMetadataRequest;
import com.codepilot1c.core.edt.metadata.DeleteMetadataRequest;
import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;
import com.codepilot1c.core.edt.metadata.EdtMetadataService;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.metadata.MetadataOperationResult;
import com.codepilot1c.core.tools.ToolResult;

public class EdtMetadataSmokeToolTest {

    @Test
    public void externalProjectSkipsMetadataMutations() {
        IProject project = proxy(IProject.class, (object, method, args) -> switch (method.getName()) {
            case "exists", "isOpen" -> Boolean.TRUE; //$NON-NLS-1$ //$NON-NLS-2$
            case "getName" -> "TDT1C"; //$NON-NLS-1$ //$NON-NLS-2$
            default -> defaultValue(method.getReturnType());
        });
        IExternalObjectProject externalProject = proxy(IExternalObjectProject.class,
                (object, method, args) -> defaultValue(method.getReturnType()));
        IV8ProjectManager v8Projects = proxy(IV8ProjectManager.class, (object, method, args) ->
                "getProject".equals(method.getName()) ? externalProject : defaultValue(method.getReturnType())); //$NON-NLS-1$
        IDtProject dtProject = proxy(IDtProject.class,
                (object, method, args) -> defaultValue(method.getReturnType()));
        IDtProjectManager dtProjects = proxy(IDtProjectManager.class, (object, method, args) ->
                "getDtProject".equals(method.getName()) ? dtProject : defaultValue(method.getReturnType())); //$NON-NLS-1$
        IDerivedDataManager derivedData = proxy(IDerivedDataManager.class, (object, method, args) ->
                ("isIdle".equals(method.getName()) || "isAllComputed".equals(method.getName())) //$NON-NLS-1$ //$NON-NLS-2$
                        ? Boolean.TRUE : defaultValue(method.getReturnType()));
        IDerivedDataManagerProvider derivedDataProvider = proxy(IDerivedDataManagerProvider.class,
                (object, method, args) -> "get".equals(method.getName()) //$NON-NLS-1$
                        ? derivedData : defaultValue(method.getReturnType()));
        IBmModelManager bmModel = proxy(IBmModelManager.class, (object, method, args) ->
                "executeReadOnlyTask".equals(method.getName()) //$NON-NLS-1$
                        ? Boolean.TRUE : defaultValue(method.getReturnType()));
        EdtMetadataGateway gateway = new EdtMetadataGateway() {
            @Override
            public IProject resolveProject(String projectName) {
                return project;
            }

            @Override
            public IV8ProjectManager getV8ProjectManager() {
                return v8Projects;
            }

            @Override
            public IDtProjectManager getDtProjectManager() {
                return dtProjects;
            }

            @Override
            public IDerivedDataManagerProvider getDerivedDataManagerProvider() {
                return derivedDataProvider;
            }

            @Override
            public IBmModelManager getBmModelManager() {
                return bmModel;
            }
        };
        AtomicInteger mutationCalls = new AtomicInteger();
        EdtMetadataService metadataService = new EdtMetadataService() {
            @Override
            public boolean isEdtAvailable() {
                return true;
            }

            @Override
            public MetadataOperationResult createMetadata(CreateMetadataRequest request) {
                mutationCalls.incrementAndGet();
                throw new MetadataOperationException(MetadataOperationCode.EDT_TRANSACTION_FAILED,
                        "namespace mismatch", false); //$NON-NLS-1$
            }

            @Override
            public MetadataOperationResult addMetadataChild(AddMetadataChildRequest request) {
                mutationCalls.incrementAndGet();
                throw new AssertionError("addMetadataChild must not run"); //$NON-NLS-1$
            }

            @Override
            public MetadataOperationResult deleteMetadata(DeleteMetadataRequest request) {
                mutationCalls.incrementAndGet();
                throw new AssertionError("deleteMetadata must not run"); //$NON-NLS-1$
            }
        };

        EdtMetadataSmokeTool tool = new EdtMetadataSmokeTool(gateway, metadataService);
        ToolResult result = tool.execute(Map.of("project", "TDT1C")).join(); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("[OK] readiness_precheck")); //$NON-NLS-1$
        assertTrue(result.getContent().contains("[OK] bm_read_tx_probe")); //$NON-NLS-1$
        assertTrue(result.getContent().contains("code=SKIPPED_EXTERNAL_PROJECT")); //$NON-NLS-1$
        assertTrue(result.getContent().contains("TDT1C")); //$NON-NLS-1$
        assertEquals(0, mutationCalls.get());

        ToolResult dryRun = tool.execute(Map.of("project", "TDT1C", "dry_run", true)).join(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(dryRun.isSuccess());
        assertTrue(dryRun.getContent().contains("[OK] readiness_precheck")); //$NON-NLS-1$
        assertTrue(dryRun.getContent().contains("[OK] bm_read_tx_probe")); //$NON-NLS-1$
        assertTrue(dryRun.getContent().contains("dry_run=true")); //$NON-NLS-1$
        assertEquals(0, mutationCalls.get());
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
