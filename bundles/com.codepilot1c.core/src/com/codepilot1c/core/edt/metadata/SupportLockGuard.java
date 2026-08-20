package com.codepilot1c.core.edt.metadata;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.md.distribution.support.DistributionDescription;
import com._1c.g5.v8.dt.md.distribution.support.IDistributionDescriptionProvider;
import com._1c.g5.v8.dt.md.distribution.support.UserSupportMode;

import com.codepilot1c.core.logging.VibeLogger;

/**
 * Machine gate for antipattern #70 ("silent platform failures"): a generic
 * mutation of a vendor-supplied object that is on support with the
 * "changes not allowed" lock must not happen silently — the platform gives
 * no error, the support state silently degrades and every vendor update
 * afterwards turns into a manual merge.
 *
 * <p>The gate consults the same support model the EDT UI shows as lock icons:
 * {@code src/Configuration/ParentConfigurations.bin} exposed through
 * {@link IDistributionDescriptionProvider} of the
 * {@code com._1c.g5.v8.dt.md.distribution.support} plug-in. The mutation
 * subject is identified by the owning object's {@code .mdo} uuid, resolved
 * bottom-up from the touched file — one mechanism for both BM mutation tools
 * (FQN → {@code src/<Folder>/<Name>/<Name>.mdo}) and direct file tools.</p>
 *
 * <p>Semantics:</p>
 * <ul>
 *   <li>{@link UserSupportMode#CHANGES_NOT_ALLOWED} (замок) — mutation is
 *       refused with {@link MetadataOperationCode#SUPPORTED_OBJECT_LOCKED}
 *       unless the caller passed the explicit
 *       {@code allow_supported_object_edit: true} flag;</li>
 *   <li>{@link UserSupportMode#CHANGES_ALLOWED} / {@link UserSupportMode#CANCELLED}
 *       / not on support — mutation proceeds;</li>
 *   <li>support model unavailable (no plug-in, closed project, resolution
 *       error) — the gate FAILS OPEN with a logged warning: breaking every
 *       mutation on EDT API drift would be worse than missing the check.</li>
 * </ul>
 */
public final class SupportLockGuard {

    /** Explicit-consent flag accepted by all mutation tools covered by the gate. */
    public static final String PARAMETER_NAME = "allow_supported_object_edit"; //$NON-NLS-1$

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(SupportLockGuard.class);

    private static final String SUPPORT_BUNDLE_ID = "com._1c.g5.v8.dt.md.distribution.support"; //$NON-NLS-1$
    private static final String SUPPORT_PLUGIN_CLASS =
            "com._1c.g5.v8.dt.internal.md.distribution.support.DistributionSupportPlugin"; //$NON-NLS-1$
    private static final String SRC_FOLDER_NAME = "src"; //$NON-NLS-1$
    private static final String MDO_EXTENSION = ".mdo"; //$NON-NLS-1$
    /** Root-element uuid of an EDT .mdo lives in the opening tag — a prefix read is enough. */
    private static final int MDO_UUID_PROBE_BYTES = 16 * 1024;
    private static final Pattern UUID_ATTRIBUTE = Pattern.compile(
            "uuid=\"([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\""); //$NON-NLS-1$

    private static volatile boolean unavailabilityLogged;

    private SupportLockGuard() {
    }

