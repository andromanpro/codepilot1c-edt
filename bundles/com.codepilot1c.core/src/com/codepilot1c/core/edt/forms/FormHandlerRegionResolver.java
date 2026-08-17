package com.codepilot1c.core.edt.forms;

import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.form.model.DecorationExtInfo;
import com._1c.g5.v8.dt.form.model.EventHandlerContainer;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.form.model.FormVisualEntity;
import com._1c.g5.v8.dt.form.model.GroupExtInfo;
import com._1c.g5.v8.dt.form.model.Table;

/** Resolves the exact standard form-module region from the live EMF containment chain. */
public class FormHandlerRegionResolver {

    public HandlerRegionTarget resolve(EventHandlerContainer container) {
        return resolveContainment(container);
    }

    public HandlerRegionTarget resolve(FormVisualEntity target) {
        return resolveContainment(target);
    }

    private HandlerRegionTarget resolveContainment(EObject start) {
        boolean headerItem = false;
        boolean form = false;
        for (EObject current = start; current != null; current = current.eContainer()) {
            if (current instanceof Table table) {
                String tableName = table.getName();
                return tableName == null || tableName.isBlank()
                        ? HandlerRegionTarget.unknown()
                        : HandlerRegionTarget.forTable(tableName);
            }
            if (current instanceof FormField
                    || current instanceof DecorationExtInfo
                    || current instanceof GroupExtInfo) {
                headerItem = true;
            } else if (current instanceof Form) {
                form = true;
            }
        }
        if (headerItem) {
            return HandlerRegionTarget.of(FormModuleRegion.FORM_HEADER_ITEMS_EVENT_HANDLERS);
        }
        if (form) {
            return HandlerRegionTarget.of(FormModuleRegion.FORM_EVENT_HANDLERS);
        }
        return HandlerRegionTarget.unknown();
    }
}
