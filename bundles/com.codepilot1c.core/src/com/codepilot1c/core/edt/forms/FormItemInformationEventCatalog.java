package com.codepilot1c.core.edt.forms;

import java.lang.reflect.Method;
import java.util.List;

import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.form.model.FormVisualEntity;
import com._1c.g5.v8.dt.form.service.FormItemInformationService;
import com._1c.g5.v8.dt.mcore.Event;

import com.codepilot1c.core.logging.VibeLogger;

/**
 * Default {@link EventHandlerCatalog} implementation delegating to the platform
 * {@link FormItemInformationService}.
 *
 * <p>This is the only class in the codebase that references
 * {@code FormItemInformationService} directly, so the platform dependency stays
 * isolated behind the {@link EventHandlerCatalog} seam.</p>
 *
 * <p>The service is resolved through the form-bundle Guice injector: on EDT 2025.2.3+
 * {@code FormItemInformationService} has injected dependencies (e.g. {@code versionSupport}),
 * and a bare {@code new FormItemInformationService()} leaves them {@code null}, failing later
 * inside the first {@code getAllowedEvents} call with
 * "Cannot invoke IRuntimeVersionSupport.getRuntimeVersion ... versionSupport is null".
 * The bare constructor is kept only as a fallback for platforms where the injector
 * is unavailable (older EDT versions tolerated it).</p>
 */
public class FormItemInformationEventCatalog implements EventHandlerCatalog {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(FormItemInformationEventCatalog.class);

    private static final String FORM_BUNDLE_ID = "com._1c.g5.v8.dt.form"; //$NON-NLS-1$
    private static final String FORM_PLUGIN_CLASS = "com._1c.g5.v8.dt.internal.form.FormPlugin"; //$NON-NLS-1$
    private static final String INJECTOR_API_CLASS = "com.google.inject.Injector"; //$NON-NLS-1$

    private volatile FormItemInformationService service;

    @Override
    public List<Event> allowedEvents(FormVisualEntity item) {
        return resolveService().getAllowedEvents(item);
    }

    private FormItemInformationService resolveService() {
        FormItemInformationService resolved = service;
        if (resolved != null) {
            return resolved;
        }
        synchronized (this) {
            if (service == null) {
                service = createService();
            }
            return service;
        }
    }

    private static FormItemInformationService createService() {
        try {
            Bundle formBundle = Platform.getBundle(FORM_BUNDLE_ID);
            if (formBundle != null) {
                Class<?> pluginClass = formBundle.loadClass(FORM_PLUGIN_CLASS);
                Object plugin = pluginClass.getMethod("getDefault").invoke(null); //$NON-NLS-1$
                if (plugin == null) {
                    formBundle.start(Bundle.START_TRANSIENT);
                    plugin = pluginClass.getMethod("getDefault").invoke(null); //$NON-NLS-1$
                }
                if (plugin != null) {
                    Object injector = pluginClass.getMethod("getInjector").invoke(plugin); //$NON-NLS-1$
                    if (injector != null) {
                        Class<?> injectorApi = pluginClass.getClassLoader().loadClass(INJECTOR_API_CLASS);
                        Method getInstance = injectorApi.getMethod("getInstance", Class.class); //$NON-NLS-1$
                        Object viaInjector = getInstance.invoke(injector, FormItemInformationService.class);
                        if (viaInjector instanceof FormItemInformationService typed) {
                            return typed;
                        }
                    }
                }
            }
            LOG.warn("Form-bundle injector unavailable, using bare FormItemInformationService constructor"); //$NON-NLS-1$
        } catch (Exception | LinkageError e) {
            LOG.warn("FormItemInformationService injector resolution failed (%s), using bare constructor", //$NON-NLS-1$
                    e.getMessage());
        }
        return new FormItemInformationService();
    }
}
