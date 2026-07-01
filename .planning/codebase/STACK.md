# Technology Stack

**Analysis Date:** 2026-07-01

## Languages

**Primary:**
- Java 17 - Eclipse RCP/OSGi plugin code in `bundles/com.codepilot1c.core/src`, `bundles/com.codepilot1c.ui/src`, `bundles/com.codepilot1c.core.tests/src`, and `bundles/com.codepilot1c.ui.tests/src`; the reactor contains 931 Java files outside generated/Node dependencies.

**Secondary:**
- XML - Maven/Tycho reactor metadata in `pom.xml`, `bom/pom.xml`, `bundles/pom.xml`, `features/pom.xml`, `repositories/pom.xml`, `targets/pom.xml`, bundle metadata in `META-INF/MANIFEST.MF`, extension metadata in `plugin.xml`, feature/update-site metadata in `features/com.codepilot1c.feature/feature.xml` and `repositories/com.codepilot1c.update/category.xml`, and extension schemas in `bundles/com.codepilot1c.core/schema/*.exsd`.
- Properties files - OSGi localization and UI messages in `bundles/com.codepilot1c.core/OSGI-INF/l10n/bundle.properties`, `bundles/com.codepilot1c.core/OSGI-INF/l10n/bundle_ru.properties`, `bundles/com.codepilot1c.ui/OSGI-INF/l10n/bundle.properties`, `bundles/com.codepilot1c.ui/OSGI-INF/l10n/bundle_ru.properties`, and `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/internal/messages*.properties`.
- JavaScript, HTML, CSS - Embedded remote UI assets in `bundles/com.codepilot1c.core/web/remote/index.html`, `bundles/com.codepilot1c.core/web/remote/app.js`, `bundles/com.codepilot1c.core/web/remote/app.css`, Mermaid support in `bundles/com.codepilot1c.ui/web/mermaid.min.js`, static update-site landing pages in `site/index.html` and `site/root-index.html`, and Playwright remote-web tests in `e2e/remote-web/tests`.
- Markdown - Product docs, runbooks, and planning references in `docs/`, including `docs/reports/edt-metadata-uuid-export-runbook.md`, `docs/reports/edt-api-patterns-retrospective-2026-02-14.md`, `docs/reports/edt-bm-model-investigation-2026-02-13.md`, `docs/reports/edt-diagnostics-research-2026-02-15.md`, and `docs/reports/tool-graph-router-plan-2026-02-25.md`.

## Runtime

**Environment:**
- JavaSE-17 - Required by `bundles/com.codepilot1c.core/META-INF/MANIFEST.MF`, `bundles/com.codepilot1c.ui/META-INF/MANIFEST.MF`, and `bom/pom.xml`.
- Eclipse RCP/OSGi - Bundles are `eclipse-plugin` artifacts in `bundles/com.codepilot1c.core/pom.xml` and `bundles/com.codepilot1c.ui/pom.xml`; UI desktop tests use `eclipse-test-plugin` in `bundles/com.codepilot1c.ui.tests/pom.xml`.
- Eclipse target platform - `targets/default/default.target` resolves Eclipse 2023-12, Xtext 2.33.0, `org.eclipse.tm.terminal.feature.feature.group`, `org.eclipse.cdt.native.feature.group`, `com.google.gson`, and a local 1C:EDT install at `/Applications/1C/1CE/components/1c-edt-2025.1.5+34-x86_64/1cedt (2025.1.5+34).app/Contents/Eclipse`.
- OSGi bundle startup - Core activator is `com.codepilot1c.core.internal.VibeCorePlugin` in `bundles/com.codepilot1c.core/META-INF/MANIFEST.MF`; UI activator is `com.codepilot1c.ui.internal.VibeUiPlugin` in `bundles/com.codepilot1c.ui/META-INF/MANIFEST.MF`.
- Node.js - Used only for remote-web Playwright E2E tests under `e2e/remote-web`; not part of the Tycho product runtime.

**Package Manager:**
- Maven - Full reactor build from repository root is the authoritative deliverable flow: `mvn -DskipTests package`.
- Maven wrapper: Not detected (`mvnw` is absent).
- Lockfile: No Java lockfile; p2 resolution is governed by `targets/default/default.target` and Tycho metadata. Node lockfile `e2e/remote-web/package-lock.json` is present for remote-web E2E tests.
- npm - Used only in `e2e/remote-web/package.json` for `npm test` / `npm run test:live`.

