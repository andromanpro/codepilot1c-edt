#!/usr/bin/env bash
# Прогон негативных контролей гейтов антипаттернов #70-72 без Tycho.
#
# Зачем отдельный скрипт: модуль com.codepilot1c.core.tests под EDT 2025.2.3 не
# собирается (upstream-стабы не реализуют новые abstract-методы) — pre-existing,
# к рантайм-плагину отношения не имеет. Но гейт, который не умеет ОТКАЗАТЬ,
# ничего не гейтит, поэтому проверка обязана быть воспроизводимой независимо от
# состояния Tycho-сборки: здесь классы гейтов и тесты компилируются напрямую
# javac'ом против jar-ов установленной EDT.
#
# Использование:
#   bash scripts/run-gate-eval.sh             # обычный прогон (ожидается зелёный)
#   bash scripts/run-gate-eval.sh --sabotage  # контроль: тесты обязаны покраснеть
#
# Саботаж нужен потому, что зелёный набор, который физически не способен упасть,
# ничего не проверяет. Именно так гейт #71 и прожил с дырой: пустое значение он
# искал литеральным uuid="", а обязательный идентификатор предопределённого
# элемента справочника зовётся id — и молчание выглядело как «проект чистый».
set -euo pipefail

# Пути к JVM-инструментам обязаны быть в форме Windows: Git Bash отдаёт POSIX
# (/f/..., /c/...), javac и java такой classpath молча принимают и не находят по
# нему ни одного класса — прогон падает на ClassNotFoundException, что выглядит
# как «тестов нет», а не как «пути кривые». cygpath -m даёт C:/... с прямыми слешами.
win_path() { command -v cygpath >/dev/null 2>&1 && cygpath -m "$1" || printf '%s' "$1"; }

REPO_ROOT="$(win_path "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)")"
SRC="$REPO_ROOT/bundles/com.codepilot1c.core/src"
TESTS="$REPO_ROOT/bundles/com.codepilot1c.core.tests/src"

EDT_HOME="${EDT_HOME:-C:/Program Files/1C/1CE/components/1c-edt-2025.2.3+30-x86_64}"
JDK_HOME="${JDK_HOME:-C:/Program Files/Eclipse Adoptium/jdk-17.0.17.10-hotspot}"
M2_REPO="${M2_REPO:-$(win_path "$HOME")/.m2/repository}"

JUNIT="$M2_REPO/junit/junit/4.13.2/junit-4.13.2.jar"
HAMCREST="$M2_REPO/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar"
# Каталог сборки берётся внутри репозитория, а не из mktemp: в Git Bash mktemp
# отдаёт POSIX-путь (/tmp/...), которого JVM на Windows не видит — classpath
# молча оказывается пустым, и прогон падает на ClassNotFoundException вместо
# того, чтобы что-то проверить.
WORK="$REPO_ROOT/target/gate-eval"
OUT="$WORK/classes"
rm -rf "$WORK"; mkdir -p "$OUT" "$WORK/src"
trap 'rm -rf "$WORK"' EXIT

for path in "$EDT_HOME/plugins" "$JDK_HOME/bin" "$JUNIT" "$HAMCREST"; do
    [ -e "$path" ] || { echo "НЕ НАЙДЕНО: $path" >&2; exit 2; }
done

CP="$EDT_HOME/plugins/*;$JUNIT;$HAMCREST"
# JNA лежит в EDT распакованным каталогом, а не jar-ом, поэтому под маску
# plugins/* не попадает. С upstream 1.3.9 она нужна: пакет core/filesystem
# (anchored Unix fd) импортирует com.sun.jna.
JNA_DIR="$(ls -d "$EDT_HOME"/plugins/com.sun.jna_* 2>/dev/null | head -1 || true)"
[ -n "$JNA_DIR" ] && CP="$CP;$JNA_DIR"

