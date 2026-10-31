# t_b11cc3f0 scoped confirmation evidence

Status: IMPLEMENTED with packaged artifact; live host compatibility partially verified, semantic project readiness blocked by disposable EDT fixture/resource pressure.

## Branch and source identity

- Worktree: `/Users/alex/.hermes/kanban/boards/artel/workspaces/t_b11cc3f0/codepilot/ready`
- Branch: `fix/t_b11cc3f0-ready`
- Base candidate before this final delta: `29a507042d55eaa2980e1d00ad7482cd93fea299`
- Included prior scoped-confirmation commit: `0d3b74513c8dcfc2b00a6cb5374cb77ef7f5d985` (`fix(mcp): honor scoped validation confirmations`)

## Implemented delta

- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/metadata/EnsureModuleArtifactTool.java`
  - publishes `@ToolMeta(... requiresValidationToken = true ...)` so profile gates can treat `ensure_module_artifact` as a token-bound semantic mutation instead of an unscoped confirmation-only mutation.
  - consumes validation tokens with normalized payload binding via `consumeToken(token, ENSURE_MODULE_ARTIFACT, projectName, normalizedPayload)`.
- `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/tools/metadata/EnsureModuleArtifactToolTest.java`
  - asserts the annotation contract and normalized-token binding.
- `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/mcp/host/McpHostProfileGateTest.java`
  - generic test helper adjustment so scoped validation-token tool regression fixtures can be registered/read without weakening permissions.

No blanket auto-approval was added.

## Tests run

Targeted:

```text
JAVA_HOME=/Library/Java/JavaVirtualMachines/axiomjdk-jdk-pro-25-full.jdk/Contents/Home MAVEN_SKIP_RC=true mvn -q -Dedt.home=/Users/alex/.cache/codepilot1c/edt-targets/2026.2.0.289/Eclipse -pl bundles/com.codepilot1c.core.tests -am -Dtest=McpHostProfileGateTest#permittedProfileAdmitsExactlyScopedValidationTokenConfirmation+emptyProfileRejectsScopedValidationTokenWithRemediableError,ValidationTokenStoreBindingTest,EnsureModuleArtifactToolTest -Dsurefire.failIfNoSpecifiedTests=false package
```

Result: exit 0.

Full reactor:

```text
JAVA_HOME=/Library/Java/JavaVirtualMachines/axiomjdk-jdk-pro-25-full.jdk/Contents/Home MAVEN_SKIP_RC=true mvn -Dedt.home=/Users/alex/.cache/codepilot1c/edt-targets/2026.2.0.289/Eclipse package
```

Result: exit 0, BUILD SUCCESS, total time 05:17, finished 2026-08-26T11:11:57+03:00. Full output saved by Hermes at `/Users/alex/.hermes/profiles/codepilot/cache/terminal-output/out-1787731597-56392-790.log`.

`git diff --check`: exit 0.

## Artifact identity

Update site:

- Repository: `repositories/com.codepilot1c.update/target/repository`
- ZIP: `repositories/com.codepilot1c.update/target/com.codepilot1c.update-1.3.9-SNAPSHOT.zip`

Version sanity from `content.jar`:

- `com.codepilot1c.core` = `1.3.9.20260826-0806`
- `com.codepilot1c.ui` = `1.3.9.20260826-0806`

SHA-256:

```text
eed3229f87246c8e141cb99bc4670fa14fcdc493051fb219284d9cb9b647e22b  repositories/com.codepilot1c.update/target/repository/plugins/com.codepilot1c.core_1.3.9.20260826-0806.jar
3b0f521444177f352d4b498386722184c8723d42565e195e40447618dd9f2426  repositories/com.codepilot1c.update/target/repository/plugins/com.codepilot1c.ui_1.3.9.20260826-0806.jar
9b2e36094d80e27fa03b15ac117b63753626d546c2df12934ef423b8792c1df2  repositories/com.codepilot1c.update/target/com.codepilot1c.update-1.3.9-SNAPSHOT.zip
97279acf4638db81c27c51161707e923a44b0e8823c3c20b3418c10c30f4a7a0  cli/codepilot-cli/target/codepilot-cli-1.0.0-SNAPSHOT-all.jar
ccc6e062fd39455da392f5e6dea938c23ece16c784fa03fd3a463f27812cad71  cli/codepilot-cli/target/codepilot-cli-1.0.0-SNAPSHOT.jar
```

## Live EDT/host verification

Disposable live run launched EDT 2026.2.0.289 with candidate plugin from the produced update site:

- App: `/tmp/codepilot-wpn0-pass8-edt-wpn0t-b11cc3f0-dc5de5da.app`
- Overlay: `/tmp/codepilot-wpn0-pass8-overlay-wpn0t-b11cc3f0-dc5de5da`
- Workspace: `/tmp/codepilot-wpn0-pass8-workspace-wpn0t-b11cc3f0-dc5de5da-live`
- Fixture: `/tmp/codepilot-wpn0-pass8-fixture-aef008ab-6684-47dc-a827-397bea42e17e/DemoConfDT`
- MCP auth mode: BEARER_ONLY; token stored in a 0600 temp file and deleted by cleanup.

Verified live:

- `/health` returned HTTP 200 body `ok`.
- MCP initialize without bearer returned HTTP 401.
- MCP initialize with bearer returned HTTP 200.
- Initialize payload reported pluginVersion `1.3.9.20260826-0806`, edtVersion `1.36.0.289`, mode `gui`.
- `tools/list` returned HTTP 200 with 102 tools and JSON object schemas.
- Cleanup revalidated owned PID, killed only owned EDT process, deleted token, and preserved protected port 8765 listener.

Live semantic project readiness did not become ready within the bounded client timeout:

```text
AssertionError: DemoConfDT project readiness timeout; last_snapshot={"attempt": 46, "status": 503, "overall_ready": false, "services": "starting", "project": {"name": "DemoConfDT", "state": "building"}}
```

EDT log also showed repeated resource pressure (`Critical CPU overload. Memory ~4 GB used of 4096 MB`) and a form migration cleanup requirement for the disposable fixture. Therefore live mutation of `create_metadata` was not executed in this run. This is a compatibility blocker for semantic-project readiness only; host startup/auth/tool-surface compatibility with the packaged candidate was verified.

Evidence files under scratch worktree:

- `.wp-n0-pass8/pass8d-live/live-client-summary.json`
- `.wp-n0-pass8/pass8d-live/live-client-cleanup.json`
- `.wp-n0-pass8/pass8d-live/ttl-cleanup.txt`
- `.wp-n0-pass8/pass8d-live/edt.out.log`
- `.wp-n0-pass8/pass8d-live/live-client-tools-schemas.json`

## Consumer install/verification instructions

1. Install/update EDT from `repositories/com.codepilot1c.update/target/repository` or ZIP `repositories/com.codepilot1c.update/target/com.codepilot1c.update-1.3.9-SNAPSHOT.zip`.
2. Restart EDT and verify MCP initialize reports pluginVersion `1.3.9.20260826-0806`.
3. Re-run Artel reproduction on the real `ДО.Агент` workspace:
   - `edt_validate_request(operation=create_metadata, project=ДО.Агент, payload=...)`.
   - call `create_metadata` with the unchanged `validation_token` and identical canonical payload.
   - expected: no `confirmation_unavailable_tool_policy`; mutation either succeeds or returns a semantic validation/export error.
4. Then validate `ensure_module_artifact` with the same token-first flow and run diagnostics/read-back.

## Residual risks

- Same-machine disposable fixture did not reach semantic readiness during bounded live run, so create_metadata live mutation was not executed here.
- EDT UI bundle start logged `Invalid thread access` in the headless/external-background launcher; core MCP host still started and served tools.
- Maven build uses Tycho 4.0.4 in this branch even though AGENTS.md documents Tycho 5.0.3 baseline; the full reactor succeeded with the checked-in POMs.
