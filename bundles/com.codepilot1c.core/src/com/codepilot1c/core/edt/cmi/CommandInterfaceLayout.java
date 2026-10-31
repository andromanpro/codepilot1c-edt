package com.codepilot1c.core.edt.cmi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com._1c.g5.v8.dt.cmi.model.CmiFactory;
import com._1c.g5.v8.dt.cmi.model.CommandInterface;
import com._1c.g5.v8.dt.cmi.model.CommandsOrderFragment;
import com._1c.g5.v8.dt.cmi.model.CommandsPlacementFragment;
import com._1c.g5.v8.dt.cmi.model.CommandsVisibilityFragment;
import com._1c.g5.v8.dt.mcore.Command;
import com._1c.g5.v8.dt.mcore.CommandGroup;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/** Explicit patches of a command interface; unrelated and platform entries are preserved. */
public final class CommandInterfaceLayout {
    private CommandInterfaceLayout() { }

    public static boolean isLayout(String op) {
        return Set.of("set_visibility", "place_commands", "order_commands").contains(op);
    }

    public static Map<String, Object> normalize(Map<String, Object> operation) {
        String op = CommandInterfaceFragments.requireKnownOperation(operation == null ? null : operation.get("op"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("op", op);
        Set<String> allowed = new LinkedHashSet<>(List.of("op"));
        if ("set_visibility".equals(op)) {
            allowed.addAll(List.of("command", "visible"));
            result.put("command", requiredString(operation.get("command"), "command"));
            if (!(operation.get("visible") instanceof Boolean)) {
                throw invalid("visible must be a boolean");
            }
            result.put("visible", operation.get("visible"));
        } else if (isLayout(op)) {
            allowed.addAll(List.of("group", "commands"));
            String group = requiredString(operation.get("group"), "group");
            if (!group.startsWith("CommandGroup.")) {
                throw invalid("group must identify an existing custom CommandGroup.<Name>");
            }
            result.put("group", group);
            if (!(operation.get("commands") instanceof List<?> commands) || commands.isEmpty()) {
                throw invalid("commands must be a nonempty array of unique command FQNs");
            }
            List<String> normalized = new ArrayList<>();
            for (Object command : commands) {
                String fqn = requiredString(command, "commands");
                if (normalized.contains(fqn)) {
                    throw invalid("Duplicate command: " + fqn);
                }
                normalized.add(fqn);
            }
            result.put("commands", normalized);
        }
        for (String key : operation.keySet()) {
            if (!allowed.contains(key)) {
                throw invalid("Unsupported field for " + op + ": " + key);
            }
        }
        return result;
    }

    private static String requiredString(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw invalid(field + " must be a nonempty FQN string");
        }
        return text.trim();
    }

    public static MetadataOperationException invalid(String message) {
        return new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_CHANGE, message, false);
    }

    /** Patches the common flag, retaining existing per-role visibility overrides. */
    public static void setVisibility(CommandInterface cmi, Command command, boolean visible) {
        if (cmi.getCommandsVisibility() == null) {
            cmi.setCommandsVisibility(CmiFactory.eINSTANCE.createCommandsVisibility());
        }
        List<CommandsVisibilityFragment> matches = cmi.getCommandsVisibility().getVisibilityFragments().stream()
                .filter(fragment -> fragment.getCommand() == command).toList();
        if (matches.isEmpty()) {
            CommandsVisibilityFragment fragment = CmiFactory.eINSTANCE.createCommandsVisibilityFragment();
            fragment.setCommand(command);
            cmi.getCommandsVisibility().getVisibilityFragments().add(fragment);
            matches = List.of(fragment);
        }
        for (CommandsVisibilityFragment fragment : matches) {
            if (fragment.getVisible() == null) {
                fragment.setVisible(MdClassFactory.eINSTANCE.createAdjustableBoolean());
            }
            fragment.getVisible().setCommon(visible);
        }
    }

    /** Moves just the requested commands; leaves unrelated commands and their relative order intact. */
    public static void place(CommandInterface cmi, CommandGroup group, List<Command> commands) {
        if (cmi.getCommandsPlacement() == null) {
            cmi.setCommandsPlacement(CmiFactory.eINSTANCE.createCommandsPlacement());
        }
        CommandsPlacementFragment destination = cmi.getCommandsPlacement().getPlacementFragments().stream()
                .filter(fragment -> fragment.getGroup() == group).findFirst().orElse(null);
        if (destination == null) {
            destination = CmiFactory.eINSTANCE.createCommandsPlacementFragment();
            destination.setGroup(group);
            cmi.getCommandsPlacement().getPlacementFragments().add(destination);
        }
        for (CommandsPlacementFragment fragment : cmi.getCommandsPlacement().getPlacementFragments()) {
            if (fragment != destination) {
                fragment.getCommands().removeAll(commands);
            }
        }
        if (cmi.getCommandsOrder() != null) {
            for (CommandsOrderFragment fragment : cmi.getCommandsOrder().getOrderFragments()) {
                if (fragment.getGroup() != group) {
                    fragment.getCommands().removeAll(commands);
                }
            }
        }
        for (Command command : commands) {
            if (!destination.getCommands().contains(command)) {
                destination.getCommands().add(command);
            }
        }
    }

    /** Requested commands lead the group; unspecified order entries retain their relative order. */
    public static void order(CommandInterface cmi, CommandGroup group, List<Command> commands) {
        if (cmi.getCommandsOrder() == null) {
            cmi.setCommandsOrder(CmiFactory.eINSTANCE.createCommandsOrder());
        }
        CommandsOrderFragment destination = cmi.getCommandsOrder().getOrderFragments().stream()
                .filter(fragment -> fragment.getGroup() == group).findFirst().orElse(null);
        if (destination == null) {
            destination = CmiFactory.eINSTANCE.createCommandsOrderFragment();
            destination.setGroup(group);
            cmi.getCommandsOrder().getOrderFragments().add(destination);
        }
        List<Command> tail = new ArrayList<>(destination.getCommands());
        tail.removeAll(commands);
        destination.getCommands().clear();
        destination.getCommands().addAll(commands);
        destination.getCommands().addAll(tail);
    }
}
