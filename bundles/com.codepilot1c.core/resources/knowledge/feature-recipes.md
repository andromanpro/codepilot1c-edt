# 1C development harness

Use this knowledge as a platform harness, not as a single business-domain recipe.
Concrete scenarios are examples composed from patterns.

## Problem frames

Classify a request before choosing metadata:

- Reference data: catalogs, classifiers, stable entities and their attributes.
- Business event: documents, commands, posting, cancellation, and object modules.
- Resource accounting: balances, turnovers, accumulation registers, movement records.
- State history: information registers, statuses, periodic values, object states.
- Reporting: reports, DCS schemas, query datasets, report parameters, period boundaries.
- Form UX: managed forms, form attributes, items, commands, handlers.
- Module logic: BSL modules, validation code, posting code, manager helpers.
- QA verification: Vanessa features, YAxUnit, diagnostics, smoke checks.
- Integration: exchanges, external reports/processings, data import/export.
- Extension layer: extension project, adopted objects, compatibility boundaries.

Low-confidence classification should lead to exploration or a user-visible assumption, not immediate mutation.

## Platform patterns

Use patterns as composable building blocks:

- Catalog pattern: stable business entity reused by documents, registers, and reports.
- Document event pattern: user records a business event that may write movements.
- Accumulation balance pattern: numeric resources with balance or turnover semantics.
- Information register pattern: facts, statuses, or periodic values without resource balance semantics.
- Posting movement pattern: document posting creates deterministic register movements.
- Availability check pattern: resource consumption checks availability before writing расход movements.
- Report-on-date pattern: a report parameter has explicit period boundary semantics.
- Managed form pattern: form attributes, visual items, commands, and handlers are separate model parts.
- DCS report pattern: report schema, datasets, parameters, selected fields, and diagnostics.

Do not treat goods accounting as the default architecture. It is one composition of these patterns.

## Harness invariants

- Metadata, forms, DCS, extensions, and external objects are changed through semantic EDT tools.
- Mutating EDT tools require `edt_validate_request` and the unchanged `validation_token`.
- Do not use `write_file` or `edit_file` as the primary path for `.mdo`, `.form`, `.mxl`, `.dcs`, or DCS template artifacts.
- Before BSL module edits, use `ensure_module_artifact`.
- Re-run diagnostics after metadata, form, DCS, module, extension, or external-object mutation.
- Treat missing or invalid `type` diagnostics as correctness defects.
- Never create custom attributes with names reserved by the metadata object type.
- If a document consumes a resource, validate availability against movements aggregated by accounting key, not only per source row.
- If a report is "on date", make the boundary explicit: exact moment, start of day, end of day, or a user-specified interval. Do not leave it implicit and do not hardcode a sample date.
- DCS schemas should be represented as EDT template metadata plus external `Template.dcs` content, not as an embedded `DataCompositionSchemaImpl` reference value.
- Form attributes and visual items are different entities; bind items to attributes explicitly.

## Example composition: quantity stock accounting

Use only when classification confirms a resource-accounting task with goods/items, receipt, issue/sale, and balance reporting.

- Reference data: catalog for items.
- Business events: receipt and issue/sale documents with tabular sections.
- Resource accounting: accumulation register with item dimension and quantity resource.
- Module logic: posting writes приход/расход movements; расход checks aggregated availability.
- Reporting: report/DCS over balances with explicit period boundary selected from the user's request.
- QA: e2e scenario covers receipt, issue/sale, insufficient stock, and report boundary behavior.

This example must remain regression data and a pattern composition. It must not be copied into global prompt rules as the only way to build 1C features.
