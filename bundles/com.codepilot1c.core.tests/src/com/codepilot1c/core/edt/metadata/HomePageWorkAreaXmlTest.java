package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Test;

/**
 * Контракт формата .hpwa: рендер обязан совпадать с тем, что пишет сама EDT 2025.2.3
 * (образец снят с живого проекта), а разбор параметров - отбивать невалидную раскладку.
 */
public class HomePageWorkAreaXmlTest {

    private static final HomePageWorkAreaXml.Item MONITOR =
            new HomePageWorkAreaXml.Item("DataProcessor.МониторДиспетчера.Form.Форма", 10, true); //$NON-NLS-1$
    private static final HomePageWorkAreaXml.Item ORDERS =
            new HomePageWorkAreaXml.Item("Document.ЗаказНаОтгрузку.Form.ФормаСписка", 10, true); //$NON-NLS-1$

    @Test
    public void rendersTwoColumnsExactlyLikeEdt() {
        String expected = """
                <?xml version="1.0" encoding="UTF-8"?>
                <hpwa:HomePageWorkArea xmlns:hpwa="http://g5.1c.ru/v8/dt/hpwa">
                  <workingAreaTemplate>TwoColumnsEqualWidth</workingAreaTemplate>
                  <columns>
                    <items>
                      <form>DataProcessor.МониторДиспетчера.Form.Форма</form>
                      <height>10</height>
                      <visibility>
                        <common>true</common>
                      </visibility>
                    </items>
                  </columns>
                  <columns>
                    <items>
                      <form>Document.ЗаказНаОтгрузку.Form.ФормаСписка</form>
                      <height>10</height>
                      <visibility>
                        <common>true</common>
                      </visibility>
                    </items>
                  </columns>
                </hpwa:HomePageWorkArea>
                """;
        assertEquals(expected, HomePageWorkAreaXml.render("TwoColumnsEqualWidth", List.of(MONITOR), List.of(ORDERS))); //$NON-NLS-1$
    }

    @Test
    public void emptyRightColumnIsSelfClosingAndOneColumnHasNoSecondColumn() {
        String two = HomePageWorkAreaXml.render(null, List.of(MONITOR), List.of());
        assertTrue(two.contains("  <columns/>\n</hpwa:HomePageWorkArea>")); //$NON-NLS-1$
        assertTrue(two.contains("<workingAreaTemplate>TwoColumnsEqualWidth</workingAreaTemplate>")); //$NON-NLS-1$
        String one = HomePageWorkAreaXml.render("OneColumn", List.of(MONITOR), null); //$NON-NLS-1$
        assertEquals(1, one.split("<columns>", -1).length - 1); //$NON-NLS-1$
        assertFalse(one.contains("<columns/>")); //$NON-NLS-1$
    }

    @Test
    public void templateNormalizationAcceptsEnumNamesAndRejectsUnknown() {
        assertEquals("TwoColumnsVariableWidth", HomePageWorkAreaXml.normalizeTemplate("TWO_COLUMNS_VARIABLE_WIDTH")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("OneColumn", HomePageWorkAreaXml.normalizeTemplate("onecolumn")); //$NON-NLS-1$ //$NON-NLS-2$
        try {
            HomePageWorkAreaXml.normalizeTemplate("ThreeColumns"); //$NON-NLS-1$
            fail("unknown template must be rejected"); //$NON-NLS-1$
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("TwoColumnsEqualWidth")); //$NON-NLS-1$
        }
    }

    @Test
    public void validationRejectsEmptyLayoutRightColumnForOneColumnAndNonFormFqn() {
        expectRejected("TwoColumnsEqualWidth", List.of(), List.of()); //$NON-NLS-1$
        expectRejected("OneColumn", List.of(MONITOR), List.of(ORDERS)); //$NON-NLS-1$
        expectRejected("TwoColumnsEqualWidth", //$NON-NLS-1$
                List.of(new HomePageWorkAreaXml.Item("DataProcessor.МониторДиспетчера", 10, true)), List.of()); //$NON-NLS-1$
        expectRejected("TwoColumnsEqualWidth", //$NON-NLS-1$
                List.of(new HomePageWorkAreaXml.Item("DataProcessor.МониторДиспетчера.Form.Форма", 0, true)), List.of()); //$NON-NLS-1$
        assertTrue(HomePageWorkAreaXml.looksLikeFormFqn("CommonForm.Помощник")); //$NON-NLS-1$
        assertFalse(HomePageWorkAreaXml.looksLikeFormFqn("Catalog.Номенклатура.ФормаСписка")); //$NON-NLS-1$
    }

    @Test
    public void escapesXmlSpecialCharactersInFormName() {
        String xml = HomePageWorkAreaXml.render("OneColumn", //$NON-NLS-1$
                List.of(new HomePageWorkAreaXml.Item("CommonForm.A&B<C>", 3, false)), null); //$NON-NLS-1$
        assertTrue(xml.contains("<form>CommonForm.A&amp;B&lt;C&gt;</form>")); //$NON-NLS-1$
        assertTrue(xml.contains("<height>3</height>")); //$NON-NLS-1$
        assertTrue(xml.contains("<common>false</common>")); //$NON-NLS-1$
    }

    private static void expectRejected(String template, List<HomePageWorkAreaXml.Item> left,
            List<HomePageWorkAreaXml.Item> right) {
        try {
            HomePageWorkAreaXml.validate(template, left, right);
            fail("layout must be rejected: " + template + " " + left + " " + right); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        } catch (IllegalArgumentException expected) {
            // ожидаемо
        }
    }
}
