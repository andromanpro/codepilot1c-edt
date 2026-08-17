package com.codepilot1c.core.edt.forms;

import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;

/** Canonical form-module sections in the order enforced by EDT code-style checks. */
public enum FormModuleRegion {
    VARIABLES(false),
    FORM_EVENT_HANDLERS(false),
    FORM_HEADER_ITEMS_EVENT_HANDLERS(false),
    FORM_TABLE_ITEMS_EVENT_HANDLERS(true),
    FORM_COMMAND_EVENT_HANDLERS(false),
    PRIVATE(false),
    INITIALIZE(false);

    private final boolean suffixed;

    FormModuleRegion(boolean suffixed) {
        this.suffixed = suffixed;
    }

    public int ordinalIndex() {
        return ordinal();
    }

    public boolean isSuffixed() {
        return suffixed;
    }

    public String name(ScriptVariant variant) {
        return switch (this) {
            case VARIABLES -> BslKeywords.regionVariables(variant);
            case FORM_EVENT_HANDLERS -> BslKeywords.regionFormEventHandlers(variant);
            case FORM_HEADER_ITEMS_EVENT_HANDLERS -> BslKeywords.regionFormHeaderItemsEventHandlers(variant);
            case FORM_TABLE_ITEMS_EVENT_HANDLERS -> BslKeywords.regionFormTableItemsEventHandlers(variant);
            case FORM_COMMAND_EVENT_HANDLERS -> BslKeywords.regionFormCommandEventHandlers(variant);
            case PRIVATE -> BslKeywords.regionPrivate(variant);
            case INITIALIZE -> BslKeywords.regionInitialize(variant);
        };
    }
}
