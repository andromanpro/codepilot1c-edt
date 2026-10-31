#!/usr/bin/env bash
# Парный eval расписания регламентного задания (ветка feat/scheduled-job-schedule).
#
# Дефект: update_metadata {target_fqn:"ScheduledJob.X", changes:{set:{schedule:{repeatPeriodInDay:300,
# daysRepeatPeriod:1}}}} отвечал [INVALID_METADATA_CHANGE] Field is read-only: schedule. В модели EDT
# ScheduledJob.schedule — @ExternalProperty refers transient Schedule: расписание живёт отдельным верхним объектом BM
# (файл Schedule.schedule), общий сеттер отбивает любую transient-фичу, а write_file новые не-.md файлы не создаёт.
# Воспроизведено 24.09.2026 на сборке 1.3.9.20260924-1242.
#
# Использование:
#   bash scripts/run-scheduled-job-schedule-eval.sh                      # против target/classes, ожидается зелёный
#   bash scripts/run-scheduled-job-schedule-eval.sh --baseline <каталог> # против распакованного jar сборки ДО фикса:
#                                                                        # обязан покраснеть поимённо на симптоме
#   bash scripts/run-scheduled-job-schedule-eval.sh --classes <каталог>  # против другой сборки, ожидается зелёный
#   bash scripts/run-scheduled-job-schedule-eval.sh --sabotage [--classes <каталог>]  # каждый дефект обязан дать красный
#
# EdtMetadataServiceScheduledJobScheduleTest бьёт рефлексией по настоящим setFeatureValue, unsetFeatureValue и
# collectSupportedTopLevelProperties (задание и расписание — из фабрик EMF) и не ссылается на классы фикса — один
# бинарник идёт против сборки до фикса и после. Эталоны — дословные Schedule.schedule рабочих проектов, сравнение
# EcoreUtil.equals (значения и порядок дней недели).
#
# Чего этот eval НЕ проверяет: ветку «расписания ещё нет» (attachTopObject + generateExternalPropertyFqn — нужен
# движок BM), запись файла Schedule.schedule на диск и откат транзакции. Это закрывает только живая проверка вызовом
# тула через MCP на установленной сборке.
set -euo pipefail

# Пути к JVM-инструментам обязаны быть в форме Windows: Git Bash отдаёт POSIX (/f/..., /c/...),
# javac и java такой classpath молча принимают и не находят по нему ни одного класса.
win_path() { command -v cygpath >/dev/null 2>&1 && cygpath -m "$1" || printf '%s' "$1"; }

REPO_ROOT="$(win_path "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)")"
SRC="$REPO_ROOT/bundles/com.codepilot1c.core/src"
TESTS="$REPO_ROOT/bundles/com.codepilot1c.core.tests/src"

EDT_HOME="${EDT_HOME:-C:/Program Files/1C/1CE/components/1c-edt-2025.2.3+30-x86_64}"
JDK_HOME="${JDK_HOME:-C:/Program Files/Eclipse Adoptium/jdk-17.0.17.10-hotspot}"
M2_REPO="${M2_REPO:-$(win_path "$HOME")/.m2/repository}"

JUNIT="$M2_REPO/junit/junit/4.13.2/junit-4.13.2.jar"
HAMCREST="$M2_REPO/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar"
# ECJ — тот же компилятор, которым собирает Tycho: только он принимает EdtMetadataService.java.
ECJ="${ECJ:-$M2_REPO/org/eclipse/jdt/ecj/3.36.0/ecj-3.36.0.jar}"

# Каталог внутри репозитория, а не mktemp: в Git Bash mktemp отдаёт POSIX-путь, которого JVM не видит.
WORK="$REPO_ROOT/target/scheduled-job-schedule-eval"
rm -rf "$WORK"; mkdir -p "$WORK/tests" "$WORK/src" "$WORK/overlay"
trap 'rm -rf "$WORK"' EXIT

for required_path in "$EDT_HOME/plugins" "$JDK_HOME/bin" "$JUNIT" "$HAMCREST"; do
    [ -e "$required_path" ] || { echo "НЕ НАЙДЕНО: $required_path" >&2; exit 2; }
done

MODE="run"
CLASSES_GIVEN=0
CLASSES="$REPO_ROOT/bundles/com.codepilot1c.core/target/classes"
while [ $# -gt 0 ]; do
    case "$1" in
        --sabotage) MODE="sabotage"; shift ;;
        --classes) CLASSES="$(win_path "$2")"; [ "$MODE" = "sabotage" ] || MODE="classes"; CLASSES_GIVEN=1; shift 2 ;;
        --baseline) CLASSES="$(win_path "$2")"; MODE="baseline"; shift 2 ;;
        *) echo "неизвестный аргумент: $1" >&2; exit 2 ;;
    esac