## Frameworks

**Core:**
- Eclipse PDE/OSGi - Bundle manifests in `bundles/com.codepilot1c.core/META-INF/MANIFEST.MF` and `bundles/com.codepilot1c.ui/META-INF/MANIFEST.MF`; extension points in `bundles/com.codepilot1c.core/plugin.xml`.
- Eclipse RCP UI/JFace/SWT - UI workbench integration is in `bundles/com.codepilot1c.ui/plugin.xml`, `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/views`, `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/preferences`, and `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/handlers`.
- 1C:EDT BM/API integration - EDT services are imported in `bundles/com.codepilot1c.core/META-INF/MANIFEST.MF` and accessed through gateway/service classes such as `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataGateway.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/ast/EdtServiceGateway.java`, and `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/runtime/EdtRuntimeGateway.java`.
- Agent/tool runtime - Agent profiles, permissions, tools, and graph routing live in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/agent`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/agent/profiles`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools`, and `bundles/com.codepilot1c.core/src/com/codepilot1c/core/permissions`.
- MCP client and host - Implemented in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp`, including outbound transports under `mcp/transport` and inbound host transport under `mcp/host/transport`.
- Memory/context subsystem - Implemented in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory`, with Markdown storage in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory/store/MarkdownMemoryStore.java` and in-memory search in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory/search/InMemorySearchIndex.java`.
- Dedicated RAG bundle - `bundles/com.codepilot1c.rag` is not present in `bundles/pom.xml`, `features/com.codepilot1c.feature/feature.xml`, or the current `bundles/` directory. Current retrieval/search code lives in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory`.

**Testing:**
- JUnit 4.13.2 - Core tests in `bundles/com.codepilot1c.core.tests/pom.xml` and `bundles/com.codepilot1c.core.tests/src`.
- Maven Surefire 3.2.5 - Plain JUnit test execution for `bundles/com.codepilot1c.core.tests`.
- Tycho Surefire 4.0.4 - UI/PDE desktop test execution with UI harness and UI thread enabled in `bundles/com.codepilot1c.ui.tests/pom.xml`; activated through the `desktop-ui-tests` Maven profile in `pom.xml`.
- Playwright 1.53.2 - Remote web E2E tests in `e2e/remote-web/package.json`, configured by `e2e/remote-web/playwright.config.mjs`.

**Build/Dev:**
- Tycho 4.0.4 - Managed in `bom/pom.xml` through `tycho-maven-plugin`, `tycho-compiler-plugin`, `tycho-packaging-plugin`, `tycho-p2-plugin`, `tycho-p2-publisher-plugin`, `tycho-p2-repository-plugin`, `tycho-surefire-plugin`, and `target-platform-configuration`.
- Maven compiler Java 17 - `maven.compiler.source` and `maven.compiler.target` are set in `bom/pom.xml`; core test compilation uses `maven-compiler-plugin` release 17 in `bundles/com.codepilot1c.core.tests/pom.xml`.
- Eclipse p2 repository build - `repositories/com.codepilot1c.update/pom.xml` packages `eclipse-repository`; `repositories/com.codepilot1c.update/category.xml` defines the `copilot` update-site category.
- Tycho target platform - `targets/default/pom.xml` and `targets/default/default.target` are the p2 resolution source.
- PDE build properties - Runtime packaging includes `META-INF/`, `plugin.xml`, `OSGI-INF/`, `schema/`, `icons/`, `lib/`, `skills/`, `web/`, and `resources/` from `bundles/com.codepilot1c.core/build.properties`; UI packaging includes `META-INF/`, `plugin.xml`, `OSGI-INF/`, `icons/`, `resources/`, `web/`, and `lib/` from `bundles/com.codepilot1c.ui/build.properties`.
- JVM build overrides - `.mvn/jvm.config` disables several JDK XML entity-size limits needed by the Tycho/Eclipse metadata build.

## Key Dependencies

