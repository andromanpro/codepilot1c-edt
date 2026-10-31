#!/usr/bin/env bash
# Карта дельты форка перед форвард-портом на новый upstream.
#
# Зачем: мердж 2026-06-11 (da3c65b) молча взял upstream-версию файла и потерял
# целую фичу; порт 2026-07-27 показал, что грепать надо по СИГНАТУРАМ
# (имя типа / метода / константы), а не по тексту комментариев: комментарий
# может уцелеть при выброшенном коде, а метод может быть переименован
# upstream'ом и выглядеть «потерянным», хотя он на месте.
#
# Что делает: собирает маркеры из НАШЕЙ дельты (merge-base..main) и считает
# каждый в трёх состояниях — upstream, наш main и (после мерджа) рабочее дерево.
# Отдельно проверяет подключение: форк-онли тул и форк-онли тип обязаны быть
# вызваны из кода вне своего объявления, иначе ПОДКЛЮЧЕНИЕ ПОТЕРЯНО и код 1 —
# потерянное место вызова при живом классе счётчик маркеров не видит.
#
#   bash forward-port-delta-map.sh                    # до мерджа: upstream vs наш main
#   bash forward-port-delta-map.sh --worktree <путь>  # после мерджа: проверка выживания
#   bash forward-port-delta-map.sh --all              # печатать и общие маркеры
#   DUMP_ENGINE=fixed bash forward-port-delta-map.sh  # прежний дамп списком -F -f: git без PCRE2, десятки минут
set -uo pipefail

# Пути к JVM/скриптам в форме Windows: Git Bash отдаёт POSIX (/f/..., /c/...),
# нативные инструменты такой путь молча принимают и ничего по нему не находят.
win_path() { command -v cygpath >/dev/null 2>&1 && cygpath -m "$1" || printf '%s' "$1"; }

REPO="${REPO:-$(win_path "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)")}"
UP="${UP:-origin/main}"
OURS="${OURS:-main}"
WORK="${WORK:-$REPO/target/delta-map}"

# Лаунчер python: на Windows это `py`, в CI/на Linux — python3.
if [ -z "${PY:-}" ]; then
    if command -v py >/dev/null 2>&1; then PY="py -3.14"
    elif command -v python3 >/dev/null 2>&1; then PY="python3"
    else PY="python"; fi
fi

WORKTREE=""; SHOW_ALL=0
while [ $# -gt 0 ]; do
    case "$1" in
        --worktree) WORKTREE="$2"; shift 2 ;;
        --all) SHOW_ALL=1; shift ;;
        *) echo "неизвестный аргумент: $1" >&2; exit 2 ;;
    esac
done

cd "$REPO" || exit 2
mkdir -p "$WORK"
MB="$(git merge-base "$OURS" "$UP")"

echo "=== КАРТА ДЕЛЬТЫ ФОРКА ==="
echo "merge-base : ${MB:0:12}  $(git log -1 --format='%ad %s' --date=short "$MB" | cut -c1-58)"
echo "upstream   : $UP  $(git log -1 --format='%ad' --date=short "$UP")"
echo "наш        : $OURS  $(git log -1 --format='%ad' --date=short "$OURS")"
[ -n "$WORKTREE" ] && echo "рабочее дерево: $WORKTREE"
echo

EXTRACT="$WORK/extract-markers.py"
cat > "$EXTRACT" <<'PYEOF'
"""Имена типов, методов и констант из добавленных строк дельты."""
import re, sys

# newline=chr(10): на Windows print() иначе допишет CR, маркеры уедут с ним,
# grep -F станет искать "LOG\r" и вернёт честный ноль по всему списку — карта
# выйдет из сплошных нулей и будет выглядеть достоверно (наступлено 2026-08-27).
sys.stdout.reconfigure(encoding='utf-8', newline=chr(10))

path = sys.argv[1]
# Второй аргумент (необязательный) — файл, куда дописать имена ТИПОВ отдельно:
# по ним идёт проверка подключения, а markers.tsv свой формат сохраняет.
types_path = sys.argv[2] if len(sys.argv) > 2 else None
TYPE = re.compile(r'\b(?:class|record|enum|interface)\s+([A-Z][A-Za-z0-9_]*)')
CONST = re.compile(r'\bstatic\s+final\s+[^=;]*?\b([A-Z][A-Z0-9_]{2,})\s*=')
METH = re.compile(r'^\s*(?:@\w+\s+)*(?:public|private|protected)\s[^=;()]*?\b([a-z][A-Za-z0-9_]*)\s*\(')
SKIP = {'get', 'set', 'is', 'to', 'of', 'new', 'run', 'main', 'test', 'equals',
        'hashCode', 'toString', 'value', 'values', 'name', 'apply', 'accept',
        'execute', 'doExecute', 'getDescription', 'getParameterSchema', 'getName'}

out = set(); types = set()
for line in sys.stdin:
    if not line.startswith('+') or line.startswith('+++'):
        continue
    body = line[1:]
    for rx in (TYPE, CONST):
        for hit in rx.finditer(body):
            out.add(hit.group(1))
            if rx is TYPE:
                types.add(hit.group(1))
    hit = METH.match(body)
    if hit and hit.group(1) not in SKIP:
        out.add(hit.group(1))