done

[ -d "$CLASSES" ] || { echo "ОТКАЗ: нет $CLASSES. Сначала: mvn -DskipTests -Dedt.home=<Eclipse> -pl '!:com.codepilot1c.core.tests' package" >&2; exit 2; }

if [ "$CLASSES_GIVEN" -eq 0 ] && { [ "$MODE" = "run" ] || [ "$MODE" = "sabotage" ]; }; then
    # Прогон против устаревших классов зелёный ровно так же, как против свежих, поэтому свежесть
    # проверяется. Точка отсчёта — jar последней сборки: mtime каталога classes не меняется при
    # перезаписи файлов внутри. Без jar-а (классы собраны не Maven) ls падает, и под pipefail
    # подстановка без || true молча роняла бы весь скрипт с кодом 2.
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

TEST_CLASS=com.codepilot1c.core.edt.metadata.EdtMetadataServiceScheduledJobScheduleTest

# Тест компилируется один раз: саботаж меняет продакшн-класс, а не тест. Ошибки javac видны
# целиком — сломанный тест иначе выглядит как ClassNotFoundException.
"$JDK_HOME/bin/javac" -encoding UTF-8 -nowarn -d "$WORK/tests" -cp "$CP" "$TESTS/${TEST_CLASS//.//}.java" \
    2> "$WORK/javac-tests.log" || {
    cat "$WORK/javac-tests.log" >&2
    echo "тест не скомпилировался" >&2
    exit 1
}

# overlay (саботированный класс) — раньше классов плагина.
run_suite() {
    "$JDK_HOME/bin/java" -Dfile.encoding=UTF-8 \
        -cp "$WORK/overlay;$WORK/tests;$CP" org.junit.runner.JUnitCore "$TEST_CLASS"
}

if [ "$MODE" = "run" ] || [ "$MODE" = "classes" ]; then
    run_suite
    exit $?
fi

