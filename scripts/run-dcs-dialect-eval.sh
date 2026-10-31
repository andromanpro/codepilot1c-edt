#!/usr/bin/env bash
# Прогон контракта диалектов СКД (dcs_manage) без Tycho.
#
# Зачем отдельный скрипт: модуль com.codepilot1c.core.tests под EDT 2025.2.3 не
# собирается (upstream-стабы не реализуют новые abstract-методы) — pre-existing,
# к рантайм-плагину отношения не имеет. Тест, который физически не запускается,
# не защищает ничего, а именно этот фикс уже терялся один раз: он жил на невлитой
# ветке, приехал в сборку 1.3.3.20260820-1845, и следующая же сборка из main его
# молча откатила. Проверка обязана быть воспроизводимой независимо от состояния
# Tycho-сборки, поэтому классы и тест компилируются напрямую javac'ом против
# jar-ов установленной EDT.
#
# Использование:
#   bash scripts/run-dcs-dialect-eval.sh             # обычный прогон (ожидается зелёный)
#   bash scripts/run-dcs-dialect-eval.sh --sabotage  # контроль: тесты обязаны покраснеть
#   DCS_SAMPLES_DIR=F:/temp/cp-dcs-order/target/dcs-samples bash scripts/run-dcs-dialect-eval.sh
#
# Саботаж нужен потому, что зелёный набор, который физически не способен упасть,
# ничего не проверяет. Каждый дефект — это ровно то состояние, в котором сервис
# жил до фикса, и каждый обязан быть пойман своим тестом.
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
WORK="$REPO_ROOT/target/dcs-dialect-eval"
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

SERVICE_REL="com/codepilot1c/core/edt/dcs/EdtDcsService.java"
TEST_RELS=(
    "com/codepilot1c/core/edt/dcs/EdtDcsPlatformDialectTest.java"
    "com/codepilot1c/core/edt/dcs/EdtDcsPlatformOrderTest.java"
)
TEST_CLASSES=(
    "com.codepilot1c.core.edt.dcs.EdtDcsPlatformDialectTest"
    "com.codepilot1c.core.edt.dcs.EdtDcsPlatformOrderTest"
)
JAVA_ARGS=(-Dfile.encoding=UTF-8)
if [ -n "${DCS_SAMPLES_DIR:-}" ]; then
    JAVA_ARGS+=("-Ddcs.samples.dir=$(win_path "$DCS_SAMPLES_DIR")")
fi

# Классы, собранные Tycho. Это не «удобство»: тянуть весь граф исходников через
# -sourcepath перестало работать на upstream 1.3.9 — там есть instanceof-паттерны,
# где выражение уже подтип паттерна; ECJ (которым собирает Tycho) их принимает,
# javac считает ошибкой, и прогон падает на ЧУЖОМ файле, к СКД отношения не имеющем.
CLASSES="$REPO_ROOT/bundles/com.codepilot1c.core/target/classes"
if [ -d "$CLASSES" ]; then
    # Свежесть обязательна и проверяется, а не предполагается: прогон против
    # устаревших классов зелёный ровно так же, как против свежих. Точка отсчёта —
    # jar последней сборки, а не каталог classes: mtime каталога не меняется при
    # перезаписи файлов внутри. EdtDcsService.java исключён — его набор компилирует
    # ЯВНО, иначе правка самого сервиса блокировала бы его же прогон.
    BUILD_STAMP="$(ls -1t "$REPO_ROOT/bundles/com.codepilot1c.core/target"/com.codepilot1c.core-*.jar 2>/dev/null | head -1 || true)"
    [ -n "$BUILD_STAMP" ] || BUILD_STAMP="$CLASSES"
    NEWEST_SRC="$(find "$SRC" -name '*.java' ! -path "*/edt/dcs/EdtDcsService.java" \
        -newer "$BUILD_STAMP" -print -quit 2>/dev/null)"
    if [ -n "$NEWEST_SRC" ]; then
        echo "ОТКАЗ: bundles/com.codepilot1c.core/target/classes старше исходников." >&2
        echo "  первый более новый: ${NEWEST_SRC#$SRC/}" >&2
        echo "  прогон против устаревших классов неотличим от прогона против свежих," >&2
        echo "  поэтому он не запускается. Сначала: mvn -DskipTests -Dedt.home=<Eclipse> -pl '!:com.codepilot1c.core.tests' package" >&2
        exit 2
    fi
    CP="$CLASSES;$CP"
    echo "компиляция против собранных классов Tycho (свежесть проверена)"
