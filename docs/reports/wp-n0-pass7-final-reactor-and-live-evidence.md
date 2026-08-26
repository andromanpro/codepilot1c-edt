# WP-N0 final reactor and live-readiness evidence

Status: **READY**. This report supersedes the earlier pass-7
`CHANGES_REQUIRED` assessment. The final candidate completed the authoritative
JDK 25 reactor gates and a bounded disposable EDT live acceptance. This is not
a deployment action.

## Scope and safety

* Worktree / branch: `/Users/alex/.herdr/worktrees/codepilot1c-oss/wp-n0-readiness-pass4` /
  `fix/wp-n0-headless-semantic-readiness`.
* Candidate: `1.3.9.20260826-0055`.
* JDK 25 materialized EDT target: `/private/tmp/codepilot-edt-2026.2.0.289-target.Q6U1KI`.
* The disposable run used an owned child, temporary workspace, fixture and
  loopback port. No request was sent to the protected user port 8765; the user
  EDT process remained listening after cleanup. No Artel source was read,
  imported, copied or modified. No credential is recorded here.
* Source EDT baseline remained unchanged (including `1cedt.ini`
  `62bc2b5e…` and `bundles.info` `8ee35996…`).

## Semantic-readiness defect and fix

The prior live run exposed a semantic false positive: global services and the
project lifecycle reported ready while `scan_metadata_index` still returned
`PROJECT_NOT_READY` because EDT derived data was building.

`EdtWorkspaceProjectStates` now shares the canonical non-blocking predicate
used by semantic metadata operations,
`MetadataProjectReadinessChecker.isDerivedDataReady`. An active project with
an active V8 service context remains `building` until that predicate holds;
status lookup failures remain fail-closed. `McpSemanticReadinessProbe` retains
per-project detail and publishes `starting/not_ready` until every imported
project is semantically ready. Tests cover building-to-ready transition,
health/MCP false-ready prevention, and mixed-project output.

## Authoritative build and test evidence

The authoritative isolated bare JDK 25 reactor completed with exit 0:

```text
JAVA_HOME=/Applications/1C/1CE/components/axiom-jdk-full-25.0.2+12-x86_64 MAVEN_SKIP_RC=true mvn -q -Dedt.home=/private/tmp/codepilot-edt-2026.2.0.289-target.Q6U1KI package
exit_code=0
```

Focused semantic tests also passed (14 tests, zero failures/errors):

```text
... -pl bundles/com.codepilot1c.core.tests -am -Dtest=McpSemanticReadinessProbeTest,EdtWorkspaceProjectStatesTest,MetadataProjectReadinessCheckerTest -Dsurefire.failIfNoSpecifiedTests=false package
exit_code=0
```

The preserved `.wp-n0-pass8/pass8e-*.log` files distinguish early diagnostic
failures (incompatible target/selector or overlapping Maven runs) from the
authoritative isolated green runs. They are reviewer evidence only and are not
part of this commit.

## Final disposable live acceptance

Sanitized authoritative artifacts are retained under
`.wp-n0-pass8/pass8d-live/` for review. `live-client-summary.json` has no
failure and records:

* `/health` returned 200 `ok`; missing authentication returned 401;
  authenticated initialize returned 200.
* `tools/list` returned 102 strict schemas.
* `/health/ready` remained HTTP 503 for `DemoConfDT` while `imported` and then
  `building`; it became HTTP 200 only when the project became `ready`.
* All read-only calls 10 through 16 returned HTTP 200 with `isError=false`:
  two `list_files` calls, `read_file`, `scan_metadata_index`,
  `edt_get_configuration_properties`, `edt_get_problem_summary`, and
  `debug_status`.
* `scan_metadata_index` succeeded with engine `edt_configuration_scan`, total
  219, and 10 returned rows. This is the direct proof that the prior false
  ready no longer races semantic access.
* The workspace `.location` resolved to the neutral fixture, no project source
  tree was copied into the workspace, and `.project` plus `Configuration.mdo`
  hashes were unchanged.
* Overlay hashes match exact same-version update-site artifacts:
  `com.codepilot1c.core` `aa2e7b0c16f46c228918d01b62e087beff65caf3460402f740a2c61de3911307`;
  `com.codepilot1c.ui` `a029e456dd1a6c1f42fc247c17ea0d5007ca3e61ddc615764bf729af43bc69de`.
* The live client exited 0. Cleanup recorded stop-file ownership validation,
  owned child removal, token deletion, and continued protected-port listening.

The candidate-proof harness compares overlay jars to their exact versioned
update-site artifacts, not to a subsequently repackaged mutable SNAPSHOT jar.

## Commit boundary

The `.wp-n0-pass8/**` tree remains untracked, sanitized reviewer evidence and
is intentionally excluded from the commit. The commit contains only the
production implementation, tests, and this durable canonical report. No
merge, push, deploy, p2 install, or EDT launch occurred while finalizing it.
