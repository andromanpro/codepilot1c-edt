package com.codepilot1c.core.edt.forms;

import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;

/**
 * Sanctioned hand-rolled RU/EN literal map for generated handler stubs and their
 * form-module region wrappers.
 *
 * <p>This is the ONLY hand-rolled-BSL-text surface in the codebase, per the project's
 * documented "stub-body text is the sanctioned hand-roll exception" carve-out (see
 * {@code 07-RESEARCH.md} Pattern 3 / Don't Hand-Roll table). Region directives and
 * canonical form-module section names are part of the same exception: the pure-text
 * post-export path cannot use the Guice-provided module-structure service, and core
 * must not depend on {@code com.e1c.v8codestyle.bsl}. Every literal here is a named
 * constant so this surface stays small, auditable, and greppable in one file.</p>
 *
 * <p>Selection is keyed by {@link ScriptVariant}: {@code russian == true} unless the
 * configuration's script variant is explicitly {@link ScriptVariant#ENGLISH}.</p>
 */
final class BslKeywords {

    private static final String PROCEDURE_RU = "Процедура"; //$NON-NLS-1$
    private static final String PROCEDURE_EN = "Procedure"; //$NON-NLS-1$

    private static final String END_PROCEDURE_RU = "КонецПроцедуры"; //$NON-NLS-1$
    private static final String END_PROCEDURE_EN = "EndProcedure"; //$NON-NLS-1$

    private static final String DIRECTIVE_AT_CLIENT_RU = "&НаКлиенте"; //$NON-NLS-1$
    private static final String DIRECTIVE_AT_CLIENT_EN = "&AtClient"; //$NON-NLS-1$

    private static final String DIRECTIVE_AT_SERVER_RU = "&НаСервере"; //$NON-NLS-1$
    private static final String DIRECTIVE_AT_SERVER_EN = "&AtServer"; //$NON-NLS-1$

    private static final String DIRECTIVE_AT_SERVER_NO_CONTEXT_RU = "&НаСервереБезКонтекста"; //$NON-NLS-1$
    private static final String DIRECTIVE_AT_SERVER_NO_CONTEXT_EN = "&AtServerNoContext"; //$NON-NLS-1$

    private static final String COMMAND_PARAMETER_RU = "Команда"; //$NON-NLS-1$
    private static final String COMMAND_PARAMETER_EN = "Command"; //$NON-NLS-1$

    private static final String HANDLER_BODY_COMMENT_RU = "// Вставить содержимое обработчика."; //$NON-NLS-1$
    private static final String HANDLER_BODY_COMMENT_EN = "// Insert handler content."; //$NON-NLS-1$

    private static final String REGION_DIRECTIVE_RU = "#Область"; //$NON-NLS-1$
    private static final String REGION_DIRECTIVE_EN = "#Region"; //$NON-NLS-1$

    private static final String END_REGION_DIRECTIVE_RU = "#КонецОбласти"; //$NON-NLS-1$
    private static final String END_REGION_DIRECTIVE_EN = "#EndRegion"; //$NON-NLS-1$

    private static final String REGION_VARIABLES_RU = "ОписаниеПеременных"; //$NON-NLS-1$
    private static final String REGION_VARIABLES_EN = "Variables"; //$NON-NLS-1$

    private static final String REGION_FORM_EVENT_HANDLERS_RU = "ОбработчикиСобытийФормы"; //$NON-NLS-1$
    private static final String REGION_FORM_EVENT_HANDLERS_EN = "FormEventHandlers"; //$NON-NLS-1$

    private static final String REGION_FORM_HEADER_ITEMS_EVENT_HANDLERS_RU =
            "ОбработчикиСобытийЭлементовШапкиФормы"; //$NON-NLS-1$
    private static final String REGION_FORM_HEADER_ITEMS_EVENT_HANDLERS_EN =
            "FormHeaderItemsEventHandlers"; //$NON-NLS-1$

    private static final String REGION_FORM_TABLE_ITEMS_EVENT_HANDLERS_RU =
            "ОбработчикиСобытийЭлементовТаблицыФормы"; //$NON-NLS-1$
    private static final String REGION_FORM_TABLE_ITEMS_EVENT_HANDLERS_EN =
            "FormTableItemsEventHandlers"; //$NON-NLS-1$

