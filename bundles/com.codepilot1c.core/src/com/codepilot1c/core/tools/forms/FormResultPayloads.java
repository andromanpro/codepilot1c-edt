/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.forms;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import com.codepilot1c.core.edt.forms.FormRecipePartialFailureException;
import com.codepilot1c.core.edt.forms.FormRecipeResult;
import com.codepilot1c.core.edt.forms.HandlerStubReport;
import com.codepilot1c.core.edt.forms.UpdateFormModelResult;

/** Shared deterministic payload builders for managed-form mutation results. */
final class FormResultPayloads {

    private FormResultPayloads() {
    }

    static void addStubBlock(JsonObject target, HandlerStubReport report) {
        target.add("handler_stubs_written", toJsonArray(report.written())); //$NON-NLS-1$
        target.add("handler_stubs_skipped_existing", toJsonArray(report.skippedExisting())); //$NON-NLS-1$
        target.addProperty("complete", report.complete()); //$NON-NLS-1$
    }

    static JsonObject formRecipeSuccess(FormRecipeResult result) {
        JsonObject structured = new JsonObject();
        structured.addProperty("form_fqn", result.formFqn()); //$NON-NLS-1$
        structured.addProperty("attributes_created", result.attributesCreated()); //$NON-NLS-1$
        structured.addProperty("attributes_updated", result.attributesUpdated()); //$NON-NLS-1$
        structured.addProperty("attributes_removed", result.attributesRemoved()); //$NON-NLS-1$
        structured.addProperty("layout_operations_applied", result.layoutOperationsApplied()); //$NON-NLS-1$
        addStubBlock(structured, result.handlerStubs());
        return structured;
    }

    static JsonObject formModelSuccess(UpdateFormModelResult result) {
        JsonObject structured = new JsonObject();
        structured.addProperty("form_fqn", result.formFqn()); //$NON-NLS-1$
        structured.addProperty("operations_applied", result.operationsApplied()); //$NON-NLS-1$
        addStubBlock(structured, result.handlerStubs());
        return structured;
    }

    static JsonObject partialFailure(FormRecipePartialFailureException failure) {
        JsonObject structured = new JsonObject();
        structured.addProperty("code", failure.getCode().name()); //$NON-NLS-1$
        structured.addProperty("recoverable", failure.isRecoverable()); //$NON-NLS-1$
        structured.addProperty("complete", false); //$NON-NLS-1$
        structured.addProperty("partial", true); //$NON-NLS-1$
        structured.addProperty("form_fqn", failure.formFqn()); //$NON-NLS-1$
        structured.addProperty("external_project", failure.externalProject()); //$NON-NLS-1$
        structured.addProperty("bm_export_verified_before_stub_phase", true); //$NON-NLS-1$
        structured.addProperty("attributes_created", failure.attributesCreated()); //$NON-NLS-1$
        structured.addProperty("attributes_updated", failure.attributesUpdated()); //$NON-NLS-1$
        structured.addProperty("attributes_removed", failure.attributesRemoved()); //$NON-NLS-1$
        structured.addProperty(
                "layout_operations_initially_committed", //$NON-NLS-1$
                failure.layoutOperationsInitiallyCommitted());
        structured.add(
                "handler_slots_initially_committed", //$NON-NLS-1$
                toJsonArray(failure.handlerSlotsInitiallyCommitted()));
        structured.add("handler_stubs_written", toJsonArray(failure.handlerStubsWritten())); //$NON-NLS-1$
        structured.add(
                "handler_stubs_skipped_existing", //$NON-NLS-1$
                toJsonArray(failure.handlerStubsSkippedExisting()));
        structured.add(
                "handler_slots_without_written_stub", //$NON-NLS-1$
                toJsonArray(failure.handlerSlotsWithoutWrittenStub()));
        structured.addProperty("failure_phase", failure.failurePhase().name()); //$NON-NLS-1$
        structured.addProperty("failed_handler", failure.failedHandler()); //$NON-NLS-1$
        structured.addProperty("rollback_status", failure.rollbackStatus().name()); //$NON-NLS-1$
        structured.addProperty("bm_state", failure.bmState().name()); //$NON-NLS-1$
        structured.addProperty("serialized_model_state", failure.serializedModelState().name()); //$NON-NLS-1$
        return structured;
    }

    static JsonArray toJsonArray(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }
}
