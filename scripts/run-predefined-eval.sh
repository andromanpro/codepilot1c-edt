#!/usr/bin/env bash
# Paired-eval предопределённых элементов справочника (child_kind=PredefinedItem).
#
# Зачем отдельный скрипт: модуль com.codepilot1c.core.tests под EDT 2025.2.3 не
# собирается целиком (upstream-стабы не реализуют новые abstract-методы) —
# pre-existing, к рантайм-плагину отношения не имеет. Здесь класс и тест
# компилируются напрямую javac'ом против jar-ов установленной EDT, поэтому
# прогон не зависит ни от состояния Tycho-сборки, ни от запущенной EDT.
#
# Использование:
#   bash scripts/run-predefined-eval.sh            # обычный прогон (ожидается зелёный)
#   bash scripts/run-predefined-eval.sh --sabotage # контроль: тесты обязаны покраснеть
#
# Саботаж нужен потому, что зелёный набор тестов, который физически не способен
# упасть, ничего не проверяет. Режим вносит в копию исходника четыре реальных
# дефекта (по одному за прогон) и требует красного от каждого.
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
# отдаёт POSIX-путь (/tmp/...), которого JVM на Windows не видит.
WORK="$REPO_ROOT/target/predefined-eval"
rm -rf "$WORK"; mkdir -p "$WORK/classes" "$WORK/src"
trap 'rm -rf "$WORK"' EXIT

for path in "$EDT_HOME/plugins" "$JDK_HOME/bin" "$JUNIT" "$HAMCREST"; do
    [ -e "$path" ] || { echo "НЕ НАЙДЕНО: $path" >&2; exit 2; }
done

CP="$EDT_HOME/plugins/*;$JUNIT;$HAMCREST"
TEST_CLASS="com.codepilot1c.core.edt.metadata.PredefinedItemBuilderTest"

SOURCES=(
    "com/codepilot1c/core/edt/metadata/PredefinedItemBuilder.java"
    "com/codepilot1c/core/edt/metadata/MetadataChildKind.java"
    "com/codepilot1c/core/edt/metadata/MetadataNameValidator.java"
    "com/codepilot1c/core/edt/metadata/MetadataOperationException.java"
    "com/codepilot1c/core/edt/metadata/MetadataOperationCode.java"
)

# Собирает и прогоняет набор из $WORK/src. Возвращает код выхода java.
run_suite() {
    rm -rf "$WORK/classes"; mkdir -p "$WORK/classes"
    local files=()
    for rel in "${SOURCES[@]}"; do files+=("$WORK/src/$rel"); done
    files+=("$WORK/src/com/codepilot1c/core/edt/metadata/PredefinedItemBuilderTest.java")
    if ! "$JDK_HOME/bin/javac" -encoding UTF-8 -nowarn -d "$WORK/classes" -cp "$CP" "${files[@]}"; then
        return 90   # не скомпилировалось — для саботажа это тоже «красный»
    fi
    "$JDK_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$WORK/classes;$CP" \
        org.junit.runner.JUnitCore "$TEST_CLASS"
}

seed_sources() {
    rm -rf "$WORK/src"; mkdir -p "$WORK/src"
    for rel in "${SOURCES[@]}"; do
        mkdir -p "$WORK/src/$(dirname "$rel")"
        cp "$SRC/$rel" "$WORK/src/$rel"
    done
    cp "$TESTS/com/codepilot1c/core/edt/metadata/PredefinedItemBuilderTest.java" \
       "$WORK/src/com/codepilot1c/core/edt/metadata/PredefinedItemBuilderTest.java"
}

BUILDER="$WORK/src/com/codepilot1c/core/edt/metadata/PredefinedItemBuilder.java"

if [ "${1:-}" != "--sabotage" ]; then
    seed_sources
    run_suite
    echo
    echo "PREDEFINED EVAL: OK"
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
        echo "🚨 НЕ ПОЙМАН: $label — набор остался зелёным"
        FAILED_TO_CATCH=$((FAILED_TO_CATCH + 1))
    else
        echo "ok (красный, rc=$rc): $(grep -m1 -E '^(Tests run|FAILURES|[0-9]+ error)' "$WORK/out.txt" || echo "не скомпилировалось")"
    fi
}

# 1. Убрать проверку дубля — конфигурация загрузится с двумя одноимёнными предопределёнными.
sabotage "нет проверки дубля" \
    perl -0pi -e 's/EObject duplicate = findByName\(owner, descriptor\.name\(\)\);/EObject duplicate = null;/' "$BUILDER"

# 2. Не задавать id — EDT напишет id="" (SU106), экспорт в формат платформы упадёт.
sabotage "id не задаётся" \
    perl -0pi -e 's/^\s*assignId\(item\);\s*$/\n/m' "$BUILDER"

# 3. Нумеровать по количеству, а не по максимуму — коллизия на разреженной нумерации.
sabotage "код по количеству вместо максимума" \
    perl -0pi -e 's/max = Math\.max\(max, Long\.parseLong\(trimmed\)\);/max = max + 1;/' "$BUILDER"

# 4. Сравнивать имена регистрозависимо — 1С считает их одним именем.
sabotage "имена сравниваются регистрозависимо" \
    perl -0pi -e 's/if \(name\.equalsIgnoreCase\(nameOf\(item\)\)\) \{/if (name.equals(nameOf(item))) {/' "$BUILDER"

echo
if [ "$FAILED_TO_CATCH" -ne 0 ]; then
    echo "САБОТАЖ-КОНТРОЛЬ ПРОВАЛЕН: не пойманных дефектов $FAILED_TO_CATCH"
    exit 1
fi
echo "САБОТАЖ-КОНТРОЛЬ: все 4 дефекта пойманы"
