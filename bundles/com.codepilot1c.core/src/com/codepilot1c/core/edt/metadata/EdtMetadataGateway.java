package com.codepilot1c.core.edt.metadata;

import java.lang.reflect.Method;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.bm.integration.IBmPlatformGlobalEditingContext;
import com._1c.g5.v8.dt.bm.xtext.BmAwareResourceSetProvider;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IExternalObjectProjectManager;
import com._1c.g5.v8.dt.core.platform.IExtensionProjectManager;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.form.service.FormItemInformationService;
import com._1c.g5.v8.dt.md.extension.IMdAdoptedPropertyAccess;
import com._1c.g5.v8.dt.md.extension.adopt.IModelObjectAdopter;
import com._1c.g5.v8.dt.platform.version.Version;
import com.codepilot1c.core.internal.VibeCorePlugin;

/**
 * Access to EDT runtime services.
 */
public class EdtMetadataGateway {

    private static final String FORM_BUNDLE_ID = "com._1c.g5.v8.dt.form"; //$NON-NLS-1$
    private static final String FORM_PLUGIN_CLASS = "com._1c.g5.v8.dt.internal.form.FormPlugin"; //$NON-NLS-1$
    private static final String GUICE_INJECTOR_CLASS = "com.google.inject.Injector"; //$NON-NLS-1$

    public IProject resolveProject(String projectName) {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        if (workspace == null || projectName == null || projectName.isBlank()) {
            return null;
        }
        return workspace.getRoot().getProject(projectName);
    }

    public IConfigurationProvider getConfigurationProvider() {
        VibeCorePlugin plugin = requirePlugin();
        IConfigurationProvider service = plugin.getConfigurationProvider();
        if (service == null) {
            throw serviceUnavailable("IConfigurationProvider"); //$NON-NLS-1$
        }
        return service;
    }

    public IBmModelManager getBmModelManager() {
        VibeCorePlugin plugin = requirePlugin();
        IBmModelManager service = plugin.getBmModelManager();
        if (service == null) {
            throw serviceUnavailable("IBmModelManager"); //$NON-NLS-1$
        }
        return service;
    }

    public IDtProjectManager getDtProjectManager() {
        VibeCorePlugin plugin = requirePlugin();
        IDtProjectManager service = plugin.getDtProjectManager();
        if (service == null) {
            throw serviceUnavailable("IDtProjectManager"); //$NON-NLS-1$
        }
        return service;
    }

    public IV8ProjectManager getV8ProjectManager() {
        VibeCorePlugin plugin = requirePlugin();
        IV8ProjectManager service = plugin.getV8ProjectManager();
        if (service == null) {
            throw serviceUnavailable("IV8ProjectManager"); //$NON-NLS-1$
        }
        return service;
    }

    public IExtensionProjectManager getExtensionProjectManager() {
        VibeCorePlugin plugin = requirePlugin();
        IExtensionProjectManager service = plugin.getExtensionProjectManager();
        if (service == null) {
            throw serviceUnavailable("IExtensionProjectManager"); //$NON-NLS-1$
        }
        return service;
    }

    public IExternalObjectProjectManager getExternalObjectProjectManager() {
        VibeCorePlugin plugin = requirePlugin();
        IExternalObjectProjectManager service = plugin.getExternalObjectProjectManager();
        if (service == null) {
            throw serviceUnavailable("IExternalObjectProjectManager"); //$NON-NLS-1$
        }
        return service;
    }

    public IModelObjectAdopter getModelObjectAdopter() {
        VibeCorePlugin plugin = requirePlugin();
        IModelObjectAdopter service = plugin.getModelObjectAdopter();
        if (service == null) {
            throw serviceUnavailable("IModelObjectAdopter"); //$NON-NLS-1$
        }
        return service;
    }

    public IMdAdoptedPropertyAccess getMdAdoptedPropertyAccess() {
        VibeCorePlugin plugin = requirePlugin();
        IMdAdoptedPropertyAccess service = plugin.getMdAdoptedPropertyAccess();
        if (service == null) {
            throw serviceUnavailable("IMdAdoptedPropertyAccess"); //$NON-NLS-1$
        }
        return service;
    }

    public IDerivedDataManagerProvider getDerivedDataManagerProvider() {
        VibeCorePlugin plugin = requirePlugin();
        IDerivedDataManagerProvider service = plugin.getDerivedDataManagerProvider();
        if (service == null) {
            throw serviceUnavailable("IDerivedDataManagerProvider"); //$NON-NLS-1$
        }
        return service;
    }

    public ITopObjectFqnGenerator getTopObjectFqnGenerator() {
        VibeCorePlugin plugin = requirePlugin();
        ITopObjectFqnGenerator service = plugin.getTopObjectFqnGenerator();
        if (service == null) {
            throw serviceUnavailable("ITopObjectFqnGenerator"); //$NON-NLS-1$
        }
        return service;
    }

    public BmAwareResourceSetProvider getResourceSetProvider() {
        VibeCorePlugin plugin = requirePlugin();
        BmAwareResourceSetProvider service = plugin.getResourceSetProvider();
        if (service == null) {
            throw serviceUnavailable("BmAwareResourceSetProvider"); //$NON-NLS-1$
        }
        return service;
    }

