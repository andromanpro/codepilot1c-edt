# Provider-Neutral Tool Surface Cleanup

**Дата:** 2026-07-21

**Статус:** готов к ревью

**Тип:** brownfield cleanup provider/tool contracts

**Основной результат:** единая model-facing tool surface, не зависящая от Qwen, CodePilot backend или глобального выбора provider в UI

## 1. Контекст и результат исследования

В марте 2026 года в provider/tool pipeline был добавлен отдельный Qwen-контур:

- определение Qwen model family в `ProviderCapabilities`;
- `QwenFunctionCallingTransport` с XML priming;
- `QwenToolCallExamples` с примерами для каждого tool;
- Qwen-specific content и streaming parsers;
- Qwen-specific rewrite model-facing descriptions и JSON schemas tools.

Коммит `a4dad3d` от 2026-05-27 уже удалил большую часть этого контура:

- удалены `QwenFunctionCallingTransport`, `QwenToolCallExamples` и `QwenStreamingToolCallParser`;
- `QwenContentToolCallParser` преобразован в provider-neutral `ContentToolCallFallbackParser`;
- model-family flags удалены из `ProviderCapabilities`;
- CodePilot backend переведён на общий OpenAI-compatible structured-tool path без XML priming;
- Qwen-specific tool contributors переименованы в backend-oriented contributors.

Текущий production-код не содержит отдельного Qwen transport. Однако в tool surface осталась унаследованная условная ветка: если в глобальных UI preferences выбран CodePilot backend, `ToolSurfaceAugmentor` меняет model-facing definitions tools.

Текущий overlay содержит:

- 56 overrides описаний built-in tools;
- 8 explicit overrides JSON schemas;
- routing guidance для 10 категорий tools;
- recursive schema hardening для built-in и dynamic/MCP tools.

Overlay не меняет регистрацию или исполнение `ITool`. Он меняет только контракт, который видит модель или MCP-клиент:

- `ChatView` отправляет effective definitions через structured `tools`;
- `AgentRunner` отправляет effective definitions через structured `tools` и повторяет их сокращённые описания через `ToolPromptRenderer`;
- `DiscoverToolsTool` возвращает effective definitions раскрытых категорий;
- `McpHostRequestRouter.tools/list` публикует effective description и `inputSchema` внешним MCP-клиентам.

Текущая привязка к глобальному UI selection создаёт две проблемы.

1. Одинаковый tool имеет разные публичные description/schema в зависимости от выбранного provider.
2. Remote, subagent или MCP path может получить surface, определённую не фактическим execution provider, а несвязанной глобальной UI preference.

Исследование также подтвердило drift между overlay и runtime contracts:

- overlay schema `edt_validate_request.operation` не показывает актуальные `external_manage`, `extension_manage`, `dcs_manage` и `mutate_role_rights`;
- overlay description/schema `write_file` не отражает актуальную возможность создавать документационные `.md` и `.txt` файлы;
- model-facing overrides и канонические `ITool.getDescription()`/`getParameterSchema()` развиваются независимо.

Characterization suite из 15 тестов прошёл до изменений. Он подтверждает условный backend rewrite, распространение augmentation в MCP host, единый OpenAI structured-tool path без XML priming и сохранение content fallback.

## 2. Цель

Удалить Qwen/CodePilot-specific зависимость из tool surface, сохранив общий overlay как provider-neutral слой нормализации.

После cleanup:

- один и тот же зарегистрированный tool имеет один effective model-facing contract для всех providers;
- сборка tool definitions не читает active provider или глобальный UI selection;
- built-in и dynamic/MCP tools проходят одинаковую provider-neutral нормализацию;
- ChatView, AgentRunner, `discover_tools` и MCP host используют один assembly path;
- новые и изменённые tools не требуют Qwen examples, Qwen model patterns или Qwen-specific streaming checks;
- generic OpenAI-compatible transport и внешние Qwen CLI/MCP workflows продолжают работать.

## 3. Не-цели

В cleanup не входят:

