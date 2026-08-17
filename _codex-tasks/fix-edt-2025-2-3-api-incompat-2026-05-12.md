# Task: Fix 5 EDT 2025.2.3 API incompat в codepilot1c-edt

Name: codex-developer-edt-2025-2-3-api-fixes
Profile: Codex CLI 0.128+, gpt-5.5, xhigh reasoning, --sandbox workspace-write
Goal: Заменить 5 удалённых API-вызовов EDT 2025.2.3 на актуальные новые API так, чтобы `mvn -DskipTests package` дал BUILD SUCCESS.
Constraints:
- НЕ использовать рефлексию (proper new API через OSGi services)
- Сохранить семантику каждого вызова (export config, get/store settings, update infobase)
- НЕ менять другие файлы кроме указанных 3
- Maven cache в `C:/Users/Roono/.m2/repository`, target platform path установить как наш предыдущий fix (commit `8532e26` дал прецедент)
Watches: 5 compile errors + commit `8532e26` как образец (наш ThickClient fix новый API)
Produces: 3 файла с patched API + BUILD SUCCESS

## Operational backstory

Запущен в `F:/WorkAI/knowledge/codepilot1c-edt`, на ветке `main` с HEAD=`8532e26` (наш ThickClient fix + 14 upstream commits до 4b6a21c). Maven: `"F:/Program Files/maven/bin/mvn"`. JDK 17 (`JAVA_HOME=C:/Program Files/Eclipse Adoptium/jdk-17.0.17.10-hotspot`).

EDT 2025.2.3 plugin jars (для javap inspection новых API):
```
C:/Program Files/1C/1CE/components/1c-edt-2025.2.3+30-x86_64/plugins/com._1c.g5.v8.dt.platform.services.core_21.0.0.v202602241426.jar
C:/Program Files/1C/1CE/components/1c-edt-2025.2.3+30-x86_64/plugins/com._1c.g5.v8.dt.platform.services.model_6.1.0.v202602241426.jar
```

Команда для inspection классов:
```bash
JAR="C:/Program Files/1C/1CE/components/1c-edt-2025.2.3+30-x86_64/plugins/com._1c.g5.v8.dt.platform.services.core_21.0.0.v202602241426.jar"
# List all classes in package
unzip -l "$JAR" | grep "infobase\|runtime\|launcher" | head -40
# Methods of a class
unzip -p "$JAR" "com/_1c/g5/v8/dt/platform/services/core/infobases/IInfobaseAccessManager.class" | "C:/Program Files/Eclipse Adoptium/jdk-17.0.17.10-hotspot/bin/javap.exe" -p
```

## 5 compile errors

```
[ERROR] EdtProjectImportService.java:[235] exportConfigurationToXml(...) is undefined for IThickClientLauncher
[ERROR] EdtRuntimeService.java:[267]  getSettings(...) is undefined for IInfobaseAccessManager
[ERROR] EdtRuntimeService.java:[433]  updateInfobase() is undefined for RuntimeExecutionCommandBuilder
[ERROR] EdtRuntimeService.java:[595]  getSettings(...) is undefined for IInfobaseAccessManager
[ERROR] EdtInfobaseConnectService.java:[499] storeSettings(...) is undefined for IInfobaseAccessManager
```

## Образец из нашего предыдущего fix (commit 8532e26 / d2d6217)

Старый API:
```java
ThickClientInfo info = runtimeInstallation.getThickClientInfo();  // удалён в EDT 2025.2.0
```

Новый API:
```java
IResolvableRuntimeInstallationManager mgr = VibeCorePlugin.getInstance()
    .getResolvableRuntimeInstallationManager();
ResolvedLaunchableRuntimeExecutor executor = mgr.resolveExecutor(
    ILaunchableRuntimeComponent.THICK_CLIENT,
    IThickClientLauncher.class,
    project, version, arch);
IThickClientLauncher launcher = executor.getLauncher();
```

Pattern: **OSGi service → ServiceTracker в VibeCorePlugin → getter → resolveExecutor(...) → Launcher**.

Аналогичный pattern должен быть для `IInfobaseAccessManager.getSettings`/`storeSettings` (вероятно новый service `IInfobaseAccessSettingsManager` или подобное) и для `RuntimeExecutionCommandBuilder.updateInfobase()` (вероятно новый builder).

## Working directory

`F:/WorkAI/knowledge/codepilot1c-edt` (твой `--cd`)

## Project context

Read:
- `LOCAL-BUILD.md` — описание сборки и наших патчей
- `git show 8532e26` — наш предыдущий fix (образец)
- 3 файла-источника ошибок: `EdtProjectImportService.java`, `EdtRuntimeService.java`, `EdtInfobaseConnectService.java`

## Test plan (mandatory 5-step)

1. **Find relevant code** — найти 5 строк ошибок в 3 файлах; прочитать вокруг (что делает функция, какие аргументы)
2. **Reproduce error** — `"F:/Program Files/maven/bin/mvn" -Dmaven.repo.local=C:/Users/Roono/.m2/repository -DskipTests compile` сейчас падает на 5 errors
3. **Inspect new API** — javap классов из EDT 2025.2.3 jars (см. backstory) для каждого removed method:
   - `IInfobaseAccessManager` — какие методы есть сейчас? Что заменяет `getSettings`/`storeSettings`?
   - `IThickClientLauncher` — что заменяет `exportConfigurationToXml`?
   - `RuntimeExecutionCommandBuilder` — что заменяет `updateInfobase`?
4. **Edit source** — заменить вызовы. Если нужен новый OSGi service — добавить ServiceTracker в `VibeCorePlugin` + getter в `EdtRuntimeGateway` по образцу d2d6217.
5. **Verify fix** — `mvn -DskipTests package` → BUILD SUCCESS; артефакт в `repositories/com.codepilot1c.update/target/com.codepilot1c.update-0.1.7-SNAPSHOT.zip`

## Acceptance criteria

- [ ] 5 compile errors устранены
- [ ] `mvn -DskipTests package` BUILD SUCCESS
- [ ] Никакой рефлексии (proper new API)
- [ ] OSGi services объявлены через ServiceTracker pattern (если нужны новые)
- [ ] `MANIFEST.MF` обновлён если нужны новые Import-Package
- [ ] Семантика сохранена (export → export, get settings → get settings, update → update)

## Final report (structured JSON via --output-schema)

Required: `files_modified` (paths + per-file description того что изменено), `summary`, `tested` (boolean), `test_results` (mvn output last 30 lines), `open_questions`, `deviations_from_spec`.

## Out of scope

- НЕ делать git commit (architect сделает после review)
- НЕ запускать тесты unit (только compile + package)
- НЕ менять `pom.xml`
- НЕ касаться файлов вне 3 источников ошибок + (опционально) VibeCorePlugin/EdtRuntimeGateway если нужен новый service tracker
