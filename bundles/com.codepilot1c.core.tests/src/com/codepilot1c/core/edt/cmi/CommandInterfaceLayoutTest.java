package com.codepilot1c.core.edt.cmi;

import static org.junit.Assert.*;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.common.util.URI;
import com._1c.g5.v8.dt.cmi.model.CmiFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;

public class CommandInterfaceLayoutTest {
    @Test public void normalizationRetainsTheExactVisibilityPayload() {
        var input = Map.<String,Object>of("op", "set_visibility", "command", "Catalog.Example.StandardCommand.List", "visible", false);
        assertEquals(input, CommandInterfaceLayout.normalize(input));
        var payload = new MetadataRequestValidationService().normalizeMutateCommandInterfacePayload("Example", "Subsystem.Example", List.of(input));
        assertEquals(List.of(input), payload.get("operations"));
        assertNotEquals(payload, new MetadataRequestValidationService().normalizeMutateCommandInterfacePayload("Example", "Subsystem.Example",
                List.of(Map.of("op", "set_visibility", "command", "Catalog.Other.StandardCommand.List", "visible", false))));
    }
    @Test public void normalizationPreservesCommandOrderAndDistinguishesGroups() {
        var patch = Map.<String,Object>of("op", "place_commands", "group", "CommandGroup.Knowledge", "commands", List.of("Catalog.Book.StandardCommand.List", "Catalog.Chunk.StandardCommand.List"));
        assertEquals(patch, CommandInterfaceLayout.normalize(patch));
        assertNotEquals(patch, CommandInterfaceLayout.normalize(Map.of("op", "place_commands", "group", "CommandGroup.Other", "commands", patch.get("commands"))));
    }
    @Test public void malformedAndDuplicateArgumentsAreRefused() {
        for (var patch : List.of(
                Map.<String,Object>of("op", "set_visibility", "command", "X", "visible", "false"),
                Map.<String,Object>of("op", "set_visibility", "command", "X", "visible", true, "role", "Admin"),
                Map.<String,Object>of("op", "place_commands", "group", "CommandGroup.G", "commands", List.of("X", "X")),
                Map.<String,Object>of("op", "order_commands", "group", "CommandGroup.G", "commands", List.of()),
                Map.<String,Object>of("op", "place_commands", "group", "StandardCommandGroup.NavigationPanelOrdinary", "commands", List.of("X")))) {
            assertThrows(MetadataOperationException.class, () -> CommandInterfaceLayout.normalize(patch));
        }
    }
    @Test public void visibilityUpdatesInPlaceAndPreservesUnrelatedPlatformEntry() {
        var cmi = CmiFactory.eINSTANCE.createCommandInterface();
        var command = MdClassFactory.eINSTANCE.createStandardCommand(); command.setName("List");
        var platform = MdClassFactory.eINSTANCE.createStandardCommand();
        ((InternalEObject) platform).eSetProxyURI(URI.createURI("0:11111111-1111-1111-1111-111111111111"));
        CommandInterfaceLayout.setVisibility(cmi, platform, false);
        var untouched = cmi.getCommandsVisibility().getVisibilityFragments().get(0);
        CommandInterfaceLayout.setVisibility(cmi, command, true);
        var existing = cmi.getCommandsVisibility().getVisibilityFragments().get(1);
        var existingFlag = existing.getVisible();
        var roleOverride = MdClassFactory.eINSTANCE.createForRoleType();
        existingFlag.getFor().add(roleOverride);
        CommandInterfaceLayout.setVisibility(cmi, command, false);
        assertEquals(2, cmi.getCommandsVisibility().getVisibilityFragments().size());
        assertSame(untouched, cmi.getCommandsVisibility().getVisibilityFragments().get(0));
        assertSame(existingFlag, existing.getVisible());
        assertEquals(List.of(roleOverride), existing.getVisible().getFor());
        assertFalse(existing.getVisible().isCommon());
        assertFalse(untouched.getVisible().isCommon());
    }
    @Test public void placementMovesOnlyRequestedCommandsAndRemovesTheirOldOrder() {
        var cmi = CmiFactory.eINSTANCE.createCommandInterface();
        var old = MdClassFactory.eINSTANCE.createCommandGroup();
        var destination = MdClassFactory.eINSTANCE.createCommandGroup();
        var first = MdClassFactory.eINSTANCE.createStandardCommand();
        var keep = MdClassFactory.eINSTANCE.createStandardCommand();
        CommandInterfaceLayout.place(cmi, old, List.of(first, keep));
        CommandInterfaceLayout.order(cmi, old, List.of(keep, first));
        CommandInterfaceLayout.place(cmi, destination, List.of(first));
        assertEquals(List.of(keep), cmi.getCommandsPlacement().getPlacementFragments().get(0).getCommands());
        assertEquals(List.of(first), cmi.getCommandsPlacement().getPlacementFragments().get(1).getCommands());
        assertEquals(List.of(keep), cmi.getCommandsOrder().getOrderFragments().get(0).getCommands());
        CommandInterfaceLayout.place(cmi, destination, List.of(first));
        assertEquals(2, cmi.getCommandsPlacement().getPlacementFragments().size());
        assertEquals(1, cmi.getCommandsPlacement().getPlacementFragments().get(1).getCommands().size());
    }
    @Test public void orderingPreservesUnspecifiedTailAndOtherGroups() {
        var cmi = CmiFactory.eINSTANCE.createCommandInterface();
        var group = MdClassFactory.eINSTANCE.createCommandGroup();
        var other = MdClassFactory.eINSTANCE.createCommandGroup();
        var a = MdClassFactory.eINSTANCE.createStandardCommand();
        var b = MdClassFactory.eINSTANCE.createStandardCommand();
        var c = MdClassFactory.eINSTANCE.createStandardCommand();
        CommandInterfaceLayout.order(cmi, group, List.of(a,b,c));
        CommandInterfaceLayout.order(cmi, other, List.of(c));
        CommandInterfaceLayout.order(cmi, group, List.of(b));
        assertEquals(List.of(b,a,c), cmi.getCommandsOrder().getOrderFragments().get(0).getCommands());
        assertEquals(List.of(c), cmi.getCommandsOrder().getOrderFragments().get(1).getCommands());
    }
}
