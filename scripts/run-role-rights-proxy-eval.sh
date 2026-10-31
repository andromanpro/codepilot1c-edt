#!/usr/bin/env bash
# Парный eval: роль без Rights.rights на диске, у которой Role.rights — неразрешённый прокси (ветка fix/role-rights-proxy).
#
# Живая проба 24.09.2026 на сборке 1.3.9.20260924-1711 (CpScratchForms): у роли удалили Rights.rights при закрытой EDT
# (так делает git checkout), EDT запустили. getRights() отдал не null, а неразрешённый прокси на ожидаемый внешний
# объект; он проходит instanceof RoleDescription. inspect_role_rights показал hasRightsModel:true с пустыми списками,
# mutate_role_rights ответил «set WebClient=Set (was Unset)» — файла на диске не появилось, в модели пусто.
# Тот же дефект, что у расписания регламентного задания (ScheduledJob.schedule, коммит d4f1650a).
#
# Использование:
#   bash scripts/run-role-rights-proxy-eval.sh                      # против target/classes, ожидается зелёный
#   bash scripts/run-role-rights-proxy-eval.sh --baseline <каталог> # против распакованного jar сборки ДО фикса:
#                                                                   # обязан покраснеть поимённо на симптоме
#   bash scripts/run-role-rights-proxy-eval.sh --classes <каталог>  # против другой сборки, ожидается зелёный
#   bash scripts/run-role-rights-proxy-eval.sh --sabotage           # каждый дефект обязан дать красный
#
# EdtRoleRightsServiceProxyRightsTest бьёт рефлексией по настоящим getOrCreateRoleDescription и readSnapshot (роль и
# модель прав из фабрик EMF, транзакция BM, менеджер модели и генератор FQN — динамические прокси) и не ссылается на
# классы фикса: один бинарник идёт против сборки до фикса и после.
#
# Чего этот eval НЕ проверяет: настоящую транзакцию BM и выгрузку Rights.rights на диск. Это закрывает только живая
# проба на установленной сборке — роль, чей Rights.rights удалён при закрытой EDT.
set -euo pipefail

win_path() { command -v cygpath >/dev/null 2>&1 && cygpath -m "$1" || printf '%s' "$1"; }

REPO_ROOT="$(win_path "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)")"
SRC="$REPO_ROOT/bundles/com.codepilot1c.core/src"
TESTS="$REPO_ROOT/bundles/com.codepilot1c.core.tests/src"

EDT_HOME="${EDT_HOME:-C:/Program Files/1C/1CE/components/1c-edt-2025.2.3+30-x86_64}"
JDK_HOME="${JDK_HOME:-C:/Program Files/Eclipse Adoptium/jdk-17.0.17.10-hotspot}"
M2_REPO="${M2_REPO:-$(win_path "$HOME")/.m2/repository}"

JUNIT="$M2_REPO/junit/junit/4.13.2/junit-4.13.2.jar"
HAMCREST="$M2_REPO/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar"
ECJ="${ECJ:-$M2_REPO/org/eclipse/jdt/ecj/3.36.0/ecj-3.36.0.jar}"

WORK="$REPO_ROOT/target/role-rights-proxy-eval"
rm -rf "$WORK"; mkdir -p "$WORK/tests" "$WORK/src" "$WORK/overlay"
trap 'rm -rf "$WORK"' EXIT

for required_path in "$EDT_HOME/plugins" "$JDK_HOME/bin" "$JUNIT" "$HAMCREST"; do
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
    # Прогон против устаревших классов зелёный ровно так же, как против свежих. Точка отсчёта — jar последней
    # сборки: mtime каталога classes не меняется при перезаписи файлов внутри.
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
JNA_DIR="$(ls -d "$EDT_HOME"/plugins/com.sun.jna_* 2>/dev/null | head -1 || true)"
[ -n "$JNA_DIR" ] && CP="$CP;$JNA_DIR"
CP="$CP;$REPO_ROOT/bundles/com.codepilot1c.core/lib/*"
[ -d "$CLASSES/lib" ] && CP="$CP;$CLASSES/lib/*"
CP="$CLASSES;$CP"

TEST_CLASS=com.codepilot1c.core.edt.rights.EdtRoleRightsServiceProxyRightsTest

"$JDK_HOME/bin/javac" -encoding UTF-8 -nowarn -d "$WORK/tests" -cp "$CP" "$TESTS/${TEST_CLASS//.//}.java" \
    2> "$WORK/javac-tests.log" || {
    cat "$WORK/javac-tests.log" >&2
    echo "тест не скомпилировался" >&2
    exit 1
}

run_suite() {
    "$JDK_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$WORK/overlay;$WORK/tests;$CP" org.junit.runner.JUnitCore "$TEST_CLASS"
}

if [ "$MODE" = "run" ] || [ "$MODE" = "classes" ]; then
    run_suite
    exit $?
fi

