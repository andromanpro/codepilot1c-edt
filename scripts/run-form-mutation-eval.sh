#!/usr/bin/env bash
# Прогон регрессии пробелов mutate_form_model без Tycho (Gitea androman/codepilot1c#109).
#
# Что защищает набор — четыре дефекта, найденные 2026-09-14 на формах wms.tnext:
#   1. excludedCommands формы и таблицы отбивались «Reference property updates are not
#      supported directly» и валили весь пакет;
#   2. titleDataPath страницы и обычной группы — та же ошибка;
#   3. автокомандная панель (ФормаКоманднаяПанель, <Таблица>КоманднаяПанель) была
#      недостижима: не родитель для move_item, кнопка в ней не находилась, add_button без
#      родителя падал в корень формы, inspect_form_layout панели не показывал;
#   4. заготовки серверных событий формы (ПриСозданииНаСервере, ПриЧтенииНаСервере)
#      писались под &НаКлиенте — диагностика EDT молчит, форма падает при открытии.
#
# Тесты вызывают НАСТОЯЩИЙ EdtMetadataService, собранный Tycho (ECJ), рефлексией —
# javac этот файл не компилирует (instanceof-паттерны с выражением-подтипом), поэтому
# копия логики в тесте доказывала бы копию. Модуль com.codepilot1c.core.tests под
# EDT 2025.2.3 целиком не собирается (pre-existing), отдельные тесты — собираются.
#
# Использование:
#   bash scripts/run-form-mutation-eval.sh                    # обычный прогон (ожидается зелёный)
#   bash scripts/run-form-mutation-eval.sh --classes <каталог> # против других классов, напр.
#                                                              # сохранённых до фикса: ожидается красный
#   bash scripts/run-form-mutation-eval.sh --sabotage          # контроль: каждый дефект обязан дать красный
set -euo pipefail

# Пути к JVM-инструментам обязаны быть в форме Windows: Git Bash отдаёт POSIX
# (/f/..., /c/...), javac и java такой classpath молча принимают и не находят по
# нему ни одного класса.
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

WORK="$REPO_ROOT/target/form-mutation-eval"
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
        *) echo "неизвестный аргумент: $1" >&2; exit 2 ;;
    esac
done

[ -d "$CLASSES" ] || { echo "ОТКАЗ: нет $CLASSES. Сначала: mvn -DskipTests -Dedt.home=<Eclipse> -pl '!:com.codepilot1c.core.tests' package" >&2; exit 2; }

if [ "$MODE" != "classes" ]; then
    # Свежесть обязательна и проверяется: прогон против устаревших классов зелёный ровно
    # так же, как против свежих. Точка отсчёта — jar последней сборки, а не каталог classes.
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
CP="$CLASSES;$CP"

TEST_CLASSES=(
    com.codepilot1c.core.edt.metadata.FormMutationGapsTest
    com.codepilot1c.core.edt.metadata.FormItemServicePathTest
    com.codepilot1c.core.edt.forms.BslHandlerStubGeneratorTest
    com.codepilot1c.core.edt.metadata.EventHandlerWiringTest
    com.codepilot1c.core.edt.metadata.EventHandlerInspectTest
)
test_sources() {
    local class
    for class in "${TEST_CLASSES[@]}"; do
        printf '%s\n' "$TESTS/${class//.//}.java"
    done
}

# Тесты компилируются один раз: саботаж меняет продакшн-классы, а не тесты. Ошибки javac
# показываются целиком — сломанный тест иначе выглядит как ClassNotFoundException.
mapfile -t SOURCES < <(test_sources)
"$JDK_HOME/bin/javac" -encoding UTF-8 -nowarn -d "$WORK/tests" -cp "$CP" "${SOURCES[@]}" 2> "$WORK/javac-tests.log" || {
    cat "$WORK/javac-tests.log" >&2
    echo "набор тестов не скомпилировался" >&2
    exit 1
}

run_suite() {
    "$JDK_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$WORK/overlay;$WORK/tests;$CP" org.junit.runner.JUnitCore "${TEST_CLASSES[@]}"
}

if [ "$MODE" != "sabotage" ]; then
    rm -rf "$WORK/overlay"; mkdir -p "$WORK/overlay"
    run_suite
    exit $?
fi

# ─── саботаж ──────────────────────────────────────────────────────────────

[ -f "$ECJ" ] || { echo "НЕ НАЙДЕНО: $ECJ (нужен для саботажа EdtMetadataService)" >&2; exit 2; }

