/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.internal;

/**
 * Runtime signal shared by the headless application and core runtime gateways.
 */
public final class HeadlessModeSignal {

    public static final String APPLICATION_ID = "com.codepilot1c.core.headless"; //$NON-NLS-1$
    public static final String HEADLESS_PROPERTY = "codepilot1c.headless"; //$NON-NLS-1$
    public static final String ECLIPSE_APPLICATION_PROPERTY = "eclipse.application"; //$NON-NLS-1$

    private final String previousHeadlessValue;
    private final String previousApplicationValue;
    private boolean restored;

    private HeadlessModeSignal(String previousHeadlessValue, String previousApplicationValue) {
        this.previousHeadlessValue = previousHeadlessValue;
        this.previousApplicationValue = previousApplicationValue;
    }

    /**
     * Sets the explicit CodePilot headless signal for the lifetime of an application invocation.
     *
     * @return a handle that restores the previous process properties
     */
    public static synchronized HeadlessModeSignal activate() {
        HeadlessModeSignal signal = new HeadlessModeSignal(
                System.getProperty(HEADLESS_PROPERTY),
                System.getProperty(ECLIPSE_APPLICATION_PROPERTY));
        System.setProperty(HEADLESS_PROPERTY, Boolean.TRUE.toString());
        System.setProperty(ECLIPSE_APPLICATION_PROPERTY, APPLICATION_ID);
        return signal;
    }

    /** Restores the process properties that were present before activation. */
    public synchronized void restore() {
        if (restored) {
            return;
        }
        restoreProperty(HEADLESS_PROPERTY, previousHeadlessValue);
        restoreProperty(ECLIPSE_APPLICATION_PROPERTY, previousApplicationValue);
        restored = true;
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
