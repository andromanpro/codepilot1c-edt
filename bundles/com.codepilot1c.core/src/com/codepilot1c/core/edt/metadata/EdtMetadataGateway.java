package com.codepilot1c.core.edt.metadata;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;

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

    private volatile FormItemInformationServiceCache formItemInformationServiceCache;

    private record FormItemInformationServiceCache(
            Object injector,
            FormItemInformationService service
    ) {
    }

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
     * Returns the form item information service assembled by the EDT form
     * plug-in injector. The service has Guice-injected runtime-version support
     * and keeps EDT's per-version event/type caches between calls.
     */
    public FormItemInformationService getFormItemInformationService() {
        Object injector = EdtPluginInjectorLocator.formInjector();
        FormItemInformationServiceCache cached = formItemInformationServiceCache;
        if (cached != null && cached.injector() == injector) {
            return cached.service();
        }
        synchronized (this) {
            cached = formItemInformationServiceCache;
            if (cached != null && cached.injector() == injector) {
                return cached.service();
            }
            FormItemInformationService service =
                    EdtPluginInjectorLocator.service(injector, FormItemInformationService.class);
            formItemInformationServiceCache = new FormItemInformationServiceCache(injector, service);
            return service;
        }
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