- удаление `evals/qwen/`;
- удаление Qwen CLI runners, qwen-codex queue и task templates;
- удаление README-разделов про внешний Qwen CLI/MCP workflow;
- удаление исторических Qwen упоминаний из release notes и закрытых implementation plans;
- удаление `ContentToolCallFallbackParser`, XML/JSON/Kimi marker parsing или `JsonRepairUtil`;
- удаление generic OpenAI streaming tool-call accumulation и repair;
- изменение tool registration, built-in-over-dynamic precedence или `ToolExecutionService`;
- изменение profile allowlists, permissions, `ToolContextGate`, `ToolGraph` или deferred loading;
- реализация M002 Unified Agent Runtime;
- полный перенос всех overlay descriptions и schemas в классы конкретных tools.

## 4. Рассмотренные варианты

### Вариант A — канонизировать definitions в каждом tool и удалить overlay

Перенести необходимые description/schema rules в `ITool` implementations и удалить augmentation abstraction.

**Отклонён для этого cleanup.** Вариант устраняет дублирование наиболее полно, но требует широкого изменения десятков tools и увеличивает риск смешать provider cleanup с пересмотром публичных contracts.

### Вариант B — сохранить общий provider-neutral overlay

Оставить `ToolSurfaceAugmentor`, но удалить provider state и backend gates. Все consumers получают одинаковый normalized contract.

**Выбран.** Вариант минимизирует runtime churn, сохраняет существующие safety descriptions/schema hardening и устраняет Qwen/CodePilot dependency.

### Вариант C — удалить overlay без миграции

Вернуть всем consumers raw `ITool` definitions.

**Отклонён.** Вариант теряет часть EDT safety guidance и schema hardening и создаёт неконтролируемое изменение MCP surface.

## 5. Текущий и целевой data flow

### 5.1. Текущий flow

```text
global UI provider selection
          |
          v
ProviderContextResolver
          |
          v
ToolSurfaceContext(provider, backendSelectedInUi, profile, category, provenance)
          |
          v
backend-gated contributors
          |
          v
ChatView / AgentRunner / discover_tools / MCP tools/list
```

### 5.2. Целевой flow

```text
ITool + profile + category + built-in/dynamic provenance
                         |
                         v
            provider-neutral contributors
                         |
                         v
          one effective ToolDefinition
                         |
                         v
ChatView / AgentRunner / discover_tools / MCP tools/list
```

Execution path остаётся отдельным:

```text
ToolCall -> ToolRegistry -> ToolExecutionService -> ITool.execute
```

Cleanup не меняет этот путь и не использует normalized schema как замену runtime validation.

## 6. Целевая архитектура

### 6.1. `ToolSurfaceAugmentor`

`ToolSurfaceAugmentor` остаётся единственной точкой построения model-facing `ToolDefinition`.

Default pipeline содержит три provider-neutral contributor:

1. built-in description/schema rewrite;
2. category routing guidance;
3. dynamic/MCP guidance и schema hardening.

Contributors продолжают исполняться в детерминированном порядке. Имя tool не может быть изменено contributor.

### 6.2. `ToolSurfaceContext`

Контекст содержит только данные, влияющие на provider-neutral surface:

- `AgentProfile`;
- `ToolCategory`;
- built-in/dynamic provenance.

Из контекста удаляются:

- provider snapshot;
- active provider id;
- `backendSelectedInUi`.

`ToolSurfaceContext` не читает preferences и не разрешает provider.

### 6.3. Contributors

`BackendToolSurfaceRewriteContributor` получает provider-neutral имя, отражающее его фактическую роль. Его `supports(...)` проверяет только built-in provenance.

`ToolRoutingSurfaceContributor` применяется ко всем built-in tools с известной нединамической категорией. Текст использует нейтральный префикс `Tool routing`.

`DynamicToolSurfaceContributor` применяется ко всем dynamic/MCP tools. Текст `Backend note` заменяется на provider-neutral указание о внешнем происхождении tool и необходимости следовать его schema.

`ToolSurfaceSchemaNormalizer` остаётся общим механизмом:

- 8 explicit built-in overrides сохраняются после обязательной сверки с runtime contracts;
- остальные object schemas получают `additionalProperties: false`, если поле не задано;
- object schemas с `properties` получают `required: []`, если required отсутствует;
- рекурсивно обрабатываются `properties`, `items`, `anyOf`, `oneOf` и `allOf`;
- исходный `ITool` и его raw schema не мутируются.