if [ "$MODE" = "baseline" ]; then
    # Красный из-за classpath или сигнатуры доказал бы только обвязку. Поэтому сверяются имена и причины.
    EXPECTED_RED=(
        unresolvedRightsProxyIsReplacedByAttachedDescription
        inspectReportsNoRightsModelForUnresolvedProxy
    )
    EXPECTED_GREEN=(
        missingRightsAreCreated
        resolvedRightsAreReusedWithoutAttach
        inspectReportsRightsModelForResolvedDescription
    )
    set +e
    run_suite > "$WORK/baseline.txt" 2>&1
    suite_rc=$?
    set -e
    awk '/^[0-9]+\) / { print; getline; print "     " substr($0, 1, 260) } /^Tests run|^OK \(/ { print }' "$WORK/baseline.txt"
    PROBLEMS=0
    [ "$suite_rc" -ne 0 ] || { echo "🚨 сборка до фикса ЗЕЛЁНАЯ — eval не воспроизводит дефект"; PROBLEMS=1; }
    if grep -a -q -E 'ExceptionInInitializerError|NoClassDefFoundError|NoSuchMethodException|ClassNotFoundException' "$WORK/baseline.txt"; then
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
    # Сам симптом: мутация вернула прокси, чтение показало прокси моделью прав.
    grep -a -q 'вернулся неразрешённый прокси' "$WORK/baseline.txt" || { echo "🚨 нет симптома «вернулся неразрешённый прокси»"; PROBLEMS=1; }
    grep -a -q 'прокси показан как модель прав' "$WORK/baseline.txt" || { echo "🚨 нет симптома «прокси показан как модель прав»"; PROBLEMS=1; }
    TOTAL=$(( ${#EXPECTED_RED[@]} + ${#EXPECTED_GREEN[@]} ))
    grep -a -q -E "^Tests run: $TOTAL," "$WORK/baseline.txt" || { echo "🚨 в наборе не $TOTAL тестов — сверка устарела"; PROBLEMS=1; }
    if [ "$PROBLEMS" -ne 0 ]; then
        echo "BASELINE: ПРОВАЛ"
        exit 1
    fi
    echo "BASELINE: сборка до фикса красная ровно на симптоме (${#EXPECTED_RED[@]} из $TOTAL), роль без прокси не задета"
    exit 0
fi

# ─── саботаж ──────────────────────────────────────────────────────────────

[ -f "$ECJ" ] || { echo "НЕ НАЙДЕНО: $ECJ (нужен для саботажа EdtRoleRightsService)" >&2; exit 2; }

{
    printf -- '-classpath\n"%s' "$CLASSES"
    for ecj_jar in "$EDT_HOME"/plugins/*.jar; do printf ';%s' "$ecj_jar"; done
    for ecj_dir in "$EDT_HOME"/plugins/*/; do printf ';%s' "${ecj_dir%/}"; done
    for lib_jar in "$REPO_ROOT"/bundles/com.codepilot1c.core/lib/*.jar; do [ -e "$lib_jar" ] && printf ';%s' "$lib_jar"; done
    printf '"\n'
} > "$WORK/ecj-classpath.args"

# Саботаж на красной базе беззубый: «пойман» там любой дефект.
if ! run_suite > "$WORK/clean.txt" 2>&1; then
    grep -a -E '^[0-9]+\) |^Tests run' "$WORK/clean.txt" | head -10 || true
    echo "ОТКАЗ: набор красный и без саботажа — контроль ничего бы не доказал" >&2
    exit 1
fi

echo "=== САБОТАЖ-КОНТРОЛЬ прокси вместо прав роли: каждый дефект обязан дать красный ==="
FAILED_TO_CATCH=0
SABOTAGES=0
SVC="com/codepilot1c/core/edt/rights/EdtRoleRightsService.java"

# sabotage <метка> <perl-выражение>. Уроки run-command-interface-eval.sh: уникальные имена и local у переменных
# (динамическая область видимости bash), применение сверяется с копией до замены, cmp = 2 — отдельная ошибка.
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

# Исходный дефект мутации: прокси считается существующей моделью прав и правится.
sabotage "мутация правит прокси (дефект до фикса)" \
    's/if \(description instanceof RoleDescription roleDescription && !roleDescription\.eIsProxy\(\)\) \{/if (description instanceof RoleDescription roleDescription) {/'

# Исходный дефект чтения: прокси показывается как модель прав.
sabotage "чтение показывает прокси моделью прав (дефект до фикса)" \
    's/if \(!\(description instanceof RoleDescription roleDescription\) \|\| roleDescription\.eIsProxy\(\)\) \{/if (!(description instanceof RoleDescription roleDescription)) {/'

# Новая модель прав создана и подключена верхним объектом, но роль на неё не переключена — прокси остаётся.
sabotage "роль не переключена на новую модель прав" \
    's/\n\s*txRole\.setRights\(roleDescription\);\n/\n/'

# «Починка» наизнанку: создаётся новая модель прав и поверх настоящей — существующие права роли теряются.
sabotage "настоящая модель прав заменяется новой" \
    's/roleDescription && !roleDescription\.eIsProxy\(\)\) \{/roleDescription \&\& roleDescription.eIsProxy()) {/'

echo
if [ "$FAILED_TO_CATCH" -ne 0 ]; then
    echo "САБОТАЖ-КОНТРОЛЬ ПРОВАЛЕН: не пойманных или неприменённых дефектов $FAILED_TO_CATCH из $SABOTAGES"
    exit 1
fi
echo "САБОТАЖ-КОНТРОЛЬ: все $SABOTAGES дефектов пойманы"