    /**
     * Returns the Guice-managed EDT form information service. The concrete service has
     * injected runtime-version support; constructing it directly leaves that dependency
     * {@code null} and breaks event discovery.
     */
    public FormItemInformationService getFormItemInformationService() {
        try {
            Bundle formBundle = Platform.getBundle(FORM_BUNDLE_ID);
            if (formBundle == null) {
                throw serviceUnavailable("EDT form bundle"); //$NON-NLS-1$
            }
            Class<?> pluginClass = formBundle.loadClass(FORM_PLUGIN_CLASS);
            Method getDefault = pluginClass.getMethod("getDefault"); //$NON-NLS-1$
            Object plugin = getDefault.invoke(null);
            if (plugin == null) {
                formBundle.start(Bundle.START_TRANSIENT);
                plugin = getDefault.invoke(null);
            }
            if (plugin == null) {
                throw serviceUnavailable("FormPlugin"); //$NON-NLS-1$
            }
            Object injector = pluginClass.getMethod("getInjector").invoke(plugin); //$NON-NLS-1$
            if (injector == null) {
                throw serviceUnavailable("FormPlugin injector"); //$NON-NLS-1$
            }
            Class<?> injectorApi = resolveInjectorApiClass(injector);
            Method getInstance = injectorApi.getMethod("getInstance", Class.class); //$NON-NLS-1$
            Object service = getInstance.invoke(injector, FormItemInformationService.class);
            if (!(service instanceof FormItemInformationService result)) {
                throw serviceUnavailable("FormItemInformationService"); //$NON-NLS-1$
            }
            return result;
        } catch (MetadataOperationException e) {
            throw e;
        } catch (ReflectiveOperationException | org.osgi.framework.BundleException e) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                    "Cannot resolve EDT FormItemInformationService: " + e.getMessage(), false, e); //$NON-NLS-1$
        }
    }

    private Class<?> resolveInjectorApiClass(Object injector) {
        ClassLoader classLoader = injector.getClass().getClassLoader();
        try {
            Class<?> injectorInterface = Class.forName(GUICE_INJECTOR_CLASS, false, classLoader);
            if (injectorInterface.isAssignableFrom(injector.getClass())) {
                return injectorInterface;
            }
        } catch (ClassNotFoundException e) {
            // Fall through to the interfaces exposed by the concrete injector.
        }
        for (Class<?> iface : injector.getClass().getInterfaces()) {
            if (GUICE_INJECTOR_CLASS.equals(iface.getName())) {
                return iface;
            }
        }
        return injector.getClass();
    }

    public Version resolvePlatformVersion(IProject project) {
        try {
            IV8ProjectManager v8ProjectManager = getV8ProjectManager();
            if (project != null) {
                IV8Project v8Project = v8ProjectManager.getProject(project);
                if (v8Project != null && v8Project.getVersion() != null) {
                    return v8Project.getVersion();
                }
            }
            for (IV8Project candidate : v8ProjectManager.getProjects(IV8Project.class)) {
                if (candidate != null && candidate.getVersion() != null) {
                    return candidate.getVersion();
                }
            }
        } catch (RuntimeException e) {
            // Fall back to latest when V8 project manager is not available.
        }
        return Version.LATEST;
    }

    public IBmPlatformGlobalEditingContext getGlobalEditingContext() {
        IBmPlatformGlobalEditingContext service = getBmModelManager().getGlobalEditingContext();
        if (service == null) {
            throw serviceUnavailable("IBmPlatformGlobalEditingContext"); //$NON-NLS-1$
        }
        return service;
    }

    public void ensureValidationRuntimeAvailable() {
        // Validation requires access to project/configuration/readiness services.
        getConfigurationProvider();
        getDtProjectManager();
        getDerivedDataManagerProvider();
    }

    public void ensureMutationRuntimeAvailable() {
        // Mutation requires BM transaction services in addition to validation baseline.
        ensureValidationRuntimeAvailable();
        getBmModelManager();
        getGlobalEditingContext();
    }

    public void ensureExtensionRuntimeAvailable() {
        ensureValidationRuntimeAvailable();
        getV8ProjectManager();
        getExtensionProjectManager();
        getModelObjectAdopter();
        getMdAdoptedPropertyAccess();
    }

    public void ensureExternalObjectRuntimeAvailable() {
        ensureValidationRuntimeAvailable();
        getV8ProjectManager();
        getExternalObjectProjectManager();
    }

    public boolean isEdtAvailable() {
        try {
            ensureMutationRuntimeAvailable();
            return true;
        } catch (MetadataOperationException e) {
            return false;
        }
    }

    private VibeCorePlugin requirePlugin() {
        VibeCorePlugin plugin = VibeCorePlugin.getDefault();
        if (plugin == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                    "VibeCorePlugin is not initialized", false); //$NON-NLS-1$
        }
        return plugin;
    }

    private MetadataOperationException serviceUnavailable(String serviceName) {
        return new MetadataOperationException(
                MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                serviceName + " is unavailable in EDT runtime", false); //$NON-NLS-1$
    }
}
