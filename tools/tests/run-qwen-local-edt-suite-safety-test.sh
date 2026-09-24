#!/usr/bin/env bash
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT_DIR/tools/run-qwen-local-edt-suite.sh"
FAILURES=0
TEST_TMP="$(mktemp -d)"

cleanup() {
    rm -rf "$TEST_TMP"
}
trap cleanup EXIT

new_app() {
    local name="$1"
    local app="$TEST_TMP/$name/1cedt-test.app"
    local eclipse="$app/Contents/Eclipse"
    local bundles="$eclipse/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
    mkdir -p "$eclipse/plugins" "$(dirname "$bundles")"
    printf 'product.version=2026.2.0\neclipse.buildId=2026.2.0.289\n' > "$eclipse/configuration/config.ini"
    printf 'bundle\n' > "$eclipse/plugins/example.jar"
    printf '#encoding=UTF-8\n#version=1\nexample,1.0.0,plugins/example.jar,4,false\n' > "$bundles"
    printf '%s\n' "$app"
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

app="$(new_app existing-reuse)"
assert_code 0 "existing EDT_TEST_APP is validated and reused without a source app" \
    bash -c '
        EDT_TEST_APP="$1"
        EDT_SOURCE_APP=""
        source "$2"
        ditto() { return 97; }
        ensure_test_edt_copy
    ' _ "$app" "$SCRIPT"

app="$(new_app invalid-existing)"
external="$TEST_TMP/invalid-existing/outside.jar"
printf 'outside\n' > "$external"
bundles="$app/Contents/Eclipse/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
printf '#encoding=UTF-8\n#version=1\nexample,1.0.0,%s,4,false\n' \
    "$(python3 -c 'from pathlib import Path; import sys; print(Path(sys.argv[1]).as_uri())' "$external")" > "$bundles"
assert_code 1 "existing EDT_TEST_APP with an external bundle is rejected" \
    bash -c '
        EDT_TEST_APP="$1"
        EDT_SOURCE_APP=""
        source "$2"
        ensure_test_edt_copy
    ' _ "$app" "$SCRIPT"

if (( FAILURES > 0 )); then
    printf '%s run-qwen safety test(s) failed\n' "$FAILURES" >&2
    exit 1
fi
printf 'all run-qwen safety tests passed\n'
