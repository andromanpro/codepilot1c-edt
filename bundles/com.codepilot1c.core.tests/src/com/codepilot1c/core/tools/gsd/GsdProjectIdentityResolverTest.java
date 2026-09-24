/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.gsd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.file.Path;
import java.text.Normalizer;
import java.util.List;

import org.junit.Test;

import com.codepilot1c.core.tools.gsd.GsdProjectIdentityResolver.IdentityResolutionException;
import com.codepilot1c.core.tools.gsd.GsdProjectIdentityResolver.ProjectIdentity;

/** Security and regression tests for GSD execution path identity resolution. */
public class GsdProjectIdentityResolverTest {

    private static final Path ARTEL = Path.of("/edt/projects/exten_artel"); //$NON-NLS-1$
    private static final ProjectIdentity ARTEL_IDENTITY =
            new ProjectIdentity("ДО.Артель", ARTEL); //$NON-NLS-1$

    @Test
    public void exactCapturedIdentityPassesWithoutWorkspaceState() {
        assertEquals(ARTEL.toString(), GsdProjectIdentityResolver.resolve(
                ARTEL.toString(), ARTEL.toString(), List.of()));
    }

    @Test
    public void exactPhysicalIdentityPassesForCapturedEdtProject() {
        assertEquals(ARTEL.toString(), GsdProjectIdentityResolver.resolve(
                ARTEL.toString(), ARTEL.toString(), List.of(ARTEL_IDENTITY)));
    }

    @Test
    public void workspaceAliasPassesForCapturedEdtProject() {
        assertEquals(ARTEL.toString(), GsdProjectIdentityResolver.resolve(
                "/workspace/ДО.Артель", ARTEL.toString(), List.of(ARTEL_IDENTITY))); //$NON-NLS-1$
    }

    @Test
    public void workspaceAliasRepairsLegacyLocationPlusProjectNameCapture() {
        String legacyCapture = ARTEL.resolve(ARTEL_IDENTITY.name()).toString();
        assertEquals(ARTEL.toString(), GsdProjectIdentityResolver.resolve(
                "/workspace/ДО.Артель", legacyCapture, List.of(ARTEL_IDENTITY))); //$NON-NLS-1$
        assertEquals(ARTEL.toString(), GsdProjectIdentityResolver.resolve(
                legacyCapture, legacyCapture, List.of(ARTEL_IDENTITY)));
    }

    @Test
    public void siblingWorkspaceProjectFails() {
        ProjectIdentity base = new ProjectIdentity("ДО", Path.of("/edt/projects/do")); //$NON-NLS-1$ //$NON-NLS-2$
        assertIdentityFailure("/workspace/ДО", ARTEL.toString(), //$NON-NLS-1$
                List.of(base, ARTEL_IDENTITY));
    }

    @Test
    public void substitutedWorkspaceProjectNameFails() {
        assertIdentityFailure("/workspace/ДО.Артель-evil", ARTEL.toString(), //$NON-NLS-1$
                List.of(ARTEL_IDENTITY));
    }

    @Test
    public void sameBasenameAtUntrustedParentFails() {
        assertIdentityFailure("/attacker/exten_artel", ARTEL.toString(), //$NON-NLS-1$
                List.of(ARTEL_IDENTITY));
    }

    @Test
    public void traversalThatNormalizesToAliasStillFails() {
        assertIdentityFailure("/workspace/other/../ДО.Артель", ARTEL.toString(), //$NON-NLS-1$
                List.of(ARTEL_IDENTITY));
    }

    @Test
    public void requestedRawTraversalPrecedesAbsoluteValidation() {
        for (String requested : List.of(".", "..", "safe/../project", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "safe\\..\\project")) { //$NON-NLS-1$
            IdentityResolutionException failure = assertThrows(
                    IdentityResolutionException.class,
                    () -> GsdProjectIdentityResolver.resolve(
                            requested, ARTEL.toString(), List.of(ARTEL_IDENTITY)));
            assertEquals("project_path traversal is not allowed", failure.getMessage()); //$NON-NLS-1$
        }
    }

    @Test
    public void capturedWorkspaceAliasTraversalDoesNotAuthorizeSafeAlias() {
        assertCapturedTraversalFailure("/workspace/ДО.Артель", //$NON-NLS-1$
                "/workspace/other/../ДО.Артель", List.of(ARTEL_IDENTITY)); //$NON-NLS-1$
    }

    @Test
    public void capturedPhysicalTraversalDoesNotAuthorizeSafePhysicalPath() {
        assertCapturedTraversalFailure(ARTEL.toString(),
                "/edt/projects/other/../exten_artel", List.of(ARTEL_IDENTITY)); //$NON-NLS-1$
    }

