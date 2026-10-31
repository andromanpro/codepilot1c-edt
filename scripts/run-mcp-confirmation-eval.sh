#!/usr/bin/env bash
# Paired-eval правила отказа неподтверждаемым вызовам MCP-хоста (Gitea #85).
#
# Зачем отдельный скрипт: модуль com.codepilot1c.core.tests целиком под EDT
# 2025.2.3 не собирается — pre-existing, к рантайм-плагину отношения не имеет.
# Здесь правило и его тест компилируются напрямую javac'ом против jar-ов
# установленной EDT, поэтому прогон не зависит ни от Tycho, ни от запущенной EDT.
#
# Использование:
#   bash scripts/run-mcp-confirmation-eval.sh            # ожидается зелёный
#   bash scripts/run-mcp-confirmation-eval.sh --sabotage # контроль: обязан покраснеть
#
# Саботаж обязателен: правило состоит из трёх веток, и набор, который физически
# не может упасть, не отличает исправленное правило от исходного дефекта.
set -uo pipefail

# Пути к JVM-инструментам обязаны быть в форме Windows: Git Bash отдаёт POSIX
# (/f/..., /c/...), javac и java такой classpath молча принимают и не находят по
# нему ни одного класса — прогон падает на ClassNotFoundException, что выглядит
# как «тестов нет», а не как «пути кривые».
win_path() { command -v cygpath >/dev/null 2>&1 && cygpath -m "$1" || printf '%s' "$1"; }

REPO_ROOT="$(win_path "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)")"
SRC="$REPO_ROOT/bundles/com.codepilot1c.core/src"
TESTS="$REPO_ROOT/bundles/com.codepilot1c.core.tests/src"

EDT_HOME="${EDT_HOME:-C:/Program Files/1C/1CE/components/1c-edt-2025.2.3+30-x86_64}"
JDK_HOME="${JDK_HOME:-C:/Program Files/Eclipse Adoptium/jdk-17.0.17.10-hotspot}"
M2_REPO="${M2_REPO:-$(win_path "$HOME")/.m2/repository}"

JUNIT="$M2_REPO/junit/junit/4.13.2/junit-4.13.2.jar"
HAMCREST="$M2_REPO/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar"

# Каталог сборки внутри репозитория, а не mktemp: в Git Bash mktemp отдаёт
# POSIX-путь, которого JVM на Windows не видит.
WORK="$REPO_ROOT/target/mcp-confirmation-eval"
rm -rf "$WORK"; mkdir -p "$WORK/classes" "$WORK/src"
trap 'rm -rf "$WORK"' EXIT

for path in "$EDT_HOME/plugins" "$JDK_HOME/bin" "$JUNIT" "$HAMCREST"; do
    [ -e "$path" ] || { echo "НЕ НАЙДЕНО: $path" >&2; exit 2; }
done

CP="$EDT_HOME/plugins/*;$JUNIT;$HAMCREST"
TEST_CLASS="com.codepilot1c.core.mcp.host.McpHostConfirmationPolicyTest"

POLICY_REL="com/codepilot1c/core/mcp/host/McpHostConfirmationPolicy.java"
TEST_REL="com/codepilot1c/core/mcp/host/McpHostConfirmationPolicyTest.java"

# McpHostConfig тянется целиком ради вложенного enum MutationPolicy; отдельного
# файла у него нет, поэтому он компилируется вместе с правилом.
CONFIG_REL="com/codepilot1c/core/mcp/host/McpHostConfig.java"

seed_sources() {
    rm -rf "$WORK/src"; mkdir -p "$WORK/src/com/codepilot1c/core/mcp/host"
    cp "$SRC/$POLICY_REL" "$WORK/src/$POLICY_REL"
    cp "$SRC/$CONFIG_REL" "$WORK/src/$CONFIG_REL"
    cp "$TESTS/$TEST_REL" "$WORK/src/$TEST_REL"
}

run_suite() {
    rm -rf "$WORK/classes"; mkdir -p "$WORK/classes"
    if ! "$JDK_HOME/bin/javac" -encoding UTF-8 -nowarn -d "$WORK/classes" -cp "$CP" \
            -sourcepath "$WORK/src;$SRC" \
            "$WORK/src/$POLICY_REL" "$WORK/src/$CONFIG_REL" "$WORK/src/$TEST_REL" 2>&1 \
            | grep -E "McpHostConfirmationPolicy|McpHostConfig\.java" ; then
        : # вывод компилятора отфильтрован; решает наличие класса ниже
    fi
    [ -f "$WORK/classes/com/codepilot1c/core/mcp/host/McpHostConfirmationPolicyTest.class" ] || return 90
    "$JDK_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$WORK/classes;$CP" \
        org.junit.runner.JUnitCore "$TEST_CLASS"
}

POLICY="$WORK/src/$POLICY_REL"

if [ "${1:-}" != "--sabotage" ]; then
    seed_sources
    run_suite || exit $?
    echo
    echo "MCP CONFIRMATION EVAL: OK"
    exit 0
fi

echo "=== САБОТАЖ-КОНТРОЛЬ: каждый дефект обязан дать красный ==="
FAILED_TO_CATCH=0

sabotage() {
    local label="$1"; shift
    seed_sources
    "$@"
    echo
    echo "--- саботаж: $label"
    set +e
    run_suite > "$WORK/out.txt" 2>&1
    local rc=$?
    set -e
    if [ "$rc" -eq 0 ]; then
        echo "НЕ ПОЙМАН: $label — набор остался зелёным"
        FAILED_TO_CATCH=$((FAILED_TO_CATCH + 1))
    else
        echo "ok (красный, rc=$rc): $(grep -m1 -E '^(Tests run|FAILURES|[0-9]+ error)' "$WORK/out.txt" || echo 'не скомпилировалось')"
    fi
}

# 1. Исходный дефект #85: отказ безусловный, настроенная политика игнорируется.
sabotage "отказ безусловный (дефект 1.3.9)" \
    perl -0pi -e 's/return mutationPolicy != MutationPolicy\.ALLOW;/return true;/' "$POLICY"

# 2. Обратный перекос: пропускать всё, что требует подтверждения, — гейт снят.
sabotage "пропускает всё (гейт снят)" \
    perl -0pi -e 's/return mutationPolicy != MutationPolicy\.ALLOW;/return false;/' "$POLICY"

# 3. Отсутствие политики приравнено к ALLOW: молчание принято за разрешение.
sabotage "null-политика трактуется как разрешение" \
    perl -0pi -e 's/return mutationPolicy != MutationPolicy\.ALLOW;/return mutationPolicy == MutationPolicy.ASK || mutationPolicy == MutationPolicy.DENY;/' "$POLICY"

# 4. Контракт upstream сломан: при включённом профиле решение принимает не профиль.
sabotage "профильный гейт перестал быть решающим" \
    perl -0pi -e 's/if \(profileGateEnabled\) \{\s*\n(\s*\/\/[^\n]*\n)*\s*\/\/[^\n]*\n\s*return true;\s*\n\s*\}//s' "$POLICY"

echo
if [ "$FAILED_TO_CATCH" -ne 0 ]; then
    echo "САБОТАЖ-КОНТРОЛЬ ПРОВАЛЕН: не пойманных дефектов $FAILED_TO_CATCH"
    exit 1
fi
echo "САБОТАЖ-КОНТРОЛЬ: все 4 дефекта пойманы"
