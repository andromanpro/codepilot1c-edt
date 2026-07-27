# M002: Unified Agent Runtime and Profiled Chat

**Дата:** 2026-07-16
**Статус:** дизайн утверждён
**Тип:** brownfield architecture consolidation
**Основной результат:** один production LLM/tool-loop через `AgentRunner`

## 1. Контекст

В приложении существуют два независимых контура общения с LLM:

1. `ChatView` самостоятельно формирует `LlmRequest`, вызывает provider, собирает streaming-ответ, исполняет tools и рекурсивно продолжает диалог.
2. `AgentRunner` реализует отдельный LLM/tool-loop с профилями, permission defaults, tool filtering, tracing, timeout и agent events.

Дополнительно `LangGraphAgentRunner` оборачивает `AgentRunner` одноузловым графом `START -> run_agent -> END`. Этот слой не выполняет маршрутизацию между несколькими узлами и не добавляет самостоятельной production-оркестрации.

Дублирование приводит к разному поведению основного чата, remote agent API и подагентов:

- основной чат не применяет profile allowlists;
- исправления streaming/tool protocol приходится переносить между двумя loop;
- `ChatView` и `AgentRunner` по-разному исполняют tools и подтверждения;
- session, cancellation и provider lifetime имеют разные границы;
- `LangGraphAgentRunner` увеличивает количество переходов без фактической graph orchestration;
- неактивный `AgentView` дублирует часть UI основного чата.

## 2. Предусловие milestone

Текущий `M001: Multi-client MCP host` имеет незакрытый remediation-gate `R006`: отсутствует live two-client EDT proof из-за неготового `EDT_HOME`.

Дизайн M002 можно планировать заранее, но активировать новый milestone следует после одного из двух явно зафиксированных событий:

- M001 успешно закрыт после live EDT proof; или
- M001 формально переведён в отдельный remediation state без ложного утверждения о завершении.

M002 не ослабляет критерии M001 и не поглощает его runtime blocker.

## 3. Цель

Все production-входы приложения используют один LLM/tool-loop, реализованный в `AgentRunner`. `ChatView` отвечает только за UI, сбор пользовательского ввода и отображение типизированных agent events.

К завершению milestone:

- прямые LLM/tool-loop методы удалены из `ChatView`;
- `AgentRunner` поддерживает весь необходимый UX основного чата;
- каждое окно чата и каждый remote-клиент имеют изолированную session-scoped execution boundary;
- основной чат позволяет выбирать agent profile;
- `LangGraphAgentRunner` и связанный одноузловой execution graph удалены;
- remote API, Code.md initialization и subagents используют тот же runtime;
- старые сессии продолжают читаться;
- providers без native tool calling работают как обычный текстовый чат без ложной tool surface.

## 4. Не-цели

В M002 не входят:

- автоматический failover между LLM-провайдерами;
- переработка provider transport implementations;
- исправление всех существующих provider-specific gaps, не необходимых для перехода на единый runtime;
- настоящая многоузловая LangGraph-оркестрация;
- удаление `GraphStudio` и `LangGraphStudioService`;
- полный редизайн `BrowserChatPanel`;
- изменение публичных JSON schemas существующих tools;
- перенос UI/workbench API в bundle `com.codepilot1c.core`;
- runtime fallback на старый `ChatView` loop после завершения milestone.

## 5. Рассмотренные варианты

### Вариант A — `AgentRunner` как единственный runtime

Расширить `AgentRunner` до parity с основным чатом, ввести session-scoped execution boundary, подключить `ChatView` через events и удалить дубли.

**Выбран.** Он сохраняет существующие профили, tracing, tool filters и permission model, не создавая третий loop.

### Вариант B — новый `ConversationRuntime` над `AgentRunner`

Ввести отдельный orchestration runtime, который управляет `AgentRunner`.

**Отклонён.** Такой слой рискует стать ещё одним владельцем loop и дублировать lifecycle-контракты.

### Вариант C — вынести loop из `ChatView` в core

Канонизировать текущую реализацию основного чата и адаптировать к ней остальные пути.

**Отклонён.** Это сохраняет менее зрелую модель без profile/tool gates и потребует повторного рефакторинга.

## 6. Целевая архитектура

