#!/usr/bin/env bash
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LIB="$ROOT_DIR/tools/lib/p2-publish-lib.sh"
PUBLISH="$ROOT_DIR/tools/publish-p2-local.sh"
FAILURES=0
Q=1.3.0.20260817-1635
TEST_TMP="$(mktemp -d)"

command -v zip >/dev/null 2>&1 || {
  printf 'FAIL fixture setup requires zip\n'
  exit 1
}

cleanup() {
  rm -rf "$TEST_TMP"
}
trap cleanup EXIT

new_fixture() { # <qualifier> -> prints <root_dir>; creates a git repo with a valid p2 repository
  local q="$1" root p2
  root="$(mktemp -d "$TEST_TMP/fixture.XXXXXX")"
  p2="$root/repositories/com.codepilot1c.update/target/repository"
  mkdir -p "$p2/plugins" "$p2/features"
  printf '<repository/>\n' > "$p2/content.xml"
  ( cd "$p2" && zip -q content.jar content.xml )
  rm -f "$p2/content.xml"
  printf '<repository/>\n' > "$p2/artifacts.xml"
  ( cd "$p2" && zip -q artifacts.jar artifacts.xml )
  rm -f "$p2/artifacts.xml"
  printf 'core' > "$p2/plugins/com.codepilot1c.core_$q.jar"
  printf 'ui' > "$p2/plugins/com.codepilot1c.ui_$q.jar"
  printf 'feature' > "$p2/features/com.codepilot1c.feature_$q.jar"
  git -C "$root" init -q
  printf '%s\n' 'repositories/**/target/' 'tools/' 'bin/' 'gh-pages-worktree' \
    >> "$root/.git/info/exclude"
  GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@t GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@t \
    git -C "$root" commit -q --allow-empty -m init
  printf '%s\n' "$root"
}

assert_code() { # <expected> <name> <command...>
  local expected="$1" name="$2"
  shift 2
  "$@" >/dev/null 2>&1
  local actual=$?
  if [ "$actual" = "$expected" ]; then
    printf 'ok   %s\n' "$name"
  else
    printf 'FAIL %s (expected %s, got %s)\n' "$name" "$expected" "$actual"
    FAILURES=$((FAILURES + 1))
  fi
}

assert_absent() { # <path> <name>
  local path="$1" name="$2"
  if [ ! -e "$path" ]; then
    printf 'ok   %s\n' "$name"
  else
    printf 'FAIL %s (unexpected path: %s)\n' "$name" "$path"
    FAILURES=$((FAILURES + 1))
  fi
}

assert_equal() { # <expected> <actual> <name>
  local expected="$1" actual="$2" name="$3"
  if [ "$actual" = "$expected" ]; then
    printf 'ok   %s\n' "$name"
  else
    printf 'FAIL %s (values differ)\n' "$name"
    FAILURES=$((FAILURES + 1))
  fi
}

assert_file_content() { # <path> <expected> <name>
  local path="$1" expected="$2" name="$3" actual
  if [ -f "$path" ]; then
    actual="$(sed -n '1p' "$path")"
  else
    actual="<missing>"
  fi
  assert_equal "$expected" "$actual" "$name"
}

validate() { # <root> <expect_q> <expect_head> <require_clean>
  local root="$1"
  ( . "$LIB"
    p2_validate "$root/repositories/com.codepilot1c.update/target/repository" \
      "$root" "$2" "$3" "$4" )
}

record() {
  local root="$1"
  ( . "$LIB"
    p2_write_provenance "$root/repositories/com.codepilot1c.update/target/repository" "$root" )
}

copy_publisher() { # <root>
  local root="$1"
  mkdir -p "$root/tools/lib" "$root/bin"
  cp "$PUBLISH" "$root/tools/"
  cp "$LIB" "$root/tools/lib/"
  printf '%s\n' '#!/usr/bin/env bash' \
    'printf invoked > "$MVN_SENTINEL"' \
    'exit 99' > "$root/bin/mvn"
  chmod +x "$root/bin/mvn"
}

run_publisher() { # <root> [environment assignments and command arguments]
  local root="$1"
  shift
  env PATH="$root/bin:$PATH" MVN_SENTINEL="$root/mvn-invoked" \
    WORKTREE_DIR="$root/gh-pages-worktree" "$@" bash "$root/tools/publish-p2-local.sh"
}

