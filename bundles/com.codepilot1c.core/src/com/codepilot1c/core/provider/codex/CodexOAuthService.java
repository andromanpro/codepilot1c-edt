/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider.codex;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.codepilot1c.core.internal.VibeCorePlugin;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.mcp.auth.McpOAuthService;
import com.codepilot1c.core.mcp.auth.SecureTokenStore;
import com.codepilot1c.core.mcp.auth.SecureTokenStore.OAuthToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Drives the OpenAI Codex (ChatGPT subscription) OAuth login: PKCE authorization, a local
 * loopback callback server, authorization-code exchange and secure token persistence.
 *
 * <p>This service is UI-agnostic. The caller injects how the authorization URL is opened (e.g.
 * the UI bundle uses SWT {@code Program.launch}) and may complete the flow via the browser
 * callback or by submitting a pasted redirect URL.</p>
 */
public class CodexOAuthService {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(CodexOAuthService.class);
    private static final long CALLBACK_TIMEOUT_SECONDS = 300L;

    private final McpOAuthService oauthService;
    private final SecureTokenStore tokenStore;

    public CodexOAuthService() {
        this(new McpOAuthService(sharedHttpClient()), new SecureTokenStore());
    }

    public CodexOAuthService(McpOAuthService oauthService, SecureTokenStore tokenStore) {
        this.oauthService = oauthService;
        this.tokenStore = tokenStore;
    }

    /**
     * Begins a login: generates PKCE + state, starts the loopback callback server, and opens the
     * authorization URL via {@code openUrl}. The returned session completes when the browser
     * callback arrives or {@link CodexLoginSession#submitManualRedirect(String)} is called.
     *
     * @param openUrl consumer that opens a URL in the user's browser (may be {@code null})
     * @return an active login session
     */
    public CodexLoginSession beginLogin(Consumer<String> openUrl) {
        String verifier = oauthService.generateCodeVerifier();
        String challenge = oauthService.generateCodeChallenge(verifier);
        String state = generateState();
        String authorizationUrl = buildAuthorizeUrl(challenge, state);

        CodexLoginSession session = new CodexLoginSession(oauthService, tokenStore, verifier, state, authorizationUrl);
        session.start();
        if (openUrl != null) {
            try {
                openUrl.accept(authorizationUrl);
            } catch (Exception e) {
                LOG.warn("Failed to open Codex authorization URL: %s", e.getMessage()); //$NON-NLS-1$
            }
        }
        return session;
    }

    /**
     * @return {@code true} if a Codex token is already stored.
     */
    public boolean isLoggedIn() {
        return tokenStore.read(CodexOAuthConstants.PROFILE_ID).isPresent();
    }

    /**
     * Clears stored Codex credentials.
     */
    public void logout() {
        tokenStore.clear(CodexOAuthConstants.PROFILE_ID);
    }

    /**
     * @return the identity (email / plan / account id) decoded from the stored access token, or
     *         {@code null} when not signed in.
     */
    public CodexJwt.CodexIdentity currentIdentity() {
        return tokenStore.read(CodexOAuthConstants.PROFILE_ID)
            .map(token -> CodexJwt.resolveIdentity(token.accessToken()))
            .orElse(null);
    }

    static String buildAuthorizeUrl(String challenge, String state) {
        return CodexOAuthConstants.AUTHORIZE_URL + "?" + form( //$NON-NLS-1$
            "response_type", "code", //$NON-NLS-1$ //$NON-NLS-2$
            "client_id", CodexOAuthConstants.CLIENT_ID, //$NON-NLS-1$
            "redirect_uri", CodexOAuthConstants.REDIRECT_URI, //$NON-NLS-1$
            "scope", CodexOAuthConstants.SCOPE, //$NON-NLS-1$
            "code_challenge", challenge, //$NON-NLS-1$
            "code_challenge_method", "S256", //$NON-NLS-1$ //$NON-NLS-2$
            "state", state, //$NON-NLS-1$
            "id_token_add_organizations", "true", //$NON-NLS-1$ //$NON-NLS-2$
            "codex_cli_simplified_flow", "true", //$NON-NLS-1$ //$NON-NLS-2$
            "originator", CodexOAuthConstants.ORIGINATOR); //$NON-NLS-1$
    }

