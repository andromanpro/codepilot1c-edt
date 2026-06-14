---
name: verify-web-client
description: Verify the live 1C web client after a change — update the infobase, resolve its URL, drive a browser MCP to log in and exercise the changed UI, then report PASS/WARN/FAIL with evidence.
allowed-tools: [resolve_web_client_url, get_infobase_credentials, edt_diagnostics, get_standalone_server_status, get_diagnostics, scan_metadata_index, inspect_form_layout, read_file]
backend-only: false
allow-implicit: true
implicit-triggers: [verify web client, проверь веб-клиент, проверь в браузере, открой в браузере, веб-клиент, проверка интерфейса, browser verify, open in browser, verify ui, проверь форму в базе]
---
End-to-end verification of a change in the running 1C web client. Browser automation is performed
through a **user-configured browser MCP** (e.g. Playwright MCP) — this skill does not ship a browser.
Goal: maximum verification depth — actually open the changed feature, exercise it, and prove it works.

## Preconditions (check first, do not skip)
1. **Browser MCP available.** Confirm a browser-automation MCP is connected (Playwright MCP exposes
   tools like `browser_navigate`, `browser_type`, `browser_click`, `browser_snapshot`,
   `browser_take_screenshot`, `browser_console_messages`, `browser_network_requests`). If none is
   connected, STOP and tell the user to add a browser/Playwright MCP in EDT MCP settings — then resume.
2. **Know what to verify.** State the concrete change being verified (object/form/command and the
   expected visible result), derived from the task you just completed. Vague "check it works" is not enough.

## 1C Taxi web client — quick notes
- **Reach the object fast:** deep-link `<web_client_url>#e1cib/list/<Тип>.<Имя>` (list, e.g.
  `#e1cib/list/Справочник.Организации`), `#e1cib/app/Отчет.<Имя>` (report); or use the sections panel
  (панель разделов) / main menu → «Функции для технического специалиста». Missing command? Check the
  form's **«Ещё»** menu.
- **Automate by meaning, not by id:** the web client is a virtual DOM with auto-generated ids/classes, so
  CSS/XPath selectors break. Use accessible name / visible text / role (button captions, field synonyms,
  row text — Playwright `getByRole`/`getByText`/`getByLabel` or the `browser_snapshot` tree).
- **Commit each input:** 1C fields send their value on blur/Enter, not on keystroke — after typing, press
  Tab and wait for the busy indicator. A value that never committed makes «Записать» report «Поле … не
  заполнено» and resets the form, which looks like a config bug but is an automation artifact — re-enter and
  commit before reporting it.
- **Wait for the busy indicator** to clear after each action (1C round-trips are async) before asserting;
  errors appear both in modal dialogs and the bottom messages area (служебные сообщения). `e1cib/login`
  returning 402→200 is the normal auth handshake.

## Step 1 — Resolve the web client URL and server state
Call `resolve_web_client_url(projectName=<name>)` (omit `projectName` to use the active project).
- Read `web_client_url` (host+port already embedded — no separate port lookup needed), `server_running`,
  `server_state`, `vision_confirmed`, `vision_basis`, `vision_hint`. `vision_confirmed` is true by
  default (multimodal assumed) and false only for a recognized text-only model.
- If it fails with `WEB_CLIENT_UNAVAILABLE`, the infobase is not bound or no standalone server exists —
  report that and stop; the user must connect/create the infobase and standalone server first.

## Step 2 — Make the running base reflect the change
The web client serves the **published/updated** infobase, not your unsaved EDT model. So:
1. If `server_running` is false, or you just changed metadata/forms/modules, run
   `edt_diagnostics(command=update_infobase, project_name=<name>)` to apply the change to the infobase.
   (This is a mutating/runtime command and will ask for confirmation.)
2. Re-check with `get_standalone_server_status()` — confirm the server `state` is `started` and note its
   `ports`. If still stopped, ask the user to start the standalone server in EDT.
3. Re-run `resolve_web_client_url` if the URL or state may have changed.

## Step 3 — Obtain login credentials
Call `get_infobase_credentials(projectName=<name>)`.
- On success: use `user_name` + `password` for the web client login. If `auth_kind` is `os`, there is no
  explicit login — the web client uses the OS session; skip the credential form.
- On `CREDENTIALS_NOT_DEFINED`: ask the user for the web client login and password in chat. Do not invent them.
- **Security:** the password is sensitive. Use it only to log in. NEVER echo it into the final report,
  chat summary, screenshots you describe, or any persisted file.

## Step 4 — Drive the browser (maximum depth)
Using the browser MCP:
1. Navigate to `web_client_url`.
2. If a login form appears, type `user_name` / `password` and submit. Confirm the desktop/home form loads
   (no auth error).
3. Navigate to the **specific changed feature** — fastest via deep-link `#e1cib/list/<Тип>.<Имя>`, else the
   sections panel or «Функции для технического специалиста». For tabular/ValueTable/ValueTree changes, add a
   row via the table's own «Добавить».
4. Exercise it: open the changed form, confirm the new attribute/column/field/button is present and
   interactive; enter sample data; trigger the command/event the change introduced (check «Ещё» if not on
   the toolbar).
5. After each action wait for the busy indicator to clear, then capture evidence at each milestone (after
   login, on the changed form, after the interaction):
   - take a screenshot, AND
   - capture an accessibility/DOM snapshot (`browser_snapshot` or page text).
6. Read `browser_console_messages` and `browser_network_requests` — 1C surfaces server errors as modal
   dialogs and in the bottom messages area, plus failed requests. Treat any unhandled exception or HTTP 5xx
   as a FAIL.

## Step 5 — Analyze (multimodal-aware)
- If `vision_confirmed` is true (or you otherwise accept images): read the screenshots and judge the UI
  visually against the expected result.
- If `vision_confirmed` is false: you may not be able to interpret screenshots. Verify from the
  **accessibility snapshot / page text / DOM** instead (element labels, presence of the new field/column,
  error-dialog text), and tell the user `vision_hint` — switch to a multimodal model for full visual
  verification. Do not silently claim a screenshot looks correct if you cannot see it.

## Step 6 — Report
**Result: PASS | WARN | FAIL**
- What was verified: object/form/command and the expected result.
- Evidence: per-milestone screenshot/snapshot references; key console/network findings.
- Errors: any 1C exception dialogs, failed requests, or missing elements (with the exact text/label).
- If verification was text-only (no vision), say so and include the `vision_hint`.
- Next action if FAIL/WARN: the concrete fix or the missing precondition (server not started, infobase
  not updated, credentials missing, browser MCP not configured).
