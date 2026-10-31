#!/usr/bin/env bash
# Парный eval квалификаторов типа в properties у add_metadata_child (ветка fix/add-child-string-length).
#
# Дефект: properties={"type":"String","length":50} проходил edt_validate_request (length стал 50.0),
# мутация отвечала успехом, а в .mdo ложилось <length>150</length>. applyDefaultTypeIfNeeded отдавал в
# разбор типа только значение ключа type; соседний length не читал никто — срабатывал дефолт 150. Тем же
# путём молча терялся любой незнакомый ключ, а мусорное значение квалификатора («abc», 50.5, -1) давало
# дефолт вместо ошибки. Воспроизведено 23.09.2026 на сборке 1.3.9.20260922-2125 (wmstnextdev,
# Document.ЗаявкаНаПоступление.ТрекНомерПоставки); update_metadata с тем же length работал.
#
# Использование:
#   bash scripts/run-child-type-properties-eval.sh                      # против target/classes, ожидается зелёный
#   bash scripts/run-child-type-properties-eval.sh --baseline <каталог> # против распакованного jar сборки ДО фикса:
#                                                                       # обязан покраснеть поимённо на симптоме
#   bash scripts/run-child-type-properties-eval.sh --classes <каталог>  # против другой сборки, ожидается зелёный
#   bash scripts/run-child-type-properties-eval.sh --sabotage           # каждый дефект обязан дать красный
#
# EdtMetadataServiceChildTypePropertiesTest бьёт рефлексией по настоящему applyDefaultTypeIfNeeded
# (реквизит документа из фабрики EMF, транзакция-заглушка, заранее разрешённые String/Number) и не ссылается
# на классы фикса — один бинарник идёт против сборки до фикса и после. Вне OSGi настоящий TypeProviderService
# падает в статической инициализации, поэтому перед классами плагина кладётся заглушка из
# scripts/eval-stubs/child-type-properties: она отвечает «не нашёл», и тип берётся из кэша, как на живом пути.
# Вне OSGi без заглушки тест такие случаи пропускает (Assume); здесь свойство codepilot.eval.requireTypeResolution=true
# запрещает пропуск. EdtMetadataServiceChildTypeQualifiersTest проверяет помощники фикса без разрешения типа; до фикса
# его не собрать, поэтому в --baseline он не идёт.
#
# Чего этот eval НЕ проверяет: места вызова (одиночный ребёнок отдаёт properties без children/attributes,
# элемент пакета — без name/synonym/comment) и откат транзакции BM при отказе. Это закрывает только живая
# проверка вызовом тула через MCP на установленной сборке.
set -euo pipefail

# Пути к JVM-инструментам обязаны быть в форме Windows: Git Bash отдаёт POSIX (/f/..., /c/...),
# javac и java такой classpath молча принимают и не находят по нему ни одного класса.
win_path() { command -v cygpath >/dev/null 2>&1 && cygpath -m "$1" || printf '%s' "$1"; }

REPO_ROOT="$(win_path "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)")"
SRC="$REPO_ROOT/bundles/com.codepilot1c.core/src"
TESTS="$REPO_ROOT/bundles/com.codepilot1c.core.tests/src"
STUBS_SRC="$REPO_ROOT/scripts/eval-stubs/child-type-properties"

EDT_HOME="${EDT_HOME:-C:/Program Files/1C/1CE/components/1c-edt-2025.2.3+30-x86_64}"
JDK_HOME="${JDK_HOME:-C:/Program Files/Eclipse Adoptium/jdk-17.0.17.10-hotspot}"
M2_REPO="${M2_REPO:-$(win_path "$HOME")/.m2/repository}"

JUNIT="$M2_REPO/junit/junit/4.13.2/junit-4.13.2.jar"
HAMCREST="$M2_REPO/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar"
# ECJ — тот же компилятор, которым собирает Tycho: только он принимает EdtMetadataService.java.
ECJ="${ECJ:-$M2_REPO/org/eclipse/jdt/ecj/3.36.0/ecj-3.36.0.jar}"

