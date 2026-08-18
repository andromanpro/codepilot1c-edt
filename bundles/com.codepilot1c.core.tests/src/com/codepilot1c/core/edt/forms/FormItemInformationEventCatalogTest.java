package com.codepilot1c.core.edt.forms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EObject;
import org.junit.Test;

import com._1c.g5.v8.dt.form.model.ExtInfo;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.form.model.FormVisualEntity;
import com._1c.g5.v8.dt.form.model.InputFieldExtInfo;
import com._1c.g5.v8.dt.form.service.FormItemInformationService;
import com._1c.g5.v8.dt.mcore.Event;
import com._1c.g5.v8.dt.mcore.McoreFactory;

import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

public class FormItemInformationEventCatalogTest {

    @Test
    public void usesInjectedEdtServiceForDirectAndExtensionSurfaces() {
        FormField field = FormFactory.eINSTANCE.createFormField();
        InputFieldExtInfo extInfo = FormFactory.eINSTANCE.createInputFieldExtInfo();
        field.setExtInfo(extInfo);
        Event direct = event("OnChange"); //$NON-NLS-1$
        Event extension = event("StartChoice"); //$NON-NLS-1$
        FormItemInformationService edtService = new FormItemInformationService() {
            @Override
            public List<Event> getAllowedEvents(FormVisualEntity item, EStructuralFeature feature) {
                assertSame(field, item);
                return List.of(direct);
            }

            @Override
            public ExtInfo getExtensionInfo(EObject item) {
                assertSame(field, item);
                return extInfo;
            }

            @Override
            public List<Event> getAllowedEvents(ExtInfo item) {
                assertSame(extInfo, item);
                return List.of(extension);
            }
        };
        AtomicInteger providerCalls = new AtomicInteger();
        FormItemInformationEventCatalog catalog = new FormItemInformationEventCatalog(() -> {
            providerCalls.incrementAndGet();
            return edtService;
        });

        List<EventHandlerCatalog.EventSurface> surfaces = catalog.eventSurfaces(field);
        List<Event> allEvents = catalog.allowedEvents(field);

        assertEquals(2, providerCalls.get());
        assertEquals(2, surfaces.size());
        assertSame(field, surfaces.get(0).owner());
        assertEquals(List.of(direct), surfaces.get(0).events());
        assertSame(extInfo, surfaces.get(1).owner());
        assertEquals(List.of(extension), surfaces.get(1).events());
        assertEquals(List.of(direct, extension), allEvents);
    }

    @Test
    public void propagatesServiceUnavailableWithoutMasking() {
        MetadataOperationException expected = new MetadataOperationException(
                MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                "injector unavailable", false); //$NON-NLS-1$
        FormItemInformationEventCatalog catalog = new FormItemInformationEventCatalog(() -> {
            throw expected;
        });

        try {
            catalog.allowedEvents(FormFactory.eINSTANCE.createForm());
            fail("expected MetadataOperationException"); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            assertSame(expected, e);
        }
    }

    @Test
    public void rejectsNullServiceWithExplicitCode() {
        FormItemInformationEventCatalog catalog = new FormItemInformationEventCatalog(() -> null);

        try {
            catalog.allowedEvents(FormFactory.eINSTANCE.createForm());
            fail("expected MetadataOperationException"); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.EDT_SERVICE_UNAVAILABLE, e.getCode());
        }
    }

    @Test(expected = NullPointerException.class)
    public void rejectsNullProvider() {
        new FormItemInformationEventCatalog(null);
    }

    private static Event event(String name) {
        Event event = McoreFactory.eINSTANCE.createEvent();
        event.setName(name);
        return event;
    }
}
