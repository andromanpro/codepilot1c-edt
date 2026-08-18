package com.codepilot1c.core.edt.forms;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.form.model.EventHandlerContainer;
import com._1c.g5.v8.dt.form.model.ExtInfo;
import com._1c.g5.v8.dt.form.model.FormVisualEntity;
import com._1c.g5.v8.dt.form.service.FormItemInformationService;
import com._1c.g5.v8.dt.mcore.Event;

import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * EDT-backed event catalog that preserves the UI's real handler-owner split.
 */
public class FormItemInformationEventCatalog implements EventHandlerCatalog {

    /** Supplies the Guice-managed EDT platform service. */
    @FunctionalInterface
    public interface ServiceProvider {
        FormItemInformationService get();
    }

    private final ServiceProvider serviceProvider;

    public FormItemInformationEventCatalog(ServiceProvider serviceProvider) {
        this.serviceProvider = Objects.requireNonNull(serviceProvider, "serviceProvider"); //$NON-NLS-1$
    }

    @Override
    public List<Event> allowedEvents(FormVisualEntity item) {
        List<Event> result = new ArrayList<>();
        for (EventSurface surface : eventSurfaces(item)) {
            result.addAll(surface.events());
        }
        return result;
    }

    @Override
    public List<EventSurface> eventSurfaces(FormVisualEntity item) {
        Objects.requireNonNull(item, "item"); //$NON-NLS-1$
        FormItemInformationService service = serviceProvider.get();
        if (service == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                    "FormItemInformationService provider returned null", false); //$NON-NLS-1$
        }

        List<EventSurface> result = new ArrayList<>();
        List<Event> directEvents = service.getAllowedEvents(item, (EStructuralFeature) null);
        if (item instanceof EventHandlerContainer directOwner) {
            result.add(new EventSurface(directOwner, directEvents));
        }

        ExtInfo extInfo = service.getExtensionInfo(item);
        if (extInfo instanceof EventHandlerContainer extensionOwner) {
            result.add(new EventSurface(extensionOwner, service.getAllowedEvents(extInfo)));
        }
        return result;
    }
}