```text
ChatView × N        Remote Web API       TaskTool/subagents       Code.md init
     |                    |                     |                      |
     +--------------------+---------------------+----------------------+
                                  |
                     AgentConversationSession
                     - full conversation history
                     - provider/model/profile snapshot
                     - single-flight and cancellation
                     - approval bridge
                                  |
                             AgentRunner
                     - SystemPromptAssembler
                     - profile/context tool surface
                     - provider streaming/complete
                     - tool execution and protocol
                     - tracing and typed events
                                  |
                           ILlmProvider / tools
```

### 6.1. `AgentConversationSession`

Новая session-scoped граница владеет состоянием одного диалога, но не реализует LLM/tool-loop.

Обязанности:

- полная `List<LlmMessage>` history;
- выбранный session profile;
- создание immutable snapshot для каждого turn;
- один активный `AgentRunner` на turn;
- single-flight, cancel и dispose;
- преобразование результата turn в новую history;
- session persistence и compaction coordination;
- подключение approval handler конкретного consumer;
- сохранение session id, project binding и UI metadata.

Каждый экземпляр `ChatView` получает отдельную `AgentConversationSession`. Remote-контроллер хранит отдельную сессию на remote session/client. Каждый subagent создаёт изолированную короткоживущую сессию.

Глобальный singleton не должен владеть `activeRunner`, `activeTask` или единственной conversation history для всех consumers.

### 6.2. `AgentTurnRequest`

`IAgentRunner` получает типизированный вход вместо одного `String prompt`.

Минимальный контракт:

- user `LlmMessage`, включая multimodal content parts;
- snapshot предыдущей history;
- `AgentConfig`;
- optional model override;
- requested skills;
- turn/session correlation ids;
- streaming preference;
- token/tool-output limits.

`AgentConversationSession` фиксирует provider в turn snapshot и создаёт для этого turn экземпляр `AgentRunner` с данным provider. Provider не дублируется внутри `AgentTurnRequest`.

Существующие `run(String, ...)` могут временно делегировать новому API во время миграции, но не остаются вторым внутренним loop.

### 6.3. Turn snapshot

В начале turn фиксируются:

- active provider;
- effective model;
- выбранный profile;
- provider capabilities;
- project/session context;
- доступная tool surface;
- requested skills.

Изменения preferences во время streaming влияют только на следующий turn. Продолжение после tool results использует тот же provider/model snapshot.

### 6.4. Agent events

Core публикует UI-neutral типизированные события:

- turn started;
- step started;
- text delta;
- reasoning delta;
- stream completed;
- tool call received;
- approval requested;
- tool execution started;
- tool result;
- token usage updated;
- turn completed;
- turn failed;
- turn cancelled.

Каждое событие содержит `sessionId`, `turnId`, `step` и необходимые correlation ids. UI не восстанавливает принадлежность tool card по эвристикам.

### 6.5. Approval gateway

Core остаётся независимым от SWT и workbench API. `AgentRunner` запрашивает решение через асинхронный UI-neutral интерфейс.

Approval требуется, когда:

- `tool.requiresConfirmation()` возвращает `true`; или
- `tool.isDestructive()` возвращает `true`; или
- consumer policy требует preview/review конкретного вызова.

`ChatView` реализует gateway через существующие confirmation dialogs и edit preview. Remote API преобразует запрос в approve/reject event. Subagent без интерактивного consumer применяет profile permission defaults и fail-closed policy.

Если UI закрыт или handler потерян во время ожидания, операция отклоняется. Автоматическое подтверждение при ошибке UI запрещено.

### 6.6. Tool execution

`AgentRunner` остаётся единственным владельцем:

- protocol sequence `assistant tool_calls -> matching tool results`;
- JSON argument parsing/repair;
- permission checks;
- profile and context filtering;
- repetition detection;
- result truncation;
- tracing;
- повторного запроса к LLM.

Независимые tool calls могут выполняться bounded-parallel. Результаты добавляются в history в детерминированном порядке исходных tool calls. Вызовы, требующие approval или preview, не обходят policy gateway.

### 6.7. `ChatView`

После cutover `ChatView` отвечает за:

- ввод текста и attachments;
- выбор provider/model через существующие controls;
- выбор profile;
- создание/выбор conversation session;
- отображение agent events в `BrowserChatPanel`;
- confirmation и edit preview UI;
- команды stop/new chat/session restore.

