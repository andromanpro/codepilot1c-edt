/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.views;

import java.util.Objects;
import java.util.function.BooleanSupplier;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;

/**
 * Installs the real SWT listener and traversal wiring for the profile selector.
 * The dedicated focus target avoids relying on Cocoa's native read-only Combo
 * to become the Java key-event endpoint.
 */
final class ChatProfileSelectorKeyboardBinding {

    private ChatProfileSelectorKeyboardBinding() {
    }

    static void installOpenKeyListeners(
            Control keyboardTarget,
            Combo selector,
            Menu keyboardPopup,
            BooleanSupplier openPopup) {
        Objects.requireNonNull(keyboardTarget, "keyboardTarget"); //$NON-NLS-1$
        Objects.requireNonNull(selector, "selector"); //$NON-NLS-1$
        Objects.requireNonNull(keyboardPopup, "keyboardPopup"); //$NON-NLS-1$
        Objects.requireNonNull(openPopup, "openPopup"); //$NON-NLS-1$

        Listener listener = event -> {
            if (selector.isDisposed() || keyboardPopup.isDisposed()) {
                return;
            }
            if (ChatProfileSelectorAccessibility.shouldConsumePopupOpen(
                    event.keyCode,
                    event.character,
                    event.stateMask,
                    selector.getListVisible() || keyboardPopup.getVisible(),
                    openPopup)) {
                event.doit = false;
            }
        };
        keyboardTarget.addListener(SWT.KeyDown, listener);
        // Retain the native path on platforms where the Combo itself does own
        // SWT keyboard focus. Only the focused control receives the event.
        selector.addListener(SWT.KeyDown, listener);
    }

    static void installTraversal(
            Control reverseSource,
            Control keyboardTarget,
            Control forwardTarget) {
        Objects.requireNonNull(reverseSource, "reverseSource"); //$NON-NLS-1$
        Objects.requireNonNull(keyboardTarget, "keyboardTarget"); //$NON-NLS-1$
        Objects.requireNonNull(forwardTarget, "forwardTarget"); //$NON-NLS-1$

        reverseSource.addListener(SWT.Traverse, event -> {
            if (event.detail == SWT.TRAVERSE_TAB_PREVIOUS
                    && transferFocus(keyboardTarget)) {
                event.doit = false;
            }
        });
        keyboardTarget.addListener(SWT.Traverse, event -> {
            if (event.detail == SWT.TRAVERSE_TAB_NEXT
                    && transferFocus(forwardTarget)) {
                event.doit = false;
            }
        });
    }

    private static boolean transferFocus(Control target) {
        boolean disposed = target == null || target.isDisposed();
        return ChatProfileSelectorAccessibility.shouldSuppressTraversal(
                disposed,
                !disposed && target.isEnabled(),
                !disposed && target.isVisible(),
                () -> target.forceFocus());
    }
}
