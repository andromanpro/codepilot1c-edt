package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com._1c.g5.v8.dt.md.distribution.support.UserSupportMode;

/**
 * Негативные контроли гейта #70 (замок поддержки).
 *
 * Гейт, который не умеет ОТКАЗАТЬ, ничего не гейтит: без этих проверок
 * «зелёный» прогон одинаково выглядит и когда замок отбивается, и когда
 * SupportLockGuard молча пропускает всё (fail-open при недоступной
 * support-модели — штатная ветка кода). Поэтому каждая проверка ниже
 * зафиксирована парой «должен отказать» / «должен пропустить».
 */
public class SupportLockGuardTest {

    /** Карта поддержки для теста: uuid -> режим, плюс глобальный замок конфигурации. */
    private static final class FakeLookup implements SupportLockGuard.SupportModeLookup {

        private final Map<String, UserSupportMode> modes = new HashMap<>();
        private boolean wholeLocked;

        FakeLookup put(String uuid, UserSupportMode mode) {
            modes.put(uuid, mode);
            return this;
        }

        FakeLookup lockWholeConfiguration() {
            wholeLocked = true;
            return this;
        }

        @Override
        public boolean wholeConfigurationLocked() {
            return wholeLocked;
        }

        @Override
        public UserSupportMode modeOf(String uuid) {
            return modes.get(uuid);
        }
    }

    // ---------- enforce: ядро гейта ----------

    @Test
    public void замокБезФлагаОтклоняетОперацию() {
        try {
            SupportLockGuard.enforce(UserSupportMode.CHANGES_NOT_ALLOWED, false, "update_metadata",
                    "CommonModule.ОбщегоНазначения");
            fail("замок обязан отклонить мутацию: без отказа гейт бесполезен");
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.SUPPORTED_OBJECT_LOCKED, e.getCode());
        }
    }

    @Test
    public void явныйФлагВладельцаСнимаетЗамок() {
        SupportLockGuard.enforce(UserSupportMode.CHANGES_NOT_ALLOWED, true, "update_metadata", "X");
    }

    @Test
    public void редактируемыйНаПоддержкеПроходит() {
        SupportLockGuard.enforce(UserSupportMode.CHANGES_ALLOWED, false, "update_metadata", "X");
    }

    @Test
    public void снятыйСПоддержкиПроходит() {
        SupportLockGuard.enforce(UserSupportMode.CANCELLED, false, "update_metadata", "X");
    }

    @Test
    public void объектВнеПоддержкиПроходит() {
        SupportLockGuard.enforce(null, false, "update_metadata", "X");
    }

    // ---------- resolveFileMode: определение субъекта ----------

    @Test
    public void модульВнутриЗамкнутогоОбъектаЛовитЗамок() throws IOException {
        Path root = Files.createTempDirectory("qg-lock-");
        Path object = root.resolve("src").resolve("Catalogs").resolve("Vendor");
        Files.createDirectories(object);
        String uuid = "11111111-2222-3333-4444-555555555555";
        writeMdo(object.resolve("Vendor.mdo"), uuid);
        Path module = object.resolve("Ext");
        Files.createDirectories(module);
        Path moduleFile = module.resolve("ObjectModule.bsl");
        Files.writeString(moduleFile, "// код", StandardCharsets.UTF_8);

        FakeLookup lookup = new FakeLookup().put(uuid, UserSupportMode.CHANGES_NOT_ALLOWED);

        assertEquals("замок владельца распространяется на модуль внутри объекта",
                UserSupportMode.CHANGES_NOT_ALLOWED,
                SupportLockGuard.resolveFileMode(lookup, root, moduleFile));
    }

    @Test
    public void собственныйОбъектНеПопадаетПодЗамок() throws IOException {
        Path root = Files.createTempDirectory("qg-own-");
        Path object = root.resolve("src").resolve("Catalogs").resolve("Own");
        Files.createDirectories(object);
        writeMdo(object.resolve("Own.mdo"), "99999999-9999-9999-9999-999999999999");
        Path moduleFile = object.resolve("Own.mdo");

        FakeLookup lookup = new FakeLookup().put("11111111-2222-3333-4444-555555555555",
                UserSupportMode.CHANGES_NOT_ALLOWED);

        assertNull("объекта нет в карте поддержки — гейт обязан молчать",
                SupportLockGuard.resolveFileMode(lookup, root, moduleFile));
    }

    @Test
    public void глобальныйЗамокКонфигурацииНакрываетЛюбойФайл() throws IOException {
        Path root = Files.createTempDirectory("qg-whole-");
        Path any = root.resolve("src").resolve("Catalogs").resolve("Any");
        Files.createDirectories(any);
        Path file = any.resolve("Any.mdo");
        writeMdo(file, "00000000-0000-0000-0000-000000000000");

        assertEquals(UserSupportMode.CHANGES_NOT_ALLOWED,
                SupportLockGuard.resolveFileMode(new FakeLookup().lockWholeConfiguration(), root, file));
    }

    @Test
    public void файлВнеSrcНеРассматривается() throws IOException {
        Path root = Files.createTempDirectory("qg-outside-");
        Path outside = root.resolve("build");
        Files.createDirectories(outside);
        Path file = outside.resolve("Any.mdo");
        writeMdo(file, "00000000-0000-0000-0000-000000000000");

        assertNull(SupportLockGuard.resolveFileMode(new FakeLookup().lockWholeConfiguration(), root, file));
    }

    private static void writeMdo(Path path, String uuid) throws IOException {
        Files.writeString(path,
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<mdclass:Catalog xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\" uuid=\""
                        + uuid + "\">\n  <name>Test</name>\n</mdclass:Catalog>\n",
                StandardCharsets.UTF_8);
    }
}
