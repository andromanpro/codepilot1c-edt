package com.codepilot1c.core.tools.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Test;

import com.codepilot1c.core.diagnostics.BslSilentTypeLinter;

/**
 * Негативные контроли гейтов #71 (уникальность uuid) и #72 (Роли.Роль — ссылка).
 *
 * Проверка, которая всегда «чисто», неотличима от отсутствующей проверки:
 * позитивный прогон на здоровом проекте (0 дублей на 19 502 значениях) одинаково
 * выглядит и когда сканер работает, и когда он молча ничего не находит. Поэтому
 * каждый гейт здесь зафиксирован парой «обязан найти» / «обязан промолчать».
 */
public class GatesUuidAndLinterTest {

    // ---------- #71: уникальность uuid ----------

    @Test
    public void дубльUuidВДвухОбъектахНаходится() throws IOException {
        Path src = newSrc("uuid-dup-");
        String shared = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        writeMdo(src.resolve("Catalogs").resolve("A"), "A", shared);
        writeMdo(src.resolve("Catalogs").resolve("B"), "B", shared);

        EdtUuidCheckTool.ScanReport report = EdtUuidCheckTool.scan(src);

        assertFalse("сканер обязан признать проект грязным", report.clean());
        assertEquals(1, report.duplicates().size());
        assertEquals("обе копии обязаны попасть в карту вхождений",
                2, report.duplicates().get(shared).size());
    }

    @Test
    public void здоровоеДеревоДаётЧистыйОтчёт() throws IOException {
        Path src = newSrc("uuid-ok-");
        writeMdo(src.resolve("Catalogs").resolve("A"), "A", "11111111-1111-1111-1111-111111111111");
        writeMdo(src.resolve("Catalogs").resolve("B"), "B", "22222222-2222-2222-2222-222222222222");

        EdtUuidCheckTool.ScanReport report = EdtUuidCheckTool.scan(src);

        assertTrue("на здоровом дереве ложных дублей быть не должно", report.clean());
        assertEquals(2, report.filesScanned());
    }

    @Test
    public void пустойUuidФиксируетсяОтдельно() throws IOException {
        Path src = newSrc("uuid-empty-");
        Path dir = src.resolve("Catalogs").resolve("Empty");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("Empty.mdo"),
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mdclass:Catalog uuid=\"\">\n</mdclass:Catalog>\n",
                StandardCharsets.UTF_8);

        EdtUuidCheckTool.ScanReport report = EdtUuidCheckTool.scan(src);