**Critical:**
- Eclipse SDK / UI / Core Resources / JFace / Xtext - Resolved through `targets/default/default.target` and required by `bundles/com.codepilot1c.core/META-INF/MANIFEST.MF` and `bundles/com.codepilot1c.ui/META-INF/MANIFEST.MF`.
- 1C:EDT platform bundles - Imported through `com._1c.g5.*` and `com.e1c.g5.*` packages in `bundles/com.codepilot1c.core/META-INF/MANIFEST.MF`; required for BM metadata operations, platform docs, runtime/infobase operations, validation markers, DCS, forms, rights, and standalone server integration.
- Gson - Target-platform package plus explicit test dependency in `bundles/com.codepilot1c.core.tests/pom.xml`; used heavily for provider configs, tool schemas, MCP JSON-RPC, traces, and preferences.
- Jackson 2.17.2 - Embedded in `bundles/com.codepilot1c.core/lib` and included in the core `Bundle-ClassPath`.
- LangChain4j 1.2.0 and LangGraph4j 1.6.0 - Embedded in `bundles/com.codepilot1c.core/lib`; agent graph/studio code is under `bundles/com.codepilot1c.core/src/com/codepilot1c/core/agent/langgraph`.
- Jetty 12.0.12 and Jakarta Servlet 6.0.0 - Embedded in `bundles/com.codepilot1c.core/lib`; used by graph/studio server dependencies and packaged through the core bundle classpath.
- Flexmark 0.64.8 - Embedded in `bundles/com.codepilot1c.ui/lib`; Markdown rendering support is under `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/markdown`.
- PDFBox app 2.0.30 - Embedded in `bundles/com.codepilot1c.core/lib` and also used as a core test dependency in `bundles/com.codepilot1c.core.tests/pom.xml`.
- OpenNLP tools 2.5.4 - Embedded in `bundles/com.codepilot1c.core/lib`.

**Infrastructure:**
- Java `HttpClient` - Provider/backend clients use it in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config/DynamicLlmProvider.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/backend/BackendService.java`, and `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/codex/CodexProvider.java`.
- Java `HttpServer` - Inbound MCP host uses `com.sun.net.httpserver.HttpServer` in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/host/transport/McpHostHttpTransport.java`.
- Eclipse Secure Storage - Sensitive tokens are handled by `bundles/com.codepilot1c.core/src/com/codepilot1c/core/settings/SecureStorageUtil.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/auth/SecureTokenStore.java`, `bundles/com.codepilot1c.core/src/com/codepilot1c/core/backend/BackendService.java`, and `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/host/McpHostConfigStore.java`.
- JGit - Imported in `bundles/com.codepilot1c.core/META-INF/MANIFEST.MF` for Git-related tools under `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/git`.
- Eclipse TM Terminal and CDT native - Feature requirements in `features/com.codepilot1c.feature/feature.xml`; terminal UI integration is in `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/handlers/OpenTerminalHandler.java`.
- SLF4J 2.0.9 with JDK14 binding - Embedded in `bundles/com.codepilot1c.core/lib` and available to embedded third-party libraries.

## Configuration

