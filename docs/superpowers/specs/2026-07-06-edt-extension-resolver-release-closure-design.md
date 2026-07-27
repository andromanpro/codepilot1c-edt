# EDT Extension Resolver Release Closure Design

## Status

Approved design. Implementation must not be considered complete until the updated plugin is installed into EDT and the live audit passes against the extension workspace.

## Context

The live EDT Extension Migration Tooling audit for `/Volumes/T9/workspace/do` and extension project `ДО.Артель` still fails in three areas:

- `EDTEXT-03`: `allow_auto_prefix=false` is ignored by validation for unprefixed extension object names.
- `EDTEXT-05`: `CommonCommand.group` accepts normalized `StandardCommandGroup.*` input at validation time, but `update_metadata` fails at BM commit with `Failed to persist reference value ... StandardCommandGroupImpl`.
- `EDTEXT-06`: extension configuration-right mutation returns a partly structured unsupported diagnostic, but it is surfaced through the wrong outer code and lacks required context.

The selected approach is a resolver-layer fix with release closure. This is broader than a hotfix: the product should gain a documented, testable resolver boundary for EDT reference/value fields, while still keeping the scope tied to the audit failures and final update-site delivery.

## JavaDoc Evidence

MCP JavaDoc was used to ground the resolver design in EDT API contracts:

- `com._1c.g5.v8.dt.metadata.mdclass.BasicCommand`
  - `getGroup()` returns `com._1c.g5.v8.dt.mcore.CommandGroup`.
  - `setGroup(CommandGroup value)` sets a reference, not a string property.
- `com._1c.g5.v8.dt.mcore.StandardCommandGroup`
  - Extends `DuallyNamedElement` and `CommandGroup`.
  - Supports `category` and `priority`.
  - Inherited fields include `name` and `nameRu`.
- `com._1c.g5.v8.dt.metadata.mdclass.CommandGroup`
  - Represents custom metadata command groups and also implements `mcore.CommandGroup`.
- `com._1c.g5.v8.dt.mcore.CommandGroupCategory`
  - Defines `NAVIGATION_PANEL`, `ACTIONS_PANEL`, `FORM_NAVIGATION_PANEL`, and `FORM_COMMAND_BAR`.
- `com._1c.g5.v8.dt.platform.IEObjectStandardCommandGroupNames`
  - Provides public standard group name constants, including `FormCommandBarImportant`, `NavigationPanelOrdinary`, and related platform values.
- `com._1c.g5.v8.dt.platform.group.StdCommandGroupLoader`
  - Loads the `v8:/CommandGroup/Std` resource.
- `com._1c.g5.v8.dt.internal.platform.ModelUtil.createStandardCommandGroup(...)`
  - JavaDoc shows EDT has a helper for creating fully populated `StandardCommandGroup` objects. The package is internal and not exported by the platform bundle, so implementation should not depend on this class directly.

Repository inspection also found live `.mdo` serialization for standard groups as plain values such as `<group>FormCommandBarImportant</group>`, so the resolver must not treat standard groups as metadata FQNs.

## Scope

### In Scope

- Add a focused `CommandGroupResolver` or equivalent service-layer helper under `core`.
- Route `BasicCommand.group` create/update assignment through that resolver.
- Use complete standard-group names from `IEObjectStandardCommandGroupNames`, not a partial hardcoded list.
- Preserve support for custom metadata command groups through the existing metadata reference flow.
- Fix strict extension-name validation for `allow_auto_prefix=false`.
- Fix extension role/config-right diagnostics to surface `UNSUPPORTED_IN_EXTENSION` with required fields.
- Replace weak source-text tests with behavioral tests for resolver and diagnostics.
- Run release closure: focused tests, full reactor build, update-site sanity check, EDT install/update, live audit, and saved audit report.

### Out of Scope

- Broad migration planner rewrite beyond the affected primitive behavior.
- Deleting base configuration objects.
- UI migration wizard work.
- Manual `.mdo` XML patching as the primary mutation path.

## Architecture

### Command Group Resolver

Introduce one resolver boundary for command group values. The resolver should be owned by the EDT metadata service layer, not by individual tools.

Responsibilities:

1. Normalize user input:
   - `FormCommandBarImportant` -> `FormCommandBarImportant`
   - `StandardCommandGroup.FormCommandBarImportant` -> `FormCommandBarImportant`
   - case-insensitive matching should return canonical EDT names.
2. Classify the group:
   - standard platform group from `IEObjectStandardCommandGroupNames`;
   - custom metadata group such as `CommandGroup.<Name>` or `<Name>`.
3. Resolve standard groups to an EDT-compatible `mcore.CommandGroup` value:
   - Prefer a platform resource/proxy path compatible with `v8:/CommandGroup/Std` if it commits and serializes correctly.
   - Otherwise create a full `StandardCommandGroup` through exported APIs: `McoreFactory`, `setName`, `setNameRu`, `setCategory`, `setPriority`.
   - Do not rely on `com._1c.g5.v8.dt.internal.platform.ModelUtil` because it is not exported.
4. Resolve custom command groups through the existing metadata object reference path.
5. Provide `availableValues()` for validation, candidates, and diagnostics.

The resolver must keep implementation details behind a small interface so live EDT findings can change the selected standard-group strategy without changing tool payload contracts.

### Validation Flow

`MetadataRequestValidationService` remains responsible for pre-mutation normalization:

