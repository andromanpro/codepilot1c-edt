package com.codepilot1c.core.edt.rights;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.InternalEObject;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmNamespace;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmPlatformTransaction;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Role;
import com._1c.g5.v8.dt.rights.model.RightsFactory;
import com._1c.g5.v8.dt.rights.model.RoleDescription;
import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;

/**
 * Role whose {@code Rights.rights} is missing on disk.
 *
 * <p>Live probe 24.09.2026 on build 1.3.9.20260924-1711: {@code Rights.rights} of a role was deleted while EDT was
 * closed (what a git checkout does), EDT was started. {@code Role.getRights()} then returns not {@code null} but an
 * unresolved proxy to the expected external object. The proxy passes {@code instanceof RoleDescription}, so
 * {@code inspect_role_rights} reported {@code hasRightsModel:true} with nothing in it, and {@code mutate_role_rights}
 * edited the proxy: success in the response, no file on disk, nothing in the model.</p>
 *
 * <p>The test calls the real private {@code getOrCreateRoleDescription} and {@code readSnapshot} by reflection on
 * plain EMF objects; the BM transaction, model manager and FQN generator are dynamic proxies. It references no class
 * of the fix, so the same binary runs against the installed build and against the fixed one.</p>
 */
public class EdtRoleRightsServiceProxyRightsTest {

    private static final String RIGHTS_FQN = "Role.Зонд.Rights"; //$NON-NLS-1$

    private Role role;
    private RoleDescription rightsProxy;
    private final List<IBmObject> attached = new ArrayList<>();
    private IProject project;
    private EdtRoleRightsService service;

    @Before
    public void setUp() {
        role = MdClassFactory.eINSTANCE.createRole();
        role.setName("Зонд"); //$NON-NLS-1$
        rightsProxy = RightsFactory.eINSTANCE.createRoleDescription();
        ((InternalEObject) rightsProxy).eSetProxyURI(URI.createURI("bm://CpScratchForms/" + RIGHTS_FQN + "#/")); //$NON-NLS-1$ //$NON-NLS-2$
        project = (IProject) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { IProject.class },
                (proxy, method, args) -> "getName".equals(method.getName()) ? "CpScratchForms" : null); //$NON-NLS-1$ //$NON-NLS-2$
        IBmNamespace namespace = (IBmNamespace) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { IBmNamespace.class }, (proxy, method, args) -> null);
        IBmModelManager modelManager = (IBmModelManager) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { IBmModelManager.class }, (proxy, method, args) -> {
                    if ("getBmNamespace".equals(method.getName())) { //$NON-NLS-1$
                        return namespace;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        ITopObjectFqnGenerator fqnGenerator = (ITopObjectFqnGenerator) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] { ITopObjectFqnGenerator.class },
                (proxy, method, args) -> {
                    if ("generateExternalPropertyFqn".equals(method.getName())) { //$NON-NLS-1$
                        return RIGHTS_FQN;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        EdtMetadataGateway gateway = new EdtMetadataGateway() {
            @Override
            public IBmModelManager getBmModelManager() {
                return modelManager;
            }

            @Override
            public ITopObjectFqnGenerator getTopObjectFqnGenerator() {
                return fqnGenerator;
            }
        };
        service = new EdtRoleRightsService(gateway);
    }

    @Test
    public void unresolvedRightsProxyIsReplacedByAttachedDescription() throws Exception {
        setRights(rightsProxy);

        RoleDescription result = getOrCreate();

        assertNotSame("вернулся неразрешённый прокси — правка уйдёт в никуда", rightsProxy, result); //$NON-NLS-1$
        assertFalse("возвращённая модель прав — прокси", result.eIsProxy()); //$NON-NLS-1$
        assertEquals("новая модель прав не подключена верхним объектом BM", 1, attached.size()); //$NON-NLS-1$
        assertSame(attached.get(0), result);
        assertSame("роль не ссылается на новую модель прав", result, role.getRights()); //$NON-NLS-1$
    }

    @Test
    public void missingRightsAreCreated() throws Exception {
        RoleDescription result = getOrCreate();

        assertNotNull(result);
        assertEquals(1, attached.size());
        assertSame(result, role.getRights());
    }

    @Test
    public void resolvedRightsAreReusedWithoutAttach() throws Exception {
        RoleDescription existing = RightsFactory.eINSTANCE.createRoleDescription();
        setRights(existing);

        assertSame(existing, getOrCreate());
        assertTrue("существующая модель прав подключена повторно", attached.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void inspectReportsNoRightsModelForUnresolvedProxy() throws Exception {
        setRights(rightsProxy);

        assertFalse("прокси показан как модель прав", hasRightsModel()); //$NON-NLS-1$
    }

    @Test
    public void inspectReportsRightsModelForResolvedDescription() throws Exception {
        setRights(RightsFactory.eINSTANCE.createRoleDescription());

        assertTrue(hasRightsModel());
    }

    private void setRights(RoleDescription description) {
        // Through the feature, as the BM loader binds it, not through the typed setter.
        role.eSet(com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage.Literals.ROLE__RIGHTS, description);
    }

    private RoleDescription getOrCreate() throws Exception {
        IBmPlatformTransaction transaction = (IBmPlatformTransaction) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] { IBmPlatformTransaction.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "attachTopObject" -> { //$NON-NLS-1$
                        attached.add((IBmObject) args[1]);
                        yield null;
                    }
                    case "getTopObjectByFqn" -> attached.isEmpty() ? null : attached.get(attached.size() - 1); //$NON-NLS-1$
                    case "toTransactionObject" -> args[0]; //$NON-NLS-1$
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Method method = EdtRoleRightsService.class.getDeclaredMethod("getOrCreateRoleDescription", //$NON-NLS-1$
                IBmPlatformTransaction.class, IProject.class, Role.class);
        method.setAccessible(true);
        return (RoleDescription) invoke(method, transaction, project, role);
    }

    private boolean hasRightsModel() throws Exception {
        IBmTransaction transaction = (IBmTransaction) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { IBmTransaction.class }, (proxy, method, args) -> {
                    if ("getTopObjectByFqn".equals(method.getName())) { //$NON-NLS-1$
                        return role;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        Method method = EdtRoleRightsService.class.getDeclaredMethod("readSnapshot", //$NON-NLS-1$
                IBmTransaction.class, String.class, String.class, String.class);
        method.setAccessible(true);
        EdtRoleRightsService.RoleRightsSnapshot snapshot = (EdtRoleRightsService.RoleRightsSnapshot) invoke(method,
                transaction, "CpScratchForms", "Role.Зонд", null); //$NON-NLS-1$ //$NON-NLS-2$
        return snapshot.hasRightsModel();
    }

    private Object invoke(Method method, Object... args) throws Exception {
        try {
            return method.invoke(service, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }
}