else
    echo "ОТКАЗ: нет $CLASSES. Сначала: mvn -DskipTests -Dedt.home=<Eclipse> -pl '!:com.codepilot1c.core.tests' package" >&2
    exit 2
fi

# Кладёт правимую копию сервиса в $WORK/src; $WORK/src идёт первым в sourcepath,
# поэтому саботированная копия перебивает оригинал.
seed_sources() {
    rm -rf "$WORK/src"
    mkdir -p "$WORK/src/com/codepilot1c/core/edt/dcs"
    cp "$SRC/$SERVICE_REL" "$WORK/src/$SERVICE_REL"
}

# Ошибки в несвязанных ветках (agent/langgraph — внешние зависимости вне этого
# classpath) ожидаемы, поэтому вывод javac фильтруется по именам файлов, которые
# мы действительно собираем: молча гасить весь stderr нельзя — сломанный тест
# тогда выглядит как ClassNotFoundException, а не как ошибка компиляции.
compile_suite() {
    "$JDK_HOME/bin/javac" -encoding UTF-8 -nowarn -d "$OUT" -cp "$CP" \
        -sourcepath "$WORK/src;$SRC" \
        "$WORK/src/$SERVICE_REL" \
        "$TESTS/${TEST_RELS[0]}" "$TESTS/${TEST_RELS[1]}" 2> "$WORK/javac.log" || true
    grep -E "EdtDcsService|EdtDcsPlatform(Dialect|Order)Test" "$WORK/javac.log" || true
    [ -f "$OUT/${SERVICE_REL%.java}.class" ] || return 1
    for test_rel in "${TEST_RELS[@]}"; do
        [ -f "$OUT/${test_rel%.java}.class" ] || return 1
    done
}

# Возвращает код выхода java; 90 — не скомпилировалось. Для саботажа это НЕ «пойман»:
# несобравшийся саботаж не доказывает, что регрессионный тест ловит дефект.
run_suite() {
    rm -rf "$OUT"; mkdir -p "$OUT"
    compile_suite || return 90
    "$JDK_HOME/bin/java" "${JAVA_ARGS[@]}" -cp "$OUT;$CP" org.junit.runner.JUnitCore "${TEST_CLASSES[@]}"
}

if [ "${1:-}" != "--sabotage" ]; then
    seed_sources
    compile_suite || { echo "набор контракта диалектов не скомпилировался" >&2; exit 1; }
    "$JDK_HOME/bin/java" "${JAVA_ARGS[@]}" -cp "$OUT;$CP" org.junit.runner.JUnitCore "${TEST_CLASSES[@]}"
    exit 0
fi

echo "=== САБОТАЖ-КОНТРОЛЬ контракта диалектов СКД: каждый дефект обязан дать красный ==="
SERVICE="$WORK/src/$SERVICE_REL"
FAILED_TO_CATCH=0
SABOTAGE_COUNT=0

sabotage() {
    local label="$1"; shift
    SABOTAGE_COUNT=$((SABOTAGE_COUNT + 1))
    seed_sources
    "$@"
    if cmp -s "$SERVICE" "$SRC/$SERVICE_REL"; then
        echo "НЕ ПРИМЕНЁН: $label — исходник не изменился"
        FAILED_TO_CATCH=$((FAILED_TO_CATCH + 1))
        return
    fi
    echo
    echo "--- саботаж: $label"
    set +e
    run_suite > "$WORK/out.txt" 2>&1
    local rc=$?
    set -e
    if [ "$rc" -eq 0 ]; then
        echo "🚨 НЕ ПОЙМАН: $label — набор остался зелёным"
        FAILED_TO_CATCH=$((FAILED_TO_CATCH + 1))
    elif [ "$rc" -eq 1 ] && grep -q '^Tests run: .*Failures: [1-9]' "$WORK/out.txt"; then
        echo "ok (красный, rc=$rc): $(grep -m1 '^Tests run:' "$WORK/out.txt")"
    else
        echo "НЕ ПРОВЕРЕН: $label — ошибка запуска или компиляции (rc=$rc)"
        FAILED_TO_CATCH=$((FAILED_TO_CATCH + 1))
        tail -15 "$WORK/out.txt"
    fi
}

