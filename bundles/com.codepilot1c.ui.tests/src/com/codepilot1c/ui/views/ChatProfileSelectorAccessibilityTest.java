/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.views;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.swt.SWT;
import org.junit.Test;

import com.codepilot1c.ui.views.ChatProfileSelectorAccessibility.ComposerFocusGroup;
import com.codepilot1c.ui.views.ChatProfileSelectorAccessibility.PopupOpenKey;

public class ChatProfileSelectorAccessibilityTest {

    @Test
    public void composerTabOrderStartsWithProfileThenMessageInput() {
        assertEquals(List.of(
                ComposerFocusGroup.PROFILE_SELECTOR,
                ComposerFocusGroup.MESSAGE_INPUT,
                ComposerFocusGroup.ATTACHMENT_PREVIEW,
                ComposerFocusGroup.ACTIONS),
                ChatProfileSelectorAccessibility.composerTabOrder());
    }

    @Test
    public void localizedLabelsProvideMnemonicsAndAccessibleNamesStayStable() {
        ResourceBundle english = messages(Locale.ROOT);
        assertTrue(ChatProfileSelectorAccessibility.hasMnemonic(
                english.getString("ChatView_ProfileLabel"))); //$NON-NLS-1$
        assertEquals("Agent profile for the next chat turn", //$NON-NLS-1$
                ChatProfileSelectorAccessibility.accessibleName(
                        english.getString("ChatView_ProfileAccessibleName"))); //$NON-NLS-1$

        ResourceBundle russian = messages(Locale.forLanguageTag("ru")); //$NON-NLS-1$
        assertTrue(ChatProfileSelectorAccessibility.hasMnemonic(
                russian.getString("ChatView_ProfileLabel"))); //$NON-NLS-1$
        assertEquals("Профиль агента для следующего хода чата", //$NON-NLS-1$
                ChatProfileSelectorAccessibility.accessibleName(
                        russian.getString("ChatView_ProfileAccessibleName"))); //$NON-NLS-1$
    }

    @Test
    public void traversalIsSuppressedOnlyAfterSuccessfulFocusTransfer() {
        assertTrue(ChatProfileSelectorAccessibility.shouldSuppressTraversal(
                false, true, true, () -> true));
        assertFalse(ChatProfileSelectorAccessibility.shouldSuppressTraversal(
                false, true, true, () -> false));
    }

    @Test
    public void unavailableTraversalTargetsAreSkippedWithoutAttemptingFocus() {
        AtomicBoolean attempted = new AtomicBoolean();

        assertFalse(ChatProfileSelectorAccessibility.shouldSuppressTraversal(
                true, true, true, () -> attempted.getAndSet(true)));
        assertFalse(ChatProfileSelectorAccessibility.shouldSuppressTraversal(
                false, false, true, () -> attempted.getAndSet(true)));
        assertFalse(ChatProfileSelectorAccessibility.shouldSuppressTraversal(
                false, true, false, () -> attempted.getAndSet(true)));
        assertFalse(attempted.get());

        assertFalse(ChatProfileSelectorAccessibility.hasMnemonic("Profile:")); //$NON-NLS-1$
        assertFalse(ChatProfileSelectorAccessibility.hasMnemonic("Save && close")); //$NON-NLS-1$
    }

    @Test
    public void mousePopupRestoresOnlyForSameActiveShellAndKnownFocusOwner() {
        Object shell = new Object();
        Object selector = new Object();
        Object keyboardTarget = new Object();
        Object focusBeforePopup = new Object();

        assertTrue(ChatProfileSelectorAccessibility.shouldRestoreMousePopupFocus(
                true, false, false, shell, shell, shell,
                focusBeforePopup, focusBeforePopup, keyboardTarget, selector));
        assertTrue(ChatProfileSelectorAccessibility.shouldRestoreMousePopupFocus(
                true, false, false, shell, shell, shell,
                focusBeforePopup, keyboardTarget, keyboardTarget, selector));
        assertTrue(ChatProfileSelectorAccessibility.shouldRestoreMousePopupFocus(
                true, false, false, shell, shell, shell,
                focusBeforePopup, selector, keyboardTarget, selector));
        assertFalse(ChatProfileSelectorAccessibility.shouldRestoreMousePopupFocus(
                false, false, false, shell, shell, shell,
                focusBeforePopup, focusBeforePopup, keyboardTarget, selector));
    }

