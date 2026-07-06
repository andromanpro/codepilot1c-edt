package com.codepilot1c.core.edt.metadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;

import com._1c.g5.v8.dt.mcore.CommandGroup;
import com._1c.g5.v8.dt.mcore.CommandGroupCategory;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.StandardCommandGroup;
import com._1c.g5.v8.dt.platform.IEObjectStandardCommandGroupNames;

/**
 * Resolves 1C platform standard command groups separately from metadata command groups.
 */
public final class CommandGroupResolver {

    private static final String STANDARD_PREFIX = "StandardCommandGroup."; //$NON-NLS-1$
    private static final URI STANDARD_GROUP_RESOURCE = URI.createURI("v8:/CommandGroup/Std"); //$NON-NLS-1$

    private static final List<Definition> DEFINITIONS = List.of(
            new Definition(IEObjectStandardCommandGroupNames.STD_GROUP_NAVIGATION_PANEL_IMPORTANT,
                    "Важное", CommandGroupCategory.NAVIGATION_PANEL, 1), //$NON-NLS-1$
            new Definition(IEObjectStandardCommandGroupNames.STD_GROUP_NAVIGATION_PANEL_ORDINARY,
                    "Обычное", CommandGroupCategory.NAVIGATION_PANEL, 2), //$NON-NLS-1$
            new Definition(IEObjectStandardCommandGroupNames.STD_GROUP_NAVIGATION_PANEL_SEE_ALSO,
                    "См. также", CommandGroupCategory.NAVIGATION_PANEL, 3), //$NON-NLS-1$
            new Definition(IEObjectStandardCommandGroupNames.STD_GROUP_ACTIONS_PANEL_CREATE,
                    "Создать", CommandGroupCategory.ACTIONS_PANEL, 1), //$NON-NLS-1$
            new Definition(IEObjectStandardCommandGroupNames.STD_GROUP_ACTIONS_PANEL_REPORTS,
                    "Отчеты", CommandGroupCategory.ACTIONS_PANEL, 2), //$NON-NLS-1$
            new Definition(IEObjectStandardCommandGroupNames.STD_GROUP_ACTIONS_PANEL_TOOLS,
                    "Сервис", CommandGroupCategory.ACTIONS_PANEL, 3), //$NON-NLS-1$
            new Definition(IEObjectStandardCommandGroupNames.STD_GROUP_FORM_NAVIGATION_PANEL_IMPORTANT,
                    "Важное", CommandGroupCategory.FORM_NAVIGATION_PANEL, 1), //$NON-NLS-1$
            new Definition(IEObjectStandardCommandGroupNames.STD_GROUP_FORM_NAVIGATION_PANEL_GO_TO,
                    "Перейти", CommandGroupCategory.FORM_NAVIGATION_PANEL, 2), //$NON-NLS-1$
            new Definition(IEObjectStandardCommandGroupNames.STD_GROUP_FORM_NAVIGATION_PANEL_SEE_ALSO,
                    "См. также", CommandGroupCategory.FORM_NAVIGATION_PANEL, 3), //$NON-NLS-1$
            new Definition(IEObjectStandardCommandGroupNames.STD_GROUP_FORM_COMMAND_BAR_IMPORTANT,
                    "Важное", CommandGroupCategory.FORM_COMMAND_BAR, 1), //$NON-NLS-1$
            new Definition(IEObjectStandardCommandGroupNames.STD_GROUP_FORM_COMMAND_BAR_CREATE_BASED_ON,
                    "Создать на основании", CommandGroupCategory.FORM_COMMAND_BAR, 2) //$NON-NLS-1$
    );

    private static final Map<String, Definition> DEFINITIONS_BY_TOKEN = buildIndex();
    private static final List<String> AVAILABLE_NAMES = buildAvailableNames();

    public List<String> availableStandardCommandGroups() {
        return AVAILABLE_NAMES;
    }

    public String normalizeForValidation(Object value) {
        String raw = rawString(value);
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String stripped = stripStandardPrefix(raw);
        Definition definition = DEFINITIONS_BY_TOKEN.get(normalizeToken(stripped));
        if (definition != null) {
            return definition.name();
        }
        if (hasStandardPrefix(raw)) {
            throw unknownStandardGroup(raw);
        }
        return raw.trim();
    }

