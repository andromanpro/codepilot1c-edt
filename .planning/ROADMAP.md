# Roadmap — v0.1.9 EDT Extension Native Migration Tooling

## Phases

- [x] **Phase 1: Research EDT API Contracts and Lock Failure Reproductions** - Convert the 10 1C-agent findings into service-level contracts, fakeable EDT API seams, and RED tests.
- [x] **Phase 2: Implement Low-Level EDT Mutation and Diagnostics Fixes** - Fix TypeDescription mutation, effective-name validation, CommonCommand module semantics, command groups, Bot adopt diagnostics, and extension role-right diagnostics.
- [x] **Phase 3: Add Dry-Run Native Extension Migration Planner** - Build the high-level migration planner/tool on top of corrected primitives and verify it against representative Artel object classes.

## Phase Details

### Phase 1: Research EDT API Contracts and Lock Failure Reproductions
**Goal**: Establish exact EDT/BM API contracts behind the reported failures and create failing tests that preserve the desired behavior.
**Depends on**: Nothing (first phase)
**Requirements**: [EDTEXT-01, EDTEXT-02, EDTEXT-04, EDTEXT-05, EDTEXT-06, EDTEXT-08]
**Success Criteria** (what must be TRUE):
  1. Research notes identify current code paths and EDT API classes for each failure class.
  2. RED tests reproduce misleading `METADATA_NOT_FOUND`, unsupported `TypeDescription`, wrong CommonCommand module path/type, missing standard group alias, and extension config-right ambiguity.
  3. The tests avoid a live EDT workspace where possible by isolating service helpers and fake `IRightInfosService`/metadata models.
**Plans**: 1 plan

Plans:
- [x] 01-01: Add research-backed RED tests and service seams for extension migration tooling defects.

### Phase 2: Implement Low-Level EDT Mutation and Diagnostics Fixes
**Goal**: Make existing tools correct and explicit for the low-level operations needed by native extension migration.
**Depends on**: Phase 1
**Requirements**: [EDTEXT-01, EDTEXT-02, EDTEXT-03, EDTEXT-04, EDTEXT-05, EDTEXT-06, EDTEXT-08]
**Success Criteria** (what must be TRUE):
  1. `create_metadata(Constant, properties.type)` and `update_metadata(commandParameterType)` use a shared `TypeDescription` setter path.
  2. Extension name prefixing is visible in validation and guarded by `allow_auto_prefix` when requested.
  3. CommonCommand module materialization uses `CommandModule.bsl`, and BSL context/diagnostics see command-module semantics.
  4. Bot adoption and extension role/config rights return explicit supported/unsupported diagnostics with available values.
  5. Targeted core tests pass.
**Plans**: 1 plan

Plans:
- [x] 02-01: Implement low-level EDT mutation primitives and diagnostics fixes with targeted tests.

### Phase 3: Add Dry-Run Native Extension Migration Planner
**Goal**: Provide a high-level, dry-run-first migration tool that composes corrected primitives into safe native extension cloning.
**Depends on**: Phase 2
**Requirements**: [EDTEXT-07, EDTEXT-08]
**Success Criteria** (what must be TRUE):
  1. Dry run accepts `source_project`, `extension_project`, and `source_fqns` and emits an ordered operation plan with effective names/FQNs.
  2. Plan covers top-level object creation, children, TypeDescription fields, modules, forms, roles/rights, and reference rewrites as supported/skipped entries.
  3. Apply mode is gated by validation tokens/confirmation and does not delete base objects.
  4. Representative dry-run fixtures cover InformationRegister, Catalog, HTTPService, CommonCommand, ScheduledJob, Bot, and Role.
  5. Full reactor build path remains documented for release/update-site delivery.
**Plans**: 1 plan

Plans:
- [x] 03-01: Implement `migrate_to_extension_native` dry-run/apply planner and verification fixtures.

## Progress

**Execution Order:**
Phases execute in numeric order: 1 → 2 → 3

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Research EDT API Contracts and Lock Failure Reproductions | 1/1 | Complete | 2026-07-06 |
| 2. Implement Low-Level EDT Mutation and Diagnostics Fixes | 1/1 | Complete | 2026-07-06 |
| 3. Add Dry-Run Native Extension Migration Planner | 1/1 | Complete | 2026-07-06 |