    @Test
    public void mousePopupNeverReclaimsWithoutActiveShellOrKnownFocusOwner() {
        Object shell = new Object();
        Object otherShell = new Object();
        Object selector = new Object();
        Object keyboardTarget = new Object();
        Object focusBeforePopup = new Object();
        Object otherControl = new Object();

        assertFalse(ChatProfileSelectorAccessibility.shouldRestoreMousePopupFocus(
                true, false, false, shell, shell, shell,
                focusBeforePopup, null, keyboardTarget, selector));
        assertFalse(ChatProfileSelectorAccessibility.shouldRestoreMousePopupFocus(
                true, false, false, shell, shell, null,
                focusBeforePopup, focusBeforePopup, keyboardTarget, selector));
        assertFalse(ChatProfileSelectorAccessibility.shouldRestoreMousePopupFocus(
                true, false, false, shell, shell, otherShell,
                focusBeforePopup, focusBeforePopup, keyboardTarget, selector));
        assertFalse(ChatProfileSelectorAccessibility.shouldRestoreMousePopupFocus(
                true, false, false, shell, otherShell, shell,
                focusBeforePopup, focusBeforePopup, keyboardTarget, selector));
        assertFalse(ChatProfileSelectorAccessibility.shouldRestoreMousePopupFocus(
                true, false, false, shell, shell, shell,
                focusBeforePopup, otherControl, keyboardTarget, selector));
    }

    @Test
    public void mousePopupRestorationIsDisposalSafe() {
        Object shell = new Object();
        Object selector = new Object();
        Object keyboardTarget = new Object();
        Object focusBeforePopup = new Object();

        assertFalse(ChatProfileSelectorAccessibility.shouldRestoreMousePopupFocus(
                true, true, false, shell, shell, shell,
                focusBeforePopup, focusBeforePopup, keyboardTarget, selector));
        assertFalse(ChatProfileSelectorAccessibility.shouldRestoreMousePopupFocus(
                true, false, true, shell, shell, shell,
                focusBeforePopup, focusBeforePopup, keyboardTarget, selector));
        assertFalse(ChatProfileSelectorAccessibility.shouldRestoreMousePopupFocus(
                true, false, false, null, shell, shell,
                focusBeforePopup, focusBeforePopup, keyboardTarget, selector));
    }