for marker in sorted(out):
    sys.stdout.write(path + chr(9) + marker + chr(10))
if types_path:
    with open(types_path, 'a', encoding='utf-8', newline=chr(10)) as fh:
        for marker in sorted(types):
            fh.write(path + chr(9) + marker + chr(10))
PYEOF

git diff --name-only "$MB" "$OURS" -- '*.java' > "$WORK/files.txt"
: > "$WORK/markers.tsv"; : > "$WORK/types.tsv"
while IFS= read -r f; do
    git diff "$MB" "$OURS" -- "$f" | $PY "$EXTRACT" "$f" "$WORK/types.tsv" >> "$WORK/markers.tsv"
done < "$WORK/files.txt"

cut -f2 "$WORK/markers.tsv" | sort -u > "$WORK/patterns.txt"
echo "маркеров извлечено: $(wc -l < "$WORK/patterns.txt") уникальных из $(wc -l < "$WORK/files.txt") наших java-файлов"

# ── Подключение: ссылка из кода, а не упоминание имени ───────────────────────
# Маркерный счётчик отвечает на «имя где-то есть», а не на «класс кто-то зовёт».
# Синк, потерявший МЕСТО ВЫЗОВА форк-онли класса при живом объявлении, оставляет
# имя в файле класса, в тесте и в комментарии над бывшим вызовом — карта
# покажет «жив». Поэтому ссылкой здесь считается только код: имя целым словом,
# без import, комментариев и строковых литералов, вне тела самого типа, в
# продовом коде и дескрипторах бандлов. Тесты не в счёт: тест, зовущий класс, к
# плагину его не подключает. scripts/ тоже не в счёт — там имена живут в
# комментариях и заданиях, в том числе в этом файле (проба самотеста ниже).
CODE_SCOPE=(':(glob)bundles/*/src/**' ':(glob)bundles/*/plugin.xml' ':(glob)bundles/*/META-INF/MANIFEST.MF'
    cli runtime ':(exclude,glob)bundles/*.tests/**' ':(exclude,glob)**/src/test/**' ':(exclude,glob)**/*.md')

hits_of() { # <где> <признак rev|tree> <имя> -> «путь:строка:текст», имя целым словом
    if [ "$2" = "tree" ]; then
        ( cd "$1" && git grep -n -w -F -e "$3" -- "${CODE_SCOPE[@]}" ) 2>/dev/null
    else
        # Префикс ревизии срезается index(), а не sed: в имени ревизии бывают
        # символы регулярок (v1.3.9.…, main^{tree}).
        git grep -n -w -F -e "$3" "$1" -- "${CODE_SCOPE[@]}" 2>/dev/null \
            | awk -v p="$1:" 'index($0, p) == 1 { $0 = substr($0, length(p) + 1) } { print }'
    fi
    :
}
src_show() { # <где> <признак rev|tree> <путь> -> содержимое файла
    if [ "$2" = "tree" ]; then cat "$1/$3"; else git show "$1:$3"; fi 2>/dev/null
}