# Каталог внутри репозитория, а не mktemp: в Git Bash mktemp отдаёт POSIX-путь, которого JVM не видит.
WORK="$REPO_ROOT/target/child-type-properties-eval"
rm -rf "$WORK"; mkdir -p "$WORK/tests" "$WORK/stubs" "$WORK/src" "$WORK/overlay"
trap 'rm -rf "$WORK"' EXIT

for required_path in "$EDT_HOME/plugins" "$JDK_HOME/bin" "$JUNIT" "$HAMCREST" "$STUBS_SRC"; do
    [ -e "$required_path" ] || { echo "НЕ НАЙДЕНО: $required_path" >&2; exit 2; }
done

MODE="run"
CLASSES="$REPO_ROOT/bundles/com.codepilot1c.core/target/classes"
while [ $# -gt 0 ]; do
    case "$1" in
        --sabotage) MODE="sabotage"; shift ;;
        --classes) CLASSES="$(win_path "$2")"; MODE="classes"; shift 2 ;;
        --baseline) CLASSES="$(win_path "$2")"; MODE="baseline"; shift 2 ;;
        *) echo "неизвестный аргумент: $1" >&2; exit 2 ;;
    esac
done

[ -d "$CLASSES" ] || { echo "ОТКАЗ: нет $CLASSES. Сначала: mvn -DskipTests -Dedt.home=<Eclipse> -pl '!:com.codepilot1c.core.tests' package" >&2; exit 2; }

if [ "$MODE" = "run" ] || [ "$MODE" = "sabotage" ]; then
    # Прогон против устаревших классов зелёный ровно так же, как против свежих, поэтому свежесть
    # проверяется. Точка отсчёта — jar последней сборки: mtime каталога classes не меняется при
    # перезаписи файлов внутри.
    BUILD_STAMP="$(ls -1t "$REPO_ROOT/bundles/com.codepilot1c.core/target"/com.codepilot1c.core-*.jar 2>/dev/null | head -1 || true)"
    [ -n "$BUILD_STAMP" ] || BUILD_STAMP="$CLASSES"
    NEWEST_SRC="$(find "$SRC" -name '*.java' -newer "$BUILD_STAMP" -print -quit 2>/dev/null)"
    if [ -n "$NEWEST_SRC" ]; then
        echo "ОТКАЗ: bundles/com.codepilot1c.core/target/classes старше исходников." >&2
        echo "  первый более новый: ${NEWEST_SRC#$SRC/}" >&2
        echo "  Сначала: mvn -DskipTests -Dedt.home=<Eclipse> -pl '!:com.codepilot1c.core.tests' package" >&2
        exit 2
    fi
fi

CP="$EDT_HOME/plugins/*;$JUNIT;$HAMCREST"
# JNA лежит в EDT распакованным каталогом и под маску plugins/* не попадает.
JNA_DIR="$(ls -d "$EDT_HOME"/plugins/com.sun.jna_* 2>/dev/null | head -1 || true)"
[ -n "$JNA_DIR" ] && CP="$CP;$JNA_DIR"
# Библиотеки плагина: у target/classes они в lib/ бандла, у распакованного jar — в его lib/.
CP="$CP;$REPO_ROOT/bundles/com.codepilot1c.core/lib/*"
[ -d "$CLASSES/lib" ] && CP="$CP;$CLASSES/lib/*"
CP="$CLASSES;$CP"

BEHAVIOUR_TEST=com.codepilot1c.core.edt.metadata.EdtMetadataServiceChildTypePropertiesTest
UNIT_TEST=com.codepilot1c.core.edt.metadata.EdtMetadataServiceChildTypeQualifiersTest
if [ "$MODE" = "baseline" ]; then
    TEST_CLASSES=("$BEHAVIOUR_TEST")
else
    TEST_CLASSES=("$BEHAVIOUR_TEST" "$UNIT_TEST")
