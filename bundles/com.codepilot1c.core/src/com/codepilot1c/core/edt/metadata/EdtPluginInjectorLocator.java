package com.codepilot1c.core.edt.metadata;

import java.lang.reflect.Method;

import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

import com.codepilot1c.core.logging.VibeLogger;

/**
 * Resolves Guice-managed EDT services through their owning plug-in injector.
 *
 * <p>Some EDT service implementations are concrete public classes but depend on
 * Guice member injection. Their owning plug-in classes are not exported by EDT,
 * so the public {@code getDefault()/getInjector()} API must be reached through
 * the bundle class loader.</p>
 */
public final class EdtPluginInjectorLocator {

    private static final String FORM_BUNDLE_ID = "com._1c.g5.v8.dt.form"; //$NON-NLS-1$
    private static final String FORM_PLUGIN_CLASS = "com._1c.g5.v8.dt.internal.form.FormPlugin"; //$NON-NLS-1$
    private static final String GUICE_INJECTOR_CLASS = "com.google.inject.Injector"; //$NON-NLS-1$
    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(EdtPluginInjectorLocator.class);

    private EdtPluginInjectorLocator() {
    }

    /**
     * Returns the injector owned by the EDT form plug-in.
     */
    public static Object formInjector() {
        Bundle formBundle = requireBundle(FORM_BUNDLE_ID);
        try {
            Class<?> formPluginClass = loadBundleClass(formBundle, FORM_PLUGIN_CLASS);
            Method getDefault = formPluginClass.getMethod("getDefault"); //$NON-NLS-1$
            Object plugin = getDefault.invoke(null);
            if (plugin == null) {
                startBundle(formBundle, "Failed to start EDT form bundle: "); //$NON-NLS-1$
                plugin = getDefault.invoke(null);
            }
            if (plugin == null) {
                throw new MetadataOperationException(
                        MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                        "FormPlugin instance is unavailable", false); //$NON-NLS-1$
            }
            Object injector = formPluginClass.getMethod("getInjector").invoke(plugin); //$NON-NLS-1$
            if (injector == null) {
                throw new MetadataOperationException(
                        MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                        "FormPlugin injector is unavailable", false); //$NON-NLS-1$
            }
            return injector;
        } catch (MetadataOperationException e) {
            throw e;
        } catch (ReflectiveOperationException e) {
            throw serviceUnavailable("Cannot resolve FormPlugin injector: " + e.getMessage(), e); //$NON-NLS-1$
        }
    }

    /**
     * Returns the injector owned by an EDT plug-in.
     */
    public static Object pluginInjector(String bundleId, String pluginClassName) {
        Bundle bundle = requireBundle(bundleId);
        try {
            Class<?> pluginClass = loadBundleClass(bundle, pluginClassName);
            Method getDefault = pluginClass.getMethod("getDefault"); //$NON-NLS-1$
            Object plugin = getDefault.invoke(null);
            if (plugin == null) {
                startBundle(bundle, "Failed to start EDT bundle " + bundle.getSymbolicName() + ": "); //$NON-NLS-1$ //$NON-NLS-2$
                plugin = getDefault.invoke(null);
            }
            if (plugin == null) {
                throw new MetadataOperationException(MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                        "Plugin instance unavailable: " + pluginClassName, false); //$NON-NLS-1$
            }
            Object injector = pluginClass.getMethod("getInjector").invoke(plugin); //$NON-NLS-1$
            if (injector == null) {
                throw new MetadataOperationException(MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                        "Plugin injector unavailable: " + pluginClassName, false); //$NON-NLS-1$
            }
            return injector;
        } catch (MetadataOperationException e) {
            throw e;
        } catch (ReflectiveOperationException e) {
            throw serviceUnavailable("Cannot resolve plugin injector for " + pluginClassName + ": " + e.getMessage(), e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Resolves a Guice-managed service and verifies the returned type.
     */
    public static <T> T service(Object injector, Class<T> serviceClass) {
        if (injector == null) {
            throw serviceUnavailable("Injector is unavailable for " + serviceClass.getName(), null); //$NON-NLS-1$
        }
        try {
            Class<?> injectorApiClass = resolveInjectorApiClass(injector);
            Method getInstance = injectorApiClass.getMethod("getInstance", Class.class); //$NON-NLS-1$
            Object service = getInstance.invoke(injector, serviceClass);
            if (service == null) {
                throw new MetadataOperationException(
                        MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                        "Injector returned null for " + serviceClass.getName(), false); //$NON-NLS-1$
            }
            return serviceClass.cast(service);
        } catch (MetadataOperationException e) {
            throw e;
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw serviceUnavailable("Cannot resolve injector service " + serviceClass.getName() + ": " //$NON-NLS-1$ //$NON-NLS-2$
                    + e.getMessage(), e);
        }
    }

    private static Class<?> resolveInjectorApiClass(Object injector) {
        ClassLoader classLoader = injector.getClass().getClassLoader();
        try {
            Class<?> injectorInterface = Class.forName(GUICE_INJECTOR_CLASS, false, classLoader);
            if (injectorInterface.isAssignableFrom(injector.getClass())) {
                return injectorInterface;
            }
        } catch (ClassNotFoundException e) {
            LOG.debug("Guice Injector interface was not resolved from injector classloader: %s", e.getMessage()); //$NON-NLS-1$
        }
        for (Class<?> iface : injector.getClass().getInterfaces()) {
            if (GUICE_INJECTOR_CLASS.equals(iface.getName())) {
                return iface;
            }
        }
        return injector.getClass();
    }

    private static Bundle requireBundle(String bundleId) {
        Bundle bundle = Platform.getBundle(bundleId);
        if (bundle == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                    "Required EDT bundle is unavailable: " + bundleId, false); //$NON-NLS-1$
        }
        return bundle;
    }

    private static Class<?> loadBundleClass(Bundle bundle, String className) throws ClassNotFoundException {
        return bundle.loadClass(className);
    }

    private static void startBundle(Bundle bundle, String failurePrefix) {
        try {
            bundle.start(Bundle.START_TRANSIENT);
        } catch (Exception e) {
            throw serviceUnavailable(failurePrefix + e.getMessage(), e);
        }
    }

    private static MetadataOperationException serviceUnavailable(String message, Throwable cause) {
        return new MetadataOperationException(
                MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                message,
                false,
                cause);
    }
}
