package com.codepilot1c.core.edt.cmi;

import static org.junit.Assert.assertEquals;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;

/** Runs unchanged against the previously installed classes: their validator rejects these operations. */
public class CommandInterfaceLayoutSurfaceTest {
    @Test public void validatorBindsTheCompleteVisibilityOperation() {
        var operation = Map.<String,Object>of("op", "set_visibility", "command", "Catalog.Book.StandardCommand.List", "visible", false);
        var payload = new MetadataRequestValidationService().normalizeMutateCommandInterfacePayload("Example", "Subsystem.Knowledge", List.of(operation));
        assertEquals(List.of(operation), payload.get("operations"));
    }
    @Test public void validatorBindsTheCompletePlacementOperation() {
        var operation = Map.<String,Object>of("op", "place_commands", "group", "CommandGroup.Knowledge", "commands", List.of("Catalog.Book.StandardCommand.List"));
        var payload = new MetadataRequestValidationService().normalizeMutateCommandInterfacePayload("Example", "Subsystem.Knowledge", List.of(operation));
        assertEquals(List.of(operation), payload.get("operations"));
    }
}
