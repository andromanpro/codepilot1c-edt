package com.codepilot1c.core.edt.forms;

import java.util.Objects;

import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;

/** Exact model-derived destination for a generated form handler stub. */
public record HandlerRegionTarget(FormModuleRegion section, String tableNameOrNull) {

    private static final HandlerRegionTarget UNKNOWN = new HandlerRegionTarget(null, null);

    public HandlerRegionTarget {
        if (section != null && section.isSuffixed()
                && (tableNameOrNull == null || tableNameOrNull.isBlank())) {
            throw new IllegalArgumentException("A table handler region requires a table name"); //$NON-NLS-1$
        }
        if (section != null && !section.isSuffixed() && tableNameOrNull != null) {
            throw new IllegalArgumentException("Only table handler regions may have a suffix"); //$NON-NLS-1$
        }
    }

    public static HandlerRegionTarget of(FormModuleRegion section) {
        return new HandlerRegionTarget(Objects.requireNonNull(section, "section"), null); //$NON-NLS-1$
    }

    public static HandlerRegionTarget forTable(String tableName) {
        return new HandlerRegionTarget(FormModuleRegion.FORM_TABLE_ITEMS_EVENT_HANDLERS, tableName);
    }

    public static HandlerRegionTarget unknown() {
        return UNKNOWN;
    }

    public boolean isUnknown() {
        return section == null;
    }

    public String canonicalName(ScriptVariant variant) {
        if (isUnknown()) {
            return null;
        }
        String baseName = section.name(variant);
        return section.isSuffixed() ? baseName + tableNameOrNull : baseName;
    }
}
