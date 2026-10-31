package com.codepilot1c.core.tools.diagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.eclipse.core.resources.IProject;

import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;

/**
 * Machine gate for antipattern #71 ("silent platform failures"): duplicated or
 * hand-cloned uuids in the EDT project sources. The platform reports no error —
 * an object cloned "by example" with a foreign uuid silently shadows or loses
 * objects at configuration load, and produced-type id collisions corrupt the
 * type table. Run after mass generation, adoption or dump merges.
 *
 * <p>The check is filesystem-only (no BM): every uuid-valued XML attribute
 * ({@code uuid}, {@code typeId}, {@code valueTypeId}, ...) under {@code src}
 * must be globally unique. Empirically a healthy project has zero repeated
 * values across all of them, so any duplicate is a finding, not noise.
 * Empty uuid attributes are reported too (EDT SU106: the project stops
 * exporting to the platform format).</p>
 *
 * <p>The empty-value half used to match the single literal {@code uuid=""},
 * while the duplicate half matched any attribute name — so an empty
 * {@code id=""} of a predefined catalog item passed the gate unnoticed
 * (found 2026-08-27). The required uuid attribute names now come from the
 * EDT metamodel itself, see {@link #REQUIRED_MDO_UUID_ATTRIBUTES}.</p>
 */
@ToolMeta(name = "edt_uuid_check", category = "diagnostics",
        surfaceCategory = "smoke_runtime_recovery", tags = {"workspace", "edt"})
