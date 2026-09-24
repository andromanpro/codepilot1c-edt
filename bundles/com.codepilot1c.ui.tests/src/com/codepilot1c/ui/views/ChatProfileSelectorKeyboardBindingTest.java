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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** SWT-harness regression for the production listener/focus/traversal wiring. */
public class ChatProfileSelectorKeyboardBindingTest {

    private Display display;
    private Shell shell;
    private Canvas keyboardTarget;
    private Combo selector;
    private Text input;
    private Menu popup;

    @Before
    public void setUp() {
        display = Display.getCurrent();
        assertNotNull("PDE UI test must run on the SWT UI thread", display); //$NON-NLS-1$
        shell = new Shell(display);
        shell.setLayout(new GridLayout());
        keyboardTarget = new Canvas(shell, SWT.NONE);
        selector = new Combo(shell, SWT.READ_ONLY | SWT.DROP_DOWN);
        selector.setItems("Build", "Plan"); //$NON-NLS-1$ //$NON-NLS-2$
        selector.select(0);
        input = new Text(shell, SWT.BORDER);
        popup = new Menu(selector);
    }

    @After
    public void tearDown() {
        if (shell != null && !shell.isDisposed()) {
            shell.dispose();
        }
    }

    @Test
    public void openListenerIsInstalledOnRealKeyboardTargetAndNativeCombo() {
        AtomicInteger opens = new AtomicInteger();
        ChatProfileSelectorKeyboardBinding.installOpenKeyListeners(
                keyboardTarget, selector, popup,
                () -> opens.incrementAndGet() == 1);

        assertEquals(1, keyboardTarget.getListeners(SWT.KeyDown).length);
        assertEquals(1, selector.getListeners(SWT.KeyDown).length);

        Event f4 = keyEvent(SWT.F4, '\0', SWT.NONE);
        keyboardTarget.notifyListeners(SWT.KeyDown, f4);
        assertEquals(1, opens.get());
        assertFalse(f4.doit);

        Event extraModifier = keyEvent(SWT.ARROW_DOWN, '\0', SWT.ALT | SWT.SHIFT);
        keyboardTarget.notifyListeners(SWT.KeyDown, extraModifier);
        assertEquals(1, opens.get());
        assertTrue(extraModifier.doit);
    }

    @Test
    public void traversalMovesToKeyboardTargetAndConsumesOnlySuccessfulTransfers() {
        shell.open();
        shell.forceActive();
        while (display.readAndDispatch()) {
            // Drain activation/focus notifications before asserting ownership.
        }
        ChatProfileSelectorKeyboardBinding.installTraversal(
                input, keyboardTarget, input);

        assertTrue(input.forceFocus());
        Event reverse = traverseEvent(SWT.TRAVERSE_TAB_PREVIOUS);
        input.notifyListeners(SWT.Traverse, reverse);
        assertFalse(reverse.doit);
        assertSame(keyboardTarget, display.getFocusControl());

        input.setEnabled(false);
        Event disabledForward = traverseEvent(SWT.TRAVERSE_TAB_NEXT);
        keyboardTarget.notifyListeners(SWT.Traverse, disabledForward);
        assertTrue(disabledForward.doit);
        assertSame(keyboardTarget, display.getFocusControl());
    }

    private Event keyEvent(int keyCode, char character, int stateMask) {
        Event event = new Event();
        event.keyCode = keyCode;
        event.character = character;
        event.stateMask = stateMask;
        event.doit = true;
        return event;
    }

    private Event traverseEvent(int detail) {
        Event event = new Event();
        event.detail = detail;
        event.doit = true;
        return event;
    }
}
