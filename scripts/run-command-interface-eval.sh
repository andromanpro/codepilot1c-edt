#!/usr/bin/env bash
# Eval командного интерфейса: логика inspect/mutate_command_interface на настоящей
# EMF-модели EDT (CmiFactory/MdClassFactory) и страж .cmi в edit_file/write_file.
#
# Зачем отдельный скрипт: модуль com.codepilot1c.core.tests под EDT 2025.2.3 целиком
# не собирается (pre-existing, см. run-predefined-eval.sh). Здесь классы и тесты
# компилируются javac'ом против jar-ов установленной EDT, без Tycho и без запущенной EDT.
#
# Использование:
#   bash scripts/run-command-interface-eval.sh            # обычный прогон (ожидается зелёный)
#   bash scripts/run-command-interface-eval.sh --sabotage # контроль: каждый дефект обязан дать красный
#
# Подключение стража в сами инструменты и BM-путь eval не видит: это проверяется живьём на
# установленной сборке (отказ edit_file/write_file по .cmi, inspect до и после prune_orphans).
set -euo pipefail

# Пути к JVM-инструментам в форме Windows: POSIX-путь (/f/...) javac принимает молча и не
# находит по нему ни одного класса. cygpath -m даёт C:/... с прямыми слешами.
win_path() { command -v cygpath >/dev/null 2>&1 && cygpath -m "$1" || printf '%s' "$1"; }

REPO_ROOT="$(win_path "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)")"
SRC="$REPO_ROOT/bundles/com.codepilot1c.core/src"
TESTS="$REPO_ROOT/bundles/com.codepilot1c.core.tests/src"

EDT_HOME="${EDT_HOME:-C:/Program Files/1C/1CE/components/1c-edt-2025.2.3+30-x86_64}"
JDK_HOME="${JDK_HOME:-C:/Program Files/Eclipse Adoptium/jdk-17.0.17.10-hotspot}"
M2_REPO="${M2_REPO:-$(win_path "$HOME")/.m2/repository}"

JUNIT="$M2_REPO/junit/junit/4.13.2/junit-4.13.2.jar"
HAMCREST="$M2_REPO/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar"

WORK="$REPO_ROOT/target/command-interface-eval"
rm -rf "$WORK"; mkdir -p "$WORK/classes" "$WORK/src"
trap 'rm -rf "$WORK"' EXIT

for path in "$EDT_HOME/plugins" "$JDK_HOME/bin" "$JUNIT" "$HAMCREST"; do
    [ -e "$path" ] || { echo "НЕ НАЙДЕНО: $path" >&2; exit 2; }
done

CP="$EDT_HOME/plugins/*;$JUNIT;$HAMCREST"

SOURCES=(
    "com/codepilot1c/core/edt/cmi/CommandInterfaceFragments.java"
    "com/codepilot1c/core/edt/metadata/MetadataOperationException.java"
    "com/codepilot1c/core/edt/metadata/MetadataOperationCode.java"
    "com/codepilot1c/core/tools/file/EdtModelFileGuard.java"
)
TEST_SOURCES=(
    "com/codepilot1c/core/edt/cmi/CommandInterfaceFragmentsTest.java"
    "com/codepilot1c/core/tools/file/EdtModelFileGuardTest.java"
)
TEST_CLASSES=(
    "com.codepilot1c.core.edt.cmi.CommandInterfaceFragmentsTest"
    "com.codepilot1c.core.tools.file.EdtModelFileGuardTest"
)

run_suite() {
    rm -rf "$WORK/classes"; mkdir -p "$WORK/classes"
    local files=() source_rel
    for source_rel in "${SOURCES[@]}" "${TEST_SOURCES[@]}"; do files+=("$WORK/src/$source_rel"); done
    if ! "$JDK_HOME/bin/javac" -encoding UTF-8 -nowarn -d "$WORK/classes" -cp "$CP" "${files[@]}"; then
        return 90   # не скомпилировалось — для саботажа это тоже «красный»
    fi
    "$JDK_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$WORK/classes;$CP" \
        org.junit.runner.JUnitCore "${TEST_CLASSES[@]}"
}

seed_sources() {
    # Переменные циклов объявлены local: в bash область видимости динамическая, и цикл
    # по глобальному имени затирал переменную вызывающей функции sabotage — замена уходила
    # в последний тестовый файл, а контроль оставался зелёным (замер 22.09.2026).
    local seed_rel
    rm -rf "$WORK/src"; mkdir -p "$WORK/src"
    for seed_rel in "${SOURCES[@]}"; do
        mkdir -p "$WORK/src/$(dirname "$seed_rel")"
        cp "$SRC/$seed_rel" "$WORK/src/$seed_rel"
    done
    for seed_rel in "${TEST_SOURCES[@]}"; do
        mkdir -p "$WORK/src/$(dirname "$seed_rel")"
        cp "$TESTS/$seed_rel" "$WORK/src/$seed_rel"
    done
}

FRAGMENTS_REL="com/codepilot1c/core/edt/cmi/CommandInterfaceFragments.java"
GUARD_REL="com/codepilot1c/core/tools/file/EdtModelFileGuard.java"

if [ "${1:-}" != "--sabotage" ]; then
    seed_sources
    run_suite
    echo
    echo "COMMAND INTERFACE EVAL: OK"
    exit 0