# Кодовая часть строки. Приближение построчное: строка многострочного /* */ без
# ведущей «*» и строка текстового блока сойдут за код — комментарии в репо ведёт
# форматтер Eclipse со «*». Апостроф — \047: программа живёт в одинарных кавычках.
CODE_OF_AWK='
function code_of(s, java,    t, out, i, n, c, q) {
    t = s; sub(/^[ \t]+/, "", t)
    if (t ~ /^(\*|\/\/|\/\*|<!--|#)/ || t ~ /^(import|package)[ \t]/) return ""
    if (!java) return s
    out = ""; n = length(s); i = 1
    while (i <= n) {
        c = substr(s, i, 1)
        if (substr(s, i, 2) == "//") break
        if (substr(s, i, 2) == "/*") {
            q = index(substr(s, i + 2), "*/"); if (!q) break
            i += q + 3; out = out " "; continue
        }
        if (c == "\"" || c == "\047") {
            q = c; i++
            while (i <= n && substr(s, i, 1) != q) { if (substr(s, i, 1) == "\\") i++; i++ }
            i++; out = out " "; continue
        }
        out = out c; i++
    }
    return out
}
function split_hit(line,    q) { # «путь:строка:текст» -> HIT_PATH, HIT_LINE, HIT_TEXT
    q = index(line, ":"); HIT_PATH = substr(line, 1, q - 1); line = substr(line, q + 1)
    q = index(line, ":"); HIT_LINE = substr(line, 1, q - 1) + 0; HIT_TEXT = substr(line, q + 1)
}
'
# Строка, где закрывается тело типа, объявленного в строке from (0 — не нашлось).
# Скобки в строках, символах, текстовых блоках и комментариях не считаются:
# фигурная скобка JSON-схемы в описании тула иначе закрыла бы тело раньше.
BODY_END_AWK='
BEGIN { depth = 0; opened = 0; blk = 0; txt = 0; found = 0 }
NR < from { next }
{
    s = $0; n = length(s); i = 1
    while (i <= n) {
        c = substr(s, i, 1)
        if (blk) { if (substr(s, i, 2) == "*/") { blk = 0; i += 2 } else i++; continue }
        if (txt) { if (substr(s, i, 3) == "\"\"\"") { txt = 0; i += 3 } else if (c == "\\") i += 2; else i++; continue }
        if (substr(s, i, 2) == "//") break
        if (substr(s, i, 2) == "/*") { blk = 1; i += 2; continue }
        if (substr(s, i, 3) == "\"\"\"") { txt = 1; i += 3; continue }
        if (c == "\"" || c == "\047") {
            q = c; i++
            while (i <= n && substr(s, i, 1) != q) { if (substr(s, i, 1) == "\\") i++; i++ }
            i++; continue
        }
        if (c == "{") { depth++; opened = 1 }
        else if (c == "}" && opened && --depth == 0) { print NR; found = 1; exit }
        i++
    }
}
END { if (!found) print 0 }
'

type_refs_of() { # <где> <признак rev|tree> <тип> -> пути со ссылкой из кода; объявления — $WORK/type.decls
    local p l end
    hits_of "$1" "$2" "$3" > "$WORK/type.hits"
    awk -v name="$3" "$CODE_OF_AWK"'
        { split_hit($0)
          if (code_of(HIT_TEXT, HIT_PATH ~ /\.java$/) ~ ("(^|[^A-Za-z0-9_$])(class|record|enum|interface)[ \t]+" name "([^A-Za-z0-9_$]|$)"))
              print HIT_PATH "\t" HIT_LINE }' "$WORK/type.hits" > "$WORK/type.decls"
    : > "$WORK/type.extents"
    while IFS=$'\t' read -r p l; do
        # Тип верхнего уровня занимает свой файл целиком, вложенный — своё тело во
        # внешнем файле. Вложенный там же и зовут: исключи файл целиком — и
        # каждый вспомогательный record окажется «потерянным» (замер 15.09: восемь
        # из двенадцати вложенных продовых форк-онли типов вне своего файла не
        # упоминаются).
        if [ "${p##*/}" = "$3.java" ]; then l=1; end=999999999
        else
            end="$(src_show "$1" "$2" "$p" | awk -v from="$l" "$BODY_END_AWK")"
            # Тело не нашлось — исключается весь файл: проверка строже, а не слепее.
            [ "${end:-0}" -gt 0 ] 2>/dev/null || { l=1; end=999999999; }
        fi
        printf '%s\t%s\t%s\n' "$p" "$l" "$end" >> "$WORK/type.extents"
    done < "$WORK/type.decls"
    # FILENAME, а не NR==FNR: пустой список объявлений иначе принял бы находки за него.
    awk -F'\t' -v name="$3" "$CODE_OF_AWK"'
        FILENAME == ARGV[1] { k = ++cnt[$1]; lo[$1, k] = $2 + 0; hi[$1, k] = $3 + 0; next }
        { split_hit($0)
          for (k = 1; k <= cnt[HIT_PATH]; k++) if (HIT_LINE >= lo[HIT_PATH, k] && HIT_LINE <= hi[HIT_PATH, k]) next
          if (code_of(HIT_TEXT, HIT_PATH ~ /\.java$/) ~ ("(^|[^A-Za-z0-9_$])" name "([^A-Za-z0-9_$]|$)"))
              print HIT_PATH }' "$WORK/type.extents" "$WORK/type.hits" | sort -u
}
wiring_of() { # <rev|дерево> <класс> <признак дерева> -> файлы, откуда класс зовут
    if [ "$3" = "tree" ]; then type_refs_of "$1" tree "$2"; else type_refs_of "$1" rev "$2"; fi
}
type_wiring() { # <где> <признак rev|tree> <тип> -> строка вердикта; код 1 = подключение потеряно
    local refs
    refs="$(type_refs_of "$1" "$2" "$3" | sed 's|.*/||' | sort -u | tr '\n' ' ')"
    if [ ! -s "$WORK/type.decls" ]; then
        printf "  %-42s ПОДКЛЮЧЕНИЕ ПОТЕРЯНО: объявления нет, имя осталось только в тексте\n" "$3"
        return 1
    fi
    if [ -z "$refs" ]; then
        printf "  %-42s ПОДКЛЮЧЕНИЕ ПОТЕРЯНО: объявлен в %s— ссылок из кода вне объявления нет\n" "$3" \
            "$(cut -f1 "$WORK/type.decls" | sed 's|.*/||' | sort -u | tr '\n' ' ')"
        return 1
    fi
    printf "  %-42s подключён: %s\n" "$3" "$refs"
}

# ── Негативный самотест подключения ──────────────────────────────────────────
# Прецедент 2026-09-15 (Gitea #115/#116): при порте вызов
# McpHostConfirmationPolicy.refusesUnconfirmable(...) в McpHostRequestRouter
# конфликтовал с проверкой validation_token upstream. Потерянный вызов молча
# вернул бы #85 — без профиля хост отбивает тулы с подтверждением
# (confirmation_unavailable_tool_policy), — а маркерная карта потерю не видит.
# Самотест ставит в роутер дерева $OURS условие upstream (комментарий с именем
# класса над ним остаётся нарочно) и требует ПОДКЛЮЧЕНИЕ ПОТЕРЯНО; нетронутый
# $OURS обязан дать «подключён» — иначе проверка, всегда отвечающая «потеряно»,
# прошла бы самотест даром. Дерево собирается во временном индексе без checkout
# (git grep ищет по объекту дерева так же, как по ревизии; объекты недостижимы,
# их уберёт gc). Идёт до дампов: дампы занимают минуты, самотест — секунды.
PROBE_TYPE="McpHostConfirmationPolicy"
PROBE_FILE="bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/host/McpHostRequestRouter.java"
probe_fail() { echo "САМОТЕСТ ПОДКЛЮЧЕНИЯ ПРОВАЛЕН: $1" >&2; exit 1; }
git show "$OURS:$PROBE_FILE" > "$WORK/probe.orig.java" 2>/dev/null \
    && grep -q 'McpHostConfirmationPolicy\.refusesUnconfirmable(' "$WORK/probe.orig.java" \
    || probe_fail "в $OURS нет вызова $PROBE_TYPE.refusesUnconfirmable в роутере — проба устарела, заменить"
# Условие upstream v1.3.9.20260913-1332: requiresConfirmation() без политики форка.
awk '
    skip && /\)[ \t\r]*$/ { skip = 0; next }
    skip { next }
    /McpHostConfirmationPolicy\.refusesUnconfirmable\(/ {
        sub(/McpHostConfirmationPolicy\.refusesUnconfirmable\(.*/, "effectivePolicy.requiresConfirmation()")
        print; skip = 1; next
    }
    { print }' "$WORK/probe.orig.java" > "$WORK/probe.cut.java"
[ $(( $(wc -l < "$WORK/probe.orig.java") - $(wc -l < "$WORK/probe.cut.java") )) -eq 1 ] \
    && ! grep -q 'refusesUnconfirmable' "$WORK/probe.cut.java" \
    || probe_fail "саботаж лёг не так, как задуман (формат вызова в роутере изменился) — поправить пробу"
rm -f "$WORK/probe.index"
probe_mode="$(git ls-tree "$OURS" -- "$PROBE_FILE" | cut -d' ' -f1)"
probe_blob="$(git hash-object -w --no-filters "$WORK/probe.cut.java")" \
    && GIT_INDEX_FILE="$WORK/probe.index" git read-tree "$OURS" \
    && GIT_INDEX_FILE="$WORK/probe.index" git update-index --cacheinfo "$probe_mode,$probe_blob,$PROBE_FILE" \
    && probe_tree="$(GIT_INDEX_FILE="$WORK/probe.index" git write-tree)" \
    || probe_fail "не собралось дерево с саботажем"
rm -f "$WORK/probe.index"
probe_ok="$(type_wiring "$OURS" rev "$PROBE_TYPE")" \
    || probe_fail "на нетронутом $OURS проверка не видит подключения: $probe_ok"
case "$probe_ok" in *McpHostRequestRouter.java*) ;; *) probe_fail "подключение нашлось не через роутер: $probe_ok" ;; esac
probe_cut="$(type_wiring "$probe_tree" rev "$PROBE_TYPE")" \
    && probe_fail "роутер без вызова, а проверка видит подключение: $probe_cut"
# Именно этот вердикт: «объявления нет» значило бы, что поиск по объекту дерева
# не сработал вовсе, и «потеряно» получилось бы даром.
case "$probe_cut" in
    *"ПОДКЛЮЧЕНИЕ ПОТЕРЯНО: объявлен в $PROBE_TYPE.java"*) ;;
    *) probe_fail "не тот вердикт на дереве без вызова: $probe_cut" ;;
