package com.codepilot1c.core.edt.forms;

import java.util.List;
import java.util.Objects;

import org.eclipse.emf.ecore.resource.Resource;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.form.model.FormVisualEntity;
import com._1c.g5.v8.dt.form.service.FormItemInformationService;
import com._1c.g5.v8.dt.mcore.Event;

import com.codepilot1c.core.edt.BmObjectHelper;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * Default {@link EventHandlerCatalog} implementation delegating to the platform
 * {@link FormItemInformationService}.
 *
 * <p>The platform service is supplied by the EDT gateway, so consumers stay
 * isolated behind the {@link EventHandlerCatalog} seam.</p>
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
        Objects.requireNonNull(item, "item"); //$NON-NLS-1$
        FormItemInformationService service = serviceProvider.get();
        if (service == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                    "FormItemInformationService provider returned null", false); //$NON-NLS-1$
        }

        Resource resource = item.eResource();
        if (resource == null) {
            IBmObject top = BmObjectHelper.safeTopObject(item);
            resource = top != null ? top.eResource() : null;
        }
        if (resource == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                    "Form item is not attached to a BM resource; event catalog cannot be resolved for " //$NON-NLS-1$
                            + item.eClass().getName(),
                    false);
        }
        return service.getAllowedEvents(item, resource);
    }
}
