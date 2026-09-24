#!/usr/bin/env bash
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT_DIR/tools/materialize-edt-target.py"
FAILURES=0
TEST_TMP="$(mktemp -d)"

cleanup() {
    rm -rf "$TEST_TMP"
}
trap cleanup EXIT

new_fixture() {
    local name="$1"
    local root="$TEST_TMP/$name"
    local eclipse="$root/1cedt.app/Contents/Eclipse"
    local pool="$root/pool/plugins"
    mkdir -p "$eclipse/configuration/org.eclipse.equinox.simpleconfigurator" "$eclipse/plugins" "$pool"
    printf 'product.version=2026.2.0\neclipse.buildId=2026.2.0.289\n' > "$eclipse/configuration/config.ini"
    printf 'first\n' > "$pool/example.one_1.0.0.jar"
    printf 'second\n' > "$pool/example two_2.0.0.jar"
    mkdir -p "$pool/example.directory_3.0.0"
    printf 'directory bundle\n' > "$pool/example.directory_3.0.0/content.txt"
    python3 - "$eclipse/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info" "$pool" <<'PY'
from pathlib import Path
import sys

target, pool = map(Path, sys.argv[1:])
target.write_text(
    "#encoding=UTF-8\n"
    "#version=1\n"
    f"example.one,1.0.0,{(pool / 'example.one_1.0.0.jar').as_uri()},4,false\n"
    f"example.two,2.0.0,{(pool / 'example two_2.0.0.jar').as_uri()},4,true\n"
    f"example.directory,3.0.0,{(pool / 'example.directory_3.0.0').as_uri()},4,false\n",
    encoding="utf-8",
)
PY
    printf '%s\n' "$root"
}

assert_code() {
    local expected="$1" name="$2"
    shift 2
    "$@" >/dev/null 2>&1
    local actual=$?
    if [[ "$actual" == "$expected" ]]; then
        printf 'ok   %s\n' "$name"
    else
        printf 'FAIL %s (expected %s, got %s)\n' "$name" "$expected" "$actual"
        FAILURES=$((FAILURES + 1))
    fi
}

assert_equal() {
    local expected="$1" actual="$2" name="$3"
    if [[ "$actual" == "$expected" ]]; then
        printf 'ok   %s\n' "$name"
    else
        printf 'FAIL %s (expected [%s], got [%s])\n' "$name" "$expected" "$actual"
        FAILURES=$((FAILURES + 1))
    fi
}

root="$(new_fixture happy)"
mkdir -p "$root/cache"
output="$root/cache/exact-target"
output_real="$(cd "$(dirname "$output")" && pwd -P)/$(basename "$output")"
actual="$(python3 "$SCRIPT" "$root/1cedt.app" "$output")"
assert_equal "edt.home=$output_real/Eclipse" "$actual" "app source prints ready edt.home"
assert_code 0 "marker created" test -f "$output/.codepilot1c-edt-target.json"
assert_code 0 "absolute shared-p2 file URI materialized" test -L "$output/Eclipse/plugins/example two_2.0.0.jar"
assert_code 0 "plugin symlink resolves" test -e "$output/Eclipse/plugins/example.one_1.0.0.jar"
assert_code 0 "directory bundle symlink created" test -L "$output/Eclipse/plugins/example.directory_3.0.0"
assert_code 0 "directory bundle symlink has directory semantics" test -d "$output/Eclipse/plugins/example.directory_3.0.0"

actual="$(python3 "$SCRIPT" "$root/1cedt.app/Contents/Eclipse" "$output")"
assert_equal "edt.home=$output_real/Eclipse" "$actual" "rerun with Eclipse source is idempotent"

root="$(new_fixture self-contained-relative)"
mkdir -p "$root/cache"
eclipse="$root/1cedt.app/Contents/Eclipse"
bundles="$eclipse/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
printf 'relative\n' > "$eclipse/plugins/relative.bundle.jar"
printf '#encoding=UTF-8\n#version=1\nrelative,1.0.0,plugins/relative.bundle.jar,4,false\n' > "$bundles"
assert_code 0 "self-contained relative location materialized from app source" \
    python3 "$SCRIPT" "$root/1cedt.app" "$root/cache/target"