    private static String generateState() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static HttpClient sharedHttpClient() {
        VibeCorePlugin plugin = VibeCorePlugin.getDefault();
        if (plugin != null && plugin.getHttpClientFactory() != null) {
            return plugin.getHttpClientFactory().getSharedClient();
        }
        return HttpClient.newHttpClient();
    }

    static String form(String... kv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(encode(kv[i])).append('=').append(encode(kv[i + 1]));
        }
        return sb.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Result of a successful Codex login.
     *
     * @param accountId             ChatGPT account id
     * @param email                 account email (may be {@code null})
     * @param planType              ChatGPT plan type (may be {@code null})
     * @param expiresAtEpochSeconds access-token expiry, epoch seconds
     */
    public record CodexLoginResult(String accountId, String email, String planType, long expiresAtEpochSeconds) {
    }

    /**
     * An in-flight login. Holds the PKCE verifier, expected state and loopback callback server.
     * Resolve {@link #result()} to obtain the credentials.
     */
    public static final class CodexLoginSession implements AutoCloseable {

        private final McpOAuthService oauthService;
        private final SecureTokenStore tokenStore;
        private final String verifier;
        private final String state;
        private final String authorizationUrl;
        private final CompletableFuture<String> codeFuture = new CompletableFuture<>();
        private final CompletableFuture<CodexLoginResult> resultFuture;
        private HttpServer server;
        private volatile boolean serverAvailable;

        CodexLoginSession(McpOAuthService oauthService, SecureTokenStore tokenStore,
                String verifier, String state, String authorizationUrl) {
            this.oauthService = oauthService;
            this.tokenStore = tokenStore;
            this.verifier = verifier;
            this.state = state;
            this.authorizationUrl = authorizationUrl;
            this.resultFuture = codeFuture
                .orTimeout(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .thenApplyAsync(this::exchangeAndStore);
            this.resultFuture.whenComplete((result, error) -> stopServer());
        }

        void start() {
            try {
                server = HttpServer.create(
                    new InetSocketAddress(CodexOAuthConstants.CALLBACK_HOST, CodexOAuthConstants.CALLBACK_PORT), 0);
                server.createContext(CodexOAuthConstants.CALLBACK_PATH, this::handleCallback);
                server.start();
                serverAvailable = true;
                LOG.info("Codex OAuth callback server listening on %s:%d", //$NON-NLS-1$
                    CodexOAuthConstants.CALLBACK_HOST, Integer.valueOf(CodexOAuthConstants.CALLBACK_PORT));
            } catch (IOException e) {
                serverAvailable = false;
                LOG.warn("Codex OAuth callback server unavailable on port %d (%s); manual paste required.", //$NON-NLS-1$
                    Integer.valueOf(CodexOAuthConstants.CALLBACK_PORT), e.getMessage());
            }
        }

        /** @return the authorization URL the user must open. */
        public String authorizationUrl() {
            return authorizationUrl;
        }

        /** @return {@code true} if the loopback callback server bound successfully. */
        public boolean isServerAvailable() {
            return serverAvailable;
        }

        /** @return a future that resolves to the login result, or fails on error/timeout/cancel. */
        public CompletableFuture<CodexLoginResult> result() {
            return resultFuture;
        }

        /**
         * Completes the flow from a manually pasted redirect URL or raw authorization code.
         *
         * @param input the pasted redirect URL or code
         */
        public void submitManualRedirect(String input) {
            String[] parsed = parseAuthorizationInput(input);
            String code = parsed[0];
            String returnedState = parsed[1];
            if (returnedState != null && !returnedState.equals(state)) {
                codeFuture.completeExceptionally(new CodexOAuthException("State mismatch")); //$NON-NLS-1$
                return;
            }
            if (code == null || code.isBlank()) {
                codeFuture.completeExceptionally(new CodexOAuthException("Missing authorization code")); //$NON-NLS-1$
                return;
            }
            codeFuture.complete(code);
        }

        /** Cancels the flow and releases the callback server. */
        public void cancel() {
            if (!codeFuture.isDone()) {
                codeFuture.completeExceptionally(new CancellationException("Codex login cancelled")); //$NON-NLS-1$
            }
            stopServer();
        }

        @Override
        public void close() {
            cancel();
        }

        private void handleCallback(HttpExchange exchange) {
            try {
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                String returnedState = query.get("state"); //$NON-NLS-1$
                String code = query.get("code"); //$NON-NLS-1$
                if (state == null || !state.equals(returnedState)) {
                    writeHtml(exchange, 400, errorHtml("State mismatch.")); //$NON-NLS-1$
                    return;
                }
                if (code == null || code.isBlank()) {
                    writeHtml(exchange, 400, errorHtml("Missing authorization code.")); //$NON-NLS-1$
                    return;
                }
                writeHtml(exchange, 200, successHtml());
                codeFuture.complete(code);
            } catch (Exception e) {
                try {
                    writeHtml(exchange, 500, errorHtml("Internal error during OAuth callback.")); //$NON-NLS-1$
                } catch (IOException ignored) {
                    // Best effort.
                }
            }
        }

        private CodexLoginResult exchangeAndStore(String code) {
            try {
                OAuthToken token = oauthService.exchangeAuthorizationCode(
                    CodexOAuthConstants.TOKEN_URL, CodexOAuthConstants.CLIENT_ID, code, verifier,
                    CodexOAuthConstants.REDIRECT_URI);
                CodexJwt.CodexIdentity identity = CodexJwt.resolveIdentity(token.accessToken());
                if (identity.accountId() == null) {
                    throw new CodexOAuthException("Failed to extract accountId from token"); //$NON-NLS-1$
                }
                tokenStore.save(CodexOAuthConstants.PROFILE_ID, token);
                return new CodexLoginResult(
                    identity.accountId(), identity.email(), identity.planType(), token.expiresAtEpochSeconds());
            } catch (CodexOAuthException e) {
                throw new CompletionException(e);
            } catch (Exception e) {
                throw new CompletionException(new CodexOAuthException("Codex token exchange failed: " + e.getMessage(), e)); //$NON-NLS-1$
            }
        }

        private void stopServer() {
            HttpServer current = server;
            if (current != null) {
                server = null;
                try {
                    current.stop(0);
                } catch (Exception ignored) {
                    // Best effort.
                }
            }
        }
    }

