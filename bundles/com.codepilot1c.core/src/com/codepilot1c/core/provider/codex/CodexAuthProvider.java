/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider.codex;

import java.net.http.HttpClient;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.internal.VibeCorePlugin;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.mcp.auth.IMcpAuthProvider;
import com.codepilot1c.core.mcp.auth.McpOAuthService;
import com.codepilot1c.core.mcp.auth.SecureTokenStore;
import com.codepilot1c.core.mcp.auth.SecureTokenStore.OAuthToken;

/**
 * Resolves Codex request headers from stored OAuth credentials, refreshing the access token
 * when it is near expiry.
 *
 * <p>Unlike a static API key, the access token is short-lived. Every outgoing request resolves
 * its headers through {@link #getAuthHeaders()}, which transparently refreshes against the fixed
 * Codex token endpoint and persists the rotated refresh token. Refresh is single-flight
 * ({@code synchronized}) so concurrent agent requests do not race.</p>
 */
public class CodexAuthProvider implements IMcpAuthProvider {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(CodexAuthProvider.class);

    /** Refresh when the token expires within this many seconds. */
    private static final long REFRESH_SKEW_SECONDS = 300L;

    private final SecureTokenStore tokenStore;
    private final McpOAuthService oauthService;
    private final String profileId;

    public CodexAuthProvider() {
        this(new SecureTokenStore(), new McpOAuthService(sharedHttpClient()), CodexOAuthConstants.PROFILE_ID);
    }

    public CodexAuthProvider(SecureTokenStore tokenStore, McpOAuthService oauthService, String profileId) {
        this.tokenStore = tokenStore;
        this.oauthService = oauthService;
        this.profileId = profileId != null ? profileId : CodexOAuthConstants.PROFILE_ID;
    }

    /**
     * @return {@code true} if a Codex access token is stored.
     */
    public boolean isLoggedIn() {
        return tokenStore.read(profileId).isPresent();
    }

    @Override
    public CompletableFuture<Map<String, String>> getAuthHeaders() {
        return CompletableFuture.supplyAsync(this::resolveHeaders);
    }

    private synchronized Map<String, String> resolveHeaders() {
        Optional<OAuthToken> maybeToken = tokenStore.read(profileId);
        if (maybeToken.isEmpty()) {
            return Collections.emptyMap();
        }
        OAuthToken token = maybeToken.get();
        if (shouldRefresh(token)) {
            token = refresh(token);
        }
        return buildHeaders(token);
    }

    private static boolean shouldRefresh(OAuthToken token) {
        return token.willExpireSoon(REFRESH_SKEW_SECONDS)
            && token.refreshToken() != null
            && !token.refreshToken().isBlank();
    }

    private OAuthToken refresh(OAuthToken current) {
        try {
            OAuthToken refreshed = oauthService.refreshToken(
                CodexOAuthConstants.TOKEN_URL, CodexOAuthConstants.CLIENT_ID, current.refreshToken());
            // OpenAI rotates the refresh token on every refresh; keep the previous one only if the
            // response omitted a new value.
            String refreshToken = refreshed.refreshToken() != null && !refreshed.refreshToken().isBlank()
                ? refreshed.refreshToken()
                : current.refreshToken();
            OAuthToken next = new OAuthToken(
                refreshed.accessToken(), refreshToken, refreshed.tokenType(), refreshed.expiresAtEpochSeconds());
            tokenStore.save(profileId, next);
            return next;
        } catch (Exception e) {
            LOG.warn("Codex token refresh failed for profile %s: %s", profileId, e.getMessage()); //$NON-NLS-1$
            return current;
        }
    }

    private static Map<String, String> buildHeaders(OAuthToken token) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + token.accessToken()); //$NON-NLS-1$ //$NON-NLS-2$
        String accountId = CodexJwt.extractAccountId(token.accessToken());
        if (accountId != null) {
            headers.put("chatgpt-account-id", accountId); //$NON-NLS-1$
        }
        headers.put("originator", CodexOAuthConstants.ORIGINATOR); //$NON-NLS-1$
        return headers;
    }

    @Override
    public void invalidate() {
        tokenStore.clear(profileId);
    }

    private static HttpClient sharedHttpClient() {
        VibeCorePlugin plugin = VibeCorePlugin.getDefault();
        if (plugin != null && plugin.getHttpClientFactory() != null) {
            return plugin.getHttpClientFactory().getSharedClient();
        }
        return HttpClient.newHttpClient();
    }
}