**Environment:**
- No `.env` files are detected at repository root during this scan; do not add or read secret material from `.env`, `*.key`, `*.pem`, or credential files.
- User/provider settings are stored in Eclipse preferences using constants from `bundles/com.codepilot1c.core/src/com/codepilot1c/core/settings/VibePreferenceConstants.java`.
- Backend URL defaults are Java system properties in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/backend/BackendConfig.java`: `vibe.backend.url` defaults to `https://api.codepilot1c.ru`, `vibe.auth.url` defaults to `https://codepilot1c.ru`.
- MCP host can be configured through Eclipse preferences and Java system properties in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/host/McpHostConfigStore.java`: `codepilot.mcp.host.enabled`, `codepilot.mcp.host.http.enabled`, `codepilot.mcp.host.http.bindAddress`, `codepilot.mcp.host.http.port`, `codepilot.mcp.host.auth.mode`, `codepilot.mcp.host.policy.defaultMutationDecision`, `codepilot.mcp.host.policy.exposedTools`, and `codepilot.mcp.host.http.bearerToken`.
- MCP outbound HTTP can allow insecure HTTP only through `codepilot.mcp.allowInsecureHttp` in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/transport/McpTransportFactory.java`.
- Agent trace output is controlled by `codepilot1c.agent.trace.enabled` and `codepilot1c.agent.trace.dir` in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/evaluation/trace/AgentTraceSession.java` and `bundles/com.codepilot1c.core/src/com/codepilot1c/core/evaluation/trace/ArtifactLayout.java`.
- Project GSD skills live under `.codex/skills/`; codebase-map templates live under `.codex/get-shit-done/templates/codebase/`. These guide planning artifacts and are not plugin runtime bundles.

**Build:**
- Root reactor: `pom.xml` with modules `targets`, `bundles`, `features`, and `repositories`.
- BOM/parent: `bom/pom.xml` with group/artifact/version `com.codepilot1c:vibe-bom:0.1.7-SNAPSHOT` and Tycho 4.0.4 plugin management.
- Bundle reactor: `bundles/pom.xml` with `com.codepilot1c.core`, `com.codepilot1c.core.tests`, and `com.codepilot1c.ui`.
- Feature reactor: `features/pom.xml` and `features/com.codepilot1c.feature/pom.xml`; the installable feature is `features/com.codepilot1c.feature/feature.xml`.
- Repository reactor: `repositories/pom.xml` and `repositories/com.codepilot1c.update/pom.xml`; installable update site is emitted to `repositories/com.codepilot1c.update/target/repository`.
- Optional UI test reactor: `tests/pom.xml`, enabled by the `desktop-ui-tests` profile in `pom.xml`.
- Remote-web E2E: `e2e/remote-web/package.json`, `e2e/remote-web/package-lock.json`, and `e2e/remote-web/playwright.config.mjs`.

## Platform Requirements

**Development:**
- JDK 17 and Maven are required to build `pom.xml`; the repository does not include `mvnw`.
- The default target requires the local 1C:EDT 2025.1.5+34 Eclipse install path declared in `targets/default/default.target`; adjust the target platform before building on machines without that path.
- The target platform pulls p2 content from `https://download.eclipse.org/releases/2023-12` and `https://download.eclipse.org/modeling/tmf/xtext/updates/releases/2.33.0/` unless already cached.
- Local EDT metadata/export/debug work must follow the BM/API runbooks in `docs/reports/edt-metadata-uuid-export-runbook.md`, `docs/reports/edt-api-patterns-retrospective-2026-02-14.md`, and `docs/reports/edt-bm-model-investigation-2026-02-13.md`.
- For new tool work, built-ins belong in `ToolRegistry.registerDefaultTools()` in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/ToolRegistry.java`; runtime MCP/UI tools must use dynamic registration via `registerDynamicTool`.
- For UI-only EDT workbench access, place code in `bundles/com.codepilot1c.ui`; `get_diagnostics` is registered dynamically from `bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/internal/VibeUiPlugin.java`.
- For provider/model-specific behavior, use capability/config paths under `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider` and `bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider/config`; the current CodePilot backend compatibility layer is `ProviderCapabilities.java`, `OpenAiCompatibilityProfileResolver.java`, and `OpenAiModelCompatibilityPolicy.java`.
- Qwen-named optimization classes referenced by project instructions are not present under `bundles/com.codepilot1c.core/src` in this working tree; new Qwen-specific implementation must remain provider/capability gated and must not be folded into generic OpenAI request behavior.

**Production:**
- Deliverable is an Eclipse p2 update site built by the full reactor command `mvn -DskipTests package` from repository root.
- Install/publish artifacts must come from `repositories/com.codepilot1c.update/target/repository` or the update ZIP `repositories/com.codepilot1c.update/target/com.codepilot1c.update-1.3.0-SNAPSHOT.zip` when produced by the reactor.
- CI build/release workflows are disabled by design in `.github/workflows/build.yml` and `.github/workflows/release.yml`; they print instructions to use `tools/publish-p2-local.sh`.
- `tools/publish-p2-local.sh` is referenced by the workflows and project rules, but the tracked path is absent in the current working tree (`git status --short` reports it deleted); restore it before relying on the local publish script.
- `.github/workflows/docker.yml` still defines a Docker/GHCR pipeline, but `docker/Dockerfile`, `docker/Dockerfile.base`, `docker/Dockerfile.plugin`, and related Docker files are absent in the current working tree; do not treat that workflow as the active release path without restoring Docker assets.
- Docker EDT baseline from project rules is EDT Linux tar `/Users/alexorlik/Downloads/1c_edt_distr_offline_2025.1.5_34_linux_x86_64.tar` copied to `docker/edt.tar` for `docker/Dockerfile`, but the `docker/` context is absent in the current working tree.
- Runtime target platforms declared in `bom/pom.xml` include Linux GTK x86_64, Windows x86_64, macOS Cocoa x86_64, and macOS Cocoa aarch64.

---

*Stack analysis: 2026-07-01*