Существующее поведение при неразбираемом стороннем schema не меняется в этом cleanup. Проектирование отдельного fail-closed dynamic-tool registration contract является другой задачей.

### 6.4. `ToolRegistry`

`ToolRegistry.createRuntimeSurfaceContext(profile)` создаёт контекст напрямую. `ProviderContextResolver` удаляется.

`getToolDefinition(tool, context)` и `getToolDefinitions(...)` сохраняются как единый assembly API. Это снижает churn в ChatView, AgentRunner, `DiscoverToolsTool` и MCP host.

`ToolRegistry` по-прежнему определяет provenance и category каждого tool перед вызовом augmentor.

### 6.5. Provider capabilities

Поле `backendOptimizations`, builder method и `ProviderUtils.supportsBackendOptimizations(...)` удаляются: production-код их не использует после provider-neutral cutover.

Сохраняются capability, которые имеют отдельный runtime effect:

- `codePilotBackend`;
- prompt cache headers;
- resolved model reporting;
- text tool-call fallback;
- native deferred loading;
- multimodal capabilities;
- streaming usage.

`ProviderSelectionGate` не удаляется. Он продолжает управлять backend-only prompts, filesystem prompt overrides и skills, но больше не влияет на tool descriptions/schemas.

## 7. Schema и description contract

### 7.1. Explicit schema overrides

До provider-neutral cutover каждый из 8 overrides сверяется с runtime parser и canonical raw schema:

- `read_file`;
- `list_files`;
- `glob`;
- `grep`;
- `edit_file`;
- `write_file`;
- `edt_validate_request`;
- `ensure_module_artifact`.

Для каждого override должны совпадать:

- primary property names;
- required fields;
- типы параметров;
- числовые границы, если runtime их реально применяет;
- enum values, если surface ограничивает значение;
- смысл optional/default behavior.

Aliases, которые runtime принимает только для backward compatibility, не обязаны публиковаться как primary model-facing properties.

### 7.2. Validation operations

Enum `edt_validate_request.operation` должен включать все публичные operations, реально поддерживаемые `ValidationOperation`, включая composite operations. Contract test сравнивает surface enum с утверждённым runtime набором.

### 7.3. Descriptions

56 description overrides проходят аудит на:

- соответствие фактическому runtime behavior;
- отсутствие несуществующих tools и параметров;
- сохранение EDT metadata/form/DCS safety boundaries;
- отсутствие Qwen/backend terminology;
- разумный token footprint.

Новый tool не обязан получать explicit override. Его canonical `getDescription()` используется по умолчанию, после чего category contributor добавляет общую routing guidance.

`ToolPromptRenderer` сохраняет текущий limit в 360 символов на одно описание. Structured `tools` payload использует полное effective description.

## 8. Документация и живые инструкции

Текущий workspace-local `AGENTS.md` содержит обязательный Qwen checklist со ссылками на удалённые APIs и отсутствующий `docs/QWEN_OPTIMIZATION_PLAN.md`.

В рамках реализации:

- Qwen Optimization Rules удаляются из локального `AGENTS.md`;
- Qwen-specific пункты удаляются из checklist нового/изменённого tool;
- provider-neutral требования сохраняются: валидный JSON schema, точный required, deterministic results, profile/permission alignment и streaming contract tests;
- `tools/generate_tool_prompt_inventory.py` обновляется с Qwen transport на фактический `DynamicLlmProvider`/OpenAI-compatible path;
- generator не должен ссылаться на несуществующие source files.

`AGENTS.md` игнорируется текущим `.gitignore`. Реализация обновляет рабочую копию, но не добавляет ignored файл в product commit без отдельного решения о versioning repo instructions.

Исторические release notes и закрытые plans не переписываются. Внешние Qwen CLI/MCP инструкции остаются актуальными и не считаются остатком production Qwen transport.

## 9. Отношение к M002 Unified Agent Runtime

Cleanup выполняется отдельной серией изменений до реализации M002.

Причина: provider-neutral cutover изменяет public tool definitions для non-backend и MCP paths, а утверждённый дизайн M002 явно считает изменение публичных JSON schemas не-целью.