if [ "$MODE" = "baseline" ]; then
    # Сборка до фикса обязана покраснеть ровно на симптоме: красный из-за classpath или
    # NoSuchMethodException доказал бы только обвязку. Поэтому сверяются имена и причины.
    EXPECTED_RED=(
        setScheduleEvery300SecondsMatchesPlatformFile
        numbersFromJsonAsDoubleAreAccepted
        platformPropertyNamesAreAccepted
        dailySchedulesMatchPlatformFile
        weekDaysAreStoredInPlatformOrder
        emptyListsAndZeroWeeksPeriodAreReproducible
        setReplacesTheWholeSchedule
        refusedValueLeavesScheduleUntouched
        malformedValuesAreRefused
        newScheduleOnDetachedJobFailsClosed
        unresolvedScheduleProxyIsTreatedAsMissing
        unsetScheduleExplainsTheAlternative
        createMetadataListsScheduleAsSupportedProperty
    )
    EXPECTED_GREEN=(
        otherScheduledJobFieldsStillUseGenericSetter
        transientFieldsOfOtherObjectsStayReadOnly
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
    # Сам симптом из воспроизведения — дословно.
    grep -a -q 'Field is read-only: schedule' "$WORK/baseline.txt" \
        || { echo "🚨 нет симптома «Field is read-only: schedule» в причинах"; PROBLEMS=1; }
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

[ -f "$ECJ" ] || { echo "НЕ НАЙДЕНО: $ECJ (нужен для саботажа)" >&2; exit 2; }

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

echo "=== САБОТАЖ-КОНТРОЛЬ расписания регламентного задания: каждый дефект обязан дать красный ==="
FAILED_TO_CATCH=0
SABOTAGES=0
SVC="com/codepilot1c/core/edt/metadata/EdtMetadataService.java"
APPLIER="com/codepilot1c/core/edt/metadata/ScheduledJobScheduleApplier.java"

# sabotage <метка> <файл относительно src> <perl-выражение> — правит копию класса и компилирует её ECJ в overlay.
# Урок run-command-interface-eval.sh: у переменных уникальные имена и local (динамическая область видимости
# bash), применение сверяется с копией, снятой до замены, а cmp = 2 — отдельная ошибка, не «различаются».
sabotage() {
    local sabotage_label="$1" sabotage_file="$2" sabotage_expr="$3"
    local sabotaged="$WORK/src/$sabotage_file" pristine="$WORK/pristine.java"
    SABOTAGES=$((SABOTAGES + 1))
    rm -rf "$WORK/src" "$WORK/overlay"; mkdir -p "$(dirname "$sabotaged")" "$WORK/overlay"
    cp "$SRC/$sabotage_file" "$sabotaged"
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

# Исходный дефект: schedule снова уходит в общий сеттер и отбивается как read-only.
sabotage "schedule снова в общем сеттере (дефект до фикса)" "$APPLIER" \
    's/return target instanceof com\._1c\.g5\.v8\.dt\.metadata\.mdclass\.ScheduledJob\n/return false \&\& target instanceof com._1c.g5.v8.dt.metadata.mdclass.ScheduledJob\n/'

# Спецветка ловит любое поле задания, а не только schedule.
sabotage "спецветка по любому полю ScheduledJob" "$APPLIER" \
    's/&& FIELD_NAME\.equalsIgnoreCase\(fieldName\.trim\(\)\);/\&\& !fieldName.isBlank();/'

# «Слияние» вместо «замены»: прежнее окно и дни недели переживают set.
sabotage "fill без сброса к умолчаниям (слияние)" "$APPLIER" \
    's/Map<EStructuralFeature, Object> parsed = parse\(value\);\n        resetToDefaults\(schedule\);/Map<EStructuralFeature, Object> parsed = parse(value);/'

# Сброс раньше разбора: отказанное значение успевает испортить расписание.
sabotage "сброс до разбора значения" "$APPLIER" \
    's/Map<EStructuralFeature, Object> parsed = parse\(value\);\n        resetToDefaults\(schedule\);/resetToDefaults(schedule);\n        Map<EStructuralFeature, Object> parsed = parse(value);/'

# Умолчание дней недели — порядок фабрики EDT (Sun..Sat), не порядок файлов платформы.
sabotage "все дни недели в порядке фабрики EDT" "$APPLIER" \
    's/replaceList\(schedule\.getWeekDays\(\), allWeekDays\(\)\);/replaceList(schedule.getWeekDays(), WeekDays.VALUES);/'

# Дни недели в порядке запроса, без сортировки и с дублями.
sabotage "дни недели без сортировки" "$APPLIER" \
    's/TreeSet<Integer> numbers = new TreeSet<>\(\);\n        for \(Object item : asList\(raw, path\)\) \{\n            WeekDays day/List<Integer> numbers = new ArrayList<>();\n        for (Object item : asList(raw, path)) {\n            WeekDays day/'

# Нестрогая дата EDT: 2026-13-01 молча становится 2027-01-01.
sabotage "дата без проверки круга" "$APPLIER" \
    's/if \(!text\.equals\(ScheduleDateTimeUtil\.dateToString\(date\)\)\) \{/if (false) {/'

# Нестрогое время EDT: 25:00:00 молча становится 01:00:00.
sabotage "время без проверки диапазона" "$APPLIER" \
    's/if \(hours > 23 \|\| minutes > 59 \|\| seconds > 59\) \{/if (false) {/'

# Дробное число молча усекается.
sabotage "дробное число усекается" "$APPLIER" \
    's/ \|\| asDouble != Math\.rint\(asDouble\)\) \{/) {/'

# Неизвестный ключ молча пропускается.
sabotage "неизвестный ключ пропускается" "$APPLIER" \
    's/if \(key == null\) \{\n                throw invalid\("schedule: unknown key/if (key == null) {\n                if (true) continue;\n                throw invalid("schedule: unknown key/'

# Отказ unset без подсказки об use=false.
sabotage "unset schedule — общий отказ" "$SVC" \
    's/if \(ScheduledJobScheduleApplier\.isScheduleField\(target, fieldName\)\) \{\n            throw new MetadataOperationException\(/if (false) {\n            throw new MetadataOperationException(/'

# Прокси вместо расписания снова считается существующим: правка прокси ничего не сохраняет (живая проба -1645).
sabotage "прокси считается существующим расписанием" "$SVC" \
    's/if \(existing != null && !existing\.eIsProxy\(\)\) \{/if (existing != null) {/'

# create_metadata не называет schedule среди поддерживаемых свойств.
sabotage "schedule не в списке свойств create_metadata" "$SVC" \
    's/names\.add\(ScheduledJobScheduleApplier\.FIELD_NAME\);/;/'

echo
if [ "$FAILED_TO_CATCH" -ne 0 ]; then
    echo "САБОТАЖ-КОНТРОЛЬ ПРОВАЛЕН: не пойманных или неприменённых дефектов $FAILED_TO_CATCH из $SABOTAGES"
    exit 1
fi
echo "САБОТАЖ-КОНТРОЛЬ: все $SABOTAGES дефектов пойманы"
