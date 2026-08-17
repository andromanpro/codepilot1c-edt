package com.codepilot1c.core.edt.forms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.junit.Test;

import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormVisualEntity;
import com._1c.g5.v8.dt.form.service.FormItemInformationService;
import com._1c.g5.v8.dt.mcore.Event;
import com._1c.g5.v8.dt.mcore.McoreFactory;

import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

public class FormItemInformationEventCatalogTest {

    @Test
    public void delegatesToProvidedServiceWithAttachedResource() {
        Event expected = McoreFactory.eINSTANCE.createEvent();
        StubFormItemInformationService service = new StubFormItemInformationService(List.of(expected));
        FormItemInformationEventCatalog catalog = new FormItemInformationEventCatalog(() -> service);
        Form form = attachedForm();

        List<Event> actual = catalog.allowedEvents(form);

        assertEquals(List.of(expected), actual);
        assertSame(form.eResource(), service.resource);
        assertEquals(1, service.calls);
    }

    @Test
    public void resolvesServiceOnEachCall() {
        StubFormItemInformationService service = new StubFormItemInformationService(List.of());
        AtomicInteger providerCalls = new AtomicInteger();
        FormItemInformationEventCatalog catalog = new FormItemInformationEventCatalog(() -> {
            providerCalls.incrementAndGet();
            return service;
        });
        Form form = attachedForm();

        catalog.allowedEvents(form);
        catalog.allowedEvents(form);

        assertEquals(2, providerCalls.get());
        assertEquals(2, service.calls);
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
            assertEquals(MetadataOperationCode.EDT_SERVICE_UNAVAILABLE, e.getCode());
        }
    }

    @Test
    public void detachedItemFailsWithExplicitServiceUnavailable() {
        StubFormItemInformationService service = new StubFormItemInformationService(List.of());
        FormItemInformationEventCatalog catalog = new FormItemInformationEventCatalog(() -> service);

        try {
            catalog.allowedEvents(FormFactory.eINSTANCE.createForm());
            fail("expected MetadataOperationException"); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.EDT_SERVICE_UNAVAILABLE, e.getCode());
            assertTrue(e.getMessage().contains("not attached to a BM resource")); //$NON-NLS-1$
            assertEquals(0, service.calls);
        }
    }

    @Test(expected = NullPointerException.class)
    public void rejectsNullProvider() {
        new FormItemInformationEventCatalog(null);
    }

    private static Form attachedForm() {
        Form form = FormFactory.eINSTANCE.createForm();
        Resource resource = new ResourceImpl();
        resource.getContents().add(form);
        return form;
    }

    private static final class StubFormItemInformationService extends FormItemInformationService {
        private final List<Event> events;
        private Resource resource;
        private int calls;

        private StubFormItemInformationService(List<Event> events) {
            this.events = events;
        }

        @Override
        public List<Event> getAllowedEvents(FormVisualEntity item, Resource resource) {
            this.resource = resource;
            calls++;
            return events;
        }
    }
}