    @Test
    public void capturedHeadlessTraversalDoesNotAuthorizeSafeExactIdentity() {
        assertCapturedTraversalFailure("/headless/exact", //$NON-NLS-1$
                "/headless/other/../exact", List.of()); //$NON-NLS-1$
    }

    @Test
    public void capturedRawTraversalPrecedesAbsoluteValidation() {
        for (String captured : List.of(".", "..", "safe/../project", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "safe\\..\\project")) { //$NON-NLS-1$
            assertCapturedTraversalFailure(ARTEL.toString(), captured,
                    List.of(ARTEL_IDENTITY));
        }
    }

    @Test
    public void capturedMixedSeparatorAndEncodedLookingTraversalFailsClosed() {
        assertCapturedTraversalFailure("/workspace/ДО.Артель", //$NON-NLS-1$
                "/workspace\\other/../workspace/ДО.Артель", //$NON-NLS-1$
                List.of(ARTEL_IDENTITY));
        assertCapturedTraversalFailure("/workspace/ДО.Артель", //$NON-NLS-1$
                "/workspace/%2e%2e/../ДО.Артель", //$NON-NLS-1$
                List.of(ARTEL_IDENTITY));
    }

    @Test
    public void symlinkLikeAlternativePathFailsEvenWithSameBasename() {
        assertIdentityFailure("/edt/links/exten_artel", ARTEL.toString(), //$NON-NLS-1$
                List.of(ARTEL_IDENTITY));
    }

    @Test
    public void missingCapturedIdentityFailsClosed() {
        assertIdentityFailure("/workspace/ДО.Артель", "", List.of(ARTEL_IDENTITY)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void relativeRequestedIdentityFailsClosed() {
        assertIdentityFailure("ДО.Артель", ARTEL.toString(), List.of(ARTEL_IDENTITY)); //$NON-NLS-1$
    }

    @Test
    public void unicodeCanonicalRussianProjectAliasPassesCaseSensitively() {
        String composedName = "ДО.Артель-Й"; //$NON-NLS-1$
        String decomposedName = Normalizer.normalize(composedName, Normalizer.Form.NFD);
        Path location = Path.of("/edt/projects/russian-unicode"); //$NON-NLS-1$
        ProjectIdentity identity = new ProjectIdentity(composedName, location);

        assertEquals(location.toString(), GsdProjectIdentityResolver.resolve(
                "/workspace/" + decomposedName, location.toString(), List.of(identity))); //$NON-NLS-1$
        assertIdentityFailure("/workspace/" + composedName.toLowerCase(), //$NON-NLS-1$
                location.toString(), List.of(identity));
    }

    @Test
    public void unicodeCanonicalEquivalenceDoesNotAuthorizeDifferentPhysicalPath() {
        String composedPath = "/edt/projects/Проект-Й"; //$NON-NLS-1$
        String decomposedPath = Normalizer.normalize(composedPath, Normalizer.Form.NFD);

        assertIdentityFailure(decomposedPath, composedPath, List.of());
    }

    @Test
    public void canonicallyEquivalentMultiProjectAliasesFailAsAmbiguous() {
        String composedName = "Проект-Й"; //$NON-NLS-1$
        String decomposedName = Normalizer.normalize(composedName, Normalizer.Form.NFD);
        ProjectIdentity first = new ProjectIdentity(composedName, Path.of("/edt/projects/one")); //$NON-NLS-1$
        ProjectIdentity second = new ProjectIdentity(decomposedName, Path.of("/edt/projects/two")); //$NON-NLS-1$
        String capturedAlias = "/workspace/" + composedName; //$NON-NLS-1$

        IdentityResolutionException failure = assertThrows(IdentityResolutionException.class,
                () -> GsdProjectIdentityResolver.resolve(
                        capturedAlias, capturedAlias, List.of(first, second)));
        assertEquals("captured execution identity is ambiguous in the EDT workspace", //$NON-NLS-1$
                failure.getMessage());
    }

    @Test
    public void aliasWithoutMatchingCapturedWorkspaceProjectFails() {
        assertIdentityFailure("/workspace/ДО.Артель", //$NON-NLS-1$
                "/edt/projects/missing", List.of(ARTEL_IDENTITY)); //$NON-NLS-1$
    }

    private static void assertIdentityFailure(
            String requested, String captured, List<ProjectIdentity> projects) {
        assertThrows(IdentityResolutionException.class,
                () -> GsdProjectIdentityResolver.resolve(requested, captured, projects));
    }

    private static void assertCapturedTraversalFailure(
            String requested, String captured, List<ProjectIdentity> projects) {
        IdentityResolutionException failure = assertThrows(IdentityResolutionException.class,
                () -> GsdProjectIdentityResolver.resolve(requested, captured, projects));
        assertEquals("captured project_path traversal is not allowed", failure.getMessage()); //$NON-NLS-1$
    }
}
