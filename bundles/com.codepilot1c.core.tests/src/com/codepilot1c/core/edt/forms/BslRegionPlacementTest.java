package com.codepilot1c.core.edt.forms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Optional;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;

import com.codepilot1c.core.edt.forms.BslRegionPlacement.Insertion;

public class BslRegionPlacementTest {

    private static final String FORM_REGION = "ОбработчикиСобытийФормы"; //$NON-NLS-1$
    private static final String TABLE_REGION = "ОбработчикиСобытийЭлементовТаблицыФормы"; //$NON-NLS-1$
    private static final String COMMAND_REGION = "ОбработчикиКомандФормы"; //$NON-NLS-1$
    private static final String STUB = "&НаКлиенте\n" //$NON-NLS-1$
            + "Процедура НовыйОбработчик(Отказ)\n" //$NON-NLS-1$
            + "\t// Вставить содержимое обработчика.\n" //$NON-NLS-1$
            + "КонецПроцедуры"; //$NON-NLS-1$
    private static final String LIVE_MODULE = "\n" //$NON-NLS-1$
            + "&НаКлиенте\n" //$NON-NLS-1$
            + "Процедура ПриОткрытии(Отказ)\n" //$NON-NLS-1$
            + "\tПриОткрытииНаСервере();\n" //$NON-NLS-1$
            + "КонецПроцедуры\n\n" //$NON-NLS-1$
            + "&НаСервере\n" //$NON-NLS-1$
            + "Процедура ПриСозданииНаСервере(Отказ, СтандартнаяОбработка)\n" //$NON-NLS-1$
            + "\t// Инициализация формы элемента справочника Номенклатура\n" //$NON-NLS-1$
            + "КонецПроцедуры\n\n" //$NON-NLS-1$
            + "&НаСервере\n" //$NON-NLS-1$
            + "Процедура ПриОткрытииНаСервере()\n" //$NON-NLS-1$
            + "\t// Дополнительная серверная инициализация\n" //$NON-NLS-1$
            + "КонецПроцедуры\n"; //$NON-NLS-1$

    private final BslRegionPlacement placement = new BslRegionPlacement();

    @Test
    public void liveRegionlessShapeAppendsRegionWithoutTouchingExistingBytes() {
        String result = apply(LIVE_MODULE, plan(LIVE_MODULE, FORM_REGION, 1, STUB, ScriptVariant.RUSSIAN));

        assertTrue(result.startsWith(LIVE_MODULE));
        assertTrue(result.contains("#Область " + FORM_REGION + "\n\n" + STUB)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.endsWith("#КонецОбласти\n")); //$NON-NLS-1$
        assertFalse(result.contains("\r")); //$NON-NLS-1$
        assertFalse(result.startsWith("\uFEFF")); //$NON-NLS-1$
    }