    public Optional<CommandGroup> resolveStandardCommandGroup(Object value) {
        return resolveDefinition(value).map(this::createDetachedGroup);
    }

    public Optional<CommandGroup> resolveStandardCommandGroup(ResourceSet resourceSet, Object value) {
        Optional<Definition> definition = resolveDefinition(value);
        if (definition.isEmpty()) {
            return Optional.empty();
        }
        if (resourceSet != null) {
            Resource resource = resourceSet.getResource(STANDARD_GROUP_RESOURCE, false);
            if (resource == null) {
                try {
                    resource = resourceSet.getResource(STANDARD_GROUP_RESOURCE, true);
                } catch (RuntimeException e) {
                    resource = null;
                }
            }
            CommandGroup group = findStandardCommandGroup(resource, definition.get());
            if (group != null) {
                return Optional.of(group);
            }
        }
        return Optional.of(createDetachedGroup(definition.get()));
    }

    private Optional<Definition> resolveDefinition(Object value) {
        String raw = rawString(value);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String stripped = stripStandardPrefix(raw);
        Definition definition = DEFINITIONS_BY_TOKEN.get(normalizeToken(stripped));
        if (definition == null) {
            if (hasStandardPrefix(raw)) {
                throw unknownStandardGroup(raw);
            }
            return Optional.empty();
        }
        return Optional.of(definition);
    }

    public CommandGroupCategory categoryFor(String name) {
        Definition definition = DEFINITIONS_BY_TOKEN.get(normalizeToken(stripStandardPrefix(name)));
        if (definition == null) {
            throw unknownStandardGroup(name);
        }
        return definition.category();
    }

    private CommandGroup createDetachedGroup(Definition definition) {
        StandardCommandGroup group = McoreFactory.eINSTANCE.createStandardCommandGroup();
        group.setName(definition.name());
        group.setNameRu(definition.nameRu());
        group.setCategory(definition.category());
        group.setPriority(definition.priority());
        return group;
    }

    private CommandGroup findStandardCommandGroup(Resource resource, Definition definition) {
        if (resource == null) {
            return null;
        }
        for (EObject root : resource.getContents()) {
            CommandGroup direct = matchingCommandGroup(root, definition);
            if (direct != null) {
                return direct;
            }
            TreeIterator<EObject> contents = root.eAllContents();
            while (contents.hasNext()) {
                CommandGroup nested = matchingCommandGroup(contents.next(), definition);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private CommandGroup matchingCommandGroup(EObject object, Definition definition) {
        if (object instanceof StandardCommandGroup group && definition.name().equals(group.getName())) {
            return group;
        }
        return null;
    }

    private MetadataOperationException unknownStandardGroup(String value) {
        return new MetadataOperationException(
                MetadataOperationCode.INVALID_PROPERTY_VALUE,
                "Unknown StandardCommandGroup: " + value + ". Available values: " //$NON-NLS-1$ //$NON-NLS-2$
                        + String.join(", ", AVAILABLE_NAMES), //$NON-NLS-1$
                false);
    }

    private static Map<String, Definition> buildIndex() {
        Map<String, Definition> index = new LinkedHashMap<>();
        for (Definition definition : DEFINITIONS) {
            index.put(normalizeToken(definition.name()), definition);
        }
        return Collections.unmodifiableMap(index);
    }

    private static List<String> buildAvailableNames() {
        List<String> names = new ArrayList<>();
        for (Definition definition : DEFINITIONS) {
            names.add(definition.name());
        }
        return Collections.unmodifiableList(names);
    }

    private static boolean hasStandardPrefix(String value) {
        return value != null && value.trim().regionMatches(true, 0, STANDARD_PREFIX, 0, STANDARD_PREFIX.length());
    }

    private static String stripStandardPrefix(String value) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed != null && hasStandardPrefix(trimmed)) {
            return trimmed.substring(STANDARD_PREFIX.length());
        }
        return trimmed;
    }

    private static String rawString(Object value) {
        if (value == null) {
            return null;
        }
        return String.valueOf(value).trim();
    }

    private static String normalizeToken(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); //$NON-NLS-1$
    }

    private record Definition(String name, String nameRu, CommandGroupCategory category, int priority) {
    }
}
