package com.codepilot1c.core.harness;

import java.util.ArrayList;
import java.util.List;

/**
 * Asserts that the 1C:EDT target platform actually reached the test runtime.
 *
 * <p>These tests exercise real EDT BM and EMF APIs, so the OSGi runtime has to be on Surefire's
 * classpath. It gets there implicitly: {@code com.codepilot1c.core.tests} is a plain {@code jar}
 * module, and Tycho injects the resolved target platform of the {@code eclipse-plugin} module it
 * depends on — which only happens when {@code com.codepilot1c.core} is part of the same reactor
 * invocation.</p>
 *
 * <p>When that injection is missing, the tests fail with {@code NoClassDefFoundError} on classes
 * like {@code org.eclipse.core.runtime.IStatus} and {@code org.eclipse.emf.ecore.EObject}, which
 * reads as a broken test suite rather than a build-invocation problem. Checking up front turns it
 * into one failure that names both the missing classes and the command that supplies them.</p>
 */
public final class OsgiTestPlatform {

    /** The supported invocation; see also docs/release-notes for the pinned EDT version. */
    public static final String SUPPORTED_COMMAND =
            "mvn -Dedt.home=\"<1C:EDT Eclipse dir>\" -pl bundles/com.codepilot1c.core.tests -am package"; //$NON-NLS-1$

    /**
     * One representative class per layer the EDT-backed tests need: the Equinox runtime, the EMF
     * core model, and the 1C metadata model that {@code add_metadata_child} builds on.
     */
    private static final List<String> REQUIRED_CLASSES = List.of(
            "org.eclipse.core.runtime.IStatus", //$NON-NLS-1$
            "org.eclipse.emf.ecore.EObject", //$NON-NLS-1$
            "com._1c.g5.v8.dt.metadata.mdclass.MdObject"); //$NON-NLS-1$

    private OsgiTestPlatform() {
    }

    /**
     * @throws AssertionError naming every missing class and the supported build command
     */
    public static void requirePlatform(ClassLoader loader) {
        List<String> missing = new ArrayList<>();
        for (String className : REQUIRED_CLASSES) {
            try {
                Class.forName(className, false, loader);
            } catch (ClassNotFoundException | LinkageError absent) {
                missing.add(className);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        throw new AssertionError("The 1C:EDT target platform is missing from the test runtime classpath: " //$NON-NLS-1$
                + String.join(", ", missing) //$NON-NLS-1$
                + ". These tests exercise real EDT BM and EMF APIs, so the platform cannot be stubbed out." //$NON-NLS-1$
                + " Tycho supplies it only when com.codepilot1c.core is built in the same reactor invocation." //$NON-NLS-1$
                + " Run: " + SUPPORTED_COMMAND); //$NON-NLS-1$
    }
}
