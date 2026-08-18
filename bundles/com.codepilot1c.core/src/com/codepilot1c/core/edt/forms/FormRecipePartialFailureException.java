/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.forms;

import java.util.ArrayList;
import java.util.List;

import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * Deterministic partial-state failure raised after a form mutation's BM changes
 * and initial export were already committed and verified. This is the shared
 * partial contract for {@code apply_form_recipe} and {@code mutate_form_model};
 * mutate results report measured zeroes for the attribute counters because that
 * tool does not create form attributes.
 */
public final class FormRecipePartialFailureException extends MetadataOperationException {

    private static final long serialVersionUID = 1L;

    public enum FailurePhase {
        ENSURE_MODULE,
        RESOLVE_EVENT,
        WRITE_STUB,
        UNKNOWN
    }

    public enum RollbackStatus {
        NOT_ATTEMPTED,
        NOT_ATTEMPTED_UNSAFE,
        REMOVED_AND_EXPORTED,
        SLOT_NOT_FOUND,
        FORM_NOT_FOUND,
        FAILED,
        REMOVED_EXPORT_FAILED
    }

    public enum BmState {
        INITIAL_COMMIT_REMAINS,
        HANDLER_REMOVED,
        HANDLER_ABSENCE_CONFIRMED,
        UNKNOWN
    }

    public enum SerializedModelState {
        INITIAL_EXPORT_VERIFIED,
        ROLLBACK_EXPORT_VERIFIED,
        ROLLBACK_EXPORT_FAILED,
        UNKNOWN
    }

    private final String formFqn;
    private final boolean externalProject;
    private final int attributesCreated;
    private final int attributesUpdated;
    private final int attributesRemoved;
    private final int layoutOperationsInitiallyCommitted;
    private final List<String> handlerSlotsInitiallyCommitted;
    private final List<String> handlerStubsWritten;
    private final List<String> handlerStubsSkippedExisting;
    private final List<String> handlerSlotsWithoutWrittenStub;
    private final FailurePhase failurePhase;
    private final String failedHandler;
    private final RollbackStatus rollbackStatus;
    private final BmState bmState;
    private final SerializedModelState serializedModelState;

    public FormRecipePartialFailureException(
            MetadataOperationCode code,
            String originalMessage,
            boolean recoverable,
            String formFqn,
            boolean externalProject,
            int attributesCreated,
            int attributesUpdated,
            int attributesRemoved,
            int layoutOperationsInitiallyCommitted,
            List<String> handlerSlotsInitiallyCommitted,
            List<String> handlerStubsWritten,
            List<String> handlerStubsSkippedExisting,
            FailurePhase failurePhase,
            String failedHandler,
            RollbackStatus rollbackStatus,
            BmState bmState,
            SerializedModelState serializedModelState,
            Throwable cause
    ) {
        super(code, formatMessage(
                originalMessage,
                formFqn,
                externalProject,
                attributesCreated,
                attributesUpdated,
                attributesRemoved,
                layoutOperationsInitiallyCommitted,
                handlerSlotsInitiallyCommitted,
                handlerStubsWritten,
                handlerStubsSkippedExisting,
                failurePhase,
                failedHandler,
                rollbackStatus,
                bmState,
                serializedModelState), recoverable, cause);
        this.formFqn = safe(formFqn);
        this.externalProject = externalProject;
        this.attributesCreated = attributesCreated;
        this.attributesUpdated = attributesUpdated;
        this.attributesRemoved = attributesRemoved;
        this.layoutOperationsInitiallyCommitted = layoutOperationsInitiallyCommitted;
        this.handlerSlotsInitiallyCommitted = immutableList(handlerSlotsInitiallyCommitted);
        this.handlerStubsWritten = immutableList(handlerStubsWritten);
        this.handlerStubsSkippedExisting = immutableList(handlerStubsSkippedExisting);
        this.handlerSlotsWithoutWrittenStub = withoutWrittenStub(
                this.handlerSlotsInitiallyCommitted,
                this.handlerStubsWritten,
                this.handlerStubsSkippedExisting,
                failedHandler,
                bmState);
        this.failurePhase = failurePhase != null ? failurePhase : FailurePhase.UNKNOWN;
        this.failedHandler = safe(failedHandler);
        this.rollbackStatus = rollbackStatus != null ? rollbackStatus : RollbackStatus.NOT_ATTEMPTED;
        this.bmState = bmState != null ? bmState : BmState.UNKNOWN;
        this.serializedModelState = serializedModelState != null
                ? serializedModelState
                : SerializedModelState.UNKNOWN;
    }

