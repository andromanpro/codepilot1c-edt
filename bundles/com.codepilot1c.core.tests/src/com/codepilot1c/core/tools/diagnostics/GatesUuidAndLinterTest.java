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
     * Регрессия на живой ложняк в макете этикеток типовой библиотеки: в мокселевском
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
