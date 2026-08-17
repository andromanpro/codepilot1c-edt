# Task: Resolve cherry-pick conflicts — apply ThickClient fix on fresh upstream

Name: codex-developer-cherry-pick-resolver
Profile: Codex CLI 0.128+, gpt-5.5, xhigh reasoning, --sandbox workspace-write
Goal: Разрешить 3 merge-конфликта от `git cherry-pick d2d6217` так, чтобы остались И новые upstream tools (connect_infobase, update_infobase_status, standalone-binding) И наш ThickClient fix (resolveExecutor через новый EDT 2025.2.0 API). Затем подтвердить что mvn-build проходит.
Constraints:
- Сохранить upstream additions: новые методы, поля, OSGi service registrations, инструменты
- Применить наш fix: ThickClient resolution через `IResolvableRuntimeInstallationManager.resolveExecutor(...)` вместо удалённого `getThickClientInfo()`
- НЕ модифицировать другие файлы
- JDK 17 (`JAVA_HOME` указывает на Eclipse Adoptium 17.0.17)
- Maven: `F:/Program Files/maven/bin/mvn`
Watches: 3 файла в конфликте; commit `d2d6217` как источник нашего fix
Produces: 3 разрешённых файла + успешный `mvn -DskipTests package`

## Operational backstory

Запущен в `F:/WorkAI/knowledge/codepilot1c-edt` с `--sandbox workspace-write`. Git репо в состоянии `cherry-pick d2d6217 in progress` с 3 conflict markers. Доступны:
- `git show d2d6217 -- <file>` — наш fix содержимое
- `git show 5835885^ -- <file>` — версия файла **до** наших патчей (upstream baseline на 13 апр)
- `cat <file>` — текущее состояние с `<<<<<<` markers (ours = upstream 4b6a21c, theirs = наш d2d6217)
- Maven через `"F:/Program Files/maven/bin/mvn" -DskipTests package`

## Working directory

`F:/WorkAI/knowledge/codepilot1c-edt` (твой `--cd`)

## Project context

Read:
- `LOCAL-BUILD.md` (если есть после cherry-pick — он восстановлен из commit 5835885)
- `git show d2d6217` — full diff нашего fix (включая описание в commit message)

## 3 файла в конфликте

1. `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/runtime/EdtRuntimeService.java`
   - **Наш fix:** replace reflection `getThickClientInfo()` with `IResolvableRuntimeInstallationManager.resolveExecutor(ILaunchableRuntimeComponent, IThickClientLauncher, ...)`. Резолвить `IProject` из `InfobaseReference` через `IInfobaseAssociationManager`. Fixes `edt_launch_app`, `import_project_from_infobase`, `qa_run`, `qa_status` на EDT 2025.2.0+.
   - **Upstream changes:** добавлены поддержка standalone infobase binding (PR #28), новые методы/поля для определения infobase когда нет EDT-привязки

2. `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/runtime/EdtRuntimeGateway.java`
   - **Наш fix:** getter для `IResolvableRuntimeInstallationManager` (новый OSGi service)
   - **Upstream changes:** добавлен `connect_infobase` tool (PR #30) + standalone binding (PR #28) + getter'ы

3. `bundles/com.codepilot1c.core/src/com/codepilot1c/core/internal/VibeCorePlugin.java`
   - **Наш fix:** `ServiceTracker<IResolvableRuntimeInstallationManager>` + getter
   - **Upstream changes:** async update_infobase (PR #29), connect_infobase wiring (PR #30), tools registry expansion

## Acceptance criteria

- [ ] 3 файла без `<<<<<<<`/`=======`/`>>>>>>>` markers
- [ ] `git diff --check` чисто
- [ ] Наш ThickClient fix присутствует: `grep -l "resolveExecutor\|IResolvableRuntimeInstallationManager" bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/runtime/EdtRuntimeService.java` → match
- [ ] Upstream connect_infobase инструмент сохранён: `grep -l "connect_infobase" bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/runtime/EdtRuntimeGateway.java` → match
- [ ] `mvn -DskipTests package` BUILD SUCCESS

## Test plan (mandatory 5-step)

1. **Find relevant code** — прочитать `git show d2d6217 -- <file>` для каждого из 3 файлов; прочитать текущее состояние с конфликтами
2. **Reproduce error** — `mvn -DskipTests compile` сейчас должен падать на conflict markers
3. **Edit source** — разрешить конфликты в 3 файлах: ours (upstream) keeps additions, theirs (d2d6217) adds ThickClient fix; merge to keep BOTH
4. **Verify fix** — `mvn -DskipTests package` → BUILD SUCCESS; артефакт в `repositories/com.codepilot1c.update/target/com.codepilot1c.update-0.1.7-SNAPSHOT.zip`
5. **Consider edge cases** — если в `MANIFEST.MF` нужны новые Import-Package для `IResolvableRuntimeInstallationManager`, добавить

## Final report (structured JSON via --output-schema)

Required: `files_modified` (3 paths + brief description), `summary`, `tested` (boolean), `test_results` (mvn output last 30 lines), `open_questions`, `deviations_from_spec`.

## Out of scope

- НЕ делать git commit (architect сделает после review)
- НЕ запускать тесты YAxUnit
- НЕ менять `pom.xml` или target platform
- НЕ касаться других `.java` файлов