root="$(new_fixture "$Q")"
rm -rf "$root/repositories/com.codepilot1c.update/target/repository"
assert_code 10 "missing p2 directory" validate "$root" "$Q" "$(git -C "$root" rev-parse HEAD)" 1

root="$(new_fixture "$Q")"
: > "$root/repositories/com.codepilot1c.update/target/repository/content.jar"
assert_code 11 "empty content.jar" validate "$root" "$Q" "$(git -C "$root" rev-parse HEAD)" 1

root="$(new_fixture "$Q")"
printf 'not a zip' > "$root/repositories/com.codepilot1c.update/target/repository/content.jar"
assert_code 11 "corrupt content.jar" validate "$root" "$Q" "$(git -C "$root" rev-parse HEAD)" 1

root="$(new_fixture "$Q")"
p2="$root/repositories/com.codepilot1c.update/target/repository"
rm -f "$p2/content.jar"
printf '<wrong/>\n' > "$p2/wrong.xml"
( cd "$p2" && zip -q content.jar wrong.xml )
rm -f "$p2/wrong.xml"
assert_code 11 "content.jar without content.xml" validate "$root" "$Q" "$(git -C "$root" rev-parse HEAD)" 1

root="$(new_fixture "$Q")"
: > "$root/repositories/com.codepilot1c.update/target/repository/artifacts.jar"
assert_code 11 "empty artifacts.jar" validate "$root" "$Q" "$(git -C "$root" rev-parse HEAD)" 1

root="$(new_fixture "$Q")"
printf 'not a zip' > "$root/repositories/com.codepilot1c.update/target/repository/artifacts.jar"
assert_code 11 "corrupt artifacts.jar" validate "$root" "$Q" "$(git -C "$root" rev-parse HEAD)" 1

root="$(new_fixture "$Q")"
p2="$root/repositories/com.codepilot1c.update/target/repository"
rm -f "$p2/artifacts.jar"
printf '<wrong/>\n' > "$p2/wrong.xml"
( cd "$p2" && zip -q artifacts.jar wrong.xml )
rm -f "$p2/wrong.xml"
assert_code 11 "artifacts.jar without artifacts.xml" validate "$root" "$Q" "$(git -C "$root" rev-parse HEAD)" 1

root="$(new_fixture "$Q")"
rm -f "$root/repositories/com.codepilot1c.update/target/repository"/{plugins,features}/com.codepilot1c.*.jar
assert_code 12 "no codepilot artifacts" validate "$root" "$Q" "$(git -C "$root" rev-parse HEAD)" 1

root="$(new_fixture "$Q")"
rm -f "$root/repositories/com.codepilot1c.update/target/repository/plugins/com.codepilot1c.ui_$Q.jar"
assert_code 12 "missing ui bundle" validate "$root" "$Q" "$(git -C "$root" rev-parse HEAD)" 1

root="$(new_fixture "$Q")"
printf 'old core' > "$root/repositories/com.codepilot1c.update/target/repository/plugins/com.codepilot1c.core_1.3.0.20260817-1245.jar"
assert_code 13 "mixed qualifiers" validate "$root" "$Q" "$(git -C "$root" rev-parse HEAD)" 1

root="$(new_fixture "$Q")"
record "$root" >/dev/null
assert_code 14 "unexpected qualifier" validate "$root" 1.3.0.20260817-9999 "$(git -C "$root" rev-parse HEAD)" 1

root="$(new_fixture "$Q")"
printf 'tests' > "$root/repositories/com.codepilot1c.update/target/repository/plugins/com.codepilot1c.core.tests_$Q.jar"
assert_code 15 "tests bundle present" validate "$root" "$Q" "$(git -C "$root" rev-parse HEAD)" 1

root="$(new_fixture "$Q")"
printf 'tests feature' > "$root/repositories/com.codepilot1c.update/target/repository/features/com.codepilot1c.feature.tests_$Q.jar"
assert_code 15 "tests feature present" validate "$root" "$Q" "$(git -C "$root" rev-parse HEAD)" 1

root="$(new_fixture "$Q")"
assert_code 16 "missing provenance" validate "$root" "$Q" "$(git -C "$root" rev-parse HEAD)" 1