    public String formFqn() {
        return formFqn;
    }

    public boolean externalProject() {
        return externalProject;
    }

    public int attributesCreated() {
        return attributesCreated;
    }

    public int attributesUpdated() {
        return attributesUpdated;
    }

    public int attributesRemoved() {
        return attributesRemoved;
    }

    public int layoutOperationsInitiallyCommitted() {
        return layoutOperationsInitiallyCommitted;
    }

    public List<String> handlerSlotsInitiallyCommitted() {
        return handlerSlotsInitiallyCommitted;
    }

    public List<String> handlerStubsWritten() {
        return handlerStubsWritten;
    }

    public List<String> handlerStubsSkippedExisting() {
        return handlerStubsSkippedExisting;
    }

    public List<String> handlerSlotsWithoutWrittenStub() {
        return handlerSlotsWithoutWrittenStub;
    }

    public FailurePhase failurePhase() {
        return failurePhase;
    }

    public String failedHandler() {
        return failedHandler;
    }

    public RollbackStatus rollbackStatus() {
        return rollbackStatus;
    }

    public BmState bmState() {
        return bmState;
    }

    public SerializedModelState serializedModelState() {
        return serializedModelState;
    }

    private static String formatMessage(
            String originalMessage,
            String formFqn,
            boolean externalProject,
            int attributesCreated,
            int attributesUpdated,
            int attributesRemoved,
            int layoutOperationsInitiallyCommitted,
            List<String> handlerSlotsInitiallyCommitted,
            List<String> handlerStubsWritten,
            List<String> handlerStubsSkippedExisting,
            FailurePhase failurePhase,
            String failedHandler,
            RollbackStatus rollbackStatus,
            BmState bmState,
            SerializedModelState serializedModelState
    ) {
        List<String> initial = immutableList(handlerSlotsInitiallyCommitted);
        List<String> written = immutableList(handlerStubsWritten);
        List<String> skipped = immutableList(handlerStubsSkippedExisting);
        List<String> withoutStub = withoutWrittenStub(initial, written, skipped, failedHandler, bmState);
        return safe(originalMessage)
                + "; partial_state={form_fqn=" + safe(formFqn) //$NON-NLS-1$
                + ", external_project=" + externalProject //$NON-NLS-1$
                + ", bm_export_verified_before_stub_phase=true" //$NON-NLS-1$
                + ", attributes_created=" + attributesCreated //$NON-NLS-1$
                + ", attributes_updated=" + attributesUpdated //$NON-NLS-1$
                + ", attributes_removed=" + attributesRemoved //$NON-NLS-1$
                + ", layout_operations_initially_committed=" + layoutOperationsInitiallyCommitted //$NON-NLS-1$
                + ", handler_slots_initially_committed=" + initial //$NON-NLS-1$
                + ", handler_stubs_written=" + written //$NON-NLS-1$
                + ", handler_stubs_skipped_existing=" + skipped //$NON-NLS-1$
                + ", handler_slots_without_written_stub=" + withoutStub //$NON-NLS-1$
                + ", failure_phase=" + enumName(failurePhase, FailurePhase.UNKNOWN) //$NON-NLS-1$
                + ", failed_handler=" + safe(failedHandler) //$NON-NLS-1$
                + ", rollback_status=" + enumName(rollbackStatus, RollbackStatus.NOT_ATTEMPTED) //$NON-NLS-1$
                + ", bm_state=" + enumName(bmState, BmState.UNKNOWN) //$NON-NLS-1$
                + ", serialized_model_state=" //$NON-NLS-1$
                + enumName(serializedModelState, SerializedModelState.UNKNOWN)
                + "}"; //$NON-NLS-1$
    }

    private static List<String> withoutWrittenStub(
            List<String> initial,
            List<String> written,
            List<String> skipped,
            String failedHandler,
            BmState bmState) {
        List<String> result = new ArrayList<>(initial);
        for (String handlerName : written) {
            result.remove(handlerName);
        }
        for (String handlerName : skipped) {
            result.remove(handlerName);
        }
        if (bmState == BmState.HANDLER_REMOVED || bmState == BmState.HANDLER_ABSENCE_CONFIRMED) {
            result.removeIf(safe(failedHandler)::equals);
        }
        return List.copyOf(result);
    }

    private static List<String> immutableList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static String safe(String value) {
        return value == null ? "" : value; //$NON-NLS-1$
    }

    private static String enumName(Enum<?> value, Enum<?> fallback) {
        return value != null ? value.name() : fallback.name();
    }
}
