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
 * <p>Only one hidden requirement is real. {@code OpenTerminalHandler} resolves the launcher id
 * {@code org.eclipse.tm.terminal.connector.local.launcher.local} at runtime, which no OSGi manifest
 * header expresses, so the feature has to state it. Everything else that bundle needs -
 * {@code connector.process}, and the CDT native pty/spawner behind it - follows from that bundle's
 * own requirements. The SSH and Telnet connectors and {@code cdt.native.serial} are used nowhere and
 * came in purely as feature-import fallout.</p>
 */
public class FeatureDependencyScopeTest {

    /** Bundles nothing in this product references; importing whole features dragged them in. */
    private static final String[] UNINTENDED = {
            "org.eclipse.tm.terminal.connector.ssh", //$NON-NLS-1$
            "org.eclipse.tm.terminal.connector.telnet", //$NON-NLS-1$
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
    public void featureStatesTheOneRequirementNoManifestHeaderCanExpress() throws Exception {
        String feature = readFeature();

        assertTrue("the local terminal connector is resolved by id at runtime, so it must be" //$NON-NLS-1$
                + " declared explicitly:\n" + feature, //$NON-NLS-1$
                feature.contains("id=\"org.eclipse.tm.terminal.connector.local\"")); //$NON-NLS-1$
        // Its own Require-Bundle and the native pty/spawner packages behind it.
        assertTrue(feature, feature.contains("id=\"org.eclipse.tm.terminal.connector.process\"")); //$NON-NLS-1$
        assertTrue(feature, feature.contains("id=\"org.eclipse.cdt.core.native\"")); //$NON-NLS-1$
        assertTrue(feature, feature.contains("id=\"org.eclipse.cdt.core.macosx\"")); //$NON-NLS-1$
    }

    @Test
    public void featureNeverNamesABundleThisProductDoesNotUse() throws Exception {
        String feature = readFeature();

        // Compare declarations, not prose: the file documents by name why these are excluded.
        String declarations = stripComments(feature);
        for (String unintended : UNINTENDED) {
            assertFalse("feature.xml must not require " + unintended + ":\n" + declarations, //$NON-NLS-1$ //$NON-NLS-2$
                    declarations.contains(unintended));
        }
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
