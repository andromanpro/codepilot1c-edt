/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provider-neutral content-security engine for GSD tools.
 *
 * <p>Pure-Java, zero external dependencies. Handles:
 * <ul>
 *   <li><b>Content caps</b> — max sizes per {@link ContentKind}.</li>
 *   <li><b>Sanitization</b> — strip invisible/dangerous Unicode from untrusted text.</li>
 *   <li><b>Secret redaction</b> — deterministic pattern-based masking of
 *       Bearer tokens, API keys, private-key blocks, password assignments, etc.
 *       Raw secret values are <em>never</em> logged or stored in findings.</li>
 *   <li><b>Injection scan</b> — detects instruction-override, system-prompt extraction,
 *       tool-call impersonation and similar attack vectors with typed
 *       {@link Severity} and human-readable reasons.</li>
 *   <li><b>Policy API</b> — {@link Policy} lets callers configure
 *       {@code blockHigh}, {@code blockMedium}, {@code blockClean} with clear
 *       block-or-pass semantics. Default policy blocks HIGH only.</li>
 * </ul>
 *
 * <p>This class deliberately does <em>not</em> attempt to identify secrets
 * by inspecting environment-variable values.</p>
 */
public final class GsdContentSecurity {

	// ─── public nested types ─────────────────────────────────────────────

	/**
	 * Severity of a security finding.
	 */
	public enum Severity {
		/** No issue detected. */
		CLEAN,
		/** Suspicious — flag for review but do not block. */
		MEDIUM,
		/** Dangerous — block under default policy. */
		HIGH
	}

	/**
	 * Category of GSD content subject to size caps.
	 */
	public enum ContentKind {
		/** Goal description text. */
		GOAL(8_000),
		/** Decision / rationale text. */
		DECISION(16_000),
		/** Evidence text supporting a decision. */
		EVIDENCE(32_000),
		/** Tool-call result payload. */
		TOOL_RESULT(64_000),
		/** Untrusted external text entering the pipeline. */
		EXTERNAL_TEXT(32_000);

		private final int defaultMaxChars;

		ContentKind(int defaultMaxChars) {
			this.defaultMaxChars = defaultMaxChars;
		}

		public int defaultMaxChars() {
			return defaultMaxChars;
		}
	}

	/**
	 * Immutable security finding.
	 *
	 * <p>Never contains raw matched secret text — only
	 * {@code ruleId}, {@code severity}, and {@code reason}.</p>
	 */
	public static final class Finding {
		private final String ruleId;
		private final Severity severity;
		private final String reason;

		public Finding(String ruleId, Severity severity, String reason) {
			if (ruleId == null) throw new IllegalArgumentException("ruleId is null"); //$NON-NLS-1$
			if (severity == null) throw new IllegalArgumentException("severity is null"); //$NON-NLS-1$
			if (reason == null) throw new IllegalArgumentException("reason is null"); //$NON-NLS-1$
			this.ruleId = ruleId;
			this.severity = severity;
			this.reason = reason;
		}

		public String ruleId() { return ruleId; }
		public Severity severity() { return severity; }
		public String reason() { return reason; }

		@Override public String toString() {
			return "[" + severity + "] " + ruleId + ": " + reason; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}