fi

"$JDK_HOME/bin/javac" -encoding UTF-8 -nowarn -d "$WORK/stubs" -cp "$CP" \
    "$STUBS_SRC/com/_1c/g5/v8/dt/platform/core/typeinfo/TypeProviderService.java" 2> "$WORK/javac-stubs.log" || {
    cat "$WORK/javac-stubs.log" >&2
    echo "заглушка TypeProviderService не скомпилировалась" >&2
    exit 1
}
# Тест компилируется один раз: саботаж меняет продакшн-класс, а не тест. Ошибки javac видны
# целиком — сломанный тест иначе выглядит как ClassNotFoundException.
TEST_SOURCES=()
for test_class in "${TEST_CLASSES[@]}"; do TEST_SOURCES+=("$TESTS/${test_class//.//}.java"); done
"$JDK_HOME/bin/javac" -encoding UTF-8 -nowarn -d "$WORK/tests" -cp "$CP" "${TEST_SOURCES[@]}" 2> "$WORK/javac-tests.log" || {
    cat "$WORK/javac-tests.log" >&2
    echo "тест не скомпилировался" >&2
    exit 1
}

# overlay (саботированный класс) и заглушка — раньше классов плагина и EDT.
run_suite() {
    "$JDK_HOME/bin/java" -Dfile.encoding=UTF-8 -Dcodepilot.eval.requireTypeResolution=true \
        -cp "$WORK/overlay;$WORK/stubs;$WORK/tests;$CP" org.junit.runner.JUnitCore "${TEST_CLASSES[@]}"
}

if [ "$MODE" = "run" ] || [ "$MODE" = "classes" ]; then
    run_suite
    exit $?
fi