esac
probe_note="комментария с именем класса в роутере не осталось"
grep -q "$PROBE_TYPE" "$WORK/probe.cut.java" && probe_note="имя в комментарии роутера осталось и не засчитано"
echo "самотест подключения: $OURS — подключён через роутер; без вызова — потеряно, $probe_note — ok"

# ── Дампы состояний ──────────────────────────────────────────────────────────
# Один git grep на состояние вместо одного на маркер: собираем строки, где
# встречается хоть какой-то маркер, дальше ищем дёшево по локальному файлу.
#
# Маркеры уходят в git grep одной альтернацией -P, а не списком -F -f. Со списком
# git grep на каждой найденной строке заново ищет КАЖДЫЙ шаблон до конца файла:
# цена — найденные строки × шаблоны × размер файла. 757 маркеров шли 11–14 минут
# на состояние, прогон с --worktree — 37 минут (замер 15.09); альтернация — секунды.
# Обход и вывод остаются за git, поэтому дамп равен прежнему построчно: только
# отслеживаемые файлы без симлинков, бинарность по атрибуту diff и NUL в первых
# 8000 байтах, вместо строк бинарного файла — «Binary file <имя> matches», CR не
# срезается. Соседние пути так не умеют: `git archive | tar -xO | grep -a` читает
# jar и png текстом, склеивает последнюю строку файла без перевода со следующим
# файлом и дописывает CR по eol=crlf; `grep -r -I` судит о бинарности иначе, а
# MSYS-grep без -U срезает CR.
# DUMP_ENGINE=fixed — прежний список -F -f: для git без PCRE2 и для перепроверки.
DUMP_ENGINE="${DUMP_ENGINE:-pcre}"
case "$DUMP_ENGINE" in pcre|fixed) ;; *) echo "DUMP_ENGINE: pcre или fixed, а не '$DUMP_ENGINE'" >&2; exit 2 ;; esac
DUMP_SCOPE=(bundles cli runtime scripts)
# Альтернация равна списку -F, только пока маркеры — идентификаторы без
# метасимволов: это проверяется, а не предполагается. Строки шаблонов читаются
# как у git -f: CR в конце срезается, пустые пропускаются.
awk '{ sub(/\r$/, "") } $0 == "" { next }
     !/^[A-Za-z][A-Za-z0-9_]*$/ { bad = 1; exit }
     { printf "%s%s", (n++ ? "|" : ""), $0 }
     END { if (bad || !n) exit 1; print "" }' "$WORK/patterns.txt" > "$WORK/alternation.txt" \
    || { echo "маркер не идентификатор или маркеров нет — альтернация не равна списку -F: $WORK/patterns.txt" >&2; exit 1; }