# Классы, собранные Tycho, если они есть. Это не «удобство»: тянуть весь граф
# исходников через -sourcepath перестало работать на upstream 1.3.9 — там есть
# instanceof-паттерны, где выражение уже подтип паттерна; ECJ (которым собирает
# Tycho) их принимает, javac считает ошибкой, и прогон падает на ЧУЖОМ файле, к
# гейтам отношения не имеющем. С готовыми классами javac эти исходники не
# открывает вовсе.
CLASSES="$REPO_ROOT/bundles/com.codepilot1c.core/target/classes"
if [ -d "$CLASSES" ]; then
    # Свежесть обязательна и проверяется, а не предполагается. target/classes
    # живёт между сборками и накапливает классы, которых в HEAD уже нет
    # (замер 28.08: 59 классов-сирот из невлитых веток), поэтому доверять ему
    # молча нельзя: прогон против устаревших классов зелёный ровно так же, как
    # против свежих. Файлы, которые набор компилирует ЯВНО, из сравнения
    # исключены — иначе правка самого гейта блокировала бы его же прогон.
    # Точка отсчёта — jar последней сборки, а не каталог classes: mtime каталога
    # не меняется при перезаписи файлов внутри и на живом дереве оказался на
    # восемь секунд СТАРШЕ самого свежего .class в нём.
    BUILD_STAMP="$(ls -1t "$REPO_ROOT/bundles/com.codepilot1c.core/target"/com.codepilot1c.core-*.jar 2>/dev/null | head -1 || true)"
    [ -n "$BUILD_STAMP" ] || BUILD_STAMP="$CLASSES"
    NEWEST_SRC="$(find "$SRC" -name '*.java'         ! -path "*/tools/diagnostics/EdtUuidCheckTool.java"         ! -path "*/diagnostics/BslSilentTypeLinter.java"         -newer "$BUILD_STAMP" -print -quit 2>/dev/null)"
    if [ -n "$NEWEST_SRC" ]; then
        echo "ОТКАЗ: bundles/com.codepilot1c.core/target/classes старше исходников." >&2
        echo "  первый более новый: ${NEWEST_SRC#$SRC/}" >&2
        echo "  прогон против устаревших классов неотличим от прогона против свежих," >&2
        echo "  поэтому он не запускается. Сначала: mvn -DskipTests -Dedt.home=<Eclipse> -pl '!:com.codepilot1c.core.tests' package" >&2
        exit 2
    fi
    CP="$CLASSES;$CP"
    SOURCEPATH=()
    echo "компиляция против собранных классов Tycho (свежесть проверена)"
else
    SOURCEPATH=(-sourcepath "$WORK/src;$SRC")
    echo "target/classes нет — компиляция по sourcepath; если упадёт на чужом файле, сначала: mvn -DskipTests package"
fi

UUID_TOOL_REL="com/codepilot1c/core/tools/diagnostics/EdtUuidCheckTool.java"
UUID_TEST_REL="com/codepilot1c/core/tools/diagnostics/GatesUuidAndLinterTest.java"
UUID_TEST_CLASS="com.codepilot1c.core.tools.diagnostics.GatesUuidAndLinterTest"
UUID_TEST_CLASSFILE="com/codepilot1c/core/tools/diagnostics/GatesUuidAndLinterTest.class"

# Кладёт правимую копию гейта и его тест в $WORK/src. Остальные зависимости
# берутся из настоящего SRC по sourcepath; $WORK/src идёт первым, поэтому
# саботированная копия перебивает оригинал.
seed_uuid_sources() {
    rm -rf "$WORK/src"
    mkdir -p "$WORK/src/com/codepilot1c/core/tools/diagnostics"
    cp "$SRC/$UUID_TOOL_REL" "$WORK/src/$UUID_TOOL_REL"
    cp "$TESTS/$UUID_TEST_REL" "$WORK/src/$UUID_TEST_REL"
}

# Компилирует набор гейтов #71/#72. Ошибки в несвязанных ветках (agent/langgraph
# — внешние зависимости вне этого classpath) ожидаемы и не мешают, поэтому вывод
# javac фильтруется по именам файлов, которые мы действительно собираем: молча
# гасить весь stderr нельзя — сломанный тест тогда выглядит как
# ClassNotFoundException, а не как ошибка компиляции (наступлено 2026-08-27).
# ⚠ Не переключать здесь errexit: `set -e` в конце вернул бы его вызывающему,
# который его специально снял, и первый же красный прогон java убил бы весь
# саботаж-контроль после первого дефекта. Ошибку javac гасит `|| true`.
compile_uuid_suite() {
    "$JDK_HOME/bin/javac" -encoding UTF-8 -nowarn -d "$OUT" -cp "$CP" \
        "${SOURCEPATH[@]}" \
        "$WORK/src/$UUID_TOOL_REL" \
        "$SRC/com/codepilot1c/core/diagnostics/BslSilentTypeLinter.java" \
        "$WORK/src/$UUID_TEST_REL" 2> "$WORK/javac.log" || true
    grep -E "EdtUuidCheckTool|BslSilentTypeLinter|GatesUuidAndLinterTest" "$WORK/javac.log" || true
    [ -f "$OUT/$UUID_TEST_CLASSFILE" ]
}

