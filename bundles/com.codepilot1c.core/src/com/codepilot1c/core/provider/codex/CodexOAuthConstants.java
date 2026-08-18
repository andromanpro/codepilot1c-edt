/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider.codex;

/**
 * Constants for the OpenAI Codex (ChatGPT subscription) OAuth flow.
 *
 * <p>These mirror the first-party Codex CLI OAuth client. The access token issued by
 * this flow is usable only against the Codex Responses backend ({@link #CODEX_BASE_URL}),
 * not the standard OpenAI API.</p>
 */
public final class CodexOAuthConstants {

    private CodexOAuthConstants() {
        // Constants holder.
    }

    /** Public OAuth client id of the Codex CLI. */
    public static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"; //$NON-NLS-1$

    /** Authorization endpoint. */
    public static final String AUTHORIZE_URL = "https://auth.openai.com/oauth/authorize"; //$NON-NLS-1$

    /** Token endpoint (code exchange + refresh). */
    public static final String TOKEN_URL = "https://auth.openai.com/oauth/token"; //$NON-NLS-1$

    /** Redirect URI registered for the Codex client. Must match exactly. */
    public static final String REDIRECT_URI = "http://localhost:1455/auth/callback"; //$NON-NLS-1$

    /** Loopback host the local callback server binds to. */
    public static final String CALLBACK_HOST = "127.0.0.1"; //$NON-NLS-1$

    /** Fixed callback port required by {@link #REDIRECT_URI}. */
    public static final int CALLBACK_PORT = 1455;

    /** Callback path the local server handles. */
    public static final String CALLBACK_PATH = "/auth/callback"; //$NON-NLS-1$

    /** Requested OAuth scopes. */
    public static final String SCOPE = "openid profile email offline_access"; //$NON-NLS-1$

    /** Client identifier sent as the {@code originator} param/header. */
    public static final String ORIGINATOR = "codepilot1c"; //$NON-NLS-1$

    /** Auth-profile id under which Codex tokens are stored. */
    public static final String PROFILE_ID = "openai-codex"; //$NON-NLS-1$

    /** JWT claim holding ChatGPT auth metadata (account id, plan type). */
    public static final String JWT_AUTH_CLAIM = "https://api.openai.com/auth"; //$NON-NLS-1$

    /** JWT claim holding profile metadata (email). */
    public static final String JWT_PROFILE_CLAIM = "https://api.openai.com/profile"; //$NON-NLS-1$

    /** Base URL of the Codex Responses backend the issued token authenticates against. */
    public static final String CODEX_BASE_URL = "https://chatgpt.com/backend-api/codex"; //$NON-NLS-1$

    /** Default Codex model id offered in the UI. OpenAI changes the allowed set over time. */
    public static final String DEFAULT_MODEL = "gpt-5.6-sol"; //$NON-NLS-1$

    /** Default reasoning effort used when an existing configuration has no explicit value. */
    public static final String DEFAULT_REASONING_EFFORT = "medium"; //$NON-NLS-1$

    /** Reasoning effort values supported by the current GPT-5.6 model family. */
    public static final String[] REASONING_EFFORTS = {
        "none", //$NON-NLS-1$
        "low", //$NON-NLS-1$
        "medium", //$NON-NLS-1$
        "high", //$NON-NLS-1$
        "xhigh", //$NON-NLS-1$
        "max", //$NON-NLS-1$
    };

    /**
     * Candidate Codex model ids for the picker. The set of models a ChatGPT account may use is
     * controlled by OpenAI and changes over time, so this is an editable suggestion list, not a
     * guarantee — the backend rejects unavailable models with HTTP 400.
     */
    public static final String[] KNOWN_MODELS = {
        "gpt-5.6-sol", //$NON-NLS-1$
        "gpt-5.6-terra", //$NON-NLS-1$
        "gpt-5.6-luna", //$NON-NLS-1$
        "gpt-5.5", //$NON-NLS-1$
        "gpt-5.4", //$NON-NLS-1$
        "gpt-5.4-mini", //$NON-NLS-1$
        "gpt-5.3-codex-spark", //$NON-NLS-1$
    };
}
