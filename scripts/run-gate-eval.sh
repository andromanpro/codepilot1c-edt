#!/usr/bin/env bash
# Прогон негативных контролей гейтов антипаттернов #70-72 без Tycho.
#
# Зачем отдельный скрипт: модуль com.codepilot1c.core.tests под EDT 2025.2.3 не
# собирается (upstream-стабы не реализуют новые abstract-методы) — pre-existing,
# к рантайм-плагину отношения не имеет. Но гейт, который не умеет ОТКАЗАТЬ,
# ничего не гейтит, поэтому проверка обязана быть воспроизводимой независимо от
# состояния Tycho-сборки: здесь классы гейтов и тесты компилируются напрямую
# javac'ом против jar'ов установленной EDT.
#
# Использование:  bash scripts/run-gate-eval.sh
# Переопределение: EDT_HOME=... JDK_HOME=... M2_REPO=... bash scripts/run-gate-eval.sh
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
OUT="$REPO_ROOT/target/gate-eval-classes"
rm -rf "$OUT"; mkdir -p "$OUT"
trap 'rm -rf "$OUT"' EXIT

for path in "$EDT_HOME/plugins" "$JDK_HOME/bin" "$JUNIT" "$HAMCREST"; do
    [ -e "$path" ] || { echo "НЕ НАЙДЕНО: $path" >&2; exit 2; }
done

CP="$EDT_HOME/plugins/*;$JUNIT;$HAMCREST"

"$JDK_HOME/bin/javac" -encoding UTF-8 -nowarn -d "$OUT" -cp "$CP" \
    "$SRC/com/codepilot1c/core/edt/metadata/SupportLockGuard.java" \
    "$SRC/com/codepilot1c/core/edt/metadata/EdtPluginInjectorLocator.java" \
    "$SRC/com/codepilot1c/core/edt/metadata/MetadataOperationException.java" \
    "$SRC/com/codepilot1c/core/edt/metadata/MetadataOperationCode.java" \
    "$SRC/com/codepilot1c/core/logging/VibeLogger.java" \
    "$TESTS/com/codepilot1c/core/edt/metadata/SupportLockGuardTest.java"

# Сканер uuid и линтер тянут инфраструктуру тулов, поэтому компилируются со
# sourcepath; ошибки в несвязанных ветках (agent/langgraph — внешние зависимости
# вне этого classpath) не мешают, поэтому вывод фильтруется, а решает выход java.
"$JDK_HOME/bin/javac" -encoding UTF-8 -nowarn -d "$OUT" -cp "$CP" -sourcepath "$SRC" \
    "$SRC/com/codepilot1c/core/tools/diagnostics/EdtUuidCheckTool.java" \
    "$SRC/com/codepilot1c/core/diagnostics/BslSilentTypeLinter.java" \
    "$TESTS/com/codepilot1c/core/tools/diagnostics/GatesUuidAndLinterTest.java" 2>/dev/null || true

"$JDK_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$OUT;$CP" org.junit.runner.JUnitCore \
    com.codepilot1c.core.edt.metadata.SupportLockGuardTest \
    com.codepilot1c.core.tools.diagnostics.GatesUuidAndLinterTest