# Прогон только набора #71/#72. Возвращает код выхода java; 90 — не
# скомпилировалось, для саботажа это тоже «красный».
run_uuid_suite() {
    rm -rf "$OUT"; mkdir -p "$OUT"
    compile_uuid_suite || return 90
    "$JDK_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$OUT;$CP" org.junit.runner.JUnitCore "$UUID_TEST_CLASS"
}

if [ "${1:-}" != "--sabotage" ]; then
    "$JDK_HOME/bin/javac" -encoding UTF-8 -nowarn -d "$OUT" -cp "$CP" \
        "$SRC/com/codepilot1c/core/edt/metadata/SupportLockGuard.java" \
        "$SRC/com/codepilot1c/core/edt/metadata/EdtPluginInjectorLocator.java" \
        "$SRC/com/codepilot1c/core/edt/metadata/MetadataOperationException.java" \
        "$SRC/com/codepilot1c/core/edt/metadata/MetadataOperationCode.java" \
        "$SRC/com/codepilot1c/core/logging/VibeLogger.java" \
        "$TESTS/com/codepilot1c/core/edt/metadata/SupportLockGuardTest.java"

    seed_uuid_sources
    compile_uuid_suite || { echo "набор #71/#72 не скомпилировался" >&2; exit 1; }

    "$JDK_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$OUT;$CP" org.junit.runner.JUnitCore \
        com.codepilot1c.core.edt.metadata.SupportLockGuardTest \
        "$UUID_TEST_CLASS"
    exit 0
fi

echo "=== САБОТАЖ-КОНТРОЛЬ гейта #71: каждый дефект обязан дать красный ==="
TOOL="$WORK/src/$UUID_TOOL_REL"
FAILED_TO_CATCH=0

sabotage() {
    local label="$1"; shift
    seed_uuid_sources
    "$@"
    echo
    echo "--- саботаж: $label"
    set +e
    run_uuid_suite > "$WORK/out.txt" 2>&1
    local rc=$?
    set -e
    if [ "$rc" -eq 0 ]; then
        echo "🚨 НЕ ПОЙМАН: $label — набор остался зелёным"
        FAILED_TO_CATCH=$((FAILED_TO_CATCH + 1))
    else
        echo "ok (красный, rc=$rc): $(grep -m1 -E '^(Tests run|FAILURES|[0-9]+ error)' "$WORK/out.txt" || echo 'не скомпилировалось')"
    fi
}

# 1. Исходный дефект: носителем uuid считается только атрибут с именем uuid.
#    Пустой id="" предопределённого элемента снова проходит молча.
sabotage "носитель только uuid (дефект до 2026-08-27)" \
    perl -0pi -e 's/return \(MDO_EXTENSION[^;]*;/return "uuid".equals(attribute);/s' "$TOOL"

# 2. Только статический список: гейт протухнет на носителях, которых нет в
#    метамодели этой версии EDT.
sabotage "выученная половина отключена" \
    perl -0pi -e 's/\|\| uuidCarriers\.contains\(carrierKey\(extension, attribute\)\);/|| false;/' "$TOOL"

# 3. Носителем считается любой атрибут — гейт-крикун: SwapDate="" и title=""
#    из живой конфигурации становятся находками, и гейт выключают целиком.
sabotage "носителем считается любой атрибут" \
    perl -0pi -e 's/return \(MDO_EXTENSION/return true || (MDO_EXTENSION/' "$TOOL"

# 4. Статический .mdo-список раскатан на все расширения — целочисленный
#    id="" графической схемы становится ложной тревогой.
sabotage "статический список без привязки к .mdo" \
    perl -0pi -e 's/MDO_EXTENSION\.equals\(extension\) && REQUIRED_MDO_UUID_ATTRIBUTES/REQUIRED_MDO_UUID_ATTRIBUTES/' "$TOOL"

echo
if [ "$FAILED_TO_CATCH" -ne 0 ]; then
    echo "САБОТАЖ-КОНТРОЛЬ ПРОВАЛЕН: не пойманных дефектов $FAILED_TO_CATCH"
    exit 1
fi
echo "САБОТАЖ-КОНТРОЛЬ: все 4 дефекта пойманы"