state_grep() { # <pcre|fixed> [ревизия и прочие аргументы git grep] -> строки дампа; код ≠ 0 — git grep упал
    local engine="$1"; shift
    if [ "$engine" = fixed ]; then
        git grep -h -F -f "$WORK/patterns.txt" "$@" -- "${DUMP_SCOPE[@]}"
    else
        # LC_ALL=C: без UTF-режима PCRE2 сравнивает байты, как -F.
        LC_ALL=C git grep -h -P -f "$WORK/alternation.txt" "$@" -- "${DUMP_SCOPE[@]}"
    fi
    [ $? -le 1 ] # 1 у git grep — «совпадений нет», а не ошибка
}
# Прежде ошибка git grep глоталась, и пустой дамп выдавал каждый наш маркер за
# потерянный. С -P появился свой отказ (git без PCRE2), поэтому теперь — стоп.
dump_state() { # <файл дампа> [ревизия]; без ревизии — рабочее дерево текущего каталога
    state_grep "$DUMP_ENGINE" "${@:2}" > "$1" 2> "$1.err" && return
    echo "ДАМП НЕ СНЯТ ($DUMP_ENGINE, ${2:-рабочее дерево}): $(head -2 "$1.err")" >&2
    [ "$DUMP_ENGINE" = pcre ] && echo "  git без PCRE2 или альтернация не собралась — прежний путь: DUMP_ENGINE=fixed" >&2
    exit 1
}

# ── Самотест дампа ───────────────────────────────────────────────────────────
# До дампов, секунды. Дерево из синтетических блобов во временном индексе, как у
# самотеста подключения: строка с CR, строка с NUL дальше 8000-го байта (для git
# это текст), последняя строка без перевода, маркер внутри длинного слова, строка
# без маркера, КАЖДЫЙ маркер отдельной строкой (урезанная альтернация иначе прошла
# бы по первому) — и бинарный файл со строкой маркера. Движок дампа обязан отдать
# то же, что список -F -f (после sort, побайтно). Строки бинарного файла в дампе
# нет, вместо неё одно уведомление git — как и раньше. Проверка утечки не слепая:
# с --text та же строка обязана появиться, строка текстового файла есть всегда.
# 🚩 Метки строк пробы — без букв: маркер начинается с буквы и в такой метке не
# найдётся. Метка из слов ловит строку сама — probe, text и load оказались
# маркерами дельты, и первая редакция пробы пропустила альтернацию без маркера xml.
dump_probe_fail() { echo "САМОТЕСТ ДАМПА ПРОВАЛЕН: $1" >&2; exit 1; }
DP="$WORK/dump-probe"; rm -rf "$DP" "$DP.index"; mkdir -p "$DP"
dp_m="$(head -1 "$WORK/patterns.txt")"; dp_m2="$(tail -1 "$WORK/patterns.txt")"
{ printf -- '-1- %s\r\n-2- %s %s 0%s0\n-3-\n' "$dp_m" "$dp_m" "$dp_m2" "$dp_m"
  sed 's/^/-0- /' "$WORK/patterns.txt"
  printf -- '-4- %s' "$dp_m"; } > "$DP/text.txt"
printf -- '%08100d\n-5- \000 %s\n' 0 "$dp_m" > "$DP/late-nul.txt"
printf -- '-6-\n\000\n-7- %s\n' "$dp_m" > "$DP/binary.dat"
GIT_INDEX_FILE="$DP.index" git read-tree --empty || dump_probe_fail "не создался временный индекс"
for f in text.txt late-nul.txt binary.dat; do
    blob="$(git hash-object -w --no-filters "$DP/$f")" \
        && GIT_INDEX_FILE="$DP.index" git update-index --add --cacheinfo "100644,$blob,bundles/dump-probe/$f" \
        || dump_probe_fail "не лёг во временный индекс $f"
