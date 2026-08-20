package com.codepilot1c.core.edt.ast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmSingleNamespaceTask;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Language;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

public class EdtMetadataIndexServiceTest {

    @Test
    public void scanCombinesKnownCollectionsWithPartialReflectiveResults() {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        Language language = MdClassFactory.eINSTANCE.createLanguage();
        language.setName("Русский"); //$NON-NLS-1$
        configuration.getLanguages().add(language);
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Items"); //$NON-NLS-1$
        configuration.getCatalogs().add(catalog);

        IProject project = project("Demo"); //$NON-NLS-1$
        TestGateway gateway = new TestGateway(project, configuration);
        EdtMetadataIndexService service = new EdtMetadataIndexService(
                gateway,
                new ReadyChecker(gateway));

        MetadataIndexResult result = service.scan(new MetadataIndexRequest(
                "Demo", "all", null, 100, "ru")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(2, result.getTotal());
        Set<String> names = result.getItems().stream()
                .map(MetadataIndexResult.Item::getName)
                .collect(Collectors.toSet());
        assertEquals(Set.of("Items", "Русский"), names); //$NON-NLS-1$ //$NON-NLS-2$
        Set<String> fqns = result.getItems().stream()
                .map(MetadataIndexResult.Item::getFqn)
                .collect(Collectors.toSet());
        assertEquals(Set.of("Catalog.Items", "Language.Русский"), fqns); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("The language present in both scans must be de-duplicated", //$NON-NLS-1$
                2,
                result.getItems().size());
    }

    @Test
    public void safeFqnDoesNotUseParentTopObjectFqnForNestedMetadata() throws Exception {
        IBmObject configuration = bmObject(true, null, "Configuration"); //$NON-NLS-1$
        IBmObject language = bmObject(false, configuration, null);
        EdtMetadataIndexService service = new EdtMetadataIndexService(null, null);

        Method safeFqn = EdtMetadataIndexService.class.getDeclaredMethod(
                "safeFqn", EObject.class, String.class, String.class); //$NON-NLS-1$
        safeFqn.setAccessible(true);
        String actual = (String) safeFqn.invoke(service, language, "Language", "Русский"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("Language.Русский", actual); //$NON-NLS-1$
    }

    @Test
    public void safeFqnUsesBmFqnForTopMetadataObject() throws Exception {
        IBmObject catalog = bmObject(true, null, "Catalog.Items"); //$NON-NLS-1$
        EdtMetadataIndexService service = new EdtMetadataIndexService(null, null);

        Method safeFqn = EdtMetadataIndexService.class.getDeclaredMethod(
                "safeFqn", EObject.class, String.class, String.class); //$NON-NLS-1$
        safeFqn.setAccessible(true);
        String actual = (String) safeFqn.invoke(service, catalog, "Catalog", "Items"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("Catalog.Items", actual); //$NON-NLS-1$
    }

    private static IProject project(String name) {
        return (IProject) Proxy.newProxyInstance(
                EdtMetadataIndexServiceTest.class.getClassLoader(),
                new Class<?>[]{IProject.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name; //$NON-NLS-1$
                    case "exists", "isOpen" -> Boolean.TRUE; //$NON-NLS-1$ //$NON-NLS-2$
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static IBmObject bmObject(boolean top, IBmObject topObject, String fqn) {
        return (IBmObject) Proxy.newProxyInstance(
                EdtMetadataIndexServiceTest.class.getClassLoader(),
                new Class<?>[]{IBmObject.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "bmIsTop" -> Boolean.valueOf(top); //$NON-NLS-1$
                    case "bmIsTransient" -> Boolean.FALSE; //$NON-NLS-1$
                    case "bmGetTopObject" -> topObject; //$NON-NLS-1$
                    case "bmGetFqn" -> fqn; //$NON-NLS-1$
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static IBmTransaction transaction() {
        return (IBmTransaction) Proxy.newProxyInstance(
                EdtMetadataIndexServiceTest.class.getClassLoader(),
                new Class<?>[]{IBmTransaction.class},
                (proxy, method, args) -> {
                    if ("toTransactionObject".equals(method.getName())) { //$NON-NLS-1$
                        return args[0];
                    }
                    return defaultValue(method.getReturnType());
                });
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

    private static final class TestGateway extends EdtServiceGateway {
        private final IProject project;
        private final IConfigurationProvider configurationProvider;
        private final IBmModelManager modelManager;

        TestGateway(IProject project, Configuration configuration) {
            this.project = project;
            this.configurationProvider = (IConfigurationProvider) Proxy.newProxyInstance(
                    EdtMetadataIndexServiceTest.class.getClassLoader(),
                    new Class<?>[]{IConfigurationProvider.class},
                    (proxy, method, args) -> "getConfiguration".equals(method.getName()) //$NON-NLS-1$
                            ? configuration
                            : defaultValue(method.getReturnType()));
            this.modelManager = (IBmModelManager) Proxy.newProxyInstance(
                    EdtMetadataIndexServiceTest.class.getClassLoader(),
                    new Class<?>[]{IBmModelManager.class},
                    (proxy, method, args) -> {
                        if ("executeReadOnlyTask".equals(method.getName()) //$NON-NLS-1$
                                && args != null
                                && args.length == 2
                                && args[1] instanceof IBmSingleNamespaceTask<?> task) {
                            return task.execute(transaction());
                        }
                        return defaultValue(method.getReturnType());
                    });
        }

        @Override
        public IProject resolveProject(String projectName) {
            return project;
        }

        @Override
        public IConfigurationProvider getConfigurationProvider() {
            return configurationProvider;
        }

        @Override
        public IBmModelManager getBmModelManager() {
            return modelManager;
        }
    }

    private static final class ReadyChecker extends ProjectReadinessChecker {

        ReadyChecker(EdtServiceGateway gateway) {
            super(gateway);
        }

        @Override
        public void ensureReady(IProject project) {
            assertTrue(project.exists() && project.isOpen());
        }
    }
}