`ChatView` не должен:

- вызывать `provider.complete()` или `provider.streamComplete()`;
- самостоятельно формировать LLM/tool-loop request;
- исполнять tools;
- рекурсивно продолжать conversation after tools;
- повторно выбирать provider внутри одного turn.

### 6.8. Profile UI

Основная поверхность:

- Auto;
- Build;
- Plan;
- Explore;
- Orchestrator.

Advanced-поверхность:

- Code;
- Metadata;
- QA;
- DCS;
- Extension;
- Recovery;
- GSD, если доступен в registry.

Служебный `Init` в ручном selector не показывается.

Для существующих и новых обычных сессий default — Build. Auto использует `ProfileRouter` по первому пользовательскому сообщению, после чего resolved concrete profile фиксируется в session metadata и не маршрутизируется заново на каждом turn.

Profile является session-scoped. Смена profile в непустом диалоге предлагает создать новый chat. Это исключает смешивание system prompts и permission models в одной history.

### 6.9. Provider capabilities

В capability contract появляется явный признак native tool calling. Если provider его не поддерживает:

- `tools` не отправляются;
- `toolChoice` отключается;
- UI не обещает agent tools;
- диалог работает как обычный text/multimodal chat в пределах остальных capabilities.

M002 сохраняет provider-specific transports за `ILlmProvider`. CodePilot backend, OpenAI-compatible, Codex, legacy Claude и Ollama продолжают использовать свои request/stream parsers.

## 7. Persistence и compaction

Новая session serialization сохраняет:

- SYSTEM/USER/ASSISTANT/TOOL messages;
- reasoning content;
- tool calls и tool call ids;
- attachment descriptors и multimodal parts;
- profile id;
- effective model metadata;
- token counters и compaction marker, если применимо.

Старый сокращённый JSON формат остаётся читаемым. Миграция выполняется при следующем сохранении без обязательного массового rewrite существующих файлов.

Запись session должна быть атомарной. Частичная запись не заменяет последнюю валидную сессию.

Compaction находится на session boundary, но использует общий protocol sanitizer. Она не может разделять assistant tool-call block и соответствующие tool results.

## 8. Error handling

- Нет provider failover: provider error детерминированно завершает текущий turn.
- Tool error преобразуется в machine-usable `ToolResult` и возвращается модели.
- Unknown/disabled tool также получает matching tool result, чтобы history не нарушала protocol.
- Timeout отменяет streaming/provider и pending approval, затем завершает turn контролируемой ошибкой.
- Cancel одного окна или remote session не влияет на другие sessions.
- Потеря approval consumer приводит к DENIED/controlled failure.
- Provider без tool calling не получает tool definitions.
- Ошибка persistence не уничтожает in-memory history и последнюю валидную запись.
- Ошибка UI rendering не должна повторно исполнять tool или LLM request.

Runtime fallback на старый loop не используется.

## 9. Миграционные фазы

### Phase 1 — Canonical contracts and characterization

**Цель:** создать безопасную границу миграции до изменения основного UI.

Результаты:

- characterization tests текущего ChatView behavior;
- `AgentTurnRequest`;
- расширенный event contract с correlation ids;
- `AgentConversationSession` lifecycle contract;
- capability `supportsToolCalling`;
- session/provider/model/profile snapshot contract;
- backward-compatible persistence schema design.

Exit gate:

- существующий ChatView всё ещё работает через старый loop;
- новые контракты имеют unit tests;
- зафиксированы text, reasoning, tool, confirmation, preview, attachment и cancel scenarios.

### Phase 2 — AgentRunner parity

**Цель:** сделать `AgentRunner` функционально достаточным для основного чата.

Результаты:

- multimodal user input;
- model override и provider snapshot;
- token usage events;
- reasoning parity;
- UI-neutral approval gateway;
- `requiresConfirmation || isDestructive` policy;
- bounded-parallel tool execution с детерминированной history;
- protocol-safe compaction integration;
- cancellation и timeout parity;
- provider capability gating.

Exit gate:

- core tests доказывают streaming/non-streaming parity;
- AgentRunner проходит provider contract matrix;
- ни один UI API не добавлен в core.

