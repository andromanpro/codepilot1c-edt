package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Optional;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.InternalEObject;
import org.junit.Test;

import com._1c.g5.v8.dt.mcore.CommandGroup;
import com._1c.g5.v8.dt.mcore.CommandGroupCategory;
import com._1c.g5.v8.dt.mcore.StandardCommandGroup;
import com._1c.g5.v8.dt.platform.IEObjectStandardCommandGroupNames;

public class CommandGroupResolverTest {

    private final CommandGroupResolver resolver = new CommandGroupResolver();

    @Test
    public void exposesAllPublicStandardCommandGroupNames() {
        List<String> values = resolver.availableStandardCommandGroups();

        assertTrue(values.contains(IEObjectStandardCommandGroupNames.STD_GROUP_NAVIGATION_PANEL_IMPORTANT));
        assertTrue(values.contains(IEObjectStandardCommandGroupNames.STD_GROUP_NAVIGATION_PANEL_ORDINARY));
        assertTrue(values.contains(IEObjectStandardCommandGroupNames.STD_GROUP_NAVIGATION_PANEL_SEE_ALSO));
        assertTrue(values.contains(IEObjectStandardCommandGroupNames.STD_GROUP_ACTIONS_PANEL_CREATE));
        assertTrue(values.contains(IEObjectStandardCommandGroupNames.STD_GROUP_ACTIONS_PANEL_REPORTS));
        assertTrue(values.contains(IEObjectStandardCommandGroupNames.STD_GROUP_ACTIONS_PANEL_TOOLS));
        assertTrue(values.contains(IEObjectStandardCommandGroupNames.STD_GROUP_FORM_NAVIGATION_PANEL_IMPORTANT));
        assertTrue(values.contains(IEObjectStandardCommandGroupNames.STD_GROUP_FORM_NAVIGATION_PANEL_GO_TO));
        assertTrue(values.contains(IEObjectStandardCommandGroupNames.STD_GROUP_FORM_NAVIGATION_PANEL_SEE_ALSO));
        assertTrue(values.contains(IEObjectStandardCommandGroupNames.STD_GROUP_FORM_COMMAND_BAR_IMPORTANT));
        assertTrue(values.contains(IEObjectStandardCommandGroupNames.STD_GROUP_FORM_COMMAND_BAR_CREATE_BASED_ON));
    }

    @Test
    public void normalizesShortAndQualifiedStandardNames() {
        assertEquals("FormCommandBarImportant", //$NON-NLS-1$
                resolver.normalizeForValidation("FormCommandBarImportant")); //$NON-NLS-1$
        assertEquals("FormCommandBarImportant", //$NON-NLS-1$
                resolver.normalizeForValidation("StandardCommandGroup.FormCommandBarImportant")); //$NON-NLS-1$
        assertEquals("NavigationPanelOrdinary", //$NON-NLS-1$
                resolver.normalizeForValidation("navigationpanelordinary")); //$NON-NLS-1$
    }

    @Test
    public void leavesCustomBareGroupNamesForMetadataReferenceResolution() {
        assertEquals("MyCustomGroup", resolver.normalizeForValidation("MyCustomGroup")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(resolver.resolveStandardCommandGroup("MyCustomGroup").isPresent()); //$NON-NLS-1$
    }

    @Test
    public void rejectsUnknownQualifiedStandardGroupWithAvailableValues() {
        try {
            resolver.normalizeForValidation("StandardCommandGroup.UnknownGroup"); //$NON-NLS-1$
            fail("Expected INVALID_PROPERTY_VALUE for unknown qualified standard group"); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.INVALID_PROPERTY_VALUE, e.getCode());
            assertTrue(e.getMessage().contains("Unknown StandardCommandGroup")); //$NON-NLS-1$
            assertTrue(e.getMessage().contains("FormCommandBarImportant")); //$NON-NLS-1$
            assertTrue(e.getMessage().contains("NavigationPanelOrdinary")); //$NON-NLS-1$
        }
    }

    @Test
    public void mapsCategoriesForStandardGroups() {
        assertEquals(CommandGroupCategory.NAVIGATION_PANEL,
                resolver.categoryFor(IEObjectStandardCommandGroupNames.STD_GROUP_NAVIGATION_PANEL_ORDINARY));
        assertEquals(CommandGroupCategory.ACTIONS_PANEL,
                resolver.categoryFor(IEObjectStandardCommandGroupNames.STD_GROUP_ACTIONS_PANEL_TOOLS));
        assertEquals(CommandGroupCategory.FORM_NAVIGATION_PANEL,
                resolver.categoryFor(IEObjectStandardCommandGroupNames.STD_GROUP_FORM_NAVIGATION_PANEL_GO_TO));
        assertEquals(CommandGroupCategory.FORM_COMMAND_BAR,
                resolver.categoryFor(IEObjectStandardCommandGroupNames.STD_GROUP_FORM_COMMAND_BAR_IMPORTANT));
    }

    @Test
    public void createsPlatformProxyForStandardGroupWithoutVersionSuffix() {
        Optional<CommandGroup> resolved = resolver.resolveStandardCommandGroup(
                "StandardCommandGroup.FormCommandBarImportant"); //$NON-NLS-1$

        assertTrue(resolved.isPresent());
        assertTrue(resolved.get() instanceof StandardCommandGroup);
        StandardCommandGroup group = (StandardCommandGroup) resolved.get();
        assertEquals("FormCommandBarImportant", group.getName()); //$NON-NLS-1$
        assertEquals(CommandGroupCategory.FORM_COMMAND_BAR, group.getCategory());
        assertEquals(1, group.getPriority());
        assertTrue(((InternalEObject) group).eIsProxy());

        URI uri = ((InternalEObject) group).eProxyURI();
        assertEquals("v8:/CommandGroup/Std#/FormCommandBarImportant", uri.toString()); //$NON-NLS-1$
    }
}
