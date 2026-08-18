package com.codepilot1c.core.edt.forms;

import java.util.List;

import com._1c.g5.v8.dt.form.model.EventHandlerContainer;
import com._1c.g5.v8.dt.form.model.FormVisualEntity;
import com._1c.g5.v8.dt.mcore.Event;

/**
 * Injectable seam over the platform's event catalog for a form visual entity.
 *
 * <p>The default implementation delegates to {@code FormItemInformationService};
 * tests substitute a fake returning a fixed {@link List} of {@link Event} so
 * event resolution stays unit-testable without a live EDT/BM/OSGi session.</p>
 */
public interface EventHandlerCatalog {

    /**
     * One EDT event surface and the model object that actually owns its handler list.
     * Some visual items expose part of their events through an {@code ExtInfo}; those
     * handlers must be stored on that extension object, not on the visual item itself.
     */
    record EventSurface(EventHandlerContainer owner, List<Event> events) {
    }

    /**
     * Returns the events allowed for the given form visual entity (Form, FormField, Table, ...).
     *
     * @param item the form visual entity to enumerate allowed events for
     * @return the list of allowed events; never {@code null}
     */
    List<Event> allowedEvents(FormVisualEntity item);

    /**
     * Returns the ordered event surfaces used by the EDT properties UI. The default
     * preserves lambda/test catalog compatibility and treats the visual item itself as
     * the sole handler owner.
     */
    default List<EventSurface> eventSurfaces(FormVisualEntity item) {
        if (item instanceof EventHandlerContainer container) {
            return List.of(new EventSurface(container, allowedEvents(item)));
        }
        return List.of();
    }
}
