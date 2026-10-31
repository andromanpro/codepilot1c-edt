# WP-N0 policy remediation evidence

Status: `BLOCKED_EVIDENCE` pending a complete disposable EDT platform for the
full reactor and live MCP acceptance.

## Identity

- Worktree: `/Users/alex/.herdr/worktrees/codepilot1c-oss/wp-n0-policy-herdr-only-20260826`
- Branch: `fix/wp-n0-policy-herdr-only-20260826`
- Initial HEAD: `2fab85c96ab59a26bea851260db203151e62d1d8`
- Implementation commit: `0d3b74513c8dcfc2b00a6cb5374cb77ef7f5d985`
- User EDT, Artel repository, user endpoint, and user tokens were not accessed
  or modified.

## Change

- MCP admits a tool-contract scoped validation token only under a configured,
  resolved profile that permits the tool. An empty profile returns the actionable
  `profile_required_for_scoped_confirmation` denial.
- `create_metadata` consumes its token against the exact normalized payload.
- The token store has deterministic-clock coverage for expiry plus replay,
  operation/project, and altered-payload denial.

## Test evidence

- RED test added first: `McpHostProfileGateTest#permittedProfileAdmitsExactlyScopedValidationTokenConfirmation`.
  It could not be executed through the reactor because the disposable fixture
  lacks the required package below.
- Equivalent isolated unit execution passed (exit 0):
  `ValidationTokenStoreBindingTest` — 3 tests: exact payload, replay/tool/project,
  deterministic expiry.
- Full focused reactor command attempted (exit 1):
  `mvn -pl bundles/com.codepilot1c.core.tests -am -Dsurefire.failIfNoSpecifiedTests=false -Dedt.home=/tmp/codepilot-edt-fixture.vnzywB -Dtest=McpHostProfileGateTest#permittedProfileAdmitsExactlyScopedValidationTokenConfirmation,ValidationTokenStoreBindingTest test`
  Tycho stopped before compilation because the disposable cached-platform fixture
  lacks `java.package com._1c.g5.v8.dt.form.service`.

## Live acceptance and artifacts

No disposable EDT/MCP host could be built from the available cache, therefore
the requested live create/read-back and profile/token matrix were not run.
No repository, update-site, or CLI artifact was produced by this worktree.

## Remaining verification

Provide a complete disposable EDT target platform containing
`com._1c.g5.v8.dt.form.service` (or an approved neutral fixture). Then run the
focused and full reactor, package the plugin/CLI, and execute the required
disposable MCP create/read-back, replay, mismatch, expiry, empty-profile, and
no-approval gates. No user EDT access is needed for those steps.
