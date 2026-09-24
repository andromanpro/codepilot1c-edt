/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.views;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import org.eclipse.swt.SWT;

/**
 * Deterministic accessibility and keyboard contract for ChatView's native
 * profile selector. The actual AX projection remains owned by SWT.
 */
public final class ChatProfileSelectorAccessibility {

    /** Keyboard gestures for which ChatView supplies a portable popup fallback. */
    public enum PopupOpenKey {
        NONE,
        SPACE,
        ENTER,
        KEYPAD_ENTER,
        F4,
        ALT_ARROW_DOWN
    }

    /** Direct-child groups in the ChatView composer traversal order. */
    public enum ComposerFocusGroup {
        PROFILE_SELECTOR,
        MESSAGE_INPUT,
        ATTACHMENT_PREVIEW,
        ACTIONS
    }

    private static final List<ComposerFocusGroup> COMPOSER_TAB_ORDER = List.of(
            ComposerFocusGroup.PROFILE_SELECTOR,
            ComposerFocusGroup.MESSAGE_INPUT,
            ComposerFocusGroup.ATTACHMENT_PREVIEW,
            ComposerFocusGroup.ACTIONS);

    private ChatProfileSelectorAccessibility() {
    }

    public static List<ComposerFocusGroup> composerTabOrder() {
        return COMPOSER_TAB_ORDER;
    }

    /**
     * Attempts an explicit traversal transfer and reports whether SWT's native
     * traversal event may be suppressed. Native traversal must remain enabled
     * whenever the target cannot actually accept focus.
     */
    public static boolean shouldSuppressTraversal(
            boolean targetDisposed,
            boolean targetEnabled,
            boolean targetVisible,
            BooleanSupplier forceFocus) {
        Objects.requireNonNull(forceFocus, "forceFocus"); //$NON-NLS-1$
        return !targetDisposed
                && targetEnabled
                && targetVisible
                && forceFocus.getAsBoolean();
    }

    /**
     * Allows asynchronous native-popup focus restoration only while the
     * selector still has positive ownership of the active workbench shell and
     * focus has not moved to another control.
     */
    public static boolean shouldRestoreMousePopupFocus(
            boolean selectorMouseDown,
            boolean selectorDisposed,
            boolean ownerShellDisposed,
            Object ownerShell,
            Object selectorShell,
            Object activeShell,
            Object focusBeforePopup,
            Object currentFocus,
            Object keyboardTarget,
            Object selector) {
        return selectorMouseDown
                && !selectorDisposed
                && !ownerShellDisposed
                && ownerShell != null
                && ownerShell == selectorShell
                && ownerShell == activeShell
                && currentFocus != null
                && (currentFocus == focusBeforePopup
                        || currentFocus == keyboardTarget
                        || currentFocus == selector);
    }

    /**
     * Maps only the documented selector-open gestures. Extra modifiers are
     * deliberately rejected so Workbench and operating-system shortcuts keep
     * their native handling.
     */
    public static PopupOpenKey popupOpenKey(int keyCode, char character, int stateMask) {
        int modifiers = stateMask & SWT.MODIFIER_MASK;
        if (modifiers == SWT.ALT && keyCode == SWT.ARROW_DOWN) {
            return PopupOpenKey.ALT_ARROW_DOWN;
        }
        if (modifiers != SWT.NONE) {
            return PopupOpenKey.NONE;
        }
        if (keyCode == SWT.SPACE || character == SWT.SPACE) {
            return PopupOpenKey.SPACE;
        }
        if (keyCode == SWT.KEYPAD_CR) {
            return PopupOpenKey.KEYPAD_ENTER;
        }
        if (keyCode == SWT.CR || character == SWT.CR) {
            return PopupOpenKey.ENTER;
        }
        if (keyCode == SWT.F4) {
            return PopupOpenKey.F4;
        }
        return PopupOpenKey.NONE;
    }

    /**
     * Attempts the popup fallback and reports whether the original key event
     * may be consumed. An unmapped key, an already visible popup, or a failed
     * fallback always remains available to native SWT handling.
     */
    public static boolean shouldConsumePopupOpen(
            int keyCode,
            char character,
            int stateMask,
            boolean popupVisible,
            BooleanSupplier openPopup) {
        Objects.requireNonNull(openPopup, "openPopup"); //$NON-NLS-1$
        return !popupVisible
                && popupOpenKey(keyCode, character, stateMask) != PopupOpenKey.NONE
                && openPopup.getAsBoolean();
    }

    /** A label must contain a non-escaped SWT mnemonic marker. */
    public static boolean hasMnemonic(String label) {
        if (label == null) {
            return false;
        }
        for (int i = 0; i < label.length() - 1; i++) {
            if (label.charAt(i) == '&') {
                if (label.charAt(i + 1) == '&') {
                    i++;
                } else {
                    return true;
                }
            }
        }
        return false;
    }

    /** Validates the stable, mnemonic-free name passed to SWT accessibility APIs. */
    public static String accessibleName(String localizedName) {
        if (localizedName == null || localizedName.isBlank() || localizedName.contains("&")) { //$NON-NLS-1$
            throw new IllegalArgumentException(
                    "Profile selector accessibility name must be non-blank and mnemonic-free"); //$NON-NLS-1$
        }
        return localizedName;
    }
}
