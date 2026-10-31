#!/usr/bin/env bash
# Парный eval файла содержимого, который add_metadata_child создаёт для макета
# (Gitea androman/codepilot1c#121).
#
# Дефект: у макета любого типа на диске появлялся Template.mxl — у текстового (TextDocument)
# вместо Template.txt, у двоичного (BinaryData) вместо Template.bin, а у пакета children один
# Templates/null/Template.mxl. Тул при этом отвечал «created successfully». Воспроизведено
# 16.09.2026 на сборке 1.3.9.20260915-0710 (DemoExtension.Demo и CpScratchForms).
#
# Использование:
#   bash scripts/run-template-artifact-eval.sh                      # против target/classes, ожидается зелёный
#   bash scripts/run-template-artifact-eval.sh --baseline <каталог> # поведенческий тест против сборки ДО фикса
#                                                                   # (распакованный jar плагина): обязан покраснеть
#   bash scripts/run-template-artifact-eval.sh --classes <каталог>  # поведенческий тест против другой сборки, ожидается зелёный
#   bash scripts/run-template-artifact-eval.sh --sabotage           # каждый дефект обязан дать красный
#
# Два набора. EdtMetadataServiceTemplateArtifactTest бьёт рефлексией по настоящим
# ensureTemplateArtifact, resolveTemplateType и AddMetadataChildRequest.validate и не ссылается на
# классы фикса — поэтому один и тот же тест идёт против сборки до фикса и после. TemplateArtifactTest
# проверяет таблицу «тип → файл» и сверяет расширения с картой самой EDT; до фикса его не собрать.
#
# Чего этот eval НЕ проверяет: место вызова в addMetadataChild (цикл по пакету children, текст
# предупреждения в ответе) — без BM юнитом туда не попасть. Это закрывает только живая проверка
# вызовом тула через MCP на установленной сборке.
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
WORK="$REPO_ROOT/target/template-artifact-eval"
rm -rf "$WORK"; mkdir -p "$WORK/tests" "$WORK/src" "$WORK/overlay"
trap 'rm -rf "$WORK"' EXIT

for path in "$EDT_HOME/plugins" "$JDK_HOME/bin" "$JUNIT" "$HAMCREST"; do
    [ -e "$path" ] || { echo "НЕ НАЙДЕНО: $path" >&2; exit 2; }
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

BEHAVIOUR_TEST=com.codepilot1c.core.edt.metadata.EdtMetadataServiceTemplateArtifactTest
UNIT_TEST=com.codepilot1c.core.edt.metadata.TemplateArtifactTest
if [ "$MODE" = "run" ] || [ "$MODE" = "sabotage" ]; then
    TEST_CLASSES=("$BEHAVIOUR_TEST" "$UNIT_TEST")
else
    TEST_CLASSES=("$BEHAVIOUR_TEST")
fi

# Тесты компилируются один раз: саботаж меняет продакшн-классы, а не тесты. Ошибки javac видны
# целиком — сломанный тест иначе выглядит как ClassNotFoundException.
SOURCES=()
for class in "${TEST_CLASSES[@]}"; do SOURCES+=("$TESTS/${class//.//}.java"); done
"$JDK_HOME/bin/javac" -encoding UTF-8 -nowarn -d "$WORK/tests" -cp "$CP" "${SOURCES[@]}" 2> "$WORK/javac-tests.log" || {
    cat "$WORK/javac-tests.log" >&2
    echo "набор тестов не скомпилировался" >&2
    exit 1
}

run_suite() {
    "$JDK_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$WORK/overlay;$WORK/tests;$CP" org.junit.runner.JUnitCore "${TEST_CLASSES[@]}"
}

if [ "$MODE" = "run" ] || [ "$MODE" = "classes" ]; then
    run_suite
    exit $?
fi