done
dp_tree="$(GIT_INDEX_FILE="$DP.index" git write-tree)" || dump_probe_fail "не собралось дерево пробы"
rm -f "$DP.index"
dp_count() { grep -a -c -F -- "$2" "$1" || true; }
state_grep "$DUMP_ENGINE" "$dp_tree" > "$DP/dump" 2> "$DP/err" \
    && state_grep fixed "$dp_tree" > "$DP/fixed" 2>> "$DP/err" \
    && state_grep "$DUMP_ENGINE" --text "$dp_tree" > "$DP/as-text" 2>> "$DP/err" \
    || dump_probe_fail "git grep упал: $(head -2 "$DP/err")"
LC_ALL=C sort "$DP/dump" > "$DP/dump.sorted"; LC_ALL=C sort "$DP/fixed" > "$DP/fixed.sorted"
cmp -s "$DP/dump.sorted" "$DP/fixed.sorted" \
    || dump_probe_fail "$DUMP_ENGINE разошёлся со списком -F -f: diff -a $DP/fixed.sorted $DP/dump.sorted"
[ "$(dp_count "$DP/dump" '-4- ')" = 1 ] \
    || dump_probe_fail "строки текстового файла нет в дампе — проверке утечки не на что опереться"
[ "$(dp_count "$DP/dump" '-7- ')" = 0 ] \
    || dump_probe_fail "строка бинарного файла попала в дамп"
[ "$(dp_count "$DP/dump" "Binary file $dp_tree:bundles/dump-probe/binary.dat matches")" = 1 ] \
    || dump_probe_fail "нет уведомления git о бинарном файле — дамп перестал повторять git grep"
[ "$(dp_count "$DP/as-text" '-7- ')" = 1 ] \
    || dump_probe_fail "с --text строки бинарного файла тоже нет — проверка утечки слепая"
echo "самотест дампа: $DUMP_ENGINE = список -F -f на пробе; бинарный файл — уведомление git, строк 0 (с --text 1) — ok"

dump_state "$WORK/dump.up" "$UP"
dump_state "$WORK/dump.our" "$OURS"
if [ -n "$WORKTREE" ]; then
    ( cd "$WORKTREE" && dump_state "$WORK/dump.wt" ) || exit 1
fi

# 🚩 `grep -c` при нуле совпадений печатает 0 И выходит с кодом 1, поэтому
# `$(grep -c ... || echo 0)` даёт «0\n0» и ломает арифметику (наступлено при
# порте 2026-07-27). Здесь код выхода гасится через `|| true` ПОСЛЕ печати.
count() { grep -c -F -- "$2" "$1" 2>/dev/null || true; }

# ── Негативный самотест ──────────────────────────────────────────────────────
# Счётчик, не умеющий сказать «нет», превращает карту в список нулей и любую
# потерю показывает как «всё на месте». Но проверять его литералом мало:
# литерал не идёт той же дорогой, что маркеры, и не ловит их порчу. Поэтому
# вторая половина самотеста берёт РЕАЛЬНЫЕ маркеры из patterns.txt — они по
# построению взяты из нашего же дерева и обязаны в нём находиться.
absent="$(count "$WORK/dump.our" 'ZzMarkerThatCannotExist_9f3a')"
[ "$absent" = "0" ] || { echo "САМОТЕСТ ПРОВАЛЕН: отсутствующий маркер дал '$absent', ожидался 0" >&2; exit 1; }
resolved=0; sampled=0
while IFS= read -r probe; do
    sampled=$((sampled + 1))
    [ "$(count "$WORK/dump.our" "$probe")" -gt 0 ] 2>/dev/null && resolved=$((resolved + 1))
    [ "$sampled" -ge 10 ] && break
done < "$WORK/patterns.txt"
if [ "$resolved" -eq 0 ]; then
    echo "САМОТЕСТ ПРОВАЛЕН: ни один из $sampled реальных маркеров не нашёлся в СВОЁМ ЖЕ дереве." >&2
    echo "  Скорее всего испорчены сами маркеры (CR в конце строки?), а не дерево." >&2
    exit 1
fi
echo "самотест: отсутствующий=0; реальных маркеров разрешилось $resolved из $sampled — ok"
echo