root="$(new_fixture "$Q")"
rm -rf "$root/.git"
assert_code 19 "non-git provenance record fails" record "$root"
assert_absent "$root/repositories/com.codepilot1c.update/target/repository.provenance" \
  "non-git failure writes no marker"

root="$(new_fixture "$Q")"
record "$root" >/dev/null
marker="$root/repositories/com.codepilot1c.update/target/repository.provenance"
sed '/^dirty=/d' "$marker" > "$marker.tmp"
mv "$marker.tmp" "$marker"
assert_code 16 "missing provenance dirty flag" validate "$root" "$Q" "$(git -C "$root" rev-parse HEAD)" 1

root="$(new_fixture "$Q")"
record "$root" >/dev/null
marker="$root/repositories/com.codepilot1c.update/target/repository.provenance"
sed 's/^dirty=.*/dirty=unknown/' "$marker" > "$marker.tmp"
mv "$marker.tmp" "$marker"
assert_code 16 "invalid provenance dirty flag" validate "$root" "$Q" "$(git -C "$root" rev-parse HEAD)" 1

root="$(new_fixture "$Q")"
record "$root" >/dev/null
marker="$root/repositories/com.codepilot1c.update/target/repository.provenance"
sed 's/^head=.*/head=deadbeef/' "$marker" > "$marker.tmp"
mv "$marker.tmp" "$marker"
assert_code 17 "provenance head mismatch" validate "$root" "$Q" "$(git -C "$root" rev-parse HEAD)" 1

root="$(new_fixture "$Q")"
record "$root" >/dev/null
printf 'changed' >> "$root/repositories/com.codepilot1c.update/target/repository/plugins/com.codepilot1c.core_$Q.jar"
assert_code 18 "artifact digest mismatch" validate "$root" "$Q" "$(git -C "$root" rev-parse HEAD)" 1

root="$(new_fixture "$Q")"
record "$root" >/dev/null
assert_code 19 "expected HEAD mismatch" validate "$root" "$Q" deadbeef 1

root="$(new_fixture "$Q")"
record "$root" >/dev/null
assert_code 20 "EXPECT_HEAD rejects short prefix" validate "$root" "$Q" abc123 1
assert_code 20 "EXPECT_HEAD rejects non-hex prefix" validate "$root" "$Q" 'deadbee?' 1

root="$(new_fixture "$Q")"
record "$root" >/dev/null
printf 'dirty' > "$root/untracked"
assert_code 19 "dirty tree rejected" validate "$root" "$Q" "$(git -C "$root" rev-parse HEAD)" 1

root="$(new_fixture "$Q")"
record "$root" >/dev/null
printf 'dirty' > "$root/untracked"
assert_code 0 "dirty tree allowed after build" validate "$root" "$Q" "$(git -C "$root" rev-parse HEAD)" 0

root="$(new_fixture "$Q")"
printf 'clean\n' > "$root/source.txt"
git -C "$root" add source.txt
GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@t GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@t \
  git -C "$root" commit -q -m source
printf 'dirty\n' >> "$root/source.txt"
record "$root" >/dev/null
git -C "$root" restore source.txt
head="$(git -C "$root" rev-parse HEAD)"
assert_code 17 "dirty-record provenance rejected after tree is cleaned" validate "$root" "$Q" "$head" 1
assert_code 0 "dirty-record provenance allowed in build-first mode" validate "$root" "$Q" "$head" 0

root="$(new_fixture "$Q")"
record "$root" >/dev/null
head="$(git -C "$root" rev-parse HEAD)"
actual="$(validate "$root" "$Q" "$head" 1)"
code=$?
if [ "$code" = 0 ] && [ "$actual" = "$Q" ]; then
  printf 'ok   clean exact-artifact happy path\n'
else
  printf 'FAIL clean exact-artifact happy path (code=%s output=%s)\n' "$code" "$actual"
  FAILURES=$((FAILURES + 1))
fi

root="$(new_fixture "$Q")"
copy_publisher "$root"
mkdir -p "$root/gh-pages-worktree"
printf 'precious\n' > "$root/gh-pages-worktree/content"
assert_code 20 "SKIP_BUILD requires EXPECT_QUALIFIER" \
  run_publisher "$root" SKIP_BUILD=1 EXPECT_HEAD="$(git -C "$root" rev-parse HEAD)"
