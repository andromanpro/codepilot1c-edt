package com.codepilot1c.core.harness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

import org.eclipse.osgi.service.datalocation.Location;
import org.eclipse.core.internal.runtime.InternalPlatform;
import org.eclipse.core.internal.preferences.Activator;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.RunListener;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/** Starts an actual isolated Equinox configuration before the JUnit4 test run. */
public final class EquinoxTestRuntimeListener extends RunListener {
    private static Framework framework;
    private static Activator preferencesActivator;
    private static Path root;

    @Override public void testRunStarted(Description ignored) throws Exception { start(); }
    @Override public void testRunFinished(Result ignored) throws Exception { stop(); }

    static synchronized Path root() { return root; }

    private static synchronized void start() throws Exception {
        if (framework != null) return;
        root = Files.createTempDirectory("codepilot-equinox-test-"); //$NON-NLS-1$
        Map<String, String> properties = new HashMap<>();
        properties.put("osgi.configuration.area", root.resolve("configuration").toUri().toString()); //$NON-NLS-1$ //$NON-NLS-2$
        properties.put("osgi.instance.area", root.resolve("workspace").toUri().toString()); //$NON-NLS-1$ //$NON-NLS-2$
        properties.put("osgi.user.area", root.resolve("user").toUri().toString()); //$NON-NLS-1$ //$NON-NLS-2$
        properties.put("osgi.clean", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        properties.put("eclipse.ignoreApp", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        FrameworkFactory factory = ServiceLoader.load(FrameworkFactory.class).findFirst().orElseThrow();
        Framework candidate = factory.newFramework(properties);
        candidate.init();
        candidate.start();
        BundleContext context = candidate.getBundleContext();
        ServiceReference<?>[] refs = context.getAllServiceReferences(Location.class.getName(), Location.CONFIGURATION_FILTER);
        if (refs == null || refs.length != 1 || !(context.getService(refs[0]) instanceof Location location)
                || location.getURL() == null) {
            candidate.stop();
            throw new IllegalStateException("Equinox configuration Location service is unavailable"); //$NON-NLS-1$
        }
        // The test module is a plain Maven JAR, so its Platform class is loaded by
        // Surefire rather than by the framework bundle. Start the same official
        // runtime activators against the owned context to bind their Location
        // trackers. ConfigurationScope obtains its Location through the Equinox
        // preferences activator, not through Platform directly.
        InternalPlatform.getDefault().start(context);
        preferencesActivator = new Activator();
        preferencesActivator.start(context);
        framework = candidate;
    }

    private static synchronized void stop() throws Exception {
        if (framework != null) {
            if (preferencesActivator != null) {
                preferencesActivator.stop(framework.getBundleContext());
                preferencesActivator = null;
            }
            InternalPlatform.getDefault().stop(framework.getBundleContext());
            framework.stop();
            framework.waitForStop(10_000L);
            framework = null;
        }
    }
}
