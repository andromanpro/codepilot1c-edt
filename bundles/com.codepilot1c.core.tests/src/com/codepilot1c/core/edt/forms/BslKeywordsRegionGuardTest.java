package com.codepilot1c.core.edt.forms;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.Arrays;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;

public class BslKeywordsRegionGuardTest {

    @Test
    public void canonicalRegionNamesMatchPinnedEdtValues() {
        assertArrayEquals(new String[] {
                "ОписаниеПеременных", //$NON-NLS-1$
                "ОбработчикиСобытийФормы", //$NON-NLS-1$
                "ОбработчикиСобытийЭлементовШапкиФормы", //$NON-NLS-1$
                "ОбработчикиСобытийЭлементовТаблицыФормы", //$NON-NLS-1$
                "ОбработчикиКомандФормы", //$NON-NLS-1$
                "СлужебныеПроцедурыИФункции", //$NON-NLS-1$
                "Инициализация" //$NON-NLS-1$
        }, names(ScriptVariant.RUSSIAN));
        assertArrayEquals(new String[] {
                "Variables", //$NON-NLS-1$
                "FormEventHandlers", //$NON-NLS-1$
                "FormHeaderItemsEventHandlers", //$NON-NLS-1$
                "FormTableItemsEventHandlers", //$NON-NLS-1$
                "FormCommandsEventHandlers", //$NON-NLS-1$
                "Private", //$NON-NLS-1$
                "Initialize" //$NON-NLS-1$
        }, names(ScriptVariant.ENGLISH));
    }

    @Test
    public void regionDirectiveLiteralsMatchPinnedEdtValues() {
        assertEquals("#Область", BslKeywords.regionDirective(ScriptVariant.RUSSIAN)); //$NON-NLS-1$
        assertEquals("#Region", BslKeywords.regionDirective(ScriptVariant.ENGLISH)); //$NON-NLS-1$
        assertEquals("#КонецОбласти", BslKeywords.endRegionDirective(ScriptVariant.RUSSIAN)); //$NON-NLS-1$
        assertEquals("#EndRegion", BslKeywords.endRegionDirective(ScriptVariant.ENGLISH)); //$NON-NLS-1$
    }

    private static String[] names(ScriptVariant variant) {
        return Arrays.stream(FormModuleRegion.values()).map(region -> region.name(variant)).toArray(String[]::new);
    }
}
