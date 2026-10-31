package com.codepilot1c.core.edt.cmi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.function.Function;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.cmi.model.CommandInterface;
import com._1c.g5.v8.dt.cmi.model.CmiFactory;
import com._1c.g5.v8.dt.metadata.mdclass.AbstractCommandInterface;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com._1c.g5.v8.dt.metadata.mdclass.StandardCommandSource;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.mcore.Command;
import com._1c.g5.v8.dt.mcore.CommandGroup;
import com.codepilot1c.core.edt.BmObjectHelper;
import com.codepilot1c.core.edt.cmi.CommandInterfaceFragments.GroupEntry;
import com.codepilot1c.core.edt.cmi.CommandInterfaceFragments.VisibilityEntry;
import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;
import com.codepilot1c.core.edt.metadata.EdtMetadataService;
import com.codepilot1c.core.edt.metadata.MdObjectFqnResolver;
import com.codepilot1c.core.edt.metadata.MetadataConfigurationCollections;
import com.codepilot1c.core.edt.metadata.MetadataKind;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.metadata.SupportLockGuard;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Reads and changes the command interface of a subsystem or of the configuration root
 * ({@code CommandInterface.cmi}) through the EDT model instead of the text file.
 *
 * <p>The command interface is a separate BM top object referenced by
 * {@link Subsystem#getCommandInterface()} / {@link Configuration#getCommandInterface()}, the same way
 * role rights hang off a role; a mutation therefore runs through
 * {@link EdtMetadataService#mutateTopObjectAndExport} (BM write transaction + export of that object)
 * and is re-read afterwards: the tool reports what the model holds after the commit, not what it
 * meant to do.</p>
 */
public class EdtCommandInterfaceService {

    public static final String TOOL_MUTATE = "mutate_command_interface"; //$NON-NLS-1$

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(EdtCommandInterfaceService.class);

    private final EdtMetadataGateway gateway;
    private final EdtMetadataService metadataService;

    public EdtCommandInterfaceService() {
        this(new EdtMetadataGateway());
    }

    public EdtCommandInterfaceService(EdtMetadataGateway gateway) {
        this.gateway = gateway == null ? new EdtMetadataGateway() : gateway;
        this.metadataService = new EdtMetadataService(this.gateway);
    }

    // ---------------------------------------------------------------------------------------------
    // Read
    // ---------------------------------------------------------------------------------------------

    public Snapshot inspect(String projectName, String ownerFqn) {
        IProject project = requireProject(projectName);
        Owner owner = resolveOwner(project, ownerFqn);
        LOG.debug("inspect command interface project=%s owner=%s", projectName, owner.fqn()); //$NON-NLS-1$
        Boolean ownerShown = ownerInCommandInterface(owner);
        return executeRead(project, transaction -> {
            CommandInterface commandInterface = commandInterfaceOf(transaction.toTransactionObject(owner.object()));
            if (commandInterface == null) {
                return new Snapshot(projectName, owner.fqn(), null, owner.cmiPath(), false, ownerShown, 0, 0, 0,
                        List.of(), List.of(), List.of(), null, availableCommands(transaction.toTransactionObject(owner.object())));
            }
            Function<EObject, String> ownerOf = EdtCommandInterfaceService::topFqnOf;
            List<VisibilityEntry> visibility = CommandInterfaceFragments.visibility(commandInterface, ownerOf);
            int missing = CommandInterfaceFragments.countMissingCommand(commandInterface);
            int platform = countState(visibility, CommandInterfaceFragments.STATE_PLATFORM_COMMAND);
            int unresolved = countState(visibility, CommandInterfaceFragments.STATE_UNRESOLVED_COMMAND);
            List<String> hints = new ArrayList<>();
            if (missing > 0) {
                hints.add("Записей видимости без команды: " + missing //$NON-NLS-1$
                        + ". Убрать: edt_validate_request operation=mutate_command_interface," //$NON-NLS-1$
                        + " затем mutate_command_interface operations=[{op: prune_orphans}]."); //$NON-NLS-1$
            }
            if (Boolean.FALSE.equals(ownerShown)) {
                hints.add("Подсистема не выводится в командный интерфейс: этот интерфейс на экран не попадает." //$NON-NLS-1$
                        + " Удалить целиком: operations=[{op: delete}]."); //$NON-NLS-1$
            }
            if (platform > 0) {
                hints.add("Платформенных команд: " + platform //$NON-NLS-1$
                        + " (0:<uuid>, 100:<uuid>) - это не битые ссылки, их не удалять: запись скрывает команду платформы."); //$NON-NLS-1$
            }
            return new Snapshot(projectName, owner.fqn(), fqnOf(commandInterface), owner.cmiPath(), true, ownerShown,
                    missing, platform, unresolved, visibility,
                    CommandInterfaceFragments.placement(commandInterface, ownerOf),
                    CommandInterfaceFragments.order(commandInterface, ownerOf),
                    hints.isEmpty() ? null : String.join(" ", hints), //$NON-NLS-1$
                    availableCommands(transaction.toTransactionObject(owner.object())));
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Mutate
    // ---------------------------------------------------------------------------------------------

    public MutateResult mutate(String projectName, String ownerFqn, List<Map<String, Object>> operations,
            boolean allowSupportedObjectEdit) {
        if (operations == null || operations.isEmpty()) {
            throw new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_CHANGE,
                    "operations must contain at least one operation", false); //$NON-NLS-1$
        }
        List<String> names = new ArrayList<>();
        for (Map<String, Object> operation : operations) {
            names.add(CommandInterfaceFragments.requireKnownOperation(operation == null ? null : operation.get("op"))); //$NON-NLS-1$
        }
        IProject project = requireProject(projectName);
        Owner owner = resolveOwner(project, ownerFqn);
        if (project.getLocation() != null) {
            // Antipattern #70 gate: the command interface of a vendor subsystem on support with the
            // lock must not be changed without explicit consent.
            SupportLockGuard.checkProjectPath(project,
                    project.getLocation().toFile().toPath().resolve(owner.mdoPath()),
                    allowSupportedObjectEdit, TOOL_MUTATE, owner.fqn());
        }

        State before = readState(project, owner);
        boolean layout = names.stream().anyMatch(CommandInterfaceLayout::isLayout);
        if (layout) {
            if (!names.stream().allMatch(CommandInterfaceLayout::isLayout)) {
                throw CommandInterfaceLayout.invalid("Layout and destructive cleanup operations must use separate batches");
            }
            return mutateLayout(projectName, owner, before, operations);
        }
        boolean onlyDelete = names.stream().allMatch(CommandInterfaceFragments.OP_DELETE::equals);
        if (!before.exists() && !onlyDelete) {
            throw new MetadataOperationException(MetadataOperationCode.METADATA_NOT_FOUND,
                    "Command interface not found for " + owner.fqn(), false); //$NON-NLS-1$
        }
        List<String> details = new ArrayList<>();
        int removed = 0;
        boolean deleted = false;
        for (String name : names) {
            State current = readState(project, owner);
            if (CommandInterfaceFragments.OP_DELETE.equals(name)) {
                deleted |= deleteInterface(project, projectName, owner, current, details);
                continue;
            }
            if (!current.exists()) {
                details.add("prune_orphans: командного интерфейса уже нет, чистить нечего"); //$NON-NLS-1$
                continue;
            }
            if (current.missingCommand() == 0) {
                details.add("prune_orphans: записей видимости без команды нет, модель не менялась"); //$NON-NLS-1$
                continue;
            }
            int[] counter = new int[1];
            String exportFqn = current.commandInterfaceFqn() == null || current.commandInterfaceFqn().isBlank()
                    ? owner.fqn() : current.commandInterfaceFqn();
            metadataService.mutateTopObjectAndExport(projectName, exportFqn, transaction -> {
                CommandInterface commandInterface =
                        commandInterfaceOf(transaction.toTransactionObject(owner.object()));
                if (commandInterface == null) {
                    throw new MetadataOperationException(MetadataOperationCode.METADATA_NOT_FOUND,
                            "Command interface disappeared during the transaction: " + owner.fqn(), false); //$NON-NLS-1$
                }
                counter[0] = CommandInterfaceFragments.pruneMissingCommand(commandInterface);
            });
            removed += counter[0];
            details.add("prune_orphans: удалено записей видимости без команды: " + counter[0]); //$NON-NLS-1$
        }

        State after = readState(project, owner);
        if (after.missingCommand() != 0) {
            throw new MetadataOperationException(MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Post-verify failed for " + owner.fqn() + ": entries without command still in the model: " //$NON-NLS-1$ //$NON-NLS-2$
                            + after.missingCommand(), true);
        }
        LOG.info("mutate command interface owner=%s removed=%d before=%d after=%d deleted=%s", owner.fqn(), //$NON-NLS-1$
                Integer.valueOf(removed), Integer.valueOf(before.missingCommand()),
                Integer.valueOf(after.missingCommand()), Boolean.valueOf(deleted));
        return new MutateResult(projectName, owner.fqn(), before.commandInterfaceFqn(), owner.cmiPath(),
                before.missingCommand(), removed, after.missingCommand(), deleted, after.exists(), details);
    }

    private MutateResult mutateLayout(String projectName, Owner owner, State before,
            List<Map<String, Object>> operations) {
        List<Map<String, Object>> normalized = operations.stream().map(CommandInterfaceLayout::normalize).toList();
        String exportFqn = before.commandInterfaceFqn() == null || before.commandInterfaceFqn().isBlank()
                ? gateway.getTopObjectFqnGenerator().generateExternalPropertyFqn(owner.object(),
                        owner.object() instanceof Subsystem ? MdClassPackage.Literals.SUBSYSTEM__COMMAND_INTERFACE
                                : MdClassPackage.Literals.CONFIGURATION__COMMAND_INTERFACE)
                : before.commandInterfaceFqn();
        if (exportFqn == null || exportFqn.isBlank()) {
            throw CommandInterfaceLayout.invalid("Cannot generate the native command interface external FQN");
        }
        var namespace = gateway.getBmModelManager().getBmNamespace(requireProject(projectName));
        metadataService.mutateTopObjectAndExport(projectName, exportFqn, transaction -> {
            Function<String, Object> lookup = fqn -> transaction.getTopObjectByFqn(namespace, fqn);
            // Resolve the complete batch first: a bad last command cannot leave a partially applied menu.
            List<ResolvedLayout> resolved = new ArrayList<>();
            for (Map<String, Object> operation : normalized) {
                String op = (String) operation.get("op");
                if ("set_visibility".equals(op)) {
                    resolved.add(new ResolvedLayout(op, null,
                            List.of(resolveCommand(lookup, (String) operation.get("command"))),
                            (Boolean) operation.get("visible")));
                } else {
                    Object object = lookup.apply((String) operation.get("group"));
                    if (!(object instanceof com._1c.g5.v8.dt.metadata.mdclass.CommandGroup group) || group.eIsProxy()) {
                        throw CommandInterfaceLayout.invalid("Command group not found: " + operation.get("group"));
                    }
                    List<Command> commands = new ArrayList<>();
                    for (Object fqn : (List<?>) operation.get("commands")) {
                        commands.add(resolveCommand(lookup, (String) fqn));
                    }
                    resolved.add(new ResolvedLayout(op, group, commands, null));
                }
            }
            EObject txOwner = transaction.toTransactionObject(owner.object());
            CommandInterface cmi = commandInterfaceOf(txOwner);
            // EDT returns a typed proxy even when no CommandInterface.cmi exists yet. The native
            // BasicCICustomizationTask/CmiModelUtil path attaches a real external top object first.
            if (cmi == null || cmi.eIsProxy()) {
                Object existing = lookup.apply(exportFqn);
                if (existing instanceof CommandInterface attached && !attached.eIsProxy()) {
                    cmi = attached;
                } else {
                    CommandInterface created = CmiFactory.eINSTANCE.createCommandInterface();
                    if (!(created instanceof IBmObject bmObject)) {
                        throw CommandInterfaceLayout.invalid("Native command interface factory is not BM-aware");
                    }
                    transaction.attachTopObject(namespace, bmObject, exportFqn);
                    cmi = (CommandInterface) lookup.apply(exportFqn);
                }
                if (txOwner instanceof Subsystem subsystem) {
                    subsystem.setCommandInterface(cmi);
                } else {
                    ((Configuration) txOwner).setCommandInterface(cmi);
                }
            } else {
                cmi = transaction.toTransactionObject(cmi);
            }
            for (ResolvedLayout patch : resolved) {
                switch (patch.op()) {
                    case "set_visibility" -> CommandInterfaceLayout.setVisibility(cmi, patch.commands().get(0), patch.visible());
                    case "place_commands" -> CommandInterfaceLayout.place(cmi, patch.group(), patch.commands());
                    case "order_commands" -> CommandInterfaceLayout.order(cmi, patch.group(), patch.commands());
                    default -> throw CommandInterfaceLayout.invalid("Unsupported layout operation: " + patch.op());
                }
            }
        });
        State after = readState(requireProject(projectName), owner);
        if (!after.exists() || before.missingCommand() != after.missingCommand()) {
            throw new MetadataOperationException(MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Layout post-verification failed", true);
        }
        verifyLayout(normalized, inspect(projectName, owner.fqn()));
        return new MutateResult(projectName, owner.fqn(), after.commandInterfaceFqn(), owner.cmiPath(),
                before.missingCommand(), 0, after.missingCommand(), false, after.exists(),
                normalized.stream().map(operation -> operation.get("op").toString()).toList());
    }

    private static void verifyLayout(List<Map<String, Object>> operations, Snapshot after) {
        for (Map<String, Object> operation : operations) {
            String op = (String) operation.get("op");
            boolean verified;
            if ("set_visibility".equals(op)) {
                verified = after.visibility().stream().anyMatch(entry -> operation.get("command").equals(entry.command())
                        && operation.get("visible").equals(entry.visible()));
            } else {
                List<?> commands = (List<?>) operation.get("commands");
                List<GroupEntry> entries = "place_commands".equals(op) ? after.placement() : after.order();
                verified = entries.stream().anyMatch(entry -> operation.get("group").equals(entry.group())
                        && ("place_commands".equals(op) ? entry.commands().containsAll(commands)
                                : entry.commands().size() >= commands.size()
                                        && entry.commands().subList(0, commands.size()).equals(commands)));
            }
            if (!verified || after.commandInterfaceFqn() == null) {
                throw new MetadataOperationException(MetadataOperationCode.EDT_TRANSACTION_FAILED,
                        "Command interface layout was not persisted: " + operation, true);
            }
        }
    }

    private Command resolveCommand(Function<String, Object> lookup, String fqn) {
        String marker = ".StandardCommand.";
        int position = fqn.indexOf(marker);
        if (position > 0) {
            Object object = lookup.apply(fqn.substring(0, position));
            if (object instanceof StandardCommandSource source) {
                String name = fqn.substring(position + marker.length());
                for (var command : source.getStandardCommands()) {
                    if (name.equals(command.getName()) && !command.eIsProxy()) {
                        return command;
                    }
                }
            }
        } else {
            Object direct = lookup.apply(fqn);
            if (direct instanceof Command command && !command.eIsProxy()) {
                return command;
            }
            position = fqn.indexOf(".Command.");
            if (position > 0) {
                Object parent = lookup.apply(fqn.substring(0, position));
                if (parent instanceof MdObject md) {
                    MdObject child = MdObjectFqnResolver.findNestedChild(md, "Command", fqn.substring(position + 9));
                    if (child instanceof Command command && !command.eIsProxy()) {
                        return command;
                    }
                }
            }
        }
        throw CommandInterfaceLayout.invalid("Command not found in EDT model: " + fqn);
    }

    private List<String> availableCommands(EObject owner) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (owner instanceof Subsystem subsystem) {
            for (MdObject md : subsystem.getContent()) {
                if (md instanceof StandardCommandSource source) {
                    for (var command : source.getStandardCommands()) {
                        result.add(CommandInterfaceFragments.describeCommand(command, EdtCommandInterfaceService::topFqnOf));
                    }
                } else if (md instanceof Command command) {
                    result.add(CommandInterfaceFragments.describeCommand(command, EdtCommandInterfaceService::topFqnOf));
                }
            }
        }
        return List.copyOf(result);
    }

    private record ResolvedLayout(String op, CommandGroup group, List<Command> commands, Boolean visible) { }

    /**
     * Deletes the whole command interface object of a subsystem that is not shown in the command
     * interface ({@code includeInCommandInterface=false}): such an interface never reaches the screen, so
     * removing it cannot change navigation. A shown subsystem and the configuration root are refused -
     * there the deletion would reset the section navigation. Idempotent: no object - nothing to do.
     */
    private boolean deleteInterface(IProject project, String projectName, Owner owner, State current,
            List<String> details) {
        if (!(owner.object() instanceof Subsystem subsystem)) {
            throw new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_CHANGE,
                    "delete: командный интерфейс корня конфигурации выводится всегда, удаление не поддерживается", //$NON-NLS-1$
                    false);
        }
        if (subsystem.isIncludeInCommandInterface()) {
            throw new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_CHANGE,
                    "delete: подсистема " + owner.fqn() //$NON-NLS-1$
                            + " выводится в командный интерфейс (includeInCommandInterface=true): удаление сбросило бы" //$NON-NLS-1$
                            + " навигацию раздела. Удаляется только интерфейс подсистемы вне командного интерфейса", //$NON-NLS-1$
                    false);
        }
        IFile file = project.getFile(owner.cmiPath());
        if (!current.exists()) {
            details.add(file.exists()
                    ? "delete: в модели интерфейса нет, а файл " + owner.cmiPath() //$NON-NLS-1$
                            + " на диске есть - рассинхрон BM, файл не тронут" //$NON-NLS-1$
                    : "delete: командного интерфейса нет, удалять нечего"); //$NON-NLS-1$
            return false;
        }
        metadataService.mutateTopObjectAndExport(projectName, owner.fqn(), transaction -> {
            EObject txOwner = transaction.toTransactionObject(owner.object());
            CommandInterface commandInterface = commandInterfaceOf(txOwner);
            if (commandInterface == null) {
                return;
            }
            // reference first, then the top object: a detached object must not stay referenced
            ((Subsystem) txOwner).setCommandInterface(null);
            if (commandInterface instanceof IBmObject bmObject) {
                transaction.detachTopObject(bmObject);
            }
        });
        try {
            if (file.exists()) {
                file.delete(true, null);
            }
        } catch (CoreException e) {
            throw new MetadataOperationException(MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "delete: не удалось удалить файл " + owner.cmiPath() + ": " + e.getMessage(), true, e); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (readState(project, owner).exists()) {
            throw new MetadataOperationException(MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Post-verify failed for " + owner.fqn() + ": command interface still in the model after delete", true); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (file.exists()) {
            throw new MetadataOperationException(MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Post-verify failed: file still on disk after delete: " + owner.cmiPath(), true); //$NON-NLS-1$
        }
        details.add("delete: командный интерфейс удален из модели, файл " + owner.cmiPath() + " удален"); //$NON-NLS-1$ //$NON-NLS-2$
        return true;
    }

    private static Boolean ownerInCommandInterface(Owner owner) {
        if (owner.object() instanceof Subsystem subsystem) {
            try {
                return Boolean.valueOf(subsystem.isIncludeInCommandInterface());
            } catch (RuntimeException e) {
                return null;
            }
        }
        return owner.object() instanceof Configuration ? Boolean.TRUE : null;
    }

    private static int countState(List<VisibilityEntry> entries, String state) {
        int count = 0;
        for (VisibilityEntry entry : entries) {
            if (state.equals(entry.state())) {
                count++;
            }
        }
        return count;
    }

    // ---------------------------------------------------------------------------------------------
    // Resolution
    // ---------------------------------------------------------------------------------------------

    private Owner resolveOwner(IProject project, String ownerFqn) {
        String normalized = CommandInterfaceFragments.normalizeOwnerFqn(ownerFqn);
        Configuration configuration = gateway.getConfigurationProvider().getConfiguration(project);
        if (configuration == null) {
            throw new MetadataOperationException(MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                    "Configuration unavailable for project: " + project.getName(), false); //$NON-NLS-1$
        }
        if (CommandInterfaceFragments.CONFIGURATION.equals(normalized)) {
            return new Owner(configuration, CommandInterfaceFragments.CONFIGURATION,
                    "src/Configuration/Configuration.mdo", "src/Configuration/CommandInterface.cmi"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String[] parts = normalized.split("\\."); //$NON-NLS-1$
        MdObject current = null;
        for (MdObject candidate : MetadataConfigurationCollections.topLevelForKind(configuration, MetadataKind.SUBSYSTEM)) {
            if (candidate != null && parts[1].equalsIgnoreCase(candidate.getName())) {
                current = candidate;
                break;
            }
        }
        StringBuilder fqn = new StringBuilder();
        StringBuilder folder = new StringBuilder("src/Subsystems/"); //$NON-NLS-1$
        if (current != null) {
            fqn.append(CommandInterfaceFragments.SUBSYSTEM).append('.').append(current.getName());
            folder.append(current.getName());
        }
        for (int i = 2; current != null && i + 1 < parts.length; i += 2) {
            // Subsystem.subsystems is a non-containment reference in the EDT model; the shared
            // resolver of the write path knows that (a containment-only walk would miss the child).
            current = MdObjectFqnResolver.findNestedChild(current, CommandInterfaceFragments.SUBSYSTEM, parts[i + 1]);
            if (current != null) {
                fqn.append('.').append(CommandInterfaceFragments.SUBSYSTEM).append('.').append(current.getName());
                folder.append("/Subsystems/").append(current.getName()); //$NON-NLS-1$
            }
        }
        if (!(current instanceof Subsystem subsystem)) {
            throw new MetadataOperationException(MetadataOperationCode.METADATA_NOT_FOUND,
                    "Subsystem not found: " + normalized, false); //$NON-NLS-1$
        }
        return new Owner(subsystem, fqn.toString(),
                folder + "/" + subsystem.getName() + ".mdo", //$NON-NLS-1$ //$NON-NLS-2$
                folder + "/CommandInterface.cmi"); //$NON-NLS-1$
    }

    private static CommandInterface commandInterfaceOf(EObject owner) {
        AbstractCommandInterface commandInterface = null;
        if (owner instanceof Subsystem subsystem) {
            commandInterface = subsystem.getCommandInterface();
        } else if (owner instanceof Configuration configuration) {
            commandInterface = configuration.getCommandInterface();
        }
        return commandInterface instanceof CommandInterface result ? result : null;
    }

    private State readState(IProject project, Owner owner) {
        return executeRead(project, transaction -> {
            CommandInterface commandInterface = commandInterfaceOf(transaction.toTransactionObject(owner.object()));
            if (commandInterface == null) {
                return new State(false, null, 0);
            }
            return new State(true, fqnOf(commandInterface),
                    CommandInterfaceFragments.countMissingCommand(commandInterface));
        });
    }

    private <T> T executeRead(IProject project, ReadTransactionTask<T> task) {
        try {
            return gateway.getBmModelManager().executeReadOnlyTask(project, task::execute);
        } catch (MetadataOperationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MetadataOperationException(MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Command interface read failed: " + e.getMessage(), true, e); //$NON-NLS-1$
        }
    }

    private static String fqnOf(EObject object) {
        if (object instanceof IBmObject bmObject) {
            try {
                return bmObject.bmGetFqn();
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    private static String topFqnOf(EObject object) {
        return object instanceof IBmObject bmObject ? BmObjectHelper.safeTopFqn(bmObject) : null;
    }

    private IProject requireProject(String projectName) {
        if (projectName == null || projectName.isBlank()) {
            throw new MetadataOperationException(MetadataOperationCode.PROJECT_NOT_FOUND,
                    "project is required", false); //$NON-NLS-1$
        }
        IProject project = gateway.resolveProject(projectName);
        if (project == null || !project.exists()) {
            throw new MetadataOperationException(MetadataOperationCode.PROJECT_NOT_FOUND,
                    "Project not found: " + projectName, false); //$NON-NLS-1$
        }
        return project;
    }

    @FunctionalInterface
    private interface ReadTransactionTask<T> {
        T execute(IBmTransaction transaction);
    }

    private record Owner(EObject object, String fqn, String mdoPath, String cmiPath) {
    }

    private record State(boolean exists, String commandInterfaceFqn, int missingCommand) {
    }

    /** What {@code inspect_command_interface} returns; {@code file} is the expected workspace location. */
    public record Snapshot(String project, String ownerFqn, String commandInterfaceFqn, String file, boolean exists,
            Boolean ownerInCommandInterface, int entriesWithoutCommand, int platformCommands, int unresolvedCommands,
            List<VisibilityEntry> visibility, List<GroupEntry> placement, List<GroupEntry> order, String hint,
            List<String> availableCommands) {
    }

    public record MutateResult(String project, String ownerFqn, String commandInterfaceFqn, String file,
            int entriesWithoutCommandBefore, int removed, int entriesWithoutCommandAfter, boolean deleted,
            boolean existsAfter, List<String> details) {
    }
}