    @Test
    public void existingTargetRegionReceivesStubBeforeEndWithOneBlankLineEachSide() {
        String module = "#Область " + FORM_REGION + "\n\n" //$NON-NLS-1$ //$NON-NLS-2$
                + "Процедура Старый()\nКонецПроцедуры\n" //$NON-NLS-1$
                + "#КонецОбласти\n"; //$NON-NLS-1$

        String result = apply(module, plan(module, FORM_REGION, 1, STUB, ScriptVariant.RUSSIAN));

        assertEquals(1, count(result, "#Область " + FORM_REGION)); //$NON-NLS-1$
        assertTrue(result.contains("КонецПроцедуры\n\n" + STUB + "\n\n#КонецОбласти")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void lowerStandardRegionIsInsertedBeforeHigherStandardRegion() {
        String module = region(COMMAND_REGION, "Процедура Команда()\nКонецПроцедуры"); //$NON-NLS-1$
        String result = apply(module, plan(module, FORM_REGION, 1, STUB, ScriptVariant.RUSSIAN));

        assertTrue(result.indexOf(FORM_REGION) < result.indexOf(COMMAND_REGION));
    }

    @Test
    public void higherStandardRegionIsInsertedAfterLowerStandardRegion() {
        String module = region(FORM_REGION, "Процедура Событие()\nКонецПроцедуры"); //$NON-NLS-1$
        String result = apply(module, plan(module, COMMAND_REGION, 4, STUB, ScriptVariant.RUSSIAN));

        assertTrue(result.indexOf(FORM_REGION) < result.indexOf(COMMAND_REGION));
        assertTrue(result.indexOf("#КонецОбласти") < result.indexOf(COMMAND_REGION)); //$NON-NLS-1$
    }

    @Test
    public void commandRegionIsInsertedAfterBareTableRegionPrefix() {
        String module = region(TABLE_REGION, "Процедура Таблица()\nКонецПроцедуры"); //$NON-NLS-1$
        String result = apply(module, plan(module, COMMAND_REGION, 4, STUB, ScriptVariant.RUSSIAN));

        assertTrue(result.indexOf(TABLE_REGION) < result.indexOf(COMMAND_REGION));
        assertTrue(result.indexOf("#КонецОбласти") < result.indexOf(COMMAND_REGION)); //$NON-NLS-1$
    }

    @Test
    public void emptyModuleProducesOnlyRegionAndSingleTrailingNewline() {
        String result = apply("", plan("", FORM_REGION, 1, STUB, ScriptVariant.RUSSIAN)); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("#Область " + FORM_REGION + "\n\n" + STUB + "\n\n#КонецОбласти\n", result); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void crlfModuleEmitsOnlyCrLfInInsertedBlock() {
        String module = "&НаКлиенте\r\nПроцедура Старая()\r\nКонецПроцедуры\r\n"; //$NON-NLS-1$
        String result = apply(module, plan(module, FORM_REGION, 1, STUB, ScriptVariant.RUSSIAN));

        assertTrue(result.startsWith(module));
        assertFalse(result.matches("(?s).*(?<!\\r)\\n.*")); //$NON-NLS-1$
        assertTrue(result.endsWith("#КонецОбласти\r\n")); //$NON-NLS-1$
    }

    @Test
    public void moduleWithoutTrailingNewlineGetsSeparationAndOneTrailingNewline() {
        String module = "Процедура Старая()\nКонецПроцедуры"; //$NON-NLS-1$
        String result = apply(module, plan(module, FORM_REGION, 1, STUB, ScriptVariant.RUSSIAN));

        assertTrue(result.startsWith(module + "\n\n#Область")); //$NON-NLS-1$
        assertTrue(result.endsWith("#КонецОбласти\n")); //$NON-NLS-1$
        assertFalse(result.endsWith("#КонецОбласти\n\n")); //$NON-NLS-1$
    }

    @Test
    public void bomRemainsAtByteZero() {
        String module = "\uFEFFПроцедура Старая()\nКонецПроцедуры\n"; //$NON-NLS-1$
        String result = apply(module, plan(module, FORM_REGION, 1, STUB, ScriptVariant.RUSSIAN));

        assertTrue(result.startsWith(module));
        assertEquals('\uFEFF', result.charAt(0));
        assertEquals(1, count(result, "\uFEFF")); //$NON-NLS-1$
    }

    @Test
    public void englishVariantRendersEnglishDirectiveAndCanonicalName() {
        String englishStub = STUB.replace("&НаКлиенте", "&AtClient") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("Процедура", "Procedure") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("КонецПроцедуры", "EndProcedure"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = apply("", plan("", "FormEventHandlers", 1, englishStub, ScriptVariant.ENGLISH)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(result.startsWith("#Region FormEventHandlers\n\n")); //$NON-NLS-1$
        assertTrue(result.endsWith("#EndRegion\n")); //$NON-NLS-1$
    }

    @Test
    public void suffixedTableRegionMatchesExistingCaseInsensitively() {
        String target = "ОбработчикиСобытийЭлементовТаблицыФормыТовары"; //$NON-NLS-1$
        String module = "#Область " + target.toLowerCase() + "\n\n#КонецОбласти\n"; //$NON-NLS-1$ //$NON-NLS-2$
        String result = apply(module, plan(module, target, 3, STUB, ScriptVariant.RUSSIAN));

        assertEquals(1, count(result.toLowerCase(), "#область " + target.toLowerCase())); //$NON-NLS-1$
        assertTrue(result.indexOf(STUB) < result.indexOf("#КонецОбласти")); //$NON-NLS-1$
    }

    @Test
    public void nestedRegionIsNotUsedAsPlacementAnchor() {
        String module = "#Область Пользовательская\n" //$NON-NLS-1$
                + "#Область " + COMMAND_REGION + "\n#КонецОбласти\n" //$NON-NLS-1$ //$NON-NLS-2$
                + "#КонецОбласти\n"; //$NON-NLS-1$
        String result = apply(module, plan(module, FORM_REGION, 1, STUB, ScriptVariant.RUSSIAN));

        assertTrue(result.startsWith("#Область " + FORM_REGION)); //$NON-NLS-1$
        assertTrue(result.indexOf(FORM_REGION) < result.indexOf("Пользовательская")); //$NON-NLS-1$
    }

    @Test
    public void unbalancedRegionsVetoPlacement() {
        assertTrue(plan("#Область " + FORM_REGION + "\n", FORM_REGION, 1, STUB, ScriptVariant.RUSSIAN).isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(plan("#КонецОбласти\n", FORM_REGION, 1, STUB, ScriptVariant.RUSSIAN).isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void duplicateTopLevelTargetRegionsVetoPlacement() {
        String module = region(FORM_REGION, STUB) + "\n" + region(FORM_REGION, STUB); //$NON-NLS-1$

        assertTrue(plan(module, FORM_REGION, 1, STUB, ScriptVariant.RUSSIAN).isEmpty());
    }

    @Test
    public void targetNameOnlyNestedVetoesPlacement() {
        String module = "#Область Пользовательская\n#Область " + FORM_REGION //$NON-NLS-1$
                + "\n#КонецОбласти\n#КонецОбласти\n"; //$NON-NLS-1$

        assertTrue(plan(module, FORM_REGION, 1, STUB, ScriptVariant.RUSSIAN).isEmpty());
    }

    @Test
    public void nonIncreasingStandardSubsequenceVetoesPlacement() {
        String module = region(COMMAND_REGION, STUB) + "\n" + region(FORM_REGION, STUB); //$NON-NLS-1$

        assertTrue(plan(module, "СлужебныеПроцедурыИФункции", 5, STUB, ScriptVariant.RUSSIAN).isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void onlyNonStandardRegionsPlacesNewStandardRegionBeforeFirst() {
        String module = region("Пользовательская", STUB); //$NON-NLS-1$
        String result = apply(module, plan(module, FORM_REGION, 1, STUB, ScriptVariant.RUSSIAN));

        assertTrue(result.startsWith("#Область " + FORM_REGION)); //$NON-NLS-1$
        assertTrue(result.indexOf(FORM_REGION) < result.indexOf("Пользовательская")); //$NON-NLS-1$
    }

    @Test
    public void englishSpelledDirectiveIsRecognizedInRussianModule() {
        String module = "#Region " + FORM_REGION + "\n\n#EndRegion // keep\n"; //$NON-NLS-1$ //$NON-NLS-2$
        String result = apply(module, plan(module, FORM_REGION, 1, STUB, ScriptVariant.RUSSIAN));

        assertEquals(1, count(result, "#Region " + FORM_REGION)); //$NON-NLS-1$
        assertFalse(result.contains("#Область")); //$NON-NLS-1$
        assertTrue(result.indexOf(STUB) < result.indexOf("#EndRegion")); //$NON-NLS-1$
    }

    @Test
    public void russianOpenRegionTrailingCommentKeepsCanonicalTargetIdentity() {
        String module = "#Область " + FORM_REGION + " // keep\n\n#КонецОбласти\n"; //$NON-NLS-1$ //$NON-NLS-2$
        String result = apply(module, plan(module, FORM_REGION, 1, STUB, ScriptVariant.RUSSIAN));

        assertEquals(1, count(result, "#Область " + FORM_REGION)); //$NON-NLS-1$
        assertTrue(result.startsWith("#Область " + FORM_REGION + " // keep")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.indexOf(STUB) < result.indexOf("#КонецОбласти")); //$NON-NLS-1$
    }

    @Test
    public void englishOpenRegionTrailingCommentKeepsCanonicalTargetIdentity() {
        String target = "FormEventHandlers"; //$NON-NLS-1$
        String module = "#Region " + target + "// keep\n\n#EndRegion\n"; //$NON-NLS-1$ //$NON-NLS-2$
        String result = apply(module, plan(module, target, 1, STUB, ScriptVariant.ENGLISH));

        assertEquals(1, count(result, "#Region " + target)); //$NON-NLS-1$
        assertTrue(result.startsWith("#Region " + target + "// keep")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.indexOf(STUB) < result.indexOf("#EndRegion")); //$NON-NLS-1$
    }

    private Optional<Insertion> plan(
            String module, String name, int index, String stub, ScriptVariant variant) {
        return placement.plan(module, name, index, stub, variant);
    }

    private static String apply(String module, Optional<Insertion> planned) {
        Insertion insertion = planned.orElseThrow();
        return module.substring(0, insertion.offset()) + insertion.text() + module.substring(insertion.offset());
    }

    private static String region(String name, String body) {
        return "#Область " + name + "\n\n" + body + "\n\n#КонецОбласти\n"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static int count(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