    private static final String REGION_FORM_COMMAND_EVENT_HANDLERS_RU = "ОбработчикиКомандФормы"; //$NON-NLS-1$
    private static final String REGION_FORM_COMMAND_EVENT_HANDLERS_EN = "FormCommandsEventHandlers"; //$NON-NLS-1$

    private static final String REGION_PRIVATE_RU = "СлужебныеПроцедурыИФункции"; //$NON-NLS-1$
    private static final String REGION_PRIVATE_EN = "Private"; //$NON-NLS-1$

    private static final String REGION_INITIALIZE_RU = "Инициализация"; //$NON-NLS-1$
    private static final String REGION_INITIALIZE_EN = "Initialize"; //$NON-NLS-1$

    private BslKeywords() {
        // constants only
    }

    static boolean isRussian(ScriptVariant variant) {
        return variant != ScriptVariant.ENGLISH;
    }

    static String procedureKeyword(ScriptVariant variant) {
        return isRussian(variant) ? PROCEDURE_RU : PROCEDURE_EN;
    }

    static String endProcedureKeyword(ScriptVariant variant) {
        return isRussian(variant) ? END_PROCEDURE_RU : END_PROCEDURE_EN;
    }

    static String directiveAtClient(ScriptVariant variant) {
        return isRussian(variant) ? DIRECTIVE_AT_CLIENT_RU : DIRECTIVE_AT_CLIENT_EN;
    }

    static String directiveAtServer(ScriptVariant variant) {
        return isRussian(variant) ? DIRECTIVE_AT_SERVER_RU : DIRECTIVE_AT_SERVER_EN;
    }

    static String directiveAtServerNoContext(ScriptVariant variant) {
        return isRussian(variant) ? DIRECTIVE_AT_SERVER_NO_CONTEXT_RU : DIRECTIVE_AT_SERVER_NO_CONTEXT_EN;
    }

    static String commandParameter(ScriptVariant variant) {
        return isRussian(variant) ? COMMAND_PARAMETER_RU : COMMAND_PARAMETER_EN;
    }

    static String handlerBodyComment(ScriptVariant variant) {
        return isRussian(variant) ? HANDLER_BODY_COMMENT_RU : HANDLER_BODY_COMMENT_EN;
    }

    static String regionDirective(ScriptVariant variant) {
        return isRussian(variant) ? REGION_DIRECTIVE_RU : REGION_DIRECTIVE_EN;
    }

    static String endRegionDirective(ScriptVariant variant) {
        return isRussian(variant) ? END_REGION_DIRECTIVE_RU : END_REGION_DIRECTIVE_EN;
    }

    static String regionVariables(ScriptVariant variant) {
        return isRussian(variant) ? REGION_VARIABLES_RU : REGION_VARIABLES_EN;
    }

    static String regionFormEventHandlers(ScriptVariant variant) {
        return isRussian(variant) ? REGION_FORM_EVENT_HANDLERS_RU : REGION_FORM_EVENT_HANDLERS_EN;
    }

    static String regionFormHeaderItemsEventHandlers(ScriptVariant variant) {
        return isRussian(variant)
                ? REGION_FORM_HEADER_ITEMS_EVENT_HANDLERS_RU
                : REGION_FORM_HEADER_ITEMS_EVENT_HANDLERS_EN;
    }

    static String regionFormTableItemsEventHandlers(ScriptVariant variant) {
        return isRussian(variant)
                ? REGION_FORM_TABLE_ITEMS_EVENT_HANDLERS_RU
                : REGION_FORM_TABLE_ITEMS_EVENT_HANDLERS_EN;
    }

    static String regionFormCommandEventHandlers(ScriptVariant variant) {
        return isRussian(variant)
                ? REGION_FORM_COMMAND_EVENT_HANDLERS_RU
                : REGION_FORM_COMMAND_EVENT_HANDLERS_EN;
    }

    static String regionPrivate(ScriptVariant variant) {
        return isRussian(variant) ? REGION_PRIVATE_RU : REGION_PRIVATE_EN;
    }

    static String regionInitialize(ScriptVariant variant) {
        return isRussian(variant) ? REGION_INITIALIZE_RU : REGION_INITIALIZE_EN;
    }
}