# ── Вердикты ─────────────────────────────────────────────────────────────────
OUR_ONLY=0; SHARED=0; LOST=0
: > "$WORK/lost.txt"; : > "$WORK/our-only.txt"
prev_file=""
while IFS=$'\t' read -r f m; do
    c_our="$(count "$WORK/dump.our" "$m")"; c_up="$(count "$WORK/dump.up" "$m")"
    [ "${c_our:-0}" -eq 0 ] && continue
    if [ "${c_up:-0}" -gt 0 ]; then
        SHARED=$((SHARED + 1)); [ "$SHOW_ALL" -eq 0 ] && continue
        verdict="общий (у upstream $c_up)"
    else
        OUR_ONLY=$((OUR_ONLY + 1)); verdict="НАШ — обязан выжить"
        echo "$f	$m" >> "$WORK/our-only.txt"
    fi
    if [ -n "$WORKTREE" ]; then
        c_wt="$(count "$WORK/dump.wt" "$m")"
        if [ "${c_wt:-0}" -eq 0 ]; then
            verdict="ПОТЕРЯН при мердже"; LOST=$((LOST + 1)); echo "$f :: $m" >> "$WORK/lost.txt"
        else
            verdict="$verdict, в дереве $c_wt"
        fi
    fi
    if [ "$f" != "$prev_file" ]; then
        echo "-- $(echo "$f" | sed 's|bundles/com.codepilot1c.core/src/com/codepilot1c/core/|core:|; s|bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/|tests:|')"
        prev_file="$f"
    fi
    printf "     %-44s %s\n" "$m" "$verdict"
done < "$WORK/markers.tsv"

echo
# ── Регистрация форк-онли тулов ──────────────────────────────────────────────
# Отдельная проверка, потому что маркерная карта её НЕ ловит: `register(new
# EdtUuidCheckTool())` объявлением не является и в маркеры не попадает, а имя
# класса встречается в его собственном файле — древесный счётчик покажет «жив»
# даже если регистрация выпала вместе со взятой целиком upstream-версией
# ToolRegistry. А выпавшая регистрация = тула просто нет в MCP-поверхности.
# Имя тула -> класс берётся из САМОЙ аннотации (@ToolMeta в этом же файле), а не
# первым попавшимся файлом со строкой имени: строка "rename_metadata" живёт ещё
# и в сервисе, git grep -l отдал его первым, и проверка объявила потерянной
# регистрацию тула, который на месте (наступлено 2026-08-27).
tool_map() { # <rev> -> строки "имя<TAB>Класс"
    for f in $(git grep -l '@ToolMeta' "$1" -- bundles/com.codepilot1c.core/src 2>/dev/null | sed "s|^$1:||"); do
        n="$(git show "$1:$f" 2>/dev/null | grep -A3 '@ToolMeta' | grep -oE 'name = "[a-z0-9_]+"' | head -1 | sed 's/name = "//; s/"//')"
        [ -n "$n" ] && printf '%s\t%s\n' "$n" "$(basename "$f" .java)"
    done | sort -u
}
tool_map "$OURS" > "$WORK/tools.our.map"
tool_map "$UP"   > "$WORK/tools.up.map"
cut -f1 "$WORK/tools.our.map" > "$WORK/tools.our"
cut -f1 "$WORK/tools.up.map"  > "$WORK/tools.up"
comm -23 "$WORK/tools.our" "$WORK/tools.up" > "$WORK/tools.fork-only"
echo "=== ФОРК-ОНЛИ ТУЛЫ: класс + подключение ==="
echo "наших тулов $(wc -l < "$WORK/tools.our"), у upstream $(wc -l < "$WORK/tools.up"), только наших $(wc -l < "$WORK/tools.fork-only")"
# Проверяется не «есть строка в ToolRegistry», а «класс зовут из кода хоть где-то
# вне своего файла» (wiring_of выше): uuid_check подключён через роутер
# EdtDiagnosticsTool, и жёсткая привязка к ToolRegistry объявляла бы его
# потерянным на здоровом дереве. Комментарий с именем класса подключением не
# считается — ровно та дыра, что закрыта для типов.
UNREGISTERED=0
while IFS= read -r t; do
    [ -z "$t" ] && continue
    # awk, а не `grep -P`: PCRE в этом Git Bash нет, grep -P молча ничего не
    # находит, класс уезжает в "?" — и тогда `git grep -F "?"` совпадает почти
    # с каждым файлом, выдавая «подключён везде» вместо ответа.
    cls="$(awk -F'\t' -v n="$t" '$1==n{print $2; exit}' "$WORK/tools.our.map")"
    if [ -z "$cls" ]; then
        printf "  %-22s %-26s КЛАСС НЕ РАЗРЕШЁН по @ToolMeta — карта неполна\n" "$t" "-"
        UNREGISTERED=$((UNREGISTERED + 1)); continue
    fi
    if [ -n "$WORKTREE" ]; then
        w="$(wiring_of "$WORKTREE" "$cls" tree | tr '\n' ' ')"
        if [ -z "$w" ]; then
            printf "  %-22s %-26s ПОДКЛЮЧЕНИЕ ПОТЕРЯНО\n" "$t" "$cls"
            UNREGISTERED=$((UNREGISTERED + 1)); continue
        fi
        printf "  %-22s %-26s подключён: %s\n" "$t" "$cls" "$(echo "$w" | sed 's|[^ ]*/||g')"
    else
        w="$(wiring_of "$OURS" "$cls" rev | tr '\n' ' ')"
        [ -z "$w" ] && { UNREGISTERED=$((UNREGISTERED + 1)); w="НИГДЕ"; }
        printf "  %-22s %-26s подключён: %s\n" "$t" "$cls" "$(echo "$w" | sed 's|[^ ]*/||g')"
    fi