if [ "$MODE" = "baseline" ]; then
    # Сборка до фикса обязана покраснеть ровно на симптоме, а не на чём попало: красный прогон из-за
    # сломанного classpath доказал бы только classpath. Поэтому сверяются имена упавших тестов.
    EXPECTED_RED=(
        textTemplateGetsEmptyTemplateTxt
        binaryTemplateGetsEmptyTemplateBin
        htmlTemplateGetsHtmlDocumentDescriptorWithoutPages
        activeDocumentTemplateIsNotGivenAMoxcelFile
        edtTemplateTypeNamesResolveToTheirTypesNotToSpreadsheet
        activeDocumentIsRefusedBeforeTheTemplateIsCreated
        unknownTemplateTypeIsRefusedInsteadOfBecomingSpreadsheet
        unsupportedTypeOfABatchChildIsRefused
    )
    EXPECTED_GREEN=(
        spreadsheetTemplateKeepsTemplateMxl
        dcsTemplateGetsNoArtifactHere
        everyAdvertisedTemplateTypeIsAccepted
    )
    set +e
    run_suite > "$WORK/baseline.txt" 2>&1
    rc=$?
    set -e
    # Имя упавшего теста и первая строка причины: красный из-за NoSuchMethodException или
    # сломанного classpath выглядел бы здесь иначе, чем «ожидался Template.txt, создан Template.mxl».
    awk '/^[0-9]+\) / { print; getline; print "     " substr($0, 1, 220) } /^Tests run|^OK \(/ { print }' "$WORK/baseline.txt"
    PROBLEMS=0
    [ "$rc" -ne 0 ] || { echo "🚨 сборка до фикса ЗЕЛЁНАЯ — eval не воспроизводит дефект"; PROBLEMS=1; }
    for test in "${EXPECTED_RED[@]}"; do
        grep -a -q -E "^[0-9]+\) $test\(" "$WORK/baseline.txt" || { echo "🚨 не покраснел: $test"; PROBLEMS=1; }
    done
    for test in "${EXPECTED_GREEN[@]}"; do
        if grep -a -q -E "^[0-9]+\) $test\(" "$WORK/baseline.txt"; then
            echo "🚨 покраснел то, что до фикса работало: $test"; PROBLEMS=1
        fi
    done
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
    for jar in "$EDT_HOME"/plugins/*.jar; do printf ';%s' "$jar"; done
    for dir in "$EDT_HOME"/plugins/*/; do printf ';%s' "${dir%/}"; done
    for jar in "$REPO_ROOT"/bundles/com.codepilot1c.core/lib/*.jar; do [ -e "$jar" ] && printf ';%s' "$jar"; done
    printf '"\n'
} > "$WORK/ecj-classpath.args"

echo "=== САБОТАЖ-КОНТРОЛЬ артефакта макета: каждый дефект обязан дать красный ==="
FAILED_TO_CATCH=0
SABOTAGES=0

# sabotage <метка> <файл относительно src> <компилятор javac|ecj> <perl-выражение>
sabotage() {
    local label="$1" rel="$2" compiler="$3" expr="$4"
    SABOTAGES=$((SABOTAGES + 1))
    rm -rf "$WORK/src" "$WORK/overlay"; mkdir -p "$WORK/src/$(dirname "$rel")" "$WORK/overlay"
    cp "$SRC/$rel" "$WORK/src/$rel"
    perl -0pi -e "$expr" "$WORK/src/$rel"
    echo
    echo "--- саботаж: $label"
    if cmp -s "$SRC/$rel" "$WORK/src/$rel"; then
        echo "🚨 НЕ ПРИМЕНЁН: выражение не нашло текст в $rel — саботаж ничего не проверил"
        FAILED_TO_CATCH=$((FAILED_TO_CATCH + 1))
        return
    fi
    local rc=0
    if [ "$compiler" = "ecj" ]; then
        "$JDK_HOME/bin/java" -jar "$ECJ" -17 -encoding UTF-8 -nowarn -proc:none -d "$WORK/overlay" \
            "@$WORK/ecj-classpath.args" "$WORK/src/$rel" > "$WORK/compile.log" 2>&1 || rc=$?
    else
        "$JDK_HOME/bin/javac" -encoding UTF-8 -nowarn -d "$WORK/overlay" -cp "$CP" "$WORK/src/$rel" \
            > "$WORK/compile.log" 2>&1 || rc=$?
    fi
    if [ "$rc" -ne 0 ]; then
        # Несобравшийся саботаж не проверяет тесты — это дефект самого контроля.
        echo "🚨 НЕ СОБРАЛСЯ саботированный $rel (rc=$rc) — контроль недостоверен"
        tail -5 "$WORK/compile.log"
        FAILED_TO_CATCH=$((FAILED_TO_CATCH + 1))
        return
    fi
    set +e
    run_suite > "$WORK/out.txt" 2>&1
    rc=$?
    set -e
    if [ "$rc" -eq 0 ]; then
        echo "🚨 НЕ ПОЙМАН: $label — набор остался зелёным"
        FAILED_TO_CATCH=$((FAILED_TO_CATCH + 1))
    else
        # -a: в сообщении теста бывают NUL-байты заголовка MOXCEL, без него grep видит «Binary file».
        echo "ok (красный): $(grep -a -m1 -E '^Tests run' "$WORK/out.txt")"
    fi
}

ART="com/codepilot1c/core/edt/metadata/TemplateArtifact.java"
REQ="com/codepilot1c/core/edt/metadata/AddMetadataChildRequest.java"
SVC="com/codepilot1c/core/edt/metadata/EdtMetadataService.java"

# Исходный дефект: путь артефакта снова зашит как Template.mxl для любого типа.
sabotage "путь артефакта зашит как Template.mxl (дефект до фикса)" "$SVC" ecj \
    's/String templatePath = artifact\.relativePath\(topFolder, topName, templateName\);/String templatePath = "src\/" + topFolder + "\/" + topName + "\/Templates\/" + templateName + "\/Template.mxl";/'

# Исходный дефект: содержимое любого типа снова строится как MOXCEL.
sabotage "любой тип сериализуется как MOXCEL" "$SVC" ecj \
    's/if \(artifact != TemplateArtifact\.SPREADSHEET\) \{/if (false) {/'

# Текстовый макет снова получает Template.mxl — уже через таблицу.
sabotage "таблица: текстовому макету Template.mxl" "$ART" javac \
    's/TEXT\("Template\.txt", ""\)/TEXT("Template.mxl", "")/'

# HTML-дескриптор в чужом пространстве имён — импортёр EDT его не узнает.
sabotage "HTML-дескриптор не в пространстве имён EDT" "$ART" javac \
    's#http://g5\.1c\.ru/v8/dt/html-document#http://g5.1c.ru/v8/dt/htmldocument#'

# Неизвестный template_type снова молча становится табличным документом.
sabotage "неизвестный тип молча становится табличным" "$ART" javac \
    's/default -> throw new MetadataOperationException\(\s*MetadataOperationCode\.INVALID_PROPERTY_VALUE,\s*PROPERTY \+ "=" \+ raw \+ " is not a template type[^;]*;/default -> TemplateType.SPREADSHEET_DOCUMENT;/s'

# Активный документ и прочие неподдержанные типы снова получают табличный файл.
sabotage "неподдержанный тип не отклоняется" "$ART" javac \
    's/default -> throw new MetadataOperationException\(\s*MetadataOperationCode\.INVALID_PROPERTY_VALUE,\s*PROPERTY \+ "=" \+ type\.getLiteral\(\)[^;]*;/default -> SPREADSHEET;/s'

# Имена EDT (TextDocument из .mdo) снова не распознаются.
sabotage "имя EDT TextDocument не распознаётся" "$ART" javac \
    's/case "text", "textdocument", /case "text", /'

# Дети пакета снова не разбираются — их неподдержанный тип проходит проверку.
sabotage "дети пакета children не проверяются" "$ART" javac \
    's/Object rawChildren = request\.properties\(\) == null \? null : request\.properties\(\)\.get\("children"\);/Object rawChildren = null;/'

# Запрос снова не проверяет тип макета до мутации BM.
sabotage "validate() не проверяет тип макета" "$REQ" javac \
    's/TemplateArtifact\.requestedTemplates\(this\);//'

# BM-мутация снова разбирает template_type мимо общего разбора.
sabotage "resolveTemplateType мимо общего разбора" "$SVC" ecj \
    's/return TemplateArtifact\.resolveType\(properties\);/return com._1c.g5.v8.dt.metadata.mdclass.TemplateType.SPREADSHEET_DOCUMENT;/'

echo
if [ "$FAILED_TO_CATCH" -ne 0 ]; then
    echo "САБОТАЖ-КОНТРОЛЬ ПРОВАЛЕН: не пойманных или неприменённых дефектов $FAILED_TO_CATCH из $SABOTAGES"
    exit 1
fi
echo "САБОТАЖ-КОНТРОЛЬ: все $SABOTAGES дефектов пойманы"
