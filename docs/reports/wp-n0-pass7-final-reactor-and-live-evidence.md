# WP-N0 final reactor and live-readiness evidence

Status: **READY**. Candidate `1.3.9.20260826-0134` completed the authoritative
JDK 25 reactor gates and the required bounded disposable EDT live acceptance
after the independent-review completion-edge remediation. This is not a
deployment action.

## Scope and safety

* Worktree / branch: `/Users/alex/.herdr/worktrees/codepilot1c-oss/wp-n0-readiness-pass4` /
  `fix/wp-n0-headless-semantic-readiness`.
* Current candidate: `1.3.9.20260826-0134`.
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

## Completion-edge broker defect and fix

Independent review found a completion-edge single-flight race in
`McpHostLlmBroker`: after an owner had broadcast its terminal SSE event but
before it released the map entry, an identical late request could join the
terminal flight. That subscriber could receive only the initial `: connected`
comment and then await a terminal event that had already been sent.

Admission now happens before the HTTP response is committed. A terminal or
finished mapped flight rejects new subscribers; its exact instance is removed
with `ConcurrentHashMap.remove(key, flight)` and admission retries. The exact
remove cannot evict a newer flight. This preserves normal in-progress
coalescing and keeps provider work outside lifecycle locks.

The deterministic regression test holds the first owner after it emits
`done`, sends an identical late request while that terminal flight is still
mapped, and requires a second valid flight whose response includes a terminal
`done` event. Its RED evidence is
`.wp-n0-pass8/pass8f-broker-red.log` (9 tests, 1 expected failure, exit 1);
the GREEN evidence is `.wp-n0-pass8/pass8f-broker-green.log` (9 tests, zero
failures/errors, exit 0).

## Authoritative build and test evidence

The current authoritative isolated bare JDK 25 reactor completed with exit 0:

```text
JAVA_HOME=/Applications/1C/1CE/components/axiom-jdk-full-25.0.2+12-x86_64 MAVEN_SKIP_RC=true mvn -q -Dedt.home=/private/tmp/codepilot-edt-2026.2.0.289-target.Q6U1KI package
exit_code=0
```

The focused semantic/readiness gate also passed (14 tests, zero
failures/errors):

```text
... -pl bundles/com.codepilot1c.core.tests -am -Dtest=McpSemanticReadinessProbeTest,EdtWorkspaceProjectStatesTest,MetadataProjectReadinessCheckerTest -Dsurefire.failIfNoSpecifiedTests=false package
exit_code=0
```

The current broker, remediation, registry, and semantic gates are captured in
`.wp-n0-pass8/pass8f-broker-green.log` (9),
`.wp-n0-pass8/pass8f-remediation-trio.log` (69),
`.wp-n0-pass8/pass8f-registry.log` (14),
`.wp-n0-pass8/pass8f-semantic-readiness.log` (14), and
`.wp-n0-pass8/pass8f-full-reactor.log` (all exit 0). The new update-site
artifacts are `com.codepilot1c.core_1.3.9.20260826-0134.jar`
(`b54fe7d59713060d655f85c1dbd58e87bf950ccce8232598fe8df7217ed0008c`)
and `com.codepilot1c.ui_1.3.9.20260826-0134.jar`
(`f5f09d6aa0f8a7dc75653c1e25d31c2423ff0ab0659ae12c18eea5a06ddbe26b`).

The preserved `.wp-n0-pass8/pass8e-*.log` files distinguish prior diagnostic
failures (incompatible target/selector or overlapping Maven runs) from their
authoritative isolated green runs. They remain reviewer evidence only and are
not part of a commit.

## Prior disposable live acceptance (candidate 0055)

Candidate 0055's earlier acceptance established the semantic-readiness fix.
Its facts are retained here as historical context; the current
`.wp-n0-pass8/pass8d-live/` artifacts are the superseding candidate-0134 run.
The prior run established:

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

## Current candidate-0134 disposable live acceptance

`prepare-runtime-overlay.sh` refreshed the existing disposable overlay
idempotently from the current update site; it contains exactly one core/UI
pair at version `1.3.9.20260826-0134` with the hashes recorded above. The
refresh evidence is `.wp-n0-pass8/pass8f-overlay-refresh.log` and the active
overlay path is recorded in `.wp-n0-pass8/pass8-current-overlay.txt`; the
one-pair/hash verification is `.wp-n0-pass8/pass8f-overlay-verification.log`.

The external wrapper and client are
`.wp-n0-pass8/pass8d-launch-wrapper.sh` and
`.wp-n0-pass8/pass8d-live-client.py`. Their authoritative candidate-0134
output is under `.wp-n0-pass8/pass8d-live/`; `live-client-summary.json` has
no failure and the client exited 0. It records:

* Candidate `1.3.9.20260826-0134`, with overlay core/UI SHA-256 values exactly
  matching the versioned update-site artifacts recorded above.
* `/health/ready` returned HTTP 503 through imported/building attempts 1–9
  and HTTP 200 only when `DemoConfDT` became ready at attempt 10.
* `tools/list` returned 102 schemas. The two `list_files` calls, `read_file`,
  `scan_metadata_index`, `edt_get_configuration_properties`,
  `edt_get_problem_summary`, and `debug_status` all returned HTTP 200 with
  `isError=false`; the scan used `edt_configuration_scan` with total 219 and
  returned 10 rows.
* No-copy proof remained true and the fixture `.project` and
  `Configuration.mdo` hashes were unchanged.
* Client cleanup created the stop file, confirmed the owned PID absent,
  deleted the token, and confirmed protected port 8765 remained listening.
  Guardian cleanup records `stop_file`, identity revalidation, owned-child
  kill, token deletion, and exit 0. Fresh read-back also confirmed the owned
  PID and token absent while protected PID 42587 continued listening on 8765.

The later harness-only guardian PID-normalization remediation is documented in
`.wp-n0-pass8/pass8e-semantic-readiness-remediation.md` and its offline test
log. It neither launched EDT nor changes the accepted candidate artifacts.

## Commit boundary

The `.wp-n0-pass8/**` tree remains untracked, sanitized reviewer evidence and
is intentionally excluded from commits. The completion-edge code, test, and
this report form the follow-up remediation commit. No merge, push, deploy, p2
install, or EDT launch occurred while finalizing it.