		@Override public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof Finding)) return false;
			Finding f = (Finding) o;
			return ruleId.equals(f.ruleId) && severity == f.severity && reason.equals(f.reason);
		}

		@Override public int hashCode() {
			return 31 * (31 * ruleId.hashCode() + severity.hashCode()) + reason.hashCode();
		}
	}

	/**
	 * Result of scanning + applying a {@link Policy}.
	 */
	public static final class Report {
		private final String sanitizedText;
		private final List<Finding> findings;
		private final boolean blocked;

		public Report(String sanitizedText, List<Finding> findings, boolean blocked) {
			if (sanitizedText == null) throw new IllegalArgumentException("sanitizedText is null"); //$NON-NLS-1$
			if (findings == null) throw new IllegalArgumentException("findings is null"); //$NON-NLS-1$
			this.sanitizedText = sanitizedText;
			this.findings = Collections.unmodifiableList(new ArrayList<>(findings));
			this.blocked = blocked;
		}

		public String sanitizedText() { return sanitizedText; }
		public List<Finding> findings() { return findings; }
		public boolean blocked() { return blocked; }
		public boolean isClean() { return findings.isEmpty(); }

		public Severity highestSeverity() {
			for (Finding f : findings) {
				if (f.severity() == Severity.HIGH) return Severity.HIGH;
			}
			for (Finding f : findings) {
				if (f.severity() == Severity.MEDIUM) return Severity.MEDIUM;
			}
			return Severity.CLEAN;
		}
	}

	/**
	 * Configurable security policy.
	 *
	 * <p>All three flags express whether the corresponding severity level is <b>blocked</b>
	 * (causes {@link Report#blocked()} to be {@code true}).
	 *
	 * <h3>Truth table</h3>
	 * <table border="1" cellpadding="4" cellspacing="0">
	 * <tr><th>blockHigh</th><th>blockMedium</th><th>blockClean</th><th>HIGH finding</th><th>MEDIUM only</th><th>No findings</th></tr>
	 * <tr><td>true</td><td>true</td><td>true</td><td>✅ blocked</td><td>✅ blocked</td><td>✅ blocked</td></tr>
	 * <tr><td><b>true</b></td><td><b>false</b></td><td><b>false</b></td><td>✅ blocked</td><td>❌ passes</td><td>❌ passes</td></tr>
	 * <tr><td>false</td><td>false</td><td>false</td><td>❌ passes</td><td>❌ passes</td><td>❌ passes</td></tr>
	 * <tr><td>false</td><td>true</td><td>false</td><td>❌ passes</td><td>✅ blocked</td><td>❌ passes</td></tr>
	 * </table>
	 *
	 * <p><b>Default:</b> {@code blockHigh=true, blockMedium=false, blockClean=false}
	 * — only HIGH findings cause blocking.</p>
	 */
	public static final class Policy {
		private final boolean blockHigh;
		private final boolean blockMedium;
		private final boolean blockClean;

		private Policy(boolean blockHigh, boolean blockMedium, boolean blockClean) {
			this.blockHigh = blockHigh;
			this.blockMedium = blockMedium;
			this.blockClean = blockClean;
		}

		/**
		 * The default policy — block HIGH only; MEDIUM findings are reported but do not
		 * block, and clean text passes.
		 *
		 * <p>Equivalent to {@code of(true, false, false)}.
		 */
		public static Policy defaults() {
			return new Policy(true, false, false);
		}

		/**
		 * Create a custom policy with explicit block semantics.
		 *
		 * @param blockHigh   when {@code true}, any HIGH finding causes {@link Report#blocked()} to be true
		 * @param blockMedium when {@code true}, any MEDIUM finding (with no HIGH) causes blocking
		 * @param blockClean  when {@code true}, text with no findings at all is blocked
		 */
		public static Policy of(boolean blockHigh, boolean blockMedium, boolean blockClean) {
			return new Policy(blockHigh, blockMedium, blockClean);
		}

		/** When {@code true}, a HIGH finding causes {@link Report#blocked()} to be true. */
		boolean blockHigh() { return blockHigh; }
		/** When {@code true}, a MEDIUM-only finding causes {@link Report#blocked()} to be true. */
		boolean blockMedium() { return blockMedium; }
		/** When {@code true}, clean text (no findings) causes {@link Report#blocked()} to be true. */
		boolean blockClean() { return blockClean; }
	}

	/**
	 * Immutable content-cap configuration.
	 * Each {@link ContentKind} has an independent character limit.
	 *
	 * <p>Usage:
	 * <pre>
	 * Caps.defaults()
	 *     .with(ContentKind.GOAL, 100)
	 *     .with(ContentKind.EVIDENCE, 500)
	 * </pre>
	 * Each {@code with(...)} call returns a new {@code Caps} instance;
	 * the original is never mutated.
	 */
	public static final class Caps {
		private final int[] limits;

		private Caps(int[] limits) {
			this.limits = limits.clone();
		}

		/** Caps with all defaults. */
		public static Caps defaults() {
			int[] limits = new int[ContentKind.values().length];
			for (ContentKind k : ContentKind.values()) {
				limits[k.ordinal()] = k.defaultMaxChars();
			}
			return new Caps(limits);
		}

		/**
		 * Convenience factory: caps with a single override.
		 * Equivalent to {@code defaults().with(kind, maxChars)}.
		 */
		public static Caps of(ContentKind kind, int maxChars) {
			return defaults().with(kind, maxChars);
		}

		/**
		 * Returns a new {@code Caps} with the limit for {@code kind} overridden to
		 * {@code maxChars}. This instance is not modified.
		 *
		 * @param kind     the content kind to override
		 * @param maxChars the new limit (negative values are treated as 0)
		 * @return a new immutable {@code Caps} with the override applied
		 */
		public Caps with(ContentKind kind, int maxChars) {
			int[] copy = this.limits.clone();
			copy[kind.ordinal()] = Math.max(0, maxChars);
			return new Caps(copy);
		}

		int forKind(ContentKind kind) {
			return limits[kind.ordinal()];
		}
	}

	// ─── private constants ───────────────────────────────────────────────

	/** Invisible / dangerous Unicode code points to strip. */
	private static final Pattern INVISIBLE_UNICODE = Pattern.compile(
			"[\u200B-\u200F\u2028-\u202F\uFEFF\u00AD\u200E\u200F]" //$NON-NLS-1$
	);

	/** Unicode tag block U+E0000–E007F (invisible instruction injection). */
	private static final Pattern UNICODE_TAG_BLOCK = Pattern.compile(
			"[\uDB40\uDC00-\uDB40\uDC7F]" //$NON-NLS-1$
	);

	// ─── Secret redaction patterns ───────────────────────────────────────
	// Each pattern captures the secret value in group 1.
	// Replacement is deterministic: "[REDACTED:<ruleId>]".
	// The raw secret is never logged or stored in any finding.

	private static final class SecretPattern {
		final Pattern regex;
		final String ruleId;

		SecretPattern(String regex, String ruleId) {
			this.regex = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
			this.ruleId = ruleId;
		}
	}

	private static final SecretPattern[] SECRET_PATTERNS = {
		// Bearer token: "Bearer <token>"
		new SecretPattern("(Bearer\\s+)([A-Za-z0-9\\-._~+/]+=*)", "SECRET-BEARER"), //$NON-NLS-1$ //$NON-NLS-2$

		// API key assignment: api_key, apikey, API_KEY = "value" or 'value'
		new SecretPattern("((?:api[_-]?key|apikey)\\s*[:=]\\s*[\"'])([^\"']+)", "SECRET-API-KEY"), //$NON-NLS-1$ //$NON-NLS-2$

		// Generic token assignment: token = "value"
		new SecretPattern("((?:token|auth_token|access_token|id_token|refresh_token)\\s*[:=]\\s*[\"'])([^\"']+)", "SECRET-TOKEN"), //$NON-NLS-1$ //$NON-NLS-2$

		// Password assignment: password = "value"
		new SecretPattern("((?:password|passwd|pwd)\\s*[:=]\\s*[\"'])([^\"']+)", "SECRET-PASSWORD"), //$NON-NLS-1$ //$NON-NLS-2$

		// Private key block
		new SecretPattern("(-----BEGIN(?: RSA| EC| DSA| OPENSSH)? PRIVATE KEY-----)([\\s\\S]*?)(-----END(?: RSA| EC| DSA| OPENSSH)? PRIVATE KEY-----)", "SECRET-PRIVATE-KEY"), //$NON-NLS-1$ //$NON-NLS-2$

		// Secret assignment: secret, client_secret, etc.
		new SecretPattern("((?:client_secret|secret_key|secret)\\s*[:=]\\s*[\"'])([^\"']+)", "SECRET-GENERIC"), //$NON-NLS-1$ //$NON-NLS-2$
	};

	// ─── Injection patterns ──────────────────────────────────────────────

	private static final class InjectionPattern {
		final Pattern regex;
		final Severity severity;
		final String ruleId;
		final String reason;

		InjectionPattern(String regex, Severity severity, String ruleId, String reason) {
			this.regex = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
			this.severity = severity;
			this.ruleId = ruleId;
			this.reason = reason;
		}
	}

	private static final InjectionPattern[] INJECTION_PATTERNS = {
		// Instruction override attempts — HIGH
		new InjectionPattern(
				"ignore\\s+(all\\s+)?(previous|above)\\s+(instructions|prompts|rules|directives)", //$NON-NLS-1$
				Severity.HIGH, "INJECT-IGNORE-PREVIOUS", //$NON-NLS-1$
				"Instruction override attempt: 'ignore previous instructions'"), //$NON-NLS-1$

		new InjectionPattern(
				"disregard\\s+(all\\s+)?(previous|above|prior)", //$NON-NLS-1$
				Severity.HIGH, "INJECT-DISREGARD-PREVIOUS", //$NON-NLS-1$
				"Instruction override attempt: 'disregard previous'"), //$NON-NLS-1$

		new InjectionPattern(
				"forget\\s+(all\\s+)?(your\\s+)?(instructions|rules|system prompt)", //$NON-NLS-1$
				Severity.HIGH, "INJECT-FORGET", //$NON-NLS-1$
				"Instruction override attempt: 'forget instructions'"), //$NON-NLS-1$

		// Role/identity hijacking — HIGH
		new InjectionPattern(
				"you\\s+are\\s+now\\s+(?!a\\s+plan)(?!a\\s+phase)", //$NON-NLS-1$
				Severity.HIGH, "INJECT-ROLE-HIJACK", //$NON-NLS-1$
				"Role/identity manipulation: 'you are now …'"), //$NON-NLS-1$

		new InjectionPattern(
				"act\\s+as\\s+(?!plan|phase|wave)", //$NON-NLS-1$
				Severity.HIGH, "INJECT-ACT-AS", //$NON-NLS-1$
				"Role manipulation: 'act as …'"), //$NON-NLS-1$

		new InjectionPattern(
				"pretend\\s+(you(?:'re| are)\\s+|to\\s+be\\s+)", //$NON-NLS-1$
				Severity.HIGH, "INJECT-PRETEND", //$NON-NLS-1$
				"Role manipulation: 'pretend you are …'"), //$NON-NLS-1$

		// System prompt extraction — HIGH
		new InjectionPattern(
				"(?:print|output|reveal|show|display|repeat)\\s+(your\\s+)?(system\\s+)?(prompt|instructions|rules)", //$NON-NLS-1$
				Severity.HIGH, "INJECT-SYSTEM-PROMPT-EXTRACT", //$NON-NLS-1$
				"System prompt extraction attempt"), //$NON-NLS-1$

		new InjectionPattern(
				"what\\s+(are|is)\\s+(your\\s+)?(system\\s+)?(prompt|instructions)", //$NON-NLS-1$
				Severity.HIGH, "INJECT-SYSTEM-PROMPT-QUERY", //$NON-NLS-1$
				"System prompt query attempt"), //$NON-NLS-1$

		// System-message tag injection — HIGH
		new InjectionPattern(
				"<\\/?\\s*(?:system|assistant|human|user)\\s*>", //$NON-NLS-1$
				Severity.HIGH, "INJECT-SYSTEM-TAG", //$NON-NLS-1$
				"System/role tag injection detected"), //$NON-NLS-1$

		new InjectionPattern(
				"\\[(SYSTEM|INST|\\/INST)\\]", //$NON-NLS-1$
				Severity.HIGH, "INJECT-SYSTEM-MARKER", //$NON-NLS-1$
				"System instruction marker detected"), //$NON-NLS-1$

		new InjectionPattern(
				"<<\\s*\\/?\\s*SYS\\s*>>", //$NON-NLS-1$
				Severity.HIGH, "INJECT-LLAMA-SYS", //$NON-NLS-1$
				"Llama-style SYS delimiter detected"), //$NON-NLS-1$

		// Tool-call impersonation — HIGH
		new InjectionPattern(
				"(?:run|execute|call|invoke)\\s+(the\\s+)?(bash|shell|exec|spawn|terminal)\\s+(tool|command)?", //$NON-NLS-1$
				Severity.HIGH, "INJECT-TOOL-CALL-IMPERSONATION", //$NON-NLS-1$
				"Tool-call impersonation: attempt to invoke shell/bash"), //$NON-NLS-1$

		// Data exfiltration — HIGH
		new InjectionPattern(
				"(?:send|post|fetch|curl|wget)\\s+(?:to|from)\\s+https?://", //$NON-NLS-1$
				Severity.HIGH, "INJECT-EXFILTRATION", //$NON-NLS-1$
				"Data exfiltration attempt detected"), //$NON-NLS-1$

		new InjectionPattern(
				"(?:base64|btoa|encode)\\s+(and\\s+)?(?:send|exfiltrate|output)", //$NON-NLS-1$
				Severity.HIGH, "INJECT-EXFILTRATION-ENCODED", //$NON-NLS-1$
				"Encoded exfiltration attempt detected"), //$NON-NLS-1$

		// Character-spacing obfuscation — MEDIUM
		// Only triggers when de-spaced text matches known dangerous keywords/phrases.
		// Does NOT fire on innocent spaced sequences like "a b c d e".
		// See {@code OBFUSCATED_KEYWORDS} below.
		new InjectionPattern(
				"\\b(\\w\\s){5,}\\w\\b", //$NON-NLS-1$
				Severity.MEDIUM, "INJECT-CHAR-SPACING", //$NON-NLS-1$
				"Character-spacing obfuscation detected"), //$NON-NLS-1$

		// From-now-on authority escalation — HIGH
		new InjectionPattern(
				"from\\s+now\\s+on,?\\s+you\\s+(?:are|will|should|must)", //$NON-NLS-1$
				Severity.HIGH, "INJECT-AUTHORITY-ESCALATION", //$NON-NLS-1$
				"Authority escalation: 'from now on, you …'"), //$NON-NLS-1$

		// Override system prompt — HIGH
		new InjectionPattern(
				"override\\s+(system|previous)\\s+(prompt|instructions|rules)", //$NON-NLS-1$
				Severity.HIGH, "INJECT-OVERRIDE-SYSTEM", //$NON-NLS-1$
				"System prompt override attempt"), //$NON-NLS-1$
	};

	// ─── private state ───────────────────────────────────────────────────

	private final Caps caps;
	private final Policy policy;

	/**
	 * Keywords that, when found after removing spaces from a CHAR-SPACING match,
	 * indicate a deliberate obfuscation attempt. Innocent spaced text like
	 * "a b c d e" will not match any of these and therefore won't be flagged.
	 */
	private static final Set<String> OBFUSCATED_KEYWORDS = Set.of(
			"ignore", "disregard", "forget", "override", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			"system", "prompt", "instructions", "rules", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			"previous", "above", "prior", "directives", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			"secret", "password", "token", "apikey", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			"admin", "root", "sudo", "execute", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			"bash", "shell", "eval", "curl", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			"bearer", "privatekey", "exfil", "encode" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	);

	private GsdContentSecurity(Caps caps, Policy policy) {
		this.caps = (caps != null) ? caps : Caps.defaults();
		this.policy = (policy != null) ? policy : Policy.defaults();
	}

	/**
	 * Create a new instance with default caps and default policy.
	 */
	public static GsdContentSecurity create() {
		return new GsdContentSecurity(null, null);
	}

	/**
	 * Create with custom caps and/or policy.
	 */
	public static GsdContentSecurity create(Caps caps, Policy policy) {
		return new GsdContentSecurity(caps, policy);
	}

	// ─── public API ──────────────────────────────────────────────────────

	/**
	 * Scan and secure content of the given kind.
	 *
	 * <p>Steps:
	 * <ol>
	 *   <li>Null/empty guard — returns clean report.</li>
	 *   <li>Enforce content cap (truncate if exceeded — finding added).</li>
	 *   <li>Sanitize invisible Unicode.</li>
	 *   <li>Redact secrets deterministically.</li>
	 *   <li>Scan for injection patterns.</li>
	 *   <li>Apply policy to determine {@code blocked}.</li>
	 * </ol>
	 *
	 * @param text untrusted text (may be null)
	 * @param kind content category for cap enforcement
	 * @return report with sanitized text and findings
	 */
	public Report secure(String text, ContentKind kind) {
		List<Finding> findings = new ArrayList<>();

		// 1. Null / empty
		if (text == null || text.isEmpty()) {
			return new Report(text != null ? text : "", findings, false); //$NON-NLS-1$
		}

		// 2. Cap enforcement
		int maxChars = caps.forKind(kind);
		String working = text;
		if (maxChars > 0 && working.length() > maxChars) {
			working = working.substring(0, maxChars);
			findings.add(new Finding(
					"CAP-EXCEEDED", //$NON-NLS-1$
					Severity.MEDIUM,
					"Text truncated from " + text.length() + " to " + maxChars + " chars (cap for " + kind + ")")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		}

		// 3. Sanitize invisible Unicode
		working = sanitizeInvisibleUnicode(working, findings);

		// 4. Redact secrets
		working = redactSecrets(working, findings);

		// 5. Injection scan
		scanInjection(working, findings);

		// 6. Apply policy
		boolean blocked = applyPolicy(findings);

		return new Report(working, findings, blocked);
	}

	/**
	 * Scan-only: check text for injection and secrets without modifying it.
	 * Returns findings but text is unchanged (secrets are not redacted).
	 */
	public Report scanOnly(String text) {
		List<Finding> findings = new ArrayList<>();

		if (text == null || text.isEmpty()) {
			return new Report(text != null ? text : "", findings, false); //$NON-NLS-1$
		}

		// Check invisible Unicode (report only, don't modify)
		checkInvisibleUnicode(text, findings);

		// Check secrets (report only, don't modify)
		checkSecrets(text, findings);

		// Injection scan
		scanInjection(text, findings);

		boolean blocked = applyPolicy(findings);
		return new Report(text, findings, blocked);
	}

	/**
	 * Quick cap check — returns true if text exceeds the cap for the given kind.
	 */
	public boolean exceedsCap(String text, ContentKind kind) {
		if (text == null) return false;
		return text.length() > caps.forKind(kind);
	}

	// ─── internal: sanitize ──────────────────────────────────────────────

	private static String sanitizeInvisibleUnicode(String text, List<Finding> findings) {
		if (INVISIBLE_UNICODE.matcher(text).find()) {
			findings.add(new Finding(
					"SANITIZE-INVISIBLE-UNICODE", //$NON-NLS-1$
					Severity.MEDIUM,
					"Invisible Unicode characters stripped")); //$NON-NLS-1$
		}
		String result = INVISIBLE_UNICODE.matcher(text).replaceAll(""); //$NON-NLS-1$

		// Strip Unicode tag blocks
		if (UNICODE_TAG_BLOCK.matcher(result).find()) {
			findings.add(new Finding(
					"SANITIZE-UNICODE-TAG-BLOCK", //$NON-NLS-1$
					Severity.HIGH,
					"Unicode tag block characters (U+E0000–E007F) stripped")); //$NON-NLS-1$
			result = UNICODE_TAG_BLOCK.matcher(result).replaceAll(""); //$NON-NLS-1$
		}

		return result;
	}

	private static void checkInvisibleUnicode(String text, List<Finding> findings) {
		if (INVISIBLE_UNICODE.matcher(text).find()) {
			findings.add(new Finding(
					"SANITIZE-INVISIBLE-UNICODE", //$NON-NLS-1$
					Severity.MEDIUM,
					"Invisible Unicode characters detected")); //$NON-NLS-1$
		}
		if (UNICODE_TAG_BLOCK.matcher(text).find()) {
			findings.add(new Finding(
					"SANITIZE-UNICODE-TAG-BLOCK", //$NON-NLS-1$
					Severity.HIGH,
					"Unicode tag block characters (U+E0000–E007F) detected")); //$NON-NLS-1$
		}
	}

	// ─── internal: secret redaction ──────────────────────────────────────

	private static String redactSecrets(String text, List<Finding> findings) {
		String result = text;
		Set<String> fired = new HashSet<>();
		for (SecretPattern sp : SECRET_PATTERNS) {
			Matcher m = sp.regex.matcher(result);
			if (m.find()) {
				if (fired.add(sp.ruleId)) {
					findings.add(new Finding(
							sp.ruleId,
							Severity.HIGH,
							"Secret pattern detected and redacted")); //$NON-NLS-1$
				}
				result = m.replaceAll("[REDACTED:" + sp.ruleId + "]"); //$NON-NLS-1$ //$NON-NLS-2$
				// Re-check for additional matches after replacement
				m = sp.regex.matcher(result);
				while (m.find()) {
					if (fired.add(sp.ruleId + "-dup-" + m.start())) { //$NON-NLS-1$
						findings.add(new Finding(
								sp.ruleId,
								Severity.HIGH,
								"Additional secret pattern instance redacted")); //$NON-NLS-1$
					}
					result = m.replaceAll("[REDACTED:" + sp.ruleId + "]"); //$NON-NLS-1$ //$NON-NLS-2$
				}
			}
		}
		return result;
	}

	private static void checkSecrets(String text, List<Finding> findings) {
		for (SecretPattern sp : SECRET_PATTERNS) {
			if (sp.regex.matcher(text).find()) {
				findings.add(new Finding(
						sp.ruleId,
						Severity.HIGH,
						"Secret pattern detected")); //$NON-NLS-1$
			}
		}
	}

	// ─── internal: injection scan ────────────────────────────────────────

	private static void scanInjection(String text, List<Finding> findings) {
		for (InjectionPattern ip : INJECTION_PATTERNS) {
			Matcher m = ip.regex.matcher(text);
			if ("INJECT-CHAR-SPACING".equals(ip.ruleId)) { //$NON-NLS-1$
				// Must iterate ALL matches: a benign spaced sequence ("a b c d e")
				// may match first while a dangerous one ("i g n o r e …") follows.
				while (m.find()) {
					if (matchesObfuscatedKeyword(m)) {
						findings.add(new Finding(
								ip.ruleId, ip.severity, ip.reason));
						break; // one finding per pattern is sufficient
					}
				}
			} else if (m.find()) {
				findings.add(new Finding(
						ip.ruleId, ip.severity, ip.reason));
			}
		}
	}

	/**
	 * Checks whether the regex match, after removing spaces, contains any
	 * known dangerous keyword from {@link #OBFUSCATED_KEYWORDS}.
	 */
	private static boolean matchesObfuscatedKeyword(Matcher m) {
		String matched = m.group();
		String deSpaced = matched.replaceAll("\\s+", "").toLowerCase(Locale.ROOT); //$NON-NLS-1$ //$NON-NLS-2$
		for (String keyword : OBFUSCATED_KEYWORDS) {
			if (deSpaced.contains(keyword)) {
				return true;
			}
		}
		return false;
	}

	// ─── internal: policy ────────────────────────────────────────────────

	private boolean applyPolicy(List<Finding> findings) {
		boolean hasHigh = false;
		boolean hasMedium = false;
		for (Finding f : findings) {
			switch (f.severity()) {
				case HIGH:   hasHigh = true;   break;
				case MEDIUM: hasMedium = true; break;
				default: break;
			}
		}

		// Block if HIGH and policy blocks HIGH.
		if (hasHigh && policy.blockHigh())   return true;
		// Block if MEDIUM-only and policy blocks MEDIUM.
		if (hasMedium && !hasHigh && policy.blockMedium()) return true;
		// Block if clean and policy blocks clean.
		if (findings.isEmpty() && policy.blockClean()) return true;
		return false;
	}

	// ─── utility: single-pass sanitize without report ────────────────────

	/**
	 * Sanitize text for safe embedding in prompts.
	 * Strips invisible Unicode and neutralizes system-role markers.
	 * Returns the sanitized string (never throws on null input).
	 */
	public static String sanitizeForPrompt(String text) {
		if (text == null) return null;
		String s = INVISIBLE_UNICODE.matcher(text).replaceAll(""); //$NON-NLS-1$
		s = UNICODE_TAG_BLOCK.matcher(s).replaceAll(""); //$NON-NLS-1$
		// Neutralize system-role tags
		s = s.replaceAll("<(/?)\\s*(?:system|assistant|human|user)\\s*>", "＜$1role-tag＞"); //$NON-NLS-1$ //$NON-NLS-2$
		s = s.replaceAll("\\[(SYSTEM|INST|/INST)\\]", "[$1-TEXT]"); //$NON-NLS-1$ //$NON-NLS-2$
		s = s.replaceAll("<<\\s*/?\\s*SYS\\s*>>", "«SYS-TEXT»"); //$NON-NLS-1$ //$NON-NLS-2$
		return s;
	}
}