После cleanup M002 получает стабильный baseline:

- ChatView и AgentRunner уже используют один effective assembly API;
- turn snapshot M002 не должен содержать tool-surface provider gate;
- cutover ChatView на AgentRunner не меняет descriptions/schemas второй раз;
- provider capability `supportsToolCalling`, запланированная в M002, остаётся отдельной задачей.

Дизайн M002 не изменяется этим документом.

## 10. Error handling

- Ошибка одного contributor не должна приводить к переключению на provider-specific fallback.
- Built-in schemas считаются repository-owned contract и проверяются тестами до runtime.
- Существующий raw-schema fallback для неразбираемого dynamic schema сохраняется без расширения.
- Unknown tool и invalid tool arguments продолжают обрабатываться `ToolExecutionService` и конкретным `ITool`.
- Provider error не влияет на assembly tool surface.
- Ошибка MCP serialization возвращается на уровне MCP request и не меняет registry state.
- Cleanup не вводит compatibility flag и не оставляет старый backend-gated path.

## 11. Миграционные фазы

### Phase 1 — Characterization и contract audit

Результаты:

- snapshots текущей backend и non-backend surface;
- audit 56 descriptions;
- parity matrix для 8 schema overrides;
- regression tests текущего transport behavior;
- список разрешённых исторических/внешних Qwen упоминаний.

Exit gate:

- каждый известный drift имеет точное исправление;
- тесты падают при пропущенной validation operation или несовпавшем primary property.

### Phase 2 — Provider-neutral surface

Результаты:

- provider fields удалены из `ToolSurfaceContext`;
- contributors переименованы и больше не проверяют provider selection;
- dynamic/MCP hardening применяется универсально;
- `ProviderContextResolver` удалён;
- `ToolRegistry` строит контекст локально.

Exit gate:

- один tool даёт одинаковый effective contract при CodePilot, OpenAI-compatible, Ollama и отсутствии active provider;
- ChatView, AgentRunner, `discover_tools` и MCP используют общий assembly API;
- production assembly не читает `ProviderSelectionGate`.

### Phase 3 — Dead contract cleanup

Результаты:

- удалён `backendOptimizations` capability и его helpers/tests;
- обновлены workspace-local `AGENTS.md` и tracked inventory generator;
- tests и имена классов не используют backend/Qwen terminology для provider-neutral surface.

Exit gate:

- в live runtime source, live instructions и inventory generator нет ссылок на удалённые Qwen APIs;
- внешние Qwen workflows и исторические документы сохранены.

### Phase 4 — Verification

Результаты:

- targeted provider/tool/MCP tests;
- полный `com.codepilot1c.core.tests`;
- полный reactor package build;
- size snapshot effective tool surface.

Exit gate:

- все тесты и build проходят;
- working tree содержит только ожидаемые implementation/doc changes;
- публикация update site не выполняется без отдельного запроса.

## 12. Тестовая стратегия

### 12.1. Provider invariance

Один registry fixture строит surface при:

- `CODEPILOT_BACKEND`;
- `OPENAI_COMPATIBLE`;
- `OLLAMA`;
- отсутствии active provider.

Для одинакового profile и набора registered tools сравниваются name, description и normalized schema. Результаты должны совпадать.

### 12.2. Schema parity

Для 8 explicit overrides тесты проверяют:

- valid JSON;
- expected property set;
- required set;
- `additionalProperties` policy;
- critical enums и ranges;
- соответствие primary runtime argument names.

Для `edt_validate_request` добавляется отдельная проверка полного operation enum.

### 12.3. Consumer parity

- `ToolRegistry` возвращает normalized definitions;
- `DiscoverToolsTool` возвращает те же definitions для раскрытой категории;
- MCP `tools/list` публикует те же description/schema;
- `AgentRunner` передаёт normalized definitions в structured request и `ToolPromptRenderer`;
- characterization test ChatView подтверждает использование `ToolRegistry.getToolDefinitions()` без provider-specific branch.

### 12.4. Generic provider transport regression

Сохраняются тесты:

