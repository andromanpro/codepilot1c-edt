package com.codepilot1c.core.tools.file;

import java.util.Locale;

/**
 * Model files of EDT that the text tools must not rewrite, beyond the {@code .mdo}/{@code .form}
 * checks kept inside {@code EditFileTool} and {@code WriteTool}.
 *
 * <p>The command interface ({@code CommandInterface.cmi}) is an EDT model: a text edit bypasses BM,
 * and EDT keeps whatever the edit left behind. A July edit stripped the {@code <command>} lines of
 * two data processors and kept their {@code <visibilityFragments>} wrappers; EDT loaded the empty
 * entries into the model, re-serialized them on the next save and reported "Команда для настройки
 * видимости должна быть указана" for two months. The semantic route is
 * {@code inspect_command_interface} / {@code mutate_command_interface}.</p>
 */
public final class EdtModelFileGuard {

    private EdtModelFileGuard() {
    }

    public static boolean isCommandInterfacePath(String path) {
        if (path == null) {
            return false;
        }
        return path.trim().toLowerCase(Locale.ROOT).endsWith(".cmi"); //$NON-NLS-1$
    }

    /**
     * @param overrideParameter name of the emergency override the calling tool accepts, or
     *                          {@code null} when the tool has none
     */
    public static String commandInterfaceRefusal(String overrideParameter) {
        StringBuilder message = new StringBuilder();
        message.append("❌ Прямая правка командного интерфейса (.cmi) заблокирована: это модель EDT, ") //$NON-NLS-1$
                .append("текстовая правка идёт мимо BM, и EDT сохраняет в модели всё, что она оставила.\n") //$NON-NLS-1$
                .append("Посмотреть — inspect_command_interface, изменить — mutate_command_interface ") //$NON-NLS-1$
                .append("(edt_validate_request operation=mutate_command_interface)."); //$NON-NLS-1$
        if (overrideParameter != null && !overrideParameter.isBlank()) {
            message.append("\nДля аварийного обхода передайте ").append(overrideParameter).append("=true."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return message.toString();
    }
}