assert_code 0 "self-contained relative artifact link resolves" \
    test -e "$root/cache/target/Eclipse/plugins/relative.bundle.jar"

root="$(new_fixture self-contained-relative-file)"
mkdir -p "$root/cache"
eclipse="$root/1cedt.app/Contents/Eclipse"
bundles="$eclipse/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
printf 'relative file\n' > "$eclipse/plugins/relative-file.bundle.jar"
printf '#encoding=UTF-8\n#version=1\nrelative.file,1.0.0,file:plugins/relative-file.bundle.jar,4,false\n' > "$bundles"
assert_code 0 "file-prefixed relative location materialized from Eclipse source" \
    python3 "$SCRIPT" "$eclipse" "$root/cache/target"
assert_code 0 "file-prefixed relative artifact link resolves" \
    test -e "$root/cache/target/Eclipse/plugins/relative-file.bundle.jar"

root="$(new_fixture relative-traversal)"
mkdir -p "$root/cache"
printf 'outside\n' > "$root/outside.jar"
bundles="$root/1cedt.app/Contents/Eclipse/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
printf '#encoding=UTF-8\n#version=1\ntraversal,1.0.0,plugins/../../../outside.jar,4,false\n' > "$bundles"
assert_code 1 "external relative traversal rejected" \
    python3 "$SCRIPT" "$root/1cedt.app" "$root/cache/target"
assert_code 1 "relative traversal leaves no output" test -e "$root/cache/target"

root="$(new_fixture network)"
mkdir -p "$root/cache"
bundles="$root/1cedt.app/Contents/Eclipse/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
printf '#encoding=UTF-8\n#version=1\nnetwork,1.0.0,https://example.invalid/plugin.jar,4,false\n' > "$bundles"
assert_code 1 "network URL rejected" python3 "$SCRIPT" "$root/1cedt.app" "$root/cache/target"
assert_code 1 "failed materialization leaves no output" test -e "$root/cache/target"

root="$(new_fixture unknown-scheme)"
mkdir -p "$root/cache"
bundles="$root/1cedt.app/Contents/Eclipse/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
printf '#encoding=UTF-8\n#version=1\nunknown,1.0.0,reference:file:plugins/example.jar,4,false\n' > "$bundles"
assert_code 1 "unknown location scheme rejected" python3 "$SCRIPT" "$root/1cedt.app" "$root/cache/target"

root="$(new_fixture encoded-separator)"
mkdir -p "$root/cache"
bundles="$root/1cedt.app/Contents/Eclipse/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
printf '#encoding=UTF-8\n#version=1\nseparator,1.0.0,plugins/example%%2Fone.jar,4,false\n' > "$bundles"
assert_code 1 "encoded path separator rejected" python3 "$SCRIPT" "$root/1cedt.app" "$root/cache/target"

root="$(new_fixture authority)"
mkdir -p "$root/cache"
bundles="$root/1cedt.app/Contents/Eclipse/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
printf '#encoding=UTF-8\n#version=1\nunc,1.0.0,file://server/share/plugin.jar,4,false\n' > "$bundles"
if [[ "$(uname -s)" == "Windows_NT" ]]; then
    assert_code 1 "unreachable Windows UNC artifact rejected" python3 "$SCRIPT" "$root/1cedt.app" "$root/cache/target"
else
    assert_code 1 "file URI authority rejected on POSIX" python3 "$SCRIPT" "$root/1cedt.app" "$root/cache/target"
fi

root="$(new_fixture bad-percent)"
mkdir -p "$root/cache"
bundles="$root/1cedt.app/Contents/Eclipse/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
printf '#encoding=UTF-8\n#version=1\nbroken,1.0.0,file:///tmp/plugin%%ZZ.jar,4,false\n' > "$bundles"
assert_code 1 "malformed percent encoding rejected" python3 "$SCRIPT" "$root/1cedt.app" "$root/cache/target"

root="$(new_fixture traversal)"
mkdir -p "$root/cache"
printf 'outside\n' > "$root/pool/outside.jar"
bundles="$root/1cedt.app/Contents/Eclipse/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
python3 - "$bundles" "$root/pool/plugins" <<'PY'
from pathlib import Path
import sys

