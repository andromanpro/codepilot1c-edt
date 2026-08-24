#!/usr/bin/env bash
# Paired-eval резолва корня конфигурации в extension_manage set_state (без Tycho).
#
# До фикса set_state по корневым свойствам расширения отвечал METADATA_NOT_FOUND:
# findSourceObject обходит только containment-коллекции, корень в них не входит.
# Модуль com.codepilot1c.core.tests под Tycho не собирается (pre-existing),
# поэтому тест компилируется javac'ом против jar'ов установленной EDT —
# тот же приём, что в run-gate-eval.sh.
#
# Использование:  bash scripts/run-extension-root-eval.sh
# Переопределение: EDT_HOME=... JDK_HOME=... M2_REPO=... bash scripts/run-extension-root-eval.sh
set -euo pipefail

# Пути для JVM обязаны быть в форме Windows: POSIX-пути Git Bash javac/java
# молча не находят, и прогон падает ClassNotFoundException вместо проверки.
win_path() { command -v cygpath >/dev/null 2>&1 && cygpath -m "$1" || printf '%s' "$1"; }

REPO_ROOT="$(win_path "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)")"
SRC="$REPO_ROOT/bundles/com.codepilot1c.core/src"
TESTS="$REPO_ROOT/bundles/com.codepilot1c.core.tests/src"

EDT_HOME="${EDT_HOME:-C:/Program Files/1C/1CE/components/1c-edt-2025.2.3+30-x86_64}"
JDK_HOME="${JDK_HOME:-C:/Program Files/Eclipse Adoptium/jdk-17.0.17.10-hotspot}"
M2_REPO="${M2_REPO:-$(win_path "$HOME")/.m2/repository}"

JUNIT="$M2_REPO/junit/junit/4.13.2/junit-4.13.2.jar"
HAMCREST="$M2_REPO/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar"

OUT="$REPO_ROOT/target/extension-root-eval-classes"
rm -rf "$OUT"; mkdir -p "$OUT"
trap 'rm -rf "$OUT"' EXIT

for path in "$EDT_HOME/plugins" "$JDK_HOME/bin" "$JUNIT" "$HAMCREST"; do
    [ -e "$path" ] || { echo "НЕ НАЙДЕНО: $path" >&2; exit 2; }
done

CP="$EDT_HOME/plugins/*;$JUNIT;$HAMCREST"

# Резолвер намеренно без зависимостей на инфраструктуру плагина — компиляция
# без sourcepath, только против jar'ов EDT: любая ошибка компиляции валит прогон.
"$JDK_HOME/bin/javac" -encoding UTF-8 -nowarn -d "$OUT" -cp "$CP" \
    "$SRC/com/codepilot1c/core/edt/extension/ExtensionRootResolver.java" \
    "$TESTS/com/codepilot1c/core/edt/extension/EdtExtensionRootResolveTest.java"

"$JDK_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$OUT;$CP" org.junit.runner.JUnitCore \
    com.codepilot1c.core.edt.extension.EdtExtensionRootResolveTest