### Phase 3 — ChatView cutover and profiles

**Цель:** перевести основной пользовательский чат на canonical runtime.

Результаты:

- event-to-BrowserChatPanel adapter;
- основные и advanced profile controls;
- session-scoped profile behavior;
- attachment/model/skills handoff через `AgentTurnRequest`;
- confirmation/edit-preview gateway;
- full transcript persistence и restore;
- multi-view session isolation;
- удаление собственного loop из `ChatView` после parity proof.

Exit gate:

- `ChatView` не вызывает LLM provider напрямую;
- два окна выполняют независимые turns;
- текущий browser tool-card UX сохранён;
- profile allowlists реально меняют tool surface.

### Phase 4 — Secondary entry-point convergence

**Цель:** убрать альтернативные production execution paths.

Результаты:

- remote web agent использует отдельную `AgentConversationSession`;
- Code.md initialization использует служебный Init profile через canonical runtime;
- `task` и `delegate_to_agent` запускают прямой AgentRunner/session path;
- `AgentSessionController` перестаёт быть глобальным владельцем единственного active execution state;
- remote API contracts и event names сохраняются совместимыми.

Exit gate:

- remote start/input/approve/reject/stop scenarios проходят;
- subagent и Code.md initialization проходят;
- production-код больше не создаёт `LangGraphAgentRunner`.

### Phase 5 — Dead-code removal and release proof

**Цель:** физически завершить консолидацию и доказать release readiness.

Удаляется:

- `LangGraphAgentRunner`;
- `LangGraphAgentRunContext`;
- одноузловой `LangGraphAgentGraphFactory`;
- их execution tests;
- не зарегистрированный `AgentView`;
- старый `AgentViewAdapter`;
- obsolete ChatView loop fields/methods/tests;
- устаревшие imports/exports/documentation claims.

Сохраняется:

- `GraphStudioView`;
- `LangGraphStudioService`, пока он нужен GraphStudio;
- `BrowserChatPanel`;
- provider implementations;
- `ToolRegistry` registration/dynamic precedence;
- public remote HTTP contracts.

Exit gate:

- static cleanup checks проходят;
- operational EDT smoke проходит;
- полный reactor `mvn -DskipTests package` проходит;
- update-site содержит ожидаемый qualifier и новые bundles.

## 10. Требования milestone

| ID | Требование | Основная фаза |
|---|---|---|
| R001 | Единственный production LLM/tool-loop находится в `AgentRunner` | 2–5 |
| R002 | Chat, remote и subagent sessions изолированы; turn snapshot неизменяем | 1, 3, 4 |
| R003 | Сохранён UX parity основного чата | 1–3 |
| R004 | ChatView предоставляет основные и advanced profiles | 3 |
| R005 | Profile allowlists и permission defaults применяются в основном чате | 2, 3 |
| R006 | Полный transcript сохраняется; старый session JSON читается | 1, 3 |
| R007 | Remote, Code.md init и subagents используют canonical runtime | 4 |
| R008 | Старый ChatView loop, LangGraph execution wrapper и неактивный AgentView удалены | 3–5 |
| R009 | Provider matrix, operational smoke и full reactor проходят | 2, 5 |

## 11. Acceptance gates

### 11.1. Characterization

До cutover тестами фиксируются:

- text streaming;
- reasoning streaming;
- tool cards и ownership;
- tool-only assistant turn;
- destructive confirmation;
- edit preview approve/reject;
- image/file attachment;
- cancel during streaming;
- cancel during confirmation;
- session save/restore;
- compaction рядом с tool block.

### 11.2. Core contracts

- один AgentRunner проходит streaming и non-streaming paths;
- profile/context/config filters формируют ожидаемую tool surface;
- provider/model не меняются внутри turn;
- все tool calls получают matching results;
- bounded-parallel execution сохраняет исходный result order;
- provider without tools получает request без tool definitions;
- tracing и correlation ids сохраняются.

### 11.3. Isolation

- два `ChatView` одновременно выполняют turns;
- разные profiles не смешивают histories/tool surfaces;
- cancel одного turn не отменяет второй;
- confirmation одного окна не блокирует решение другого;
- remote session не использует history desktop session.

### 11.4. UI parity

