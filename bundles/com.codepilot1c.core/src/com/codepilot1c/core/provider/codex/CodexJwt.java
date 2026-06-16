/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider.codex;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Decodes the Codex access token (a JWT) to read identity claims.
 *
 * <p>The signature is intentionally not verified: this only reads claims from the client's
 * own token (account id, plan type, email) to build request headers and UI status. It must
 * never be used as proof of identity to a third party.</p>
 */
public final class CodexJwt {

    private static final Gson GSON = new Gson();

    private CodexJwt() {
        // Utility class.
    }

    /**
     * Identity extracted from a Codex access token.
     *
     * @param accountId ChatGPT account id (required for request headers)
     * @param planType  ChatGPT plan type (e.g. {@code plus}, {@code pro}), may be {@code null}
     * @param email     account email, may be {@code null}
     */
    public record CodexIdentity(String accountId, String planType, String email) {
    }

    /**
     * Decodes the JWT payload (middle segment) without verifying the signature.
     *
     * @param jwt the token
     * @return the payload object, or {@code null} if not a decodable JWT
     */
    public static JsonObject decodePayload(String jwt) {
        if (jwt == null) {
            return null;
        }
        String[] parts = jwt.split("\\."); //$NON-NLS-1$
        if (parts.length != 3) {
            return null;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            return GSON.fromJson(new String(decoded, StandardCharsets.UTF_8), JsonObject.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extracts the ChatGPT account id from a Codex access token.
     *
     * @param accessToken the access token
     * @return the account id, or {@code null} if absent
     */
    public static String extractAccountId(String accessToken) {
        return resolveIdentity(accessToken).accountId();
    }

    /**
     * Resolves the full identity (account id, plan type, email) from a Codex access token.
     *
     * @param accessToken the access token
     * @return the identity (fields may be {@code null} when claims are missing)
     */
    public static CodexIdentity resolveIdentity(String accessToken) {
        JsonObject payload = decodePayload(accessToken);
        if (payload == null) {
            return new CodexIdentity(null, null, null);
        }
        String accountId = null;
        String planType = null;
        String email = null;
        JsonObject auth = optObject(payload, CodexOAuthConstants.JWT_AUTH_CLAIM);
        if (auth != null) {
            accountId = optString(auth, "chatgpt_account_id"); //$NON-NLS-1$
            planType = optString(auth, "chatgpt_plan_type"); //$NON-NLS-1$
        }
        JsonObject profile = optObject(payload, CodexOAuthConstants.JWT_PROFILE_CLAIM);
        if (profile != null) {
            email = optString(profile, "email"); //$NON-NLS-1$
        }
        return new CodexIdentity(accountId, planType, email);
    }

    private static JsonObject optObject(JsonObject owner, String key) {
        return owner.has(key) && owner.get(key).isJsonObject() ? owner.getAsJsonObject(key) : null;
    }

    private static String optString(JsonObject owner, String key) {
        if (owner.has(key) && owner.get(key).isJsonPrimitive()) {
            String value = owner.get(key).getAsString();
            return value != null && !value.isBlank() ? value : null;
        }
        return null;
    }
}