# argfile для ECJ: маску plugins/* он не раскрывает, а список jar-ов не влезает в командную
# строку Windows. Весь classpath — одной строкой в кавычках: без них ECJ режет по пробелу
# в «Program Files».
{
    printf -- '-classpath\n"%s' "$CLASSES"
    for jar in "$EDT_HOME"/plugins/*.jar; do printf ';%s' "$jar"; done
    for dir in "$EDT_HOME"/plugins/*/; do printf ';%s' "${dir%/}"; done
    for jar in "$REPO_ROOT"/bundles/com.codepilot1c.core/lib/*.jar; do [ -e "$jar" ] && printf ';%s' "$jar"; done
    printf '"\n'
} > "$WORK/ecj-classpath.args"

echo "=== САБОТАЖ-КОНТРОЛЬ пробелов mutate_form_model: каждый дефект обязан дать красный ==="
FAILED_TO_CATCH=0

# sabotage <метка> <файл относительно src> <компилятор javac|ecj> <perl-выражение>
sabotage() {
    local label="$1" rel="$2" compiler="$3" expr="$4"
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
        echo "ok (красный): $(grep -m1 -E '^Tests run' "$WORK/out.txt")"
    fi
}

GEN="com/codepilot1c/core/edt/forms/BslHandlerStubGenerator.java"
EXCL="com/codepilot1c/core/edt/forms/FormStandardCommandExclusions.java"
TREE="com/codepilot1c/core/edt/forms/FormItemTree.java"
SVC="com/codepilot1c/core/edt/metadata/EdtMetadataService.java"

# 4. Исходный дефект заготовок: директива только из сред события, правило имени снято.
sabotage "директива серверного события без правила имени (дефект до 2026-09-14)" "$GEN" javac \
    's/if \(isServerEventByName\(event\)\) \{\s*return Directive\.AT_SERVER;\s*\}//'

# 1. Неизвестная стандартная команда молча пропускается: на форме «только просмотр»
#    остаётся рабочая кнопка «Удалить».
sabotage "неизвестная стандартная команда пропускается молча" "$EXCL" javac \
    's/if \(!unknown\.isEmpty\(\)\) \{/if (false) {/'

# 1. Исходный дефект: excludedCommands снова уходит в общий отказ для ссылок.
sabotage "excludedCommands снова отбивается как ссылка" "$SVC" ecj \
    's/if \(target instanceof FormStandardCommandSource source\s*&& FormStandardCommandExclusions\.isExcludedCommands\(reference\)\) \{\s*FormStandardCommandExclusions\.apply\(source, value, fieldName\);\s*return;\s*\}//s'

# 2. Исходный дефект: titleDataPath снова отбивается как ссылка.
sabotage "titleDataPath снова отбивается как ссылка" "$SVC" ecj \
    's/if \(!reference\.isMany\(\) && reference\.isContainment\(\)\s*&& AbstractDataPath\.class\.isAssignableFrom\(reference\.getEReferenceType\(\)\.getInstanceClass\(\)\)\) \{\s*applyDataPathReferenceValue\(target, reference, value, fieldName\);\s*return;\s*\}//s'

# 3. Исходный дефект: поиск видит только элементы списков getItems(), панель — нет.
sabotage "поиск не видит служебных частей (автопанель)" "$TREE" javac \
    's/(if \(contents\.next\(\) instanceof FormItem item\) \{\n(\s*))(if \(id != null && item\.getId\(\) == id\.intValue\(\)\))/$1if (itemsOwner(item) == null) {\n$2    continue;\n$2}\n$2$3/'

# 3. Исходный дефект: кнопка без родителя уходит в корень формы.
sabotage "add_button без родителя — в корень формы" "$TREE" javac \
    's/if \(form\.getAutoCommandBar\(\) != null\) \{\s*return form\.getAutoCommandBar\(\);\s*\}//'

# 3. Перенос в панель без смены типа кнопки — маркер SU107 «Unsupported button type».
sabotage "тип кнопки не подгоняется под контейнер" "$TREE" javac \
    's/(ManagedFormButtonType type = button\.getType\(\);)/$1\n        if (true) {\n            return;\n        }/'

# 3. id нового элемента снова не учитывает панели и контекстные меню — дубль id.
sabotage "id считается без служебных частей" "$TREE" javac \
    's/maxId = Math\.max\(maxId, item\.getId\(\)\);/maxId = Math.max(maxId, 0);/'

# 3. inspect_form_layout снова не показывает автопанель.
sabotage "inspect не показывает автопанель" "$SVC" ecj \
    's/if \(container instanceof CommandBarHolder holder && holder\.getAutoCommandBar\(\) != null\) \{/if (false && container instanceof CommandBarHolder holder && holder.getAutoCommandBar() != null) {/'

# 3. Служебную часть снова можно «удалить»: удаление молча ничего не делает.
sabotage "автопанель не защищена от remove/move" "$SVC" ecj \
    's/if \(FormItemTree\.isStructuralPart\(item\)\) \{/if (false) {/'

# Найдено живой проверкой 15.09: EDT называет кнопку по команде, запрошенное имя терялось.
sabotage "запрошенное имя элемента не применяется после EDT-сервиса" "$SVC" ecj \
    's/(private void applyRequestedItemName\(FormItem item, FormItemContainer parent, Form formModel, String name,\s*IFormItemNamingService namingService\) \{)/$1\n        if (true) {\n            return;\n        }/'

# Найдено второй живой проверкой 15.09: setUniqueNameWithChildren применяет соглашение EDT и в
# командной панели формы давал ФормаКнопкаОбновить вместо запрошенного КнопкаОбновить.
sabotage "запрошенное имя ставится по соглашению EDT, а не ровно" "$SVC" ecj \
    's/namingService\.rename\(name, item\);/namingService.setUniqueNameWithChildren(name, item, parent, formModel);/'

# Найдено живой проверкой 15.09: путь .mdo владельца из BM-URI, ложный FORM_MATERIALIZATION_TIMEOUT.
sabotage "путь .mdo владельца берётся из URI без проверки" "$SVC" ecj \
    's/(String ownerMdoPathFromUri\(IProject project, URI ownerUri\) \{\s*String resourcePath = toProjectRelativePath\(project, ownerUri\);)/$1\n        if (true) {\n            return resourcePath;\n        }/'

echo
if [ "$FAILED_TO_CATCH" -ne 0 ]; then
    echo "САБОТАЖ-КОНТРОЛЬ ПРОВАЛЕН: не пойманных или неприменённых дефектов $FAILED_TO_CATCH"
    exit 1
fi
echo "САБОТАЖ-КОНТРОЛЬ: все 13 дефектов пойманы"