    /**
     * Reads the consent flag from a raw tool-parameter map (JSON boolean or string).
     */
    public static boolean isAllowed(java.util.Map<String, Object> parameters) {
        Object value = parameters == null ? null : parameters.get(PARAMETER_NAME);
        if (value instanceof Boolean flag) {
            return flag.booleanValue();
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    /**
     * Support-mode lookup abstraction; production wraps {@link DistributionDescription},
     * tests substitute a map-backed fake.
     */
    interface SupportModeLookup {

        /** Whole configuration is not editable (support description forbids changes globally). */
        boolean wholeConfigurationLocked();

        /** Most restrictive support mode for the object uuid, or {@code null} if not on support. */
        UserSupportMode modeOf(String uuid);
    }

    /**
     * Gate for direct workspace-file mutations ({@code edit_file}, {@code write_file}).
     */
    public static void checkWorkspaceFile(IFile file, boolean allowEdit, String operation) {
        if (file == null || allowEdit) {
            return;
        }
        String subject = file.getFullPath() != null ? file.getFullPath().toString() : String.valueOf(file);
        checkProjectPath(file.getProject(),
                file.getLocation() != null ? file.getLocation().toFile().toPath() : null,
                allowEdit, operation, subject);
    }

    /**
     * Gate for a project-local absolute path (the owning object's {@code .mdo}
     * for BM mutations, or any {@code src} artifact for file mutations).
     *
     * @param project workspace project owning the path; {@code null} skips the check
     * @param absolutePath absolute filesystem path inside the project; {@code null} skips
     * @param allowEdit value of the {@code allow_supported_object_edit} flag
     * @param operation tool/operation name for the refusal message
     * @param subject FQN or workspace path shown in the refusal message
     */
    public static void checkProjectPath(IProject project, Path absolutePath, boolean allowEdit,
            String operation, String subject) {
        if (project == null || absolutePath == null || allowEdit) {
            return;
        }
        UserSupportMode mode;
        try {
            mode = resolveMode(project, absolutePath);
        } catch (RuntimeException | LinkageError e) {
            logUnavailable(e);
            return;
        }
        enforce(mode, false, operation, subject);
    }

    private static UserSupportMode resolveMode(IProject project, Path absolutePath) {
        if (project.getLocation() == null) {
            return null;
        }
        DistributionDescription description = descriptionProvider().getDescription(project);
        if (description == null) {
            return null;
        }
        return resolveFileMode(
                new DescriptionModeLookup(description),
                project.getLocation().toFile().toPath(),
                absolutePath);
    }

    private static IDistributionDescriptionProvider descriptionProvider() {
        Object injector = EdtPluginInjectorLocator.pluginInjector(SUPPORT_BUNDLE_ID, SUPPORT_PLUGIN_CLASS);
        return EdtPluginInjectorLocator.service(injector, IDistributionDescriptionProvider.class);
    }

    /**
     * Pure walk: the file's own {@code .mdo} identity first, then every
     * {@code <Dir>/<Dir>.mdo} from the file's directory up to the direct children
     * of {@code src}. The first uuid the support map knows decides the mode —
     * support lock granularity is the owning object, so a module, form or
     * template inside a locked vendor object hits the lock too.
     */
    static UserSupportMode resolveFileMode(SupportModeLookup lookup, Path projectRoot, Path filePath) {
        Path srcRoot = projectRoot.resolve(SRC_FOLDER_NAME).normalize();
        Path normalizedFile = filePath.normalize();
        if (!normalizedFile.startsWith(srcRoot)) {
            return null;
        }
        if (lookup.wholeConfigurationLocked()) {
            return UserSupportMode.CHANGES_NOT_ALLOWED;
        }
        String fileName = normalizedFile.getFileName() != null
                ? normalizedFile.getFileName().toString() : ""; //$NON-NLS-1$
        if (fileName.toLowerCase(Locale.ROOT).endsWith(MDO_EXTENSION) && Files.isRegularFile(normalizedFile)) {
            UserSupportMode own = lookupMdoMode(lookup, normalizedFile);
            if (own != null) {
                return own;
            }
        }
        for (Path dir = normalizedFile.getParent();
                dir != null && dir.startsWith(srcRoot) && !dir.equals(srcRoot);
                dir = dir.getParent()) {
            Path dirName = dir.getFileName();
            if (dirName == null) {
                break;
            }
            Path candidate = dir.resolve(dirName.toString() + MDO_EXTENSION);
            if (candidate.equals(normalizedFile) || !Files.isRegularFile(candidate)) {
                continue;
            }
            UserSupportMode mode = lookupMdoMode(lookup, candidate);
            if (mode != null) {
                return mode;
            }
        }
        return null;
    }

    private static UserSupportMode lookupMdoMode(SupportModeLookup lookup, Path mdoFile) {
        String uuid = readUuidAttribute(mdoFile);
        return uuid == null ? null : lookup.modeOf(uuid);
    }

    /** First {@code uuid="..."} attribute of the file prefix = the object's own uuid. */
    static String readUuidAttribute(Path mdoFile) {
        try (InputStream in = Files.newInputStream(mdoFile)) {
            byte[] probe = in.readNBytes(MDO_UUID_PROBE_BYTES);
            Matcher matcher = UUID_ATTRIBUTE.matcher(new String(probe, StandardCharsets.UTF_8));
            return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
        } catch (IOException e) {
            LOG.debug("SupportLockGuard: cannot read %s: %s", mdoFile, e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /** Production {@link SupportModeLookup} over the EDT support description. */
    static final class DescriptionModeLookup implements SupportModeLookup {

        private final DistributionDescription description;

        DescriptionModeLookup(DistributionDescription description) {
            this.description = description;
        }

        @Override
        public boolean wholeConfigurationLocked() {
            // Mirror of DistributionSupportTypeProvider: a non-"normal" configuration
            // whose parent forbids new objects is not editable as a whole.
            return !description.isNormalConfiguration()
                    && description.getParents().stream()
                            .anyMatch(parent -> parent.getNewObjectMode() == UserSupportMode.CHANGES_NOT_ALLOWED);
        }

        @Override
        public UserSupportMode modeOf(String uuid) {
            Collection<UserSupportMode> modes;
            try {
                modes = description.getUserSupportMode(UUID.fromString(uuid));
            } catch (IllegalArgumentException e) {
                return null;
            }
            if (modes == null || modes.isEmpty()) {
                return null;
            }
            UserSupportMode strictest = null;
            for (UserSupportMode mode : modes) {
                if (mode != null && (strictest == null || mode.getValue() < strictest.getValue())) {
                    strictest = mode;
                }
            }
            return strictest;
        }
    }

    /**
     * The gate itself: only {@link UserSupportMode#CHANGES_NOT_ALLOWED} without
     * the explicit flag refuses; everything else passes.
     */
    static void enforce(UserSupportMode mode, boolean allowEdit, String operation, String subject) {
        if (allowEdit || mode != UserSupportMode.CHANGES_NOT_ALLOWED) {
            return;
        }
        throw new MetadataOperationException(
                MetadataOperationCode.SUPPORTED_OBJECT_LOCKED,
                "Объект на поддержке поставщика с запретом изменений (замок): " + subject //$NON-NLS-1$
                        + ". Операция '" + operation + "' отклонена — антипаттерн #70 (молчаливые отказы платформы): " //$NON-NLS-1$ //$NON-NLS-2$
                        + "доработка типовой конфигурации выполняется расширением; снятие с поддержки — отдельное " //$NON-NLS-1$
                        + "решение человека (цена: ручное объединение при каждом обновлении поставщика). " //$NON-NLS-1$
                        + "Если владелец явно разрешил правку типового объекта — повтори вызов с параметром \"" //$NON-NLS-1$
                        + PARAMETER_NAME + "\": true.", //$NON-NLS-1$
                false);
    }

    private static void logUnavailable(Throwable cause) {
        if (!unavailabilityLogged) {
            unavailabilityLogged = true;
            LOG.warn("SupportLockGuard: support model unavailable, gate FAILS OPEN: %s", //$NON-NLS-1$
                    String.valueOf(cause.getMessage()));
        } else {
            LOG.debug("SupportLockGuard: support model unavailable: %s", String.valueOf(cause.getMessage())); //$NON-NLS-1$
        }
    }
}