- Create validation computes `requestedName`, `requestedFqn`, `effectiveName`, `effectiveFqn`, and `autoPrefixed`.
- If extension prefixing would occur and `allow_auto_prefix=false`, validation rejects before issuing a token.
- The flag must be honored whether supplied in the top-level validation payload or inside `properties`.
- Standard command group values are normalized during update/create validation, and unknown values fail with available values.

### Mutation Flow

`EdtMetadataService` applies `BasicCommand.group` through `CommandGroupResolver` before generic reference handling. This prevents the generic resolver from treating `StandardCommandGroup.FormCommandBarImportant` as a metadata FQN.

Expected flow:

1. Read validated `changes.set.group` or `properties.group`.
2. Detect target implements `BasicCommand` and field is `group`.
3. Resolve command group.
4. Call `BasicCommand.setGroup(...)`.
5. Commit BM transaction.
6. Perform export/post-check and live diagnostics as already required by metadata mutation invariants.

### Role Rights Diagnostics

`EdtRoleRightsService` should classify unsupported configuration-level rights in extension projects with a dedicated code and payload:

- outer code: `UNSUPPORTED_IN_EXTENSION`;
- payload fields: `project`, `role`, `right`, `targetKind=Configuration`, `isExtensionProject=true`, `availableRights`, `hint`.

The tool layer should preserve this code and structured payload instead of wrapping it as `METADATA_NOT_FOUND`.

## Error Contracts

### Extension Prefix Rejection

When `allow_auto_prefix=false` blocks validation:

- code: `INVALID_METADATA_NAME`
- no validation token
- payload should identify:
  - `project`
  - `kind`
  - `requestedName`
  - `expectedPrefix`
  - `effectiveName`
  - `autoPrefixed=true`
  - `allowAutoPrefix=false`

### Unknown Standard Command Group

When a group looks like a standard command group but is not known:

- code: `INVALID_PROPERTY_VALUE`
- include requested value
- include complete `availableValues`
- do not return `METADATA_NOT_FOUND`

### Unsupported Extension Config Right

When an extension project cannot mutate a configuration-level right:

- code: `UNSUPPORTED_IN_EXTENSION`
- include project, role, right, target kind, extension flag, available rights, and hint
- do not hide the condition behind generic metadata lookup failure.

## Testing Plan

### Unit and Service Tests

- `MetadataRequestValidationService`
  - normal extension validation reports requested/effective name and FQN;
  - `allow_auto_prefix=false` rejects unprefixed names from top-level and `properties` payload positions;
  - standard group normalization accepts short and `StandardCommandGroup.*` input;
  - unknown standard group reports complete available values.
- `CommandGroupResolver`
  - canonicalizes every value exposed by `IEObjectStandardCommandGroupNames`;
  - maps names to the expected `CommandGroupCategory`;
  - creates standard group values with name, Russian name where available, category, and priority populated;
  - routes custom group names to metadata reference resolution instead of standard group construction.
- `EdtMetadataService`
  - create/update `CommonCommand.group` uses the resolver before generic reference handling;
  - invalid group values return `INVALID_PROPERTY_VALUE`.
- `EdtRoleRightsService`
  - fake or controlled `IRightInfosService` verifies unsupported extension config rights return `UNSUPPORTED_IN_EXTENSION` with full context.
- Tool schema tests
  - `edt_validate_request` includes `mutate_role_rights`;
  - changed schemas remain strict and Qwen-compatible.

### Live EDT Smoke

Live smoke is required because BM commit/export semantics cannot be fully proven by source-level tests:

- update `CommonCommand.ар_аи_ОтправитьНаАнализИИ.group` with `StandardCommandGroup.FormCommandBarImportant`;
- repeat with `FormCommandBarImportant`;
- verify resulting `.mdo` contains `<group>FormCommandBarImportant</group>`;
- verify diagnostics no longer include the audited SU112 group errors for the corrected commands;
- validate and reject `allow_auto_prefix=false` before token issue;
- validate and run `mutate_role_rights` unsupported extension config-right path and confirm the outer code/payload.

## Release Closure

Implementation is complete only after all steps below pass:

1. Focused tests for validation, command group resolver, metadata mutation, role rights, and validate-request schema.
2. Broader EDT extension regression suite.
3. Full reactor build from repository root:
   `mvn -DskipTests package`
4. Update-site sanity check:
   - artifacts under `repositories/com.codepilot1c.update/target/repository`;
   - plugin versions/qualifiers match the newly built artifacts.
5. Install/update EDT from the produced update site.
6. Run live audit in `/Volumes/T9/workspace/do` against `ДО` and `ДО.Артель`.
7. Save the closure report under `.planning/audits/`.
8. Mark Phase 4/5 complete only after the report shows the prior three FAILs resolved or documents a precise remaining external blocker.

## Documentation Deliverables

- This design spec.
- Implementation notes in the Phase 4/5 summary after work is complete.
- Live audit report saved under `.planning/audits/`.
- If the resolver needs a platform-specific fallback, document the chosen strategy and the JavaDoc/resource evidence in code comments or a short report.

## Acceptance Criteria

- `EDTEXT-03` passes in live audit.
- `EDTEXT-05` passes in live audit, including successful BM commit/export and serialized `<group>FormCommandBarImportant</group>`.
- `EDTEXT-06` passes in live audit with `UNSUPPORTED_IN_EXTENSION` and full structured context.
- Full reactor build succeeds.
- Produced update site is verified and installed before final PASS is claimed.