bundles, plugins = map(Path, sys.argv[1:])
location = plugins.as_uri() + "/%2e%2e/outside.jar"
bundles.write_text(
    "#encoding=UTF-8\n#version=1\n"
    f"traversal,1.0.0,{location},4,false\n",
    encoding="utf-8",
)
PY
assert_code 1 "encoded path traversal rejected" python3 "$SCRIPT" "$root/1cedt.app" "$root/cache/target"

root="$(new_fixture missing)"
mkdir -p "$root/cache"
rm -f "$root/pool/plugins/example.one_1.0.0.jar"
assert_code 1 "missing bundle target rejected" python3 "$SCRIPT" "$root/1cedt.app" "$root/cache/target"

root="$(new_fixture malformed)"
mkdir -p "$root/cache"
bundles="$root/1cedt.app/Contents/Eclipse/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
printf '#encoding=UTF-8\n#version=1\nbroken,1.0.0,file:/tmp/broken.jar,4\n' > "$bundles"
assert_code 1 "malformed bundles.info entry rejected" python3 "$SCRIPT" "$root/1cedt.app" "$root/cache/target"

root="$(new_fixture baseline)"
mkdir -p "$root/cache"
printf 'product.version=2025.1.5\neclipse.buildId=2025.1.5.34\n' > "$root/1cedt.app/Contents/Eclipse/configuration/config.ini"
assert_code 1 "wrong EDT baseline rejected" python3 "$SCRIPT" "$root/1cedt.app" "$root/cache/target"

root="$(new_fixture occupied)"
mkdir -p "$root/cache/target"
printf 'keep\n' > "$root/cache/target/user-file"
assert_code 1 "unmarked existing output rejected" python3 "$SCRIPT" "$root/1cedt.app" "$root/cache/target"
assert_equal "keep" "$(sed -n '1p' "$root/cache/target/user-file")" "existing output is not overwritten"

root="$(new_fixture collision)"
mkdir -p "$root/cache" "$root/pool/a" "$root/pool/b"
printf 'a\n' > "$root/pool/a/same.jar"
printf 'b\n' > "$root/pool/b/SAME.jar"
bundles="$root/1cedt.app/Contents/Eclipse/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
python3 - "$bundles" "$root/pool/a/same.jar" "$root/pool/b/SAME.jar" <<'PY'
from pathlib import Path
import sys

bundles = Path(sys.argv[1])
first, second = (Path(value).as_uri() for value in sys.argv[2:])
bundles.write_text(
    "#encoding=UTF-8\n#version=1\n"
    f"first,1.0.0,{first},4,false\n"
    f"second,1.0.0,{second},4,false\n",
    encoding="utf-8",
)
PY
assert_code 1 "portable case-insensitive plugin name collision rejected" python3 "$SCRIPT" "$root/1cedt.app" "$root/cache/target"

root="$(new_fixture source-symlink)"
mkdir -p "$root/cache"
ln -s "$root/pool/plugins/example.one_1.0.0.jar" "$root/pool/plugins/linked.jar"
bundles="$root/1cedt.app/Contents/Eclipse/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
python3 - "$bundles" "$root/pool/plugins/linked.jar" <<'PY'
from pathlib import Path
import sys

bundles, linked = map(Path, sys.argv[1:])
bundles.write_text(
    "#encoding=UTF-8\n#version=1\n"
    f"linked,1.0.0,{linked.as_uri()},4,false\n",
    encoding="utf-8",
)
PY
assert_code 1 "symlink bundle source rejected" python3 "$SCRIPT" "$root/1cedt.app" "$root/cache/target"

root="$(new_fixture output-symlink)"
mkdir -p "$root/cache/real-target"
ln -s "$root/cache/real-target" "$root/cache/target"
assert_code 1 "symlink output path rejected" python3 "$SCRIPT" "$root/1cedt.app" "$root/cache/target"

root="$(new_fixture relative)"
assert_code 1 "relative output rejected" python3 "$SCRIPT" "$root/1cedt.app" relative-output

if (( FAILURES > 0 )); then
    printf '%s materializer test(s) failed\n' "$FAILURES" >&2
    exit 1
fi
printf 'all materializer tests passed\n'
