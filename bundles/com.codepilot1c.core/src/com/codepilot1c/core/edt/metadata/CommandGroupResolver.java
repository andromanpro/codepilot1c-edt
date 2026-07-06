package com.codepilot1c.core.edt.metadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;

import com._1c.g5.v8.dt.mcore.CommandGroup;
import com._1c.g5.v8.dt.mcore.CommandGroupCategory;
import com._1c.g5.v8.dt.mcore.StandardCommandGroup;
import com._1c.g5.v8.dt.platform.IEObjectStandardCommandGroupNames;

/**
 * Resolves 1C platform standard command groups separately from metadata command groups.
 */
public final class CommandGroupResolver {

    private static final String STANDARD_PREFIX = "StandardCommandGroup."; //$NON-NLS-1$
    private static final String STANDARD_GROUP_RESOURCE_PREFIX = "v8:/CommandGroup/Std"; //$NON-NLS-1$
    private static final URI STANDARD_GROUP_RESOURCE = URI.createURI(STANDARD_GROUP_RESOURCE_PREFIX);

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
        return Optional.empty();
    }

    public Optional<CommandGroup> resolveStandardCommandGroup(ResourceSet resourceSet, Object value) {
        return resolveStandardCommandGroup(resourceSet, value, null);
    }

    public Optional<CommandGroup> resolveStandardCommandGroup(ResourceSet resourceSet, Object value, String platformVersion) {
        Optional<Definition> definition = resolveDefinition(value);
        if (definition.isEmpty()) {
            return Optional.empty();
        }
        if (resourceSet != null) {
            List<URI> resourceUris = standardGroupResourceUris(resourceSet, platformVersion);
            CommandGroup group = resolveByEObjectUri(resourceSet, resourceUris, definition.get());
            if (group != null) {
                return Optional.of(group);
            }
            for (URI resourceUri : resourceUris) {
                Resource resource = resourceSet.getResource(resourceUri, false);
                if (resource == null) {
                    try {
                        resource = resourceSet.getResource(resourceUri, true);
                    } catch (RuntimeException e) {
                        resource = null;
                    }
                }
                group = findStandardCommandGroup(resource, definition.get());
                if (group != null) {
                    return Optional.of(group);
                }
            }
        }
        return Optional.empty();
    }

    public boolean isStandardCommandGroup(Object value) {
        return resolveDefinition(value).isPresent();
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

    private CommandGroup resolveByEObjectUri(ResourceSet resourceSet, List<URI> resourceUris, Definition definition) {
        for (URI resourceUri : resourceUris) {
            URI groupUri = resourceUri.appendFragment("/" + definition.name()); //$NON-NLS-1$
            EObject object;
            try {
                object = resourceSet.getEObject(groupUri, true);
            } catch (RuntimeException e) {
                object = null;
            }
            CommandGroup group = matchingCommandGroup(object, definition);
            if (group != null) {
                return group;
            }
        }
        return null;
    }

    private List<URI> standardGroupResourceUris(ResourceSet resourceSet, String platformVersion) {
        Set<String> uris = new LinkedHashSet<>();
        String normalizedVersion = normalizePlatformVersion(platformVersion);
        if (normalizedVersion != null) {
            uris.add(STANDARD_GROUP_RESOURCE_PREFIX + "/" + normalizedVersion); //$NON-NLS-1$
        }
        for (Resource resource : resourceSet.getResources()) {
            URI uri = resource.getURI();
            if (uri != null && uri.trimFragment().toString().startsWith(STANDARD_GROUP_RESOURCE_PREFIX)) {
                uris.add(uri.trimFragment().toString());
            }
        }
        uris.add(STANDARD_GROUP_RESOURCE.toString());
        return uris.stream().map(URI::createURI).toList();
    }

    private String normalizePlatformVersion(String platformVersion) {
        if (platformVersion == null || platformVersion.isBlank()) {
            return null;
        }
        String trimmed = platformVersion.trim();
        return trimmed.startsWith("v") ? trimmed : "v" + trimmed; //$NON-NLS-1$ //$NON-NLS-2$
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