fi

echo "=== САБОТАЖ-КОНТРОЛЬ: каждый дефект обязан дать красный ==="
FAILED_TO_CATCH=0
NOT_APPLIED=0

# sabotage <метка> <файл-rel> <perl-выражение>. Замена, которая не применилась, — ошибка
# самого контроля: зелёный прогон по нетронутому коду выглядел бы как «дефект не пойман».
SABOTAGE_COUNT=0
sabotage() {
    local label="$1" target_rel="$2" expr="$3"
    SABOTAGE_COUNT=$((SABOTAGE_COUNT + 1))
    seed_sources
    local target="$WORK/src/$target_rel"
    echo
    echo "--- саботаж: $label"
    if [ ! -f "$target" ]; then
        echo "🚨 САБОТАЖ НЕ ПРИМЕНИЛСЯ: нет файла $target_rel"
        NOT_APPLIED=$((NOT_APPLIED + 1))
        return
    fi
    # Сравнение с копией, снятой перед заменой: cmp по отсутствующему файлу возвращает ошибку,
    # и проверка «различаются» проходила бы на нетронутом коде.
    cp "$target" "$WORK/pristine.java"
    perl -0pi -e "$expr" "$target"
    set +e
    cmp -s "$WORK/pristine.java" "$target"
    local same=$?
    set -e
    if [ "$same" -ne 1 ]; then
        echo "🚨 САБОТАЖ НЕ ПРИМЕНИЛСЯ (cmp=$same): шаблон не нашёл код — обновить пробу, а не выключать"
        NOT_APPLIED=$((NOT_APPLIED + 1))
        return
    fi
    set +e
    run_suite > "$WORK/out.txt" 2>&1
    local rc=$?
    set -e
    if [ "$rc" -eq 0 ]; then
        echo "🚨 НЕ ПОЙМАН: $label — набор остался зелёным"
        FAILED_TO_CATCH=$((FAILED_TO_CATCH + 1))
    else
        echo "ok (красный, rc=$rc): $(grep -m1 -E '^(Tests run|FAILURES|[0-9]+ error)' "$WORK/out.txt" || echo "не скомпилировалось")"
    fi
}

# 1. Каждая запись считается сиротой — prune снёс бы видимость всех команд подсистемы.
sabotage "любая запись считается записью без команды" "$FRAGMENTS_REL" \
    's/\Qreturn fragment != null && fragment.getCommand() == null;\E/return fragment != null;/'

# 2. Неразрешённая ссылка считается сиротой — prune спрятал бы сломанную ссылку вместо починки.
sabotage "неразрешённая ссылка удаляется как запись без команды" "$FRAGMENTS_REL" \
    's/\Qreturn fragment != null && fragment.getCommand() == null;\E/return fragment != null && (fragment.getCommand() == null || fragment.getCommand().eIsProxy());/'

# 3. prune считает, но не удаляет — ответ «удалено 4» при нетронутой модели.
sabotage "prune ничего не удаляет" "$FRAGMENTS_REL" \
    's/\Qsection.getVisibilityFragments().removeAll(orphans);\E/;/'

# 4. Отчёт не различает неразрешённую ссылку — агент не узнал бы о сломанной ссылке.
sabotage "состояние не видит неразрешённых ссылок" "$FRAGMENTS_REL" \
    's/\Qif (!command.eIsProxy()) {\E/if (true) {/'

# 5. Суффикс .CommandInterface срезается при любой длине — подсистема с таким именем теряется.
sabotage "суффикс срезается и у имени подсистемы" "$FRAGMENTS_REL" \
    's/\Qif (length % 2 == 1 && isCommandInterfaceSuffix(raw[length - 1])) {\E/if (isCommandInterfaceSuffix(raw[length - 1])) {/'

# 6. Страж сравнивает регистрозависимо — .CMI прошёл бы мимо edit_file/write_file.
sabotage "страж .cmi регистрозависим" "$GUARD_REL" \
    's/\Qpath.trim().toLowerCase(Locale.ROOT).endsWith(".cmi")\E/path.trim().endsWith(".cmi")/'

# 7. Платформенная команда считается битой ссылкой - в отчете прятанная БСП команда выглядит поломкой.
sabotage "платформенная команда считается битой ссылкой" "$FRAGMENTS_REL" \
    's/\Qreturn text != null && PLATFORM_COMMAND_ID.matcher(text).matches();\E/return false;/'

# 8. Любая неразрешенная ссылка считается платформенной - битая ссылка на объект пропадает из вида.
sabotage "битая ссылка на объект считается платформенной командой" "$FRAGMENTS_REL" \
    's/\Qreturn text != null && PLATFORM_COMMAND_ID.matcher(text).matches();\E/return text != null;/'

echo
if [ "$NOT_APPLIED" -ne 0 ]; then
    echo "САБОТАЖ-КОНТРОЛЬ НЕИСПРАВЕН: не применилось замен $NOT_APPLIED"
    exit 2
fi
if [ "$FAILED_TO_CATCH" -ne 0 ]; then
    echo "САБОТАЖ-КОНТРОЛЬ ПРОВАЛЕН: не пойманных дефектов $FAILED_TO_CATCH"
    exit 1
fi
echo "САБОТАЖ-КОНТРОЛЬ: все $SABOTAGE_COUNT дефектов пойманы"