assert_absent "$root/mvn-invoked" "missing qualifier does not invoke mvn"
assert_file_content "$root/gh-pages-worktree/content" precious \
  "usage error preserves caller WORKTREE_DIR"

root="$(new_fixture "$Q")"
copy_publisher "$root"
assert_code 20 "SKIP_BUILD requires EXPECT_HEAD" \
  run_publisher "$root" SKIP_BUILD=1 EXPECT_QUALIFIER="$Q"
assert_absent "$root/mvn-invoked" "missing HEAD does not invoke mvn"

root="$(new_fixture "$Q")"
copy_publisher "$root"
assert_code 20 "RECORD_PROVENANCE contradicts SKIP_BUILD" \
  run_publisher "$root" RECORD_PROVENANCE=1 SKIP_BUILD=1 EXPECT_QUALIFIER="$Q" \
    EXPECT_HEAD="$(git -C "$root" rev-parse HEAD)"
assert_absent "$root/mvn-invoked" "contradictory mode does not invoke mvn"

root="$(new_fixture "$Q")"
copy_publisher "$root"
mkdir -p "$root/gh-pages-worktree"
printf 'precious\n' > "$root/gh-pages-worktree/content"
worktrees_before="$(git -C "$root" worktree list --porcelain)"
assert_code 16 "validation failure exits before worktree setup" \
  run_publisher "$root" SKIP_BUILD=1 DRY_RUN=1 EXPECT_QUALIFIER="$Q" \
    EXPECT_HEAD="$(git -C "$root" rev-parse HEAD)"
assert_file_content "$root/gh-pages-worktree/content" precious \
  "validation failure preserves caller WORKTREE_DIR"
assert_equal "$worktrees_before" "$(git -C "$root" worktree list --porcelain)" \
  "validation failure creates no second worktree"

root="$(new_fixture "$Q")"
copy_publisher "$root"
record "$root" >/dev/null
mkdir -p "$root/gh-pages-worktree"
printf 'precious\n' > "$root/gh-pages-worktree/content"
git -C "$root" branch gh-pages
head="$(git -C "$root" rev-parse HEAD)"
worktrees_before="$(git -C "$root" worktree list --porcelain)"
branch_before="$(git -C "$root" rev-parse gh-pages)"
assert_code 0 "SKIP_BUILD DRY_RUN validates only" \
  run_publisher "$root" SKIP_BUILD=1 DRY_RUN=1 EXPECT_QUALIFIER="$Q" EXPECT_HEAD="$head"
assert_absent "$root/mvn-invoked" "SKIP_BUILD DRY_RUN does not invoke mvn"
assert_file_content "$root/gh-pages-worktree/content" precious \
  "SKIP_BUILD DRY_RUN preserves caller WORKTREE_DIR"
assert_equal "$worktrees_before" "$(git -C "$root" worktree list --porcelain)" \
  "SKIP_BUILD DRY_RUN creates no second worktree"
assert_equal "$branch_before" "$(git -C "$root" rev-parse gh-pages)" \
  "SKIP_BUILD DRY_RUN creates no branch commit"

root="$(new_fixture "$Q")"
copy_publisher "$root"
mkdir -p "$root/gh-pages-worktree"
printf 'precious\n' > "$root/gh-pages-worktree/content"
worktrees_before="$(git -C "$root" worktree list --porcelain)"
assert_code 0 "RECORD_PROVENANCE exits before worktree setup" \
  run_publisher "$root" RECORD_PROVENANCE=1
assert_file_content "$root/gh-pages-worktree/content" precious \
  "RECORD_PROVENANCE preserves caller WORKTREE_DIR"
assert_equal "$worktrees_before" "$(git -C "$root" worktree list --porcelain)" \
  "RECORD_PROVENANCE creates no second worktree"

root="$(new_fixture "$Q")"
copy_publisher "$root"
assert_code 99 "default mode builds first" run_publisher "$root" DRY_RUN=1
if [ -f "$root/mvn-invoked" ]; then
  printf 'ok   default mode invoked mvn\n'
else
  printf 'FAIL default mode did not invoke mvn\n'
  FAILURES=$((FAILURES + 1))
fi

printf '%s failures\n' "$FAILURES"
[ "$FAILURES" = 0 ] || exit 1
