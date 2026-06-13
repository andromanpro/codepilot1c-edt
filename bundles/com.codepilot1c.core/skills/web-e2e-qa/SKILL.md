---
name: web-e2e-qa
description: Full browser E2E + UX/UI audit of newly developed 1C objects in the live web client — discover the new interface, exercise complete user flows, evaluate UX/UI quality, analyze the browser run (screenshots/console/network), and report PASS/WARN/FAIL.
allowed-tools: [resolve_web_client_url, get_infobase_credentials, edt_diagnostics, get_standalone_server_status, scan_metadata_index, inspect_form_layout, get_diagnostics, git_inspect, read_file]
backend-only: false
allow-implicit: true
implicit-triggers: [e2e, e2e тест, qa, ui тест, ux аудит, проверь ux, проверь ui, протестируй интерфейс, тестирование интерфейса, проверь новые объекты в браузере, browser e2e, full browser test]
---
End-to-end QA of newly developed 1C objects in the **running web client**, driven through a
**user-configured browser MCP** (e.g. Playwright MCP). Goes beyond a single-change check: discover the
new interface, run full user flows, audit UX/UI, and analyze the browser run into a QA verdict.
For a quick check of one known change, use `verify-web-client` instead.

## Preconditions
1. **Browser MCP connected.** Confirm a browser-automation MCP is available (Playwright MCP exposes
   `browser_navigate`, `browser_type`, `browser_click`, `browser_snapshot`, `browser_take_screenshot`,
   `browser_console_messages`, `browser_network_requests`, `browser_wait_for`, …). If none is connected,
   STOP and ask the user to add a browser/Playwright MCP in EDT MCP settings.
2. **Vision.** Prefer a multimodal model so screenshots can be judged. Step 6 has a text-only fallback.

## 1C Taxi web client — navigation & automation (read before driving)
The web client renders the **Taxi** managed interface. Know its anatomy or you will click blindly.

**Navigation — three ways, prefer the most deterministic:**
- **Deep-link by URL (best):** go to `<web_client_url>#e1cib/list/<Тип>.<Имя>` for a list
  (e.g. `#e1cib/list/Справочник.Организации`), `#e1cib/app/Отчет.<Имя>` for a report,
  `#e1cib/app/Обработка.<Имя>` for a processing. Works even when the object is in no subsystem.
- **Sections panel (панель разделов)** → the section's **navigation panel** lists its catalogs/documents/reports.
- **Main menu (≡) → «Функции для технического специалиста»** (ex «Все функции») → flat list of ALL config
  objects — the fallback to reach anything by name.

**Managed form anatomy:**
- **Form command bar** — list form: «Создать», «Создать на основании», «Скопировать», search/filter/sort;
  object form: «Записать», «Записать и закрыть», «Провести», «Провести и закрыть», «Пометить на удаление».
- **«Ещё» menu** (overflow): commands missing from the visible toolbar live here — open it before concluding
  a command is absent.
- **Tabular sections (табличные части)** have their own row command bar («Добавить», «Скопировать», «Удалить»,
  move up/down). ValueTable/ValueTree attributes render as such tables.
- Fields = **подпись (синоним) + поле ввода**; mandatory fields are marked (bold label / red underline).
- Forms may open as separate windows or blocking dialogs; act on the active window.

**Automation — locate by meaning, not by id:**
- The web client is a **virtual DOM with dynamic, auto-generated ids/classes** — CSS/XPath selectors are
  brittle and WILL break. Drive by **accessible name / visible text / role**: buttons by caption, fields by
  label/synonym, list rows by cell text (Playwright `getByRole`/`getByText`/`getByLabel`, or the
  `browser_snapshot` accessibility tree).
- **Wait for the server round-trip** after every action — 1C actions are async and show a busy indicator;
  wait for idle/network-quiet, then re-`browser_snapshot` before asserting. Never assert immediately.
- Errors surface in **two** places: the **messages area** (служебные сообщения, usually bottom) and modal
  **dialogs** — check both after each action. Login goes through `e1cib/login` (a 402→200 handshake is normal).

## Step 1 — Scope: what to test
Enumerate what the agent just developed and turn it into a concrete test plan.
- `git_inspect` (recent diff) and/or `scan_metadata_index(projectName=<name>)` — list the new/changed
  objects: catalogs, documents, registers, reports, processings, commands.
- For each object with a form, `inspect_form_layout` — record the expected items (fields, tables/columns,
  commands, buttons) so you know what MUST appear in the web client.
