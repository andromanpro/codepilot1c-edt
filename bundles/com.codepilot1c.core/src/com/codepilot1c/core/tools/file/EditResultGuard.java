/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.file;

/**
 * Guard against destructive writes that would wipe a non-empty file.
 *
 * <p>File mutation tools call this before writing computed content: when the
 * source content is non-blank and the computed result is blank, the write is
 * aborted — an empty result almost always means a bad patch or truncated
 * tool-call arguments, not an intentional edit.</p>
 */
final class EditResultGuard {

    private EditResultGuard() {
    }

    /**
     * Returns whether writing {@code after} over {@code before} would wipe
     * non-blank content.
     *
     * @param before the current file content
     * @param after the computed result content
     * @return true if the write must be aborted
     */
    static boolean wouldWipeNonEmptyFile(String before, String after) {
        return before != null && !before.isBlank() && (after == null || after.isBlank());
    }

    /**
     * Builds the failure message for a blocked wipe.
     *
     * @param path the file path for the message
     * @return model-facing rejection message
     */
    static String wipeRejectionMessage(String path) {
        return "Edit rejected: the resulting content is empty while the existing file '" + path //$NON-NLS-1$
                + "' is not. The write was aborted to prevent data loss. " //$NON-NLS-1$
                + "If you intentionally want to empty the file, use write_file with overwrite=true and allow_empty=true."; //$NON-NLS-1$
    }
}