- основной текст и reasoning обновляются потоково;
- tool cards остаются привязаны к исходному assistant turn;
- tool result обновляет существующую карточку, а не создаёт дубль;
- preview и destructive confirmation выполняются до mutation;
- model picker CodePilot backend сохраняет текущую семантику;
- selector профиля доступен и сохраняется в session metadata.

### 11.5. Persistence

- полный tool transcript восстанавливается;
- attachments сохраняются в сериализуемом виде;
- legacy session JSON открывается без ошибки;
- corrupted/incomplete new write не уничтожает предыдущую валидную сессию.

### 11.6. Secondary paths

- `task`;
- `delegate_to_agent`;
- Code.md create/update;
- remote `/agent/start`;
- remote `/agent/input`;
- remote approve/reject;
- remote stop.

Все эти пути подтверждают использование canonical runtime.

### 11.7. Static cleanup

В production source отсутствуют:

- прямые `provider.complete`/`streamComplete` в `ChatView`;
- `processToolCalls`/`continueAfterTools` и рекурсивный ChatView loop;
- production-конструкторы `LangGraphAgentRunner`;
- registration/reference неактивного `AgentView`;
- второй владелец LLM/tool protocol.

### 11.8. Provider matrix

Проверяются:

- CodePilot backend;
- dynamic OpenAI-compatible;
- OpenAI Codex Responses API;
- legacy Claude;
- provider без native tools, включая Ollama path;
- configured non-streaming provider.

Dynamic Anthropic native tool-use implementation не является обязательной частью M002, но его capability должен честно отражать фактическое поведение.

### 11.9. Operational EDT smoke

В собранном приложении проверяются:

- два окна ChatView;
- разные profiles;
- streaming и reasoning;
- file/image attachment на поддерживающей модели;
- read-only tool;
- destructive tool с confirmation;
- edit preview approve/reject;
- stop;
- save/restore;
- subagent turn;
- remote agent turn.

### 11.10. Release

- полный `mvn -DskipTests package` из корня;
- update-site берётся только из `repositories/com.codepilot1c.update/target/repository`;
- versions/qualifier проверены в `content.jar` и `plugins/`;
- release artifacts не собираются partial reactor build.

## 12. Риски и снижения

| Риск | Снижение |
|---|---|
| AgentRunner не покрывает multimodal/model override | Типизированный turn contract и Phase 2 parity до UI cutover |
| UI events недостаточны для BrowserChatPanel | Characterization tests и correlation ids до удаления старого loop |
| Singleton controller ломает multi-view | Per-session execution instances; controller не владеет глобальным active runner |
| Смена profile смешивает system prompts | Profile session-scoped; смена создаёт новый chat |
| Parallel tools меняют порядок history | Bounded execution с детерминированной записью results в исходном порядке |
| Provider capabilities завышены | Явный `supportsToolCalling` и contract matrix |
| Preview протекает в core UI APIs | Асинхронный UI-neutral approval gateway |
| Старые сессии перестают открываться | Additive schema и legacy reader tests |
| Удаление LangGraph затрагивает GraphStudio | Удаляется только execution wrapper; studio service остаётся отдельным |
| Cutover скрывает регрессию runtime fallback | Нет fallback; обязательны parity и operational gates перед удалением |

## 13. Оценка

Ожидаемый размер: 12–18 инженерных дней плюс live EDT verification.

Наиболее рискованные участки:

- Phase 2 — AgentRunner parity;
- Phase 3 — ChatView cutover и session isolation.

Phase 5 не должна становиться местом функциональных исправлений: к ней все behavior gaps уже закрыты.

## 14. Definition of Done

Milestone завершён только если одновременно выполнены условия:

- все девять требований закрыты доказательствами;
- основной ChatView использует `AgentRunner` и не содержит собственного LLM/tool-loop;
- remote, Code.md init и subagents используют canonical runtime;
- profiles работают в основном чате и реально ограничивают tools;
- multi-view и remote sessions изолированы;
- old and new session persistence tests проходят;
- production references на `LangGraphAgentRunner` отсутствуют;
- неактивный `AgentView` и старый adapter удалены;
- operational EDT smoke пройден;
- полный Tycho reactor build и update-site artifact audit пройдены;
- runtime fallback на старый loop отсутствует.
