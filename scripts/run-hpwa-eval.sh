#!/bin/bash
# Paired-eval контракта рабочей области начальной страницы (HomePageWorkAreaXml) без Tycho:
# чистая Java-логика компилируется javac'ом вместе с тестом и гоняется JUnit 4.
#
#   bash scripts/run-hpwa-eval.sh              # тест обязан быть зелёным
#   bash scripts/run-hpwa-eval.sh --sabotage   # негативный контроль: рендер с чужим namespace
#                                              # и без второй колонки обязан дать КРАСНЫЙ
#
# Зачем негативный контроль: зелёный тест против неизменённого рендера ничего не доказывает,
# если тест не умеет краснеть. Саботаж подменяет константу namespace в копии исходника и
# требует падения того же теста.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Windows-пути для javac/java: POSIX-вид (/c/..., /f/...) в classpath молча не находится
WIN_ROOT="$(cygpath -m "$REPO_ROOT")"
JDK_HOME="${JDK_HOME:-C:/Program Files/Eclipse Adoptium/jdk-17.0.17.10-hotspot}"
M2_REPO="${M2_REPO:-$(cygpath -m "$HOME")/.m2/repository}"
JUNIT="$M2_REPO/junit/junit/4.13.2/junit-4.13.2.jar"
HAMCREST="$M2_REPO/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar"
SRC="$REPO_ROOT/bundles/com.codepilot1c.core/src"
TESTS="$REPO_ROOT/bundles/com.codepilot1c.core.tests/src"
REL="com/codepilot1c/core/edt/metadata/HomePageWorkAreaXml.java"
TEST_REL="com/codepilot1c/core/edt/metadata/HomePageWorkAreaXmlTest.java"
WORK="$REPO_ROOT/target/hpwa-eval"
WIN_WORK="$WIN_ROOT/target/hpwa-eval"
rm -rf "$WORK"; mkdir -p "$WORK/src/$(dirname "$REL")" "$WORK/classes"
trap 'rm -rf "$WORK"' EXIT
for path in "$JDK_HOME/bin/javac.exe" "$JUNIT" "$HAMCREST"; do
    [ -e "$path" ] || { echo "НЕ НАЙДЕНО: $path" >&2; exit 2; }
done
cp "$SRC/$REL" "$WORK/src/$REL"
if [ "${1:-}" = "--sabotage" ]; then
    # два дефекта сразу: чужой namespace и потеря второй колонки
    sed -i 's#http://g5.1c.ru/v8/dt/hpwa#http://g5.1c.ru/v8/dt/mcore#; s#if (!TEMPLATE_ONE_COLUMN.equals(normalized)) {#if (false) {#' "$WORK/src/$REL"
    grep -q "dt/mcore" "$WORK/src/$REL" || { echo "саботаж не применился" >&2; exit 2; }
fi
CP="$JUNIT;$HAMCREST"
"$JDK_HOME/bin/javac" -encoding UTF-8 -nowarn -d "$WORK/classes" -cp "$CP" \
    "$WORK/src/$REL" "$TESTS/$TEST_REL"
set +e
"$JDK_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$WIN_WORK/classes;$CP" org.junit.runner.JUnitCore \
    com.codepilot1c.core.edt.metadata.HomePageWorkAreaXmlTest > "$WORK/junit.log" 2>&1
RC=$?
set -e
grep -E "^OK|^Tests run|FAILURES" "$WORK/junit.log" || true
if [ "${1:-}" = "--sabotage" ]; then
    if [ $RC -eq 0 ]; then echo "КРАСНЫЙ: саботаж не пойман - тест не умеет краснеть" >&2; exit 1; fi
    echo "зелёный: саботаж пойман (тест красный, как и должен)"
    exit 0
fi
exit $RC