    /**
     * Parses a pasted authorization input into {@code [code, state]}. Accepts a full redirect URL,
     * a {@code code#state} pair, a {@code code=...&state=...} query fragment, or a raw code.
     *
     * @param input the pasted value
     * @return a two-element array {@code [code, state]}; elements may be {@code null}
     */
    static String[] parseAuthorizationInput(String input) {
        String value = input == null ? "" : input.trim(); //$NON-NLS-1$
        if (value.isEmpty()) {
            return new String[] { null, null };
        }
        try {
            URI uri = URI.create(value);
            if (uri.getRawQuery() != null) {
                Map<String, String> query = parseQuery(uri.getRawQuery());
                return new String[] { query.get("code"), query.get("state") }; //$NON-NLS-1$ //$NON-NLS-2$
            }
        } catch (Exception ignored) {
            // Not a URL; fall through.
        }
        if (value.contains("#")) { //$NON-NLS-1$
            String[] split = value.split("#", 2); //$NON-NLS-1$
            return new String[] { split[0], split.length > 1 ? split[1] : null };
        }
        if (value.contains("code=")) { //$NON-NLS-1$
            Map<String, String> query = parseQuery(value);
            return new String[] { query.get("code"), query.get("state") }; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return new String[] { value, null };
    }

    static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> result = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return result;
        }
        for (String pair : rawQuery.split("&")) { //$NON-NLS-1$
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }

    private static void writeHtml(HttpExchange exchange, int status, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8"); //$NON-NLS-1$ //$NON-NLS-2$
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static String successHtml() {
        return "<html><head><meta charset=\"utf-8\"></head><body style=\"font-family:sans-serif\">" //$NON-NLS-1$
            + "<h2>Вход в ChatGPT выполнен</h2><p>Можно закрыть это окно и вернуться в 1C:EDT.</p>" //$NON-NLS-1$
            + "</body></html>"; //$NON-NLS-1$
    }

    private static String errorHtml(String message) {
        return "<html><head><meta charset=\"utf-8\"></head><body style=\"font-family:sans-serif\">" //$NON-NLS-1$
            + "<h2>Ошибка авторизации</h2><p>" + message + "</p></body></html>"; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
