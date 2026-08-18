package com.codepilot1c.core.edt.forms;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.form.model.EventHandlerContainer;
import com._1c.g5.v8.dt.form.model.ExtInfo;
import com._1c.g5.v8.dt.form.model.FormVisualEntity;
import com._1c.g5.v8.dt.form.service.FormItemInformationService;
import com._1c.g5.v8.dt.mcore.Event;

/**
 * Default {@link EventHandlerCatalog} implementation delegating to the platform
 * {@link FormItemInformationService}.
 *
 * <p>This is the only class in the codebase that references
 * {@code FormItemInformationService} directly, so the platform dependency stays
 * isolated behind the {@link EventHandlerCatalog} seam.</p>
 */
public class FormItemInformationEventCatalog implements EventHandlerCatalog {

    private final Supplier<FormItemInformationService> serviceSupplier;
    private volatile FormItemInformationService service;

    /**
     * Creates a catalog backed by the EDT-managed service instance supplied by the
     * metadata gateway. {@link FormItemInformationService} has Guice-injected runtime
     * dependencies and therefore must never be instantiated with a plain constructor.
     */
    public FormItemInformationEventCatalog(Supplier<FormItemInformationService> serviceSupplier) {
        this.serviceSupplier = Objects.requireNonNull(serviceSupplier, "serviceSupplier"); //$NON-NLS-1$
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
        FormItemInformationService service = service();
        List<EventSurface> result = new ArrayList<>();

        // This is the same split used by the EDT properties UI: events belonging to
        // the visual item first, then events contributed by its concrete ExtInfo.
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

    private FormItemInformationService service() {
        FormItemInformationService result = service;
        if (result == null) {
            synchronized (this) {
                result = service;
                if (result == null) {
                    result = Objects.requireNonNull(
                            serviceSupplier.get(), "EDT FormItemInformationService"); //$NON-NLS-1$
                    service = result;
                }
            }
        }
        return result;
    }
}