- Produce a checklist: per object → which forms (list / object / choice) and which user flows to exercise.

## Step 2 — Prepare the runtime
1. `resolve_web_client_url(projectName=<name>)` → `web_client_url`, `server_running`, `server_state`,
   `vision_confirmed`, `vision_basis`, `vision_hint`. (URL already embeds host+port. If a `.local` host
   does not resolve in the browser, retry the same port on `http://localhost:<port>/`.)
2. The web client serves the **updated infobase**, not the unsaved EDT model. Since you are testing
   freshly developed objects, run `edt_diagnostics(command=update_infobase, project_name=<name>)` to
   publish them (mutating command — asks for confirmation).
3. `get_standalone_server_status()` — confirm `state=started`; if stopped, ask the user to start it.

## Step 3 — Credentials
`get_infobase_credentials(projectName=<name>)`. On `CREDENTIALS_NOT_DEFINED`, ask the user for the web
client login/password in chat. `auth_kind=os` → no login form. **Never echo the password** into the
report, chat, or any artifact.

## Step 4 — E2E run (maximum depth)
Navigate to `web_client_url`, log in once, then for each planned object/flow (use deep-link
`#e1cib/list/<Тип>.<Имя>`, the sections panel, or «Функции для технического специалиста» to reach it):
1. Open the **list form** — confirm it loads, columns match what `inspect_form_layout` reported, data renders.
2. **Create** via «Создать»: open the object form, confirm every new attribute/field/table is present and
   editable; fill required fields with sample data. If a command seems missing, check the **«Ещё»** menu.
3. **Tabular sections / ValueTable / ValueTree**: add rows via the table's own «Добавить», fill cells,
   confirm the new columns exist and accept input; for a tree, expand/add child rows.
4. **Persist**: «Записать»/«Записать и закрыть» (documents: «Провести»/«Провести и закрыть») — confirm success,
   no error dialog.
5. **Reopen** the saved item — confirm values persisted and the layout is intact.
6. **Exercise new behavior**: run the new command/button, open the new report (set parameters, generate),
   trigger the form event the change introduced.
7. After **every** action: wait for the busy indicator to clear, then capture evidence —
   `browser_take_screenshot` AND `browser_snapshot` (accessibility/DOM) — and read
   `browser_console_messages` and `browser_network_requests`.
Treat unhandled 1C exception dialogs (modal or messages area) and HTTP 5xx as FAIL; capture the exact text.

## Step 5 — UX/UI audit
Evaluate the new interface (from screenshots + snapshot), report each item as OK / WARN / FAIL:
- **Labels** — every field/column has a clear human synonym; no system names (`Реквизит1`, `Таблица1`),
  Russian, localizable.
- **Layout** — logical grouping and order; nothing cut off, overlapping, or off-screen; reasonable tab order.
- **Mandatory & validation** — required fields marked; validation/error messages are clear and in Russian.
- **Consistency** — matches platform / BSP conventions; commands placed where users expect (form command
  bar, "Ещё"/Functions); standard pictures/states.
- **Empty & error states** — empty lists and missing-data cases render gracefully; no raw stack traces.
- **Accessibility** — interactive elements have accessible names in the snapshot; reachable by keyboard.
- **Responsiveness** — form renders without horizontal scrolling at the default window; optionally resize
  and re-check.

## Step 6 — Analyze the browser run
Synthesize the run into findings — this is the core deliverable, not just raw screenshots:
- Correlate each screenshot + snapshot + console/network slice into a concrete observation.
- Classify each: **FAIL** (error, broken flow, missing element), **WARN** (UX/UI issue, cosmetic,
  non-blocking), **PASS**.
- If `vision_confirmed=true`: judge screenshots visually against the expected result.
- If `vision_confirmed=false`: do NOT claim a screenshot looks correct. Judge from the accessibility
  snapshot / page text / DOM (element presence, labels, error text) and surface `vision_hint` to the user
  (switch to a multimodal model for full visual QA).

## Step 7 — Report
**Result: PASS | WARN | FAIL**
- Scope: which objects/forms/flows were exercised.
- Per-object findings with evidence: screenshot/snapshot references, exact labels/error text, key
  console/network lines; for metadata fixes add the EDT `file:line` or FQN.
- UX/UI audit table (checklist item → OK/WARN/FAIL → note).
- Prioritized fix list (FAIL first, then WARN).
- If verification was text-only (no vision), state it and include `vision_hint`.
- Blocking preconditions if the run could not complete (server not started, infobase not updated,
  credentials missing, browser MCP not configured).