# 1. Исходный дефект: диалект не определяется, всё читается как design-time.
#    Ровно это и показывал get_summary — «0 наборов данных» у рабочего отчёта.
sabotage "диалект всегда design-time (дефект до 2026-08-20)" \
    perl -0pi -e 's/return DCS_EDT_DT_NS\.equals\(root\.getNamespaceURI\(\)\) \? EDT_DT : PLATFORM;/return EDT_DT;/' "$SERVICE"

# 2. Имена коллекций платформенного диалекта во множественном числе — так их
#    писал сервис до фикса, и так же слепо искал.
sabotage "платформенные коллекции во множественном числе" \
    perl -0pi -e 's/"dataSet", "parameter", "calculatedField"/"dataSets", "parameters", "calculatedFields"/' "$SERVICE"

# 3. Значения платформенного диалекта читаются атрибутами, а не дочерними
#    элементами: узлы находятся, но все имена пустые.
sabotage "значения платформы читаются атрибутами" \
    perl -0pi -e 's/"settingsVariant", "DataSetQuery", false\)/"settingsVariant", "DataSetQuery", true)/' "$SERVICE"

# 4. Порядок верхнеуровневых элементов не соблюдается. Платформа валидирует
#    последовательность: набор данных, добавленный после параметра, делает файл
#    нечитаемым целиком.
sabotage "новый узел дописывается в конец" \
    perl -0pi -e 's/(private void appendInOrder\(Element root, Element child, DcsDialect dialect\) \{\s*\n\s*)if \(dialect != DcsDialect\.PLATFORM\)/${1}if (true)/s' "$SERVICE"

# 5. Варианты настроек снова только считаются, но не перечисляются: list_nodes
#    отдаёт пусто на схеме, где они есть.
sabotage "варианты настроек не перечисляются" perl -0pi -e 's/settingsVariants\.forEach\(node -> result\.add\(node\.toNodeItem\(\)\)\);//' "$SERVICE"

# 6. Appending a missing property recreates the platform sequence violation.
sabotage "новое дочернее свойство дописывается в конец" \
    perl -0pi -e 's/insertChildInOrder\(element, child\);/element.appendChild(child);/' "$SERVICE"

# 7. The EDT model name is not a valid property in platform XML.
sabotage "платформенный флаг снова autoFillAvailableFields" \
    perl -0pi -e 's/\? "autoFillFields" : "autoFillAvailableFields"/? "autoFillAvailableFields" : "autoFillAvailableFields"/' "$SERVICE"

# 8. Selecting the first source silently discards the requested binding.
sabotage "источник выбирается первым без проверки имени" \
    perl -0pi -e 's/normalize\(existingName\)\.equals\(normalize\(requested\)\)/true/' "$SERVICE"

# 9. Порядок набора-запроса применяется к любому dataSet: у объектного и объединённого
#    набора своя модель содержимого, и нормализация переставила бы их элементы.
sabotage "порядок набора-запроса применяется к любому набору" \
    perl -0pi -e 's/case "dataSet" -> isQueryDataSet\(element\)/case "dataSet" -> true/' "$SERVICE"

echo
if [ "$FAILED_TO_CATCH" -ne 0 ]; then
    echo "САБОТАЖ-КОНТРОЛЬ ПРОВАЛЕН: не пойманных дефектов $FAILED_TO_CATCH"
    exit 1
fi
echo "САБОТАЖ-КОНТРОЛЬ: все $SABOTAGE_COUNT дефектов пойманы"
