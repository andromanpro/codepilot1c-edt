package com.codepilot1c.core.edt.forms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.form.model.Button;
import com._1c.g5.v8.dt.form.model.Decoration;
import com._1c.g5.v8.dt.form.model.EventHandlerContainer;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.form.model.FormGroup;
import com._1c.g5.v8.dt.form.model.LabelDecorationExtInfo;
import com._1c.g5.v8.dt.form.model.Table;
import com._1c.g5.v8.dt.form.model.UsualGroupExtInfo;

public class FormHandlerRegionResolverTest {

    private final FormHandlerRegionResolver resolver = new FormHandlerRegionResolver();

    @Test
    public void formResolvesToFormEventHandlers() {
        Form form = FormFactory.eINSTANCE.createForm();

        assertEquals(FormModuleRegion.FORM_EVENT_HANDLERS,
                resolver.resolve((EventHandlerContainer)form).section());
    }

    @Test
    public void standaloneFormFieldResolvesToHeaderItems() {
        FormField field = FormFactory.eINSTANCE.createFormField();

        assertEquals(FormModuleRegion.FORM_HEADER_ITEMS_EVENT_HANDLERS,
                resolver.resolve((EventHandlerContainer)field).section());
    }

    @Test
    public void formFieldNestedInTableUsesContainingTableName() {
        Table table = FormFactory.eINSTANCE.createTable();
        table.setName("Товары"); //$NON-NLS-1$
        FormField field = FormFactory.eINSTANCE.createFormField();
        table.getItems().add(field);

        HandlerRegionTarget target = resolver.resolve((EventHandlerContainer)field);

        assertEquals(FormModuleRegion.FORM_TABLE_ITEMS_EVENT_HANDLERS, target.section());
        assertEquals("Товары", target.tableNameOrNull()); //$NON-NLS-1$
    }

    @Test
    public void tableUsesItsOwnName() {
        Table table = FormFactory.eINSTANCE.createTable();
        table.setName("Строки"); //$NON-NLS-1$

        HandlerRegionTarget target = resolver.resolve((EventHandlerContainer)table);

        assertEquals(FormModuleRegion.FORM_TABLE_ITEMS_EVENT_HANDLERS, target.section());
        assertEquals("Строки", target.tableNameOrNull()); //$NON-NLS-1$
    }

    @Test
    public void decorationExtInfoResolvesToHeaderItems() {
        Decoration decoration = FormFactory.eINSTANCE.createDecoration();
        LabelDecorationExtInfo extInfo = FormFactory.eINSTANCE.createLabelDecorationExtInfo();
        decoration.setExtInfo(extInfo);

        assertEquals(FormModuleRegion.FORM_HEADER_ITEMS_EVENT_HANDLERS,
                resolver.resolve((EventHandlerContainer)extInfo).section());
    }

    @Test
    public void groupExtInfoResolvesToHeaderItems() {
        FormGroup group = FormFactory.eINSTANCE.createFormGroup();
        UsualGroupExtInfo extInfo = FormFactory.eINSTANCE.createUsualGroupExtInfo();
        group.setExtInfo(extInfo);

        assertEquals(FormModuleRegion.FORM_HEADER_ITEMS_EVENT_HANDLERS,
                resolver.resolve((EventHandlerContainer)extInfo).section());
    }

    @Test
    public void unattachedForeignVisualEntityIsUnknown() {
        Button button = FormFactory.eINSTANCE.createButton();

        assertTrue(resolver.resolve(button).isUnknown());
    }

    @Test
    public void unnamedTableIsUnknownRatherThanGuessingARegion() {
        Table table = FormFactory.eINSTANCE.createTable();

        assertTrue(resolver.resolve((EventHandlerContainer)table).isUnknown());
    }
}