- CodePilot backend использует один OpenAI structured-tool path без XML priming;
- structured streaming tool calls собираются из fragments;
- pending calls корректно завершаются без provider-specific finish-reason parser;
- generic content fallback распознаёт поддерживаемые XML/JSON/Kimi markers;
- repaired arguments маркируются и mutating tools могут их отклонить;
- GLM, MiniMax и Kimi compatibility profiles не регрессируют.

### 12.5. Reference guard

Guard проверяет отсутствие live references на:

- `QwenFunctionCallingTransport`;
- `QwenToolCallExamples`;
- `QwenContentToolCallParser`;
- `QwenStreamingToolCallParser`;
- `isQwenNative`;
- `getResolvedModelFamily`;
- `resolveModelFamily`.

Guard применяется к production source, active tests, workspace-local `AGENTS.md` и inventory generator. Исторические release notes/plans и внешние Qwen CLI/MCP workflows входят в явный allowlist.

### 12.6. Size regression

Snapshot фиксирует:

- количество visible tools для representative profile;
- суммарную длину descriptions;
- суммарную длину normalized schemas;
- размер секции `ToolPromptRenderer`.

Текущая backend surface является верхней baseline. Provider-neutral cutover не должен превысить её без отдельного обоснования.

## 13. Verification commands

Targeted tests выполняются через core test module с `-am` для сборки core dependency.

После targeted tests выполняются:

```bash
mvn -pl bundles/com.codepilot1c.core.tests -am test
mvn -DskipTests package
```

Второй command проверяет полный Tycho reactor и OSGi packaging. Он не публикует update site.

## 14. Риски и смягчение

| Риск | Смягчение |
|---|---|
| Non-backend providers получают более объёмные descriptions/schemas | Size snapshot и current backend surface как верхняя baseline |
| Public MCP schema меняется сразу для внешних клиентов | Один атомарный cutover, `tools/list` parity test и release note при поставке |
| Универсальный override закрепляет текущий schema drift | Предварительный audit и parity tests для всех 8 explicit overrides |
| Новый tool забывают добавить в centralized description switch | Explicit override не обязателен; canonical description плюс category guidance являются корректным default |
| Provider-neutral rename случайно затрагивает transport compatibility | Transport regression suite и сохранение capability с реальным runtime effect |
| Cleanup конфликтует с M002 | Отдельная серия изменений до M002 и стабильный baseline для будущего runtime cutover |
| Ignored `AGENTS.md` остаётся устаревшим в других checkout | Текущая working copy обновляется; versioning repo instructions выносится в отдельное решение |

## 15. Требования

| ID | Требование |
|---|---|
| QRM-01 | Effective tool description/schema не зависит от active provider или UI selection |
| QRM-02 | Все четыре consumer path используют `ToolRegistry`/`ToolSurfaceAugmentor` assembly |
| QRM-03 | Built-in и dynamic/MCP tools получают provider-neutral normalization |
| QRM-04 | 8 explicit schema overrides соответствуют primary runtime contracts |
| QRM-05 | `edt_validate_request` публикует полный актуальный operation enum |
| QRM-06 | Tool execution, profile, permission, graph и deferred-loading semantics не меняются |
| QRM-07 | Generic OpenAI structured calls, streaming repair и content fallback сохраняются |
| QRM-08 | Внешние Qwen CLI/MCP workflows и evals сохраняются |
| QRM-09 | Live instructions и generator не ссылаются на удалённые Qwen APIs |
| QRM-10 | Cleanup выполняется отдельно до M002 и не изменяет его design document |

## 16. Definition of Done

Cleanup завершён, когда одновременно выполнены условия:

- provider selection отсутствует в tool-surface assembly data flow;
- один tool имеет один effective contract для всех providers;
- известные description/schema drift исправлены;
- `ProviderContextResolver` и dead `backendOptimizations` удалены;
- Qwen-specific требования к новым/изменённым tools удалены из живых инструкций;
- inventory generator отражает фактический provider path;
- external Qwen CLI/MCP assets и generic compatibility parsers сохранены;
- provider invariance, schema parity, consumer parity, transport regression и size tests проходят;
- полный `com.codepilot1c.core.tests` проходит;
- полный reactor `mvn -DskipTests package` проходит;
- runtime-код не содержит compatibility flag или fallback на старую backend-gated surface.