    @Test
    public void popupOpenKeysRequireExactModifiers() {
        assertEquals(PopupOpenKey.SPACE,
                ChatProfileSelectorAccessibility.popupOpenKey(SWT.SPACE, '\0', SWT.NONE));
        assertEquals(PopupOpenKey.SPACE,
                ChatProfileSelectorAccessibility.popupOpenKey(0, SWT.SPACE, SWT.NONE));
        assertEquals(PopupOpenKey.ENTER,
                ChatProfileSelectorAccessibility.popupOpenKey(SWT.CR, '\0', SWT.NONE));
        assertEquals(PopupOpenKey.ENTER,
                ChatProfileSelectorAccessibility.popupOpenKey(0, SWT.CR, SWT.NONE));
        assertEquals(PopupOpenKey.KEYPAD_ENTER,
                ChatProfileSelectorAccessibility.popupOpenKey(SWT.KEYPAD_CR, '\0', SWT.NONE));
        assertEquals(PopupOpenKey.F4,
                ChatProfileSelectorAccessibility.popupOpenKey(SWT.F4, '\0', SWT.NONE));
        assertEquals(PopupOpenKey.ALT_ARROW_DOWN,
                ChatProfileSelectorAccessibility.popupOpenKey(SWT.ARROW_DOWN, '\0', SWT.ALT));

        int[] extraModifiers = { SWT.SHIFT, SWT.CTRL, SWT.COMMAND, SWT.ALT, SWT.ALT_GR };
        for (int modifier : extraModifiers) {
            assertEquals(PopupOpenKey.NONE,
                    ChatProfileSelectorAccessibility.popupOpenKey(SWT.SPACE, SWT.SPACE, modifier));
            assertEquals(PopupOpenKey.NONE,
                    ChatProfileSelectorAccessibility.popupOpenKey(SWT.F4, '\0', modifier));
        }
        int[] extraAltArrowModifiers = { SWT.SHIFT, SWT.CTRL, SWT.COMMAND, SWT.ALT_GR };
        for (int modifier : extraAltArrowModifiers) {
            assertEquals(PopupOpenKey.NONE,
                    ChatProfileSelectorAccessibility.popupOpenKey(
                            SWT.ARROW_DOWN, '\0', SWT.ALT | modifier));
        }
        assertEquals(PopupOpenKey.NONE,
                ChatProfileSelectorAccessibility.popupOpenKey(SWT.ARROW_DOWN, '\0', SWT.NONE));
        assertEquals(PopupOpenKey.NONE,
                ChatProfileSelectorAccessibility.popupOpenKey('b', 'b', SWT.NONE));
    }

    @Test
    public void matchingOpenKeyIsConsumedOnlyAfterFallbackSuccess() {
        AtomicInteger attempts = new AtomicInteger();
        assertTrue(ChatProfileSelectorAccessibility.shouldConsumePopupOpen(
                SWT.F4, '\0', SWT.NONE, false,
                () -> attempts.incrementAndGet() == 1));
        assertFalse(ChatProfileSelectorAccessibility.shouldConsumePopupOpen(
                SWT.KEYPAD_CR, '\0', SWT.NONE, false,
                () -> attempts.incrementAndGet() < 0));
        assertEquals(2, attempts.get());
    }

    @Test
    public void nativeHandlingRemainsForVisiblePopupUnmappedKeysAndExtraModifiers() {
        AtomicBoolean attempted = new AtomicBoolean();
        assertFalse(ChatProfileSelectorAccessibility.shouldConsumePopupOpen(
                SWT.SPACE, SWT.SPACE, SWT.NONE, true,
                () -> attempted.getAndSet(true)));
        assertFalse(ChatProfileSelectorAccessibility.shouldConsumePopupOpen(
                SWT.ARROW_DOWN, '\0', SWT.NONE, false,
                () -> attempted.getAndSet(true)));
        assertFalse(ChatProfileSelectorAccessibility.shouldConsumePopupOpen(
                SWT.ARROW_DOWN, '\0', SWT.ALT | SWT.SHIFT, false,
                () -> attempted.getAndSet(true)));
        assertFalse(attempted.get());
    }

    @Test
    public void generationTraversalDoesNotConsumeTabWhenInputIsDisabled() {
        AtomicBoolean attempted = new AtomicBoolean();
        assertFalse(ChatProfileSelectorAccessibility.shouldSuppressTraversal(
                false, false, true, () -> attempted.getAndSet(true)));
        assertFalse(attempted.get());
    }

    @Test(expected = IllegalArgumentException.class)
    public void accessibleNameRejectsMnemonicMarkers() {
        ChatProfileSelectorAccessibility.accessibleName("&Profile"); //$NON-NLS-1$
    }

    private ResourceBundle messages(Locale locale) {
        return ResourceBundle.getBundle(
                "com.codepilot1c.ui.internal.messages", //$NON-NLS-1$
                locale,
                ChatProfileSelectorAccessibility.class.getClassLoader());
    }
}
