# CodePilot1C OSS — Project Planning

## What This Is

Eclipse RCP/OSGi plugin suite for 1C:EDT. The active product area is the desktop chat UI, provider/tool execution loop, EDT integrations, metadata mutation tools, extension tooling, and profile-driven agent workflows.

## Current Milestone: v0.1.9 EDT Extension Native Migration Tooling

**Goal:** Make migration of existing 1C:EDT metadata objects into extension-native objects predictable, typed, diagnosable, and safe for real DO.Артель scenarios.

**Target features:**
- Correct low-level EDT metadata mutation primitives for TypeDescription fields, CommonCommand command modules, command groups, Bot adoption diagnostics, and extension role-right diagnostics.
- Validation responses that expose effective extension-prefixed names/FQNs before mutation and return explicit unsupported/available-kind messages instead of misleading NOT_FOUND errors.
- A high-level dry-run-first migration planner/tool for cloning native extension analogs with children, modules, forms, roles/rights, and reference rewrites.
- Tests and EDT smoke evidence covering the 10 issues found during migration of `аи_Артель` objects into `ДО.Артель`.

## Key Decisions

- Keep EDT runtime access behind `EdtMetadataService`, `EdtExtensionService`, `EdtRoleRightsService`, `BslSemanticService`, and gateway/service layers; tools remain thin validation-token adapters.
- Do not primary-edit `.mdo` XML to fix metadata. New mutation support must use BM/EDT APIs and preserve export/synchronization post-checks.
- Add general `TypeDescription` support once and reuse it for constants, command parameters, defined types, register dimensions/resources, and migration clone paths.
- Extension-native migration must be dry-run-first and destructive deletes are out of scope until a confirmed, verified clone exists.
- Tool errors must distinguish unsupported kind/API limitation from missing object, and must list actionable alternatives or available values where possible.

## Active Requirements

See `.planning/REQUIREMENTS.md`.

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition:**
1. Requirements invalidated? Move to Out of Scope with reason.
2. Requirements validated? Move to Validated with phase reference.
3. New requirements emerged? Add to Active.
4. Decisions to log? Add to Key Decisions.
5. "What This Is" still accurate? Update if drifted.

**After each milestone:**
1. Full review of all sections.
2. Core value check.
3. Audit Out of Scope.
4. Update Context with current state.

_Last updated: 2026-07-05_