        assertEquals("пустой uuid = SU106, обязан быть виден", 1, report.emptyUuids().size());
        assertFalse(report.clean());
    }

    /**
     * Регрессия на живой ложняк (ШаблоныЭтикетокИЦенниковБПО): в мокселевском
     * шаблоне идентификаторы полей повторяются легитимно. Сканер обязан
     * пропускать такие файлы, иначе гейт кричит на здоровой конфигурации —
     * а гейт, который кричит зря, выключают целиком.
     */
    @Test
    public void мокселевскийШаблонПропускается() throws IOException {
        Path src = newSrc("uuid-mxlx-");
        Path dir = src.resolve("Catalogs").resolve("WithTemplate");
        Files.createDirectories(dir);
        String repeated = "cccccccc-cccc-cccc-cccc-cccccccccccc";
        Files.writeString(dir.resolve("Template.mxlx"),
                "<doc><cell uuid=\"" + repeated + "\"/><cell uuid=\"" + repeated + "\"/></doc>",
                StandardCharsets.UTF_8);

        EdtUuidCheckTool.ScanReport report = EdtUuidCheckTool.scan(src);

        assertTrue("повторы внутри .mxlx — не дубли метаданных", report.clean());
        assertEquals("файл вообще не должен сканироваться", 0, report.filesScanned());
    }

    /**
     * Негативный контроль асимметрии, найденной 2026-08-27 (Gitea #78 / PR #79,
     * «побочная находка»): дубли ловились по ЛЮБОМУ имени атрибута, а пустые —
     * только по литеральному uuid="". У предопределённого элемента справочника
     * обязательный идентификатор зовётся id (CatalogPredefinedItem.id,
     * lowerBound=1), поэтому пустой id="" гейт не замечал — и SU106 доезжал до
     * экспорта в формат платформы.
     */
    @Test
    public void пустойIdПредопределённогоЭлементаНаходится() throws IOException {
        Path src = newSrc("uuid-empty-id-");
        Path dir = src.resolve("Catalogs").resolve("Валюты");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("Валюты.mdo"),
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<mdclass:Catalog uuid=\"33333333-3333-3333-3333-333333333333\">\n"
                        + "  <predefined>\n    <items id=\"\">\n      <name>Рубль</name>\n"
                        + "    </items>\n  </predefined>\n</mdclass:Catalog>\n",
                StandardCharsets.UTF_8);

        EdtUuidCheckTool.ScanReport report = EdtUuidCheckTool.scan(src);

        assertEquals("пустой id обязательного uuid-атрибута = SU106", 1, report.emptyUuids().size());
        assertEquals("в отчёте обязано стоять реальное имя атрибута",
                "id", report.emptyUuids().get(0).attribute());
        assertFalse(report.clean());
    }

    /**
     * Полный список обязательных uuid-атрибутов, снятый обходом метамодели EDT
     * 2025.2.3 (все 134 EPackage, EAttribute с instanceClassName java.util.UUID
     * и lowerBound=1) и суженный до тех, что реально встречаются в src рабочей
     * конфигурации. Пустым обязан отбиваться каждый, а не только uuid.
     */
    @Test
    public void пустымиОтбиваютсяВсеОбязательныеИмена() throws IOException {
        String[] names = {"uuid", "id", "typeId", "valueTypeId", "classId", "objectId", "thisNode"};
        for (String name : names) {
            Path src = newSrc("uuid-empty-" + name + "-");
            Path dir = src.resolve("Catalogs").resolve("X");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("X.mdo"),
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mdclass:Catalog " + name + "=\"\">\n"
                            + "</mdclass:Catalog>\n",
                    StandardCharsets.UTF_8);

            EdtUuidCheckTool.ScanReport report = EdtUuidCheckTool.scan(src);

            assertEquals("пустой " + name + "=\"\" обязан быть находкой",
                    1, report.emptyUuids().size());
            assertEquals(name, report.emptyUuids().get(0).attribute());
        }
    }

    /**
     * Обратная сторона: расширение списка не должно превратить гейт в крикуна.
     * Эти пустые атрибуты реально встречаются в src живого проекта (SwapDate —
     * 1471 вхождение, class — 33, src/Base/style/title — единицы) и uuid не
     * несут. Гейт, который кричит на здоровой конфигурации, выключают целиком.
     */
    @Test
    public void пустыеНеUuidАтрибутыМолчат() throws IOException {
        Path src = newSrc("uuid-noise-");
        Path dir = src.resolve("Catalogs").resolve("Noise");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("Noise.mdo"),
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<mdclass:Catalog uuid=\"44444444-4444-4444-4444-444444444444\">\n"
                        + "  <item SwapDate=\"\" class=\"\" src=\"\" Base=\"\" style=\"\" title=\"\"/>\n"
                        + "</mdclass:Catalog>\n",
                StandardCharsets.UTF_8);

        EdtUuidCheckTool.ScanReport report = EdtUuidCheckTool.scan(src);

        assertTrue("не-uuid атрибуты не находки: " + report.emptyUuids(), report.clean());
    }

    /**
     * Статический список снят с метамодели ОДНОЙ версии EDT и относится к .mdo.
     * Чтобы гейт не протух на новых носителях uuid, имя, замеченное с настоящим
     * uuid в файлах того же расширения, тоже становится обязательным.
     */
    @Test
    public void имяВыученноеИзПроектаОтбиваетсяПустым() throws IOException {
        Path src = newSrc("uuid-learned-");
        Path dir = src.resolve("BusinessProcesses").resolve("Задание");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("Flowchart.scheme"),
                "<doc>\n  <Start pointUUID=\"55555555-5555-5555-5555-555555555555\"/>\n"
                        + "  <Activity pointUUID=\"\"/>\n</doc>\n",
                StandardCharsets.UTF_8);

        EdtUuidCheckTool.ScanReport report = EdtUuidCheckTool.scan(src);

        assertEquals("pointUUID нет в статическом списке, но он выучен из того же расширения",
                1, report.emptyUuids().size());
        assertEquals("pointUUID", report.emptyUuids().get(0).attribute());
    }

    /**
     * Обратная сторона выученного: целочисленный id графической схемы
     * ({@code <Start id="1">} в .scheme) uuid не несёт, поэтому статический
     * .mdo-список на .scheme не распространяется.
     */
    @Test
    public void целочисленныйIdГрафическойСхемыНеСчитаетсяUuidНосителем() throws IOException {
        Path src = newSrc("uuid-scheme-int-");
        Path dir = src.resolve("BusinessProcesses").resolve("Задание");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("Flowchart.scheme"),
                "<doc>\n  <Start id=\"1\"/>\n  <ConnectionLine id=\"\"/>\n</doc>\n",
                StandardCharsets.UTF_8);

        EdtUuidCheckTool.ScanReport report = EdtUuidCheckTool.scan(src);

        assertTrue("пустой целочисленный id — не находка гейта #71: " + report.emptyUuids(),
                report.clean());
    }

    // ---------- #72: Роли.Роль — ссылка, не строка ----------

    @Test
    public void присваиваниеСтрокиВРольПредупреждается() {
        List<String> warnings = BslSilentTypeLinter.lint(
                "СтрокаРолей = Профиль.Роли.Добавить();\n"
                        + "СтрокаРолей.Роль = \"ЧтениеБазовойНормативноСправочнойИнформации\";\n");

        assertEquals("строка в ссылочном реквизите обязана быть замечена", 1, warnings.size());
    }

    @Test
    public void правильноеПрисваиваниеСсылкиМолчит() {
        List<String> warnings = BslSilentTypeLinter.lint(
                "СтрокаРолей.Роль = ОбщегоНазначения.ИдентификаторОбъектаМетаданных(\"Роль.Имя\");\n");

        assertTrue("корректный рецепт не должен давать ложную тревогу: " + warnings, warnings.isEmpty());
    }

    @Test
    public void кодБезРолейМолчит() {
        assertTrue(BslSilentTypeLinter.lint("Значение = \"просто строка\";\n").isEmpty());
    }

    @Test
    public void линтерРазличаетТипФайла() {
        assertTrue(BslSilentTypeLinter.isBslPath("CommonModules/X/Module.bsl"));
        assertFalse(BslSilentTypeLinter.isBslPath("Catalogs/X/X.mdo"));
    }

    // ---------- helpers ----------

    private static Path newSrc(String prefix) throws IOException {
        Path root = Files.createTempDirectory(prefix);
        Path src = root.resolve("src");
        Files.createDirectories(src);
        return src;
    }

    private static void writeMdo(Path dir, String name, String uuid) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".mdo"),
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<mdclass:Catalog xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\" uuid=\""
                        + uuid + "\">\n  <name>" + name + "</name>\n</mdclass:Catalog>\n",
                StandardCharsets.UTF_8);
    }
}
