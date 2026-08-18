package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.emf.ecore.EStructuralFeature;
import org.junit.Test;

import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormVisualEntity;
import com._1c.g5.v8.dt.form.service.FormItemInformationService;
import com._1c.g5.v8.dt.mcore.Event;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com.codepilot1c.core.edt.forms.EventHandlerTargetResolver;

public class EdtMetadataServiceEventCatalogWiringTest {

    @Test
    public void productionConstructorUsesGatewayManagedFormInformationService() throws Exception {
        Event onOpen = McoreFactory.eINSTANCE.createEvent();
        onOpen.setName("OnOpen"); //$NON-NLS-1$
        FormItemInformationService formInformationService = new FormItemInformationService() {
            @Override
            public List<Event> getAllowedEvents(FormVisualEntity item, EStructuralFeature feature) {
                return List.of(onOpen);
            }
        };
        AtomicInteger gatewayCalls = new AtomicInteger();
        EdtMetadataGateway gateway = new EdtMetadataGateway() {
            @Override
            public FormItemInformationService getFormItemInformationService() {
                gatewayCalls.incrementAndGet();
                return formInformationService;
            }
        };
        EdtMetadataService service = new EdtMetadataService(gateway);
        Field resolverField = EdtMetadataService.class.getDeclaredField("eventHandlerTargetResolver"); //$NON-NLS-1$
        resolverField.setAccessible(true);
        EventHandlerTargetResolver resolver = (EventHandlerTargetResolver) resolverField.get(service);

        EventHandlerTargetResolver.ResolvedEvent resolved =
                resolver.resolveEvent(FormFactory.eINSTANCE.createForm(), "OnOpen"); //$NON-NLS-1$

        assertEquals(1, gatewayCalls.get());
        assertSame(onOpen, resolved.event());
    }
}
