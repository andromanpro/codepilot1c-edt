package com.codepilot1c.core.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Guards the OSGi/EMF runtime classpath these tests depend on.
 *
 * <p>{@code com.codepilot1c.core.tests} is a plain {@code jar} module, so it gets the 1C:EDT target
 * platform only because Tycho injects the {@code eclipse-plugin} dependency's resolved platform
 * into the reactor. That injection happens when {@code com.codepilot1c.core} is built in the same
 * invocation — the {@code -am} form documented in the release notes. Run without it and the module
 * does not compile at all; but an environment that keeps the platform on the compile classpath
 * while losing it at test runtime produces a shower of {@code NoClassDefFoundError}s naming
 * {@code org.eclipse.core.runtime.IStatus} or {@code org.eclipse.emf.ecore.EObject}, with nothing
 * pointing at the real cause.</p>
 *
 * <p>This turns that into one explicit, actionable failure.</p>
 */
public class OsgiTestPlatformAvailabilityTest {

    @Test
    public void thisBuildExposesTheOsgiAndEmfRuntimeToSurefire() {
        OsgiTestPlatform.requirePlatform(getClass().getClassLoader());
    }

    @Test
    public void theGuardNamesEveryMissingRuntimeClassAndTheSupportedCommand() {
        ClassLoader withoutPlatform = new HidingClassLoader(
                getClass().getClassLoader(), List.of("org.eclipse.", "com._1c.")); //$NON-NLS-1$ //$NON-NLS-2$

        AssertionError failure = assertThrows(AssertionError.class,
                () -> OsgiTestPlatform.requirePlatform(withoutPlatform));

        String message = failure.getMessage();
        assertTrue(message, message.contains("org.eclipse.core.runtime.IStatus")); //$NON-NLS-1$
        assertTrue(message, message.contains("org.eclipse.emf.ecore.EObject")); //$NON-NLS-1$
        assertTrue(message, message.contains("com._1c.g5.v8.dt.metadata.mdclass.MdObject")); //$NON-NLS-1$
        assertTrue("the guard must name the supported build command", //$NON-NLS-1$
                message.contains("-pl bundles/com.codepilot1c.core.tests -am")); //$NON-NLS-1$
    }

    @Test
    public void aPartiallyMissingPlatformIsReportedRatherThanPassing() {
        ClassLoader withoutEmf = new HidingClassLoader(
                getClass().getClassLoader(), List.of("org.eclipse.emf.")); //$NON-NLS-1$

        AssertionError failure = assertThrows(AssertionError.class,
                () -> OsgiTestPlatform.requirePlatform(withoutEmf));

        assertTrue(failure.getMessage(), failure.getMessage().contains("org.eclipse.emf.ecore.EObject")); //$NON-NLS-1$
        assertEquals("a present class must not be reported as missing", //$NON-NLS-1$
                -1, failure.getMessage().indexOf("org.eclipse.core.runtime.IStatus")); //$NON-NLS-1$
    }

    /** Loads everything from its parent except the prefixes it is told to hide. */
    private static final class HidingClassLoader extends ClassLoader {
        private final List<String> hidden;

        HidingClassLoader(ClassLoader parent, List<String> hidden) {
            super(parent);
            this.hidden = hidden;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (hidden.stream().anyMatch(name::startsWith)) {
                throw new ClassNotFoundException(name);
            }
            return super.loadClass(name, resolve);
        }
    }
}