public class EdtUuidCheckTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(EdtUuidCheckTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {
                  "type": "string",
                  "description": "EDT project name whose src is scanned for duplicated uuid attribute values."
                }
              },
              "required": ["project"]
            }
            """; //$NON-NLS-1$

    private static final Pattern UUID_ATTRIBUTE = Pattern.compile(
            "([A-Za-z][A-Za-z0-9_]*)=\"([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\""); //$NON-NLS-1$
    /**
     * Имена атрибутов, обязанных нести uuid в {@code .mdo}. Список не угадан:
     * снят обходом метамодели установленной EDT 2025.2.3 — все 134 EPackage,
     * {@code EAttribute} с {@code instanceClassName == java.util.UUID} и
     * {@code lowerBound == 1}. Метамодель дала 13 таких имён; здесь оставлены
     * те, что реально встречаются в src рабочей конфигурации (5687 файлов):
     * uuid 10504 вхождений, typeId 4276, valueTypeId 4276, id 455,
     * classId 7, objectId 7, thisNode 2. Остальные шесть (instanceId, key,
     * parentId, refTypeId, userId, value) принадлежат внутренним моделям EDT
     * ({@code model}, {@code mcore}), в исходники проекта не сериализуются, а
     * имена вроде {@code key} / {@code value} дали бы только ложную тревогу.
     *
     * <p>Носители: {@code uuid} — любой MdObject (166 EClass'ов);
     * {@code id} — PredefinedItem и его 4 наследника (ровно тот случай, из-за
     * которого гейт молчал); {@code typeId} / {@code valueTypeId} — 29
     * EClass'ов пакета mdtype; {@code classId} и {@code objectId} —
     * ContainedObject; {@code thisNode} — ExchangePlan.</p>
     *
     * <p>Список применяется только к {@code .mdo}: в {@code .scheme}
     * графической схемы бизнес-процесса {@code id} целочисленный
     * ({@code <Start id="1">}), и пустым он к антипаттерну #71 отношения не
     * имеет. Прочие расширения закрывает выученная половина —
     * см. {@link #scan(Path)}.</p>
     */
    private static final Set<String> REQUIRED_MDO_UUID_ATTRIBUTES = Set.of(
            "uuid", "id", "typeId", "valueTypeId", "classId", "objectId", "thisNode"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$

    private static final String MDO_EXTENSION = "mdo"; //$NON-NLS-1$

    /**
     * Пустое значение ЛЮБОГО атрибута; носитель uuid отбирается после скана по
     * имени, а не регуляркой, иначе список пришлось бы держать в двух местах.
     * Lookbehind отсекает префикс пространства имён, чтобы {@code xsi:type=""}
     * не превратился в атрибут {@code type}.
     */
    private static final Pattern EMPTY_ATTRIBUTE = Pattern.compile(
            "(?<![A-Za-z0-9_:.-])([A-Za-z][A-Za-z0-9_]*)=\"\""); //$NON-NLS-1$

    /**
     * Binary/irrelevant payloads: BSL has no uuid attributes, .bin is the support
     * map, and moxel templates (.mxl/.mxlx) repeat field ids by design — a
     * FieldName referenced from several cells is legitimate, not a clone
     * (verified live on ШаблоныЭтикетокИЦенниковБПО).
     */
    private static final Set<String> SKIPPED_EXTENSIONS = Set.of(
            "bsl", "os", "bin", "png", "jpg", "jpeg", "gif", "ico", "bmp", "svg", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$
            "pdf", "zip", "jar", "epf", "erf", "cf", "cfe", "dt", "mxl", "mxlx"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$

    private static final long MAX_FILE_BYTES = 32L * 1024 * 1024;
    private static final int MAX_DUPLICATES_LISTED = 50;
    private static final int MAX_OCCURRENCES_LISTED = 10;
    private static final int MAX_EMPTY_LISTED = 50;

    private final EdtMetadataGateway gateway;

    public EdtUuidCheckTool() {
        this(new EdtMetadataGateway());
    }

    EdtUuidCheckTool(EdtMetadataGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public String getDescription() {
        return "Контроль уникальности uuid по src EDT-проекта (антипаттерн #71): дубли uuid/typeId/valueTypeId " //$NON-NLS-1$
                + "после массовой генерации, adopt или слияния выгрузок. Пустое значение любого обязательного " //$NON-NLS-1$
                + "uuid-атрибута (uuid, id, typeId, valueTypeId, classId, objectId, thisNode) — тоже находка (SU106)."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    /** Occurrence of one uuid value. */
    record UuidOccurrence(String relativePath, String attribute, int line) {

        String render() {
            return relativePath + ":" + line + " (" + attribute + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    /**
     * Пустой атрибут вместе с расширением файла: находкой он становится только
     * после скана, когда известно, какие имена в этом расширении вообще несут
     * uuid.
     */
    private record EmptyCandidate(UuidOccurrence occurrence, String extension) {}

    /** Pure scan outcome, independent from Eclipse resources for testability. */
    record ScanReport(int filesScanned, int totalValues,
            Map<String, List<UuidOccurrence>> duplicates, List<UuidOccurrence> emptyUuids) {

        boolean clean() {
            return duplicates.isEmpty() && emptyUuids.isEmpty();
        }
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("uuid-check"); //$NON-NLS-1$
            long startedAt = System.currentTimeMillis();
            String projectName = params.requireString("project"); //$NON-NLS-1$
            LOG.info("[%s] START edt_uuid_check project=%s", opId, projectName); //$NON-NLS-1$

            IProject project = gateway.resolveProject(projectName);
            if (project == null || !project.exists()) {
                return ToolResult.failure("Project not found: " + projectName); //$NON-NLS-1$
            }
            if (project.getLocation() == null) {
                return ToolResult.failure("Project location unavailable: " + projectName); //$NON-NLS-1$
            }
            Path srcRoot = project.getLocation().toFile().toPath().resolve("src"); //$NON-NLS-1$
            if (!Files.isDirectory(srcRoot)) {
                return ToolResult.failure("Project has no src directory: " + srcRoot); //$NON-NLS-1$
            }

            try {
                ScanReport report = scan(srcRoot);
                String rendered = render(projectName, report);
                LOG.info("[%s] DONE in %s files=%d values=%d duplicates=%d empty=%d", opId, //$NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        report.filesScanned(), report.totalValues(),
                        report.duplicates().size(), report.emptyUuids().size());
                return report.clean() ? ToolResult.success(rendered) : ToolResult.failure(rendered);
            } catch (IOException e) {
                LOG.error("[" + opId + "] edt_uuid_check failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure("uuid check failed: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    /**
     * Scans every text artifact under {@code srcRoot} and groups uuid values.
     *
     * <p>Пустые значения собираются в два прохода. Первый попутно запоминает
     * пары «расширение + имя атрибута», в которых встретился настоящий uuid, —
     * это выученная половина: она закрывает носителей, о которых
     * {@link #REQUIRED_MDO_UUID_ATTRIBUTES} не знает (другая версия EDT,
     * {@code .scheme}, {@code .dcs}, расширения конфигурации). Второй проход
     * по накопленным кандидатам оставляет только имена, обязанные нести uuid, —
     * иначе гейт кричал бы на {@code SwapDate=""} и {@code title=""}, которых в
     * живом src полторы тысячи, а гейт, кричащий на здоровой конфигурации,
     * выключают целиком.</p>
     */
    static ScanReport scan(Path srcRoot) throws IOException {
        Map<String, List<UuidOccurrence>> byValue = new LinkedHashMap<>();
        List<EmptyCandidate> emptyCandidates = new ArrayList<>();
        Set<String> uuidCarriers = new HashSet<>();
        int filesScanned = 0;
        int totalValues = 0;
        List<Path> files;
        try (Stream<Path> stream = Files.walk(srcRoot)) {
            files = stream.filter(Files::isRegularFile).sorted().toList();
        }
        for (Path file : files) {
            if (isSkipped(file)) {
                continue;
            }
            String text;
            try {
                if (Files.size(file) > MAX_FILE_BYTES) {
                    continue;
                }
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                // Undeclared binary or broken encoding — not a uuid carrier.
                continue;
            }
            filesScanned++;
            String relative = srcRoot.relativize(file).toString().replace('\\', '/');
            String extension = extensionOf(file);
            Matcher matcher = UUID_ATTRIBUTE.matcher(text);
            while (matcher.find()) {
                totalValues++;
                String value = matcher.group(2).toLowerCase(Locale.ROOT);
                byValue.computeIfAbsent(value, key -> new ArrayList<>())
                        .add(new UuidOccurrence(relative, matcher.group(1), lineOf(text, matcher.start())));
                uuidCarriers.add(carrierKey(extension, matcher.group(1)));
            }
            Matcher empty = EMPTY_ATTRIBUTE.matcher(text);
            while (empty.find()) {
                emptyCandidates.add(new EmptyCandidate(
                        new UuidOccurrence(relative, empty.group(1), lineOf(text, empty.start())), extension));
            }
        }
        List<UuidOccurrence> emptyUuids = new ArrayList<>();
        for (EmptyCandidate candidate : emptyCandidates) {
            if (carriesUuid(candidate.extension(), candidate.occurrence().attribute(), uuidCarriers)) {
                emptyUuids.add(candidate.occurrence());
            }
        }
        Map<String, List<UuidOccurrence>> duplicates = new LinkedHashMap<>();
        for (Map.Entry<String, List<UuidOccurrence>> entry : byValue.entrySet()) {
            if (entry.getValue().size() > 1) {
                duplicates.put(entry.getKey(), entry.getValue());
            }
        }
        return new ScanReport(filesScanned, totalValues, duplicates, emptyUuids);
    }

    private static boolean isSkipped(Path file) {
        String extension = extensionOf(file);
        return !extension.isEmpty() && SKIPPED_EXTENSIONS.contains(extension);
    }

    /** Расширение без точки, в нижнем регистре; пустая строка, если его нет. */
    private static String extensionOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT); //$NON-NLS-1$
    }

    private static String carrierKey(String extension, String attribute) {
        return extension + '|' + attribute;
    }

    /**
     * Обязан ли атрибут с таким именем нести uuid в файле такого расширения:
     * либо он в снятом с метамодели списке для {@code .mdo}, либо в файлах
     * этого же расширения уже встречался с настоящим uuid.
     */
    private static boolean carriesUuid(String extension, String attribute, Set<String> uuidCarriers) {
        return (MDO_EXTENSION.equals(extension) && REQUIRED_MDO_UUID_ATTRIBUTES.contains(attribute))
                || uuidCarriers.contains(carrierKey(extension, attribute));
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    static String render(String projectName, ScanReport report) {
        StringBuilder out = new StringBuilder();
        out.append("Контроль уникальности uuid: проект ").append(projectName).append('\n'); //$NON-NLS-1$
        out.append("Файлов просканировано (src): ").append(report.filesScanned()).append('\n'); //$NON-NLS-1$
        out.append("Значений uuid-атрибутов: ").append(report.totalValues()).append('\n'); //$NON-NLS-1$
        if (report.clean()) {
            out.append("Дублей: 0, пустых uuid: 0 — OK"); //$NON-NLS-1$
            return out.toString();
        }
        out.append("Дублей: ").append(report.duplicates().size()) //$NON-NLS-1$
                .append(", пустых uuid: ").append(report.emptyUuids().size()).append('\n'); //$NON-NLS-1$
        int listed = 0;
        for (Map.Entry<String, List<UuidOccurrence>> entry : report.duplicates().entrySet()) {
            if (listed++ >= MAX_DUPLICATES_LISTED) {
                out.append("... (показаны первые ").append(MAX_DUPLICATES_LISTED) //$NON-NLS-1$
                        .append(" дублей из ").append(report.duplicates().size()).append(")\n"); //$NON-NLS-1$ //$NON-NLS-2$
                break;
            }
            out.append("ДУБЛЬ ").append(entry.getKey()).append(" — ") //$NON-NLS-1$ //$NON-NLS-2$
                    .append(entry.getValue().size()).append(" вхождений:\n"); //$NON-NLS-1$
            List<UuidOccurrence> occurrences = entry.getValue();
            for (int i = 0; i < occurrences.size() && i < MAX_OCCURRENCES_LISTED; i++) {
                out.append("  - ").append(occurrences.get(i).render()).append('\n'); //$NON-NLS-1$
            }
            if (occurrences.size() > MAX_OCCURRENCES_LISTED) {
                out.append("  ... ещё ").append(occurrences.size() - MAX_OCCURRENCES_LISTED).append('\n'); //$NON-NLS-1$
            }
        }
        List<UuidOccurrence> empties = report.emptyUuids();
        for (int i = 0; i < empties.size() && i < MAX_EMPTY_LISTED; i++) {
            out.append("ПУСТОЙ ").append(empties.get(i).attribute()).append("=\"\": ") //$NON-NLS-1$ //$NON-NLS-2$
                    .append(empties.get(i).render()).append('\n');
        }
        if (empties.size() > MAX_EMPTY_LISTED) {
            out.append("... (показаны первые ").append(MAX_EMPTY_LISTED) //$NON-NLS-1$
                    .append(" пустых из ").append(empties.size()).append(")\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        out.append("Антипаттерн #71 (молчаливые отказы платформы): дубль uuid молча теряет/затирает объект ") //$NON-NLS-1$
                .append("при загрузке, коллизия typeId ломает таблицу типов, пустое значение обязательного ") //$NON-NLS-1$
                .append("uuid-атрибута (uuid/id/typeId/valueTypeId/classId/objectId/thisNode) валит экспорт (SU106). ") //$NON-NLS-1$
                .append("Исправь uuid на новые уникальные и повтори проверку; затем пробная загрузка в пустую базу."); //$NON-NLS-1$
        return out.toString();
    }
}