if [ "$MODE" = "baseline" ]; then
    # Сборка до фикса обязана покраснеть ровно на симптоме: красный из-за classpath или
    # ExceptionInInitializerError доказал бы только обвязку. Поэтому сверяются имена и причины.
    EXPECTED_RED=(
        flatLengthAsIntegerIsApplied
        flatLengthAsDoubleFromJsonIsApplied
        flatLengthAsDecimalStringIsApplied
        flatFixedIsApplied
        topLevelStringQualifiersIsApplied
        lengthWithoutTypeKeepsDefaultStringType
        flatNumberQualifiersAreApplied
        unknownKeyIsRefused
        synonymInsidePropertiesIsRefused
        fractionalLengthIsRefused
        nonNumericLengthIsRefused
        negativeLengthIsRefused
        flatLengthOnCompositeTypeIsRefused
    )
    EXPECTED_GREEN=(
        qualifiersInsideTypeMapStillWork
        inlineStringLiteralStillWorks
        typeWithoutLengthKeepsDefault150
        knownCreatePropertiesAreAccepted
    )
    set +e
    run_suite > "$WORK/baseline.txt" 2>&1
    suite_rc=$?
    set -e
    awk '/^[0-9]+\) / { print; getline; print "     " substr($0, 1, 220) } /^Tests run|^OK \(/ { print }' "$WORK/baseline.txt"
    PROBLEMS=0
    [ "$suite_rc" -ne 0 ] || { echo "🚨 сборка до фикса ЗЕЛЁНАЯ — eval не воспроизводит дефект"; PROBLEMS=1; }
    if grep -a -q -E 'ExceptionInInitializerError|NoClassDefFoundError|NoSuchMethodException' "$WORK/baseline.txt"; then
        echo "🚨 красный из-за обвязки (инициализация/classpath/сигнатура), а не из-за симптома"; PROBLEMS=1
    fi
    for red_test in "${EXPECTED_RED[@]}"; do
        grep -a -q -E "^[0-9]+\) $red_test\(" "$WORK/baseline.txt" || { echo "🚨 не покраснел: $red_test"; PROBLEMS=1; }
    done
    for green_test in "${EXPECTED_GREEN[@]}"; do
        if grep -a -q -E "^[0-9]+\) $green_test\(" "$WORK/baseline.txt"; then
            echo "🚨 покраснел то, что до фикса работало: $green_test"; PROBLEMS=1
        fi
    done
    # Сам симптом из воспроизведения: длина 50 превращалась в 150.
    grep -a -q 'expected:<50> but was:<150>' "$WORK/baseline.txt" || { echo "🚨 нет симптома «50 → 150» в причинах"; PROBLEMS=1; }
    TOTAL=$(( ${#EXPECTED_RED[@]} + ${#EXPECTED_GREEN[@]} ))
    grep -a -q -E "^Tests run: $TOTAL," "$WORK/baseline.txt" || { echo "🚨 в наборе не $TOTAL тестов — сверка устарела"; PROBLEMS=1; }
    if [ "$PROBLEMS" -ne 0 ]; then
        echo "BASELINE: ПРОВАЛ"
        exit 1
    fi
    echo "BASELINE: сборка до фикса красная ровно на симптоме (${#EXPECTED_RED[@]} из $TOTAL), прежнее поведение не задето"
    exit 0
fi

# ─── саботаж ──────────────────────────────────────────────────────────────

[ -f "$ECJ" ] || { echo "НЕ НАЙДЕНО: $ECJ (нужен для саботажа EdtMetadataService)" >&2; exit 2; }

# argfile для ECJ: маску plugins/* он не раскрывает, а список jar-ов не влезает в командную строку
# Windows. Весь classpath — одной строкой в кавычках: без них ECJ режет по пробелу в «Program Files».
{
    printf -- '-classpath\n"%s' "$CLASSES"
    for ecj_jar in "$EDT_HOME"/plugins/*.jar; do printf ';%s' "$ecj_jar"; done
    for ecj_dir in "$EDT_HOME"/plugins/*/; do printf ';%s' "${ecj_dir%/}"; done
    for lib_jar in "$REPO_ROOT"/bundles/com.codepilot1c.core/lib/*.jar; do [ -e "$lib_jar" ] && printf ';%s' "$lib_jar"; done
    printf '"\n'
} > "$WORK/ecj-classpath.args"

# Саботаж на красной базе беззубый: «пойман» там любой дефект. Поэтому сначала чистый прогон обязан быть зелёным.
if ! run_suite > "$WORK/clean.txt" 2>&1; then
    # Без строк теста (JVM не стартовала, класс не найден) grep вернёт 1, и под pipefail скрипт умер бы до
    # сообщения ОТКАЗ — тогда показать хвост вывода, там настоящая причина.
    grep -a -E '^[0-9]+\) |^Tests run' "$WORK/clean.txt" | head -10 || tail -5 "$WORK/clean.txt"
    echo "ОТКАЗ: набор красный и без саботажа — контроль ничего бы не доказал" >&2
    exit 1
fi

echo "=== САБОТАЖ-КОНТРОЛЬ квалификаторов add_metadata_child: каждый дефект обязан дать красный ==="
FAILED_TO_CATCH=0
SABOTAGES=0
SVC="com/codepilot1c/core/edt/metadata/EdtMetadataService.java"

# sabotage <метка> <perl-выражение> — правит копию EdtMetadataService и компилирует её ECJ в overlay.
# Урок run-command-interface-eval.sh: у переменных уникальные имена и local (динамическая область видимости
# bash), применение сверяется с копией, снятой до замены, а cmp = 2 — отдельная ошибка, не «различаются».
sabotage() {
    local sabotage_label="$1" sabotage_expr="$2"
    local sabotaged="$WORK/src/$SVC" pristine="$WORK/pristine.java"
    SABOTAGES=$((SABOTAGES + 1))
    rm -rf "$WORK/src" "$WORK/overlay"; mkdir -p "$(dirname "$sabotaged")" "$WORK/overlay"
    cp "$SRC/$SVC" "$sabotaged"
    cp "$sabotaged" "$pristine"
    perl -0pi -e "$sabotage_expr" "$sabotaged"
    echo
    echo "--- саботаж: $sabotage_label"
    local cmp_rc=0
    cmp -s "$pristine" "$sabotaged" || cmp_rc=$?
    if [ "$cmp_rc" -eq 0 ]; then
        echo "🚨 НЕ ПРИМЕНЁН: выражение не нашло текст — саботаж ничего не проверил"
        FAILED_TO_CATCH=$((FAILED_TO_CATCH + 1))
        return
    elif [ "$cmp_rc" -ne 1 ]; then
        echo "🚨 cmp вернул $cmp_rc — сверка применения сломана"
        FAILED_TO_CATCH=$((FAILED_TO_CATCH + 1))
        return
    fi
    local compile_rc=0
    "$JDK_HOME/bin/java" -jar "$ECJ" -17 -encoding UTF-8 -nowarn -proc:none -d "$WORK/overlay" \
        "@$WORK/ecj-classpath.args" "$sabotaged" > "$WORK/compile.log" 2>&1 || compile_rc=$?
    if [ "$compile_rc" -ne 0 ]; then
        # Несобравшийся саботаж не проверяет тест — это дефект самого контроля.
        echo "🚨 НЕ СОБРАЛСЯ саботированный класс (rc=$compile_rc) — контроль недостоверен"
        tail -5 "$WORK/compile.log"
        FAILED_TO_CATCH=$((FAILED_TO_CATCH + 1))
        return
    fi
    local run_rc=0
    set +e
    run_suite > "$WORK/out.txt" 2>&1
    run_rc=$?
    set -e
    if [ "$run_rc" -eq 0 ]; then
        echo "🚨 НЕ ПОЙМАН: $sabotage_label — набор остался зелёным"
        FAILED_TO_CATCH=$((FAILED_TO_CATCH + 1))
    else
        echo "ok (красный): $(grep -a -m1 -E '^Tests run' "$WORK/out.txt")"
    fi
}

# Исходный дефект: разбор типа снова видит только значение ключа type, а не свёрнутые properties.
sabotage "тип берётся из сырого properties.type (дефект до фикса)" \
    's/Object requestedTypeValue = getMapValueIgnoreCase\(createProperties, "type"\);/Object requestedTypeValue = properties == null ? null : getMapValueIgnoreCase(properties, "type");/'

# Незнакомый ключ снова теряется молча.
sabotage "проверка незнакомых ключей выключена" \
    's/if \(!unknownKeys\.isEmpty\(\)\) \{/if (false) {/'

# Мусор в квалификаторе снова уходит в дефолт.
sabotage "квалификатор разбирается нестрого (parseInteger)" \
    's/return parseQualifierInteger\(qualifier, value\);/return parseInteger(value);/'

# Отрицательная длина снова принимается и превращается в дефолт 150.
sabotage "отрицательный квалификатор принимается" \
    's/if \(parsed >= 0\) \{/if (true) {/'

# Одна длина без type снова не достраивается до строки по умолчанию.
sabotage "квалификатор без type не получает тип по умолчанию" \
    's/if \(foldedType == null && isKindWithRequiredType\(kind\)\) \{/if (false) {/'

# stringQualifiers верхнего уровня снова не сворачивается в type.
sabotage "stringQualifiers верхнего уровня не сворачивается" \
    's/ \|\| key\.equalsIgnoreCase\("stringQualifiers"\)//'

# Плоская длина у составного типа снова сворачивается молча.
sabotage "составной тип с плоской длиной не отклоняется" \
    's/if \(foldedType instanceof List<\?>\) \{/if (false) {/'

echo
if [ "$FAILED_TO_CATCH" -ne 0 ]; then
    echo "САБОТАЖ-КОНТРОЛЬ ПРОВАЛЕН: не пойманных или неприменённых дефектов $FAILED_TO_CATCH из $SABOTAGES"
    exit 1
fi
echo "САБОТАЖ-КОНТРОЛЬ: все $SABOTAGES дефектов пойманы"