done < "$WORK/tools.fork-only"

echo
# ── Подключение форк-онли типов ──────────────────────────────────────────────
# Тип (class/record/enum/interface) из нашей дельты, которого нет у upstream,
# обязан быть вызван из кода вне своего объявления — в рабочем дереве после
# мерджа или в $OURS до него. Как у тулов, но для любого класса: маркерная карта
# потерю места вызова не видит (см. самотест подключения).
#
# Белый список — типы, которым такая ссылка не нужна. Строка: вид|значение|причина.
#   path — подстрока пути КАЖДОГО файла дельты, где объявлен тип;
#   tool — класс форк-онли тула (значение не используется).
# Каждая строка здесь — место, где потерю не увидит никто: список держать коротким.
TYPE_WIRING_ALLOW='path|.tests/src/|тесты и стабы тестовых бандлов: их зовёт раннер JUnit, продовый код на них не ссылается и не должен
path|/src/test/|тесты модулей cli и runtime: то же, maven src/test
tool|-|класс форк-онли тула: его подключение (ToolRegistry или роутер вроде EdtDiagnosticsTool) проверил раздел выше'
allow_reason() { # <тип> -> причина из белого списка или пусто
    local kind value reason
    while IFS='|' read -r kind value reason; do
        case "$kind" in
            path) awk -F'\t' -v n="$1" -v v="$value" '$2 == n { all++; if (index($1, v)) hit++ }
                      END { exit !(all && hit == all) }' "$WORK/types.fork-only.tsv" \
                      && { echo "$reason"; return; } ;;
            tool) grep -qxF -- "$1" "$WORK/tools.fork-only.classes" && { echo "$reason"; return; } ;;
        esac
    done <<< "$TYPE_WIRING_ALLOW"
}
# Форк-онли = маркер-тип из our-only.txt (у upstream его нет). Разбор TSV — awk,
# а не `grep -P` (см. урок в разделе тулов).
awk -F'\t' 'FILENAME == ARGV[1] { mine[$2] = 1; next } ($2 in mine)' "$WORK/our-only.txt" "$WORK/types.tsv" \
    | sort -u > "$WORK/types.fork-only.tsv"
cut -f2 "$WORK/types.fork-only.tsv" | sort -u > "$WORK/types.fork-only"
awk -F'\t' 'FILENAME == ARGV[1] { fork[$1] = 1; next } ($1 in fork) { print $2 }' \
    "$WORK/tools.fork-only" "$WORK/tools.our.map" | sort -u > "$WORK/tools.fork-only.classes"
echo "=== ФОРК-ОНЛИ ТИПЫ: ссылка из кода вне своего объявления ==="
[ -n "$(allow_reason "$PROBE_TYPE")" ] && { echo "белый список глотает пробу самотеста $PROBE_TYPE" >&2; exit 1; }
if [ -n "$WORKTREE" ]; then type_where="$WORKTREE"; type_mode=tree; else type_where="$OURS"; type_mode=rev; fi
UNWIRED=0; TYPES_CHECKED=0; TYPES_ALLOWED=0
: > "$WORK/types.unwired.txt"
while IFS= read -r t; do
    [ -z "$t" ] && continue
    reason="$(allow_reason "$t")"
    if [ -n "$reason" ]; then
        TYPES_ALLOWED=$((TYPES_ALLOWED + 1))
        printf "  %-42s пропуск: %s\n" "$t" "${reason%%:*}"
        continue
    fi
    TYPES_CHECKED=$((TYPES_CHECKED + 1))
    type_wiring "$type_where" "$type_mode" "$t" || { UNWIRED=$((UNWIRED + 1)); echo "$t" >> "$WORK/types.unwired.txt"; }
done < "$WORK/types.fork-only"
echo "форк-онли типов $(wc -l < "$WORK/types.fork-only"): проверено $TYPES_CHECKED, по белому списку $TYPES_ALLOWED"

echo
echo "=== ИТОГ ==="
echo "маркеров только наших : $OUR_ONLY  (список: $WORK/our-only.txt)"
echo "маркеров общих        : $SHARED$([ "$SHOW_ALL" -eq 0 ] && echo ' (не печатались, покажет --all)')"
echo "форк-онли тулов без регистрации : $UNREGISTERED"
echo "форк-онли типов без подключения : $UNWIRED"
[ "$UNWIRED" -gt 0 ] && sed 's/^/   /' "$WORK/types.unwired.txt"
if [ -n "$WORKTREE" ]; then
    if [ "$LOST" -gt 0 ] || [ "$UNREGISTERED" -gt 0 ] || [ "$UNWIRED" -gt 0 ]; then
        [ "$LOST" -gt 0 ] && { echo "ПОТЕРЯНО ПРИ МЕРДЖЕ   : $LOST"; sed 's/^/   /' "$WORK/lost.txt"; }
        exit 1
    fi
    echo "потерь при мердже     : 0"
fi
[ "$UNREGISTERED" -gt 0 ] && exit 1
[ "$UNWIRED" -gt 0 ] && exit 1
exit 0
