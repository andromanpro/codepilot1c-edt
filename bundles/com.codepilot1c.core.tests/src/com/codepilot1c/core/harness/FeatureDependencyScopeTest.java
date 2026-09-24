package com.codepilot1c.core.harness;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * Keeps the p2 install footprint scoped to what the plugin actually needs.
 *
 * <p>Installing the candidate into EDT 2026.2 added seven bundles to {@code bundles.info} rather
 * than the two expected ones. The cause was in {@code feature.xml}: it imported two whole
 * third-party features, {@code org.eclipse.tm.terminal.feature} and {@code org.eclipse.cdt.native},
 * so p2 had to satisfy every plugin in both.</p>
 *
 * <p>Later, explicit Terminal/CDT plugin entries caused the opposite compatibility failure: a site
 * built on a newer EDT target published exact IUs such as {@code org.eclipse.cdt.core.native
 * 6.6.0}, and p2 tried to install that bundle into older supported EDT builds whose
 * {@code org.eclipse.core.runtime} did not satisfy it. Keep the feature root limited to CodePilot
 * bundles; host-provided Eclipse Terminal APIs remain expressed by the UI bundle manifest.</p>
 */
public class FeatureDependencyScopeTest {

    /** Platform-provided bundles must not be exact-version payload roots in this product feature. */
    private static final String[] PLATFORM_PROVIDED = {
            "org.eclipse.tm.terminal.connector.local", //$NON-NLS-1$
            "org.eclipse.tm.terminal.connector.process", //$NON-NLS-1$
            "org.eclipse.tm.terminal.connector.ssh", //$NON-NLS-1$
            "org.eclipse.tm.terminal.connector.telnet", //$NON-NLS-1$
            "org.eclipse.cdt.core.native", //$NON-NLS-1$
            "org.eclipse.cdt.core.macosx", //$NON-NLS-1$
            "org.eclipse.cdt.core.linux", //$NON-NLS-1$
            "org.eclipse.cdt.core.linux.x86_64", //$NON-NLS-1$
            "org.eclipse.cdt.core.win32", //$NON-NLS-1$
            "org.eclipse.cdt.core.win32.x86_64", //$NON-NLS-1$
            "org.eclipse.cdt.native.serial", //$NON-NLS-1$
    };

    @Test
    public void featureDoesNotImportWholeThirdPartyFeatures() throws Exception {
        String declarations = stripComments(readFeature());

        assertFalse("importing org.eclipse.tm.terminal.feature pulls in every terminal connector:\n" //$NON-NLS-1$
                + declarations, declarations.contains("feature=\"org.eclipse.tm.terminal.feature\"")); //$NON-NLS-1$
        assertFalse("importing org.eclipse.cdt.native pulls in bundles this product never calls:\n" //$NON-NLS-1$
                + declarations, declarations.contains("feature=\"org.eclipse.cdt.native\"")); //$NON-NLS-1$
    }

    /** Drops XML comments so prose explaining an exclusion is not mistaken for a declaration. */
    private static String stripComments(String xml) {
        return xml.replaceAll("(?s)<!--.*?-->", ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void featureDoesNotDeclareHostPlatformBundlesAsPayloadRoots() throws Exception {
        String declarations = stripComments(readFeature());

        for (String providedByHost : PLATFORM_PROVIDED) {
            assertFalse("feature.xml must not pin host platform bundle " + providedByHost //$NON-NLS-1$
                    + " as an exact p2 IU requirement:\n" + declarations, //$NON-NLS-1$
                    declarations.contains("id=\"" + providedByHost + "\"")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void featureContainsOnlyCodePilotBundleRoots() throws Exception {
        String declarations = stripComments(readFeature());

        assertTrue(declarations, declarations.contains("id=\"com.codepilot1c.core\"")); //$NON-NLS-1$
        assertTrue(declarations, declarations.contains("id=\"com.codepilot1c.ui\"")); //$NON-NLS-1$
    }

    /** Located relative to the core bundle the build hands to Surefire. */
    private static String readFeature() throws Exception {
        String bundlePath = System.getProperty("core.bundle.path"); //$NON-NLS-1$
        assertTrue("core.bundle.path must be provided by the build", bundlePath != null); //$NON-NLS-1$
        // The property is built with a literal "..", so normalize before walking up:
        // <repo>/bundles/com.codepilot1c.core/target/<jar>
        Path jar = new File(bundlePath).toPath().toAbsolutePath().normalize();
        Path repositoryRoot = jar.getParent().getParent().getParent().getParent();
        Path feature = repositoryRoot.resolve("features") //$NON-NLS-1$
                .resolve("com.codepilot1c.feature").resolve("feature.xml"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("feature.xml not found at " + feature, Files.isRegularFile(feature)); //$NON-NLS-1$
        return Files.readString(feature, StandardCharsets.UTF_8);
    }
}
