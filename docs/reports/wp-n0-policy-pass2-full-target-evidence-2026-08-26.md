# WP-N0 policy pass 2 full-target evidence

Status: `BLOCKED_EVIDENCE`

## Identity

- Worktree: `/Users/alex/.herdr/worktrees/codepilot1c-oss/wp-n0-policy-pass2-full-target-20260826`
- Branch: `fix/wp-n0-policy-pass2-full-target-20260826`
- Source HEAD: `ac471d5b85ba63378ccec249ede4ac7255f907ce`
- Required base: `ac471d5b85ba63378ccec249ede4ac7255f907ce`
- Supplied EDT home: `/private/tmp/codepilot-wpn0-pass8-edt-wpn0p8d-579e0229-9ab6-42f3-87ae-74f8da7966c1.app/Contents/Eclipse`
- Required JDK VM path was read-only verified as supplied; the JDK executable reports
  OpenJDK `25.0.2+12-LTS` for `x86_64`.

No Artel project, user EDT installation, port 8765, credentials, release, deployment,
or push was accessed or changed.

## TDD gate and blocker

A new focused test was prepared to make every semantic validation-token consumer pass its
normalized payload to `consumeToken`. It was intentionally not retained because the required
focused test could not reach compilation on the supplied target; no production source was
changed.

Exact attempted command (exit `1`):

```text
JAVA_HOME=/Applications/1C/1CE/components/axiom-jdk-full-25.0.2+12-x86_64 MAVEN_SKIP_RC=true mvn -pl bundles/com.codepilot1c.core.tests -am -Dedt.home=/private/tmp/codepilot-wpn0-pass8-edt-wpn0p8d-579e0229-9ab6-42f3-87ae-74f8da7966c1.app/Contents/Eclipse -Dtest=ValidationTokenPayloadBindingSurfaceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Tycho failed before Java compilation or Surefire execution:

```text
Missing requirement: com.codepilot1c.core 1.3.9.qualifier requires
'osgi.bundle; org.eclipse.core.runtime [3.26.0,4.0.0)' but it could not be found
```

Read-only inspection confirms the stated EDT home has only
`plugins/org.eclipse.equinox.launcher_1.7.100.v20251111-0406.jar`; it has no
`org.eclipse.core.runtime` bundle and no 1C BM package provider. The prior overlay is reference
material only and does not supply a new candidate build.

## Not run

Because the required target platform cannot resolve, the RED/ GREEN focused tests, full JDK25
reactor/package, candidate artifact hashes, and neutral disposable EDT CLI/MCP acceptance cannot
be run honestly. No overlay or artifact was built, and no live listener, PID, workspace, or token
was created.

## Residual risk and required unblocking input

The current source still has legacy three-argument validation-token consumption for protected
semantic tools beyond `create_metadata`; altered payloads are therefore not uniformly rejected at
consumption time. Provide a complete disposable EDT target whose `plugins` directory (or target
definition inputs) contains `org.eclipse.core.runtime` and the required `com._1c.g5.v8.*` BM
packages, without requiring access to a user EDT installation. Then rerun the TDD, reactor,
artifact, and neutral live-acceptance gates from this branch.
