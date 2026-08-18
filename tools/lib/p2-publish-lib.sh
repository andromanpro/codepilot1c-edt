#!/usr/bin/env bash
# Helpers for exact-artifact p2 publishing.
# Sourced by tools/publish-p2-local.sh and tools/tests/p2-publish-validate-test.sh.

P2_PROVENANCE_NAME="repository.provenance"

p2_fail() { # <code> <message...>
  local code="$1"; shift
  printf 'p2-publish: %s\n' "$*" >&2
  exit "$code"
}

p2_sha256() { # <file|->
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  elif command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    return 1
  fi
}

p2_digest() { # <p2_dir> -> single digest over every file in the repository
  local dir="$1"
  ( cd "$dir" && find . -type f -print | LC_ALL=C sort \
      | while IFS= read -r f; do printf '%s  %s\n' "$(p2_sha256 "$f")" "$f"; done ) \
    | p2_sha256 /dev/stdin
}

p2_qualifiers() { # <p2_dir> -> unique codepilot qualifiers, one per line
  local dir="$1"
  ls "$dir"/plugins/com.codepilot1c.*.jar "$dir"/features/com.codepilot1c.*.jar 2>/dev/null \
    | sed -e 's#.*/##' -e 's#\.jar$##' -e 's#^.*_##' \
    | LC_ALL=C sort -u
}

p2_head() { # <root_dir>
  git -C "$1" rev-parse HEAD 2>/dev/null || p2_fail 19 "not a git repository: $1"
}

p2_write_provenance() { # <p2_dir> <root_dir>
  local p2 root marker q head dirty digest
  p2="$1"
  root="$2"
  marker="$(dirname "$p2")/$P2_PROVENANCE_NAME"
  command -v shasum >/dev/null 2>&1 || command -v sha256sum >/dev/null 2>&1 \
    || p2_fail 10 "no sha256 tool (shasum/sha256sum) available"
  [ -d "$p2" ] || p2_fail 10 "p2 repository directory not found: $p2"
  q="$(p2_qualifiers "$p2")"
  [ -n "$q" ] || p2_fail 12 "no com.codepilot1c.* artifacts in $p2"
  head="$(git -C "$root" rev-parse HEAD 2>/dev/null)" \
    || p2_fail 19 "not a git repository: $root"
  if [ -n "$(git -C "$root" status --porcelain)" ]; then
    dirty=true
  else
    dirty=false
  fi
  digest="$(p2_digest "$p2")" || p2_fail 10 "could not calculate repository digest"
  printf 'qualifier=%s\nhead=%s\ndirty=%s\ndigest=%s\n' \
    "$(printf '%s' "$q" | tr '\n' ' ' | sed 's/ *$//')" \
    "$head" \
    "$dirty" \
    "$digest" > "$marker"
  printf '%s\n' "$marker"
}

p2_validate() { # <p2_dir> <root_dir> <expect_qualifier> <expect_head> <require_clean_tree:0|1>
  local p2 root expect_q expect_head require_clean marker
  local q head m_q m_head m_dirty m_digest
  p2="$1"
  root="$2"
  expect_q="$3"
  expect_head="$4"
  require_clean="$5"
  marker="$(dirname "$p2")/$P2_PROVENANCE_NAME"

  if [ -n "$expect_head" ]; then
    [[ "$expect_head" =~ ^[[:xdigit:]]{7,40}$ ]] \
      || p2_fail 20 "EXPECT_HEAD must be a 7-40 character hexadecimal commit prefix"
  fi

  command -v shasum >/dev/null 2>&1 || command -v sha256sum >/dev/null 2>&1 \
    || p2_fail 10 "no sha256 tool (shasum/sha256sum) available"
  [ -d "$p2" ] || p2_fail 10 "p2 repository directory not found: $p2"

  [ -s "$p2/content.jar" ] || p2_fail 11 "missing or empty content.jar in $p2"
  [ -s "$p2/artifacts.jar" ] || p2_fail 11 "missing or empty artifacts.jar in $p2"
  if command -v unzip >/dev/null 2>&1; then
    unzip -l "$p2/content.jar" >/dev/null 2>&1 \
      || p2_fail 11 "content.jar is not a readable ZIP archive"
    unzip -p "$p2/content.jar" content.xml >/dev/null 2>&1 \
      || p2_fail 11 "content.jar does not contain content.xml"
    unzip -l "$p2/artifacts.jar" >/dev/null 2>&1 \
      || p2_fail 11 "artifacts.jar is not a readable ZIP archive"
    unzip -p "$p2/artifacts.jar" artifacts.xml >/dev/null 2>&1 \
      || p2_fail 11 "artifacts.jar does not contain artifacts.xml"
  fi

  q="$(p2_qualifiers "$p2")"
  [ -n "$q" ] || p2_fail 12 "no com.codepilot1c.* artifacts in $p2"
  ls "$p2"/features/com.codepilot1c.*.jar >/dev/null 2>&1 || p2_fail 12 "no feature jar in $p2/features"
  ls "$p2"/plugins/com.codepilot1c.core_*.jar >/dev/null 2>&1 || p2_fail 12 "com.codepilot1c.core jar missing"
  ls "$p2"/plugins/com.codepilot1c.ui_*.jar >/dev/null 2>&1 || p2_fail 12 "com.codepilot1c.ui jar missing"
  [ "$(printf '%s\n' "$q" | wc -l | tr -d ' ')" = "1" ] \
    || p2_fail 13 "repository mixes qualifiers: $(printf '%s ' $q)"
  if [ -n "$expect_q" ] && [ "$q" != "$expect_q" ]; then
    p2_fail 14 "qualifier mismatch: repository has $q, expected $expect_q"
  fi
  ls "$p2"/plugins/*.tests_*.jar >/dev/null 2>&1 && p2_fail 15 "tests bundle present in $p2/plugins"
  ls "$p2"/features/*.tests*.jar >/dev/null 2>&1 && p2_fail 15 "tests feature present in $p2/features"

  [ -r "$marker" ] || p2_fail 16 "provenance marker not found: $marker (run RECORD_PROVENANCE=1 first)"
  m_q="$(sed -n 's/^qualifier=//p' "$marker")"
  m_head="$(sed -n 's/^head=//p' "$marker")"
  m_dirty="$(sed -n 's/^dirty=//p' "$marker")"
  m_digest="$(sed -n 's/^digest=//p' "$marker")"
  [ -n "$m_q" ] && [ -n "$m_head" ] && [ -n "$m_dirty" ] && [ -n "$m_digest" ] \
    || p2_fail 16 "unreadable provenance marker: $marker"
  [ "$m_dirty" = "true" ] || [ "$m_dirty" = "false" ] \
    || p2_fail 16 "invalid dirty flag in provenance marker: $m_dirty"
  [ "$m_q" = "$q" ] || p2_fail 17 "provenance qualifier $m_q does not match repository $q"
  [ -z "$expect_q" ] || [ "$m_q" = "$expect_q" ] || p2_fail 17 "provenance qualifier $m_q != EXPECT_QUALIFIER $expect_q"
  [ "$(p2_digest "$p2")" = "$m_digest" ] \
    || p2_fail 18 "artifact changed after provenance was recorded; rebuild or re-record"

  head="$(p2_head "$root")"
  [ "$m_head" = "$head" ] || p2_fail 17 "provenance head $m_head != current HEAD $head"
  if [ -n "$expect_head" ]; then
    case "$head" in
      "$expect_head"*) ;;
      *) p2_fail 19 "HEAD mismatch: expected $expect_head, actual $head" ;;
    esac
  fi
  if [ "$require_clean" = "1" ] && [ "$m_dirty" != "false" ]; then
    p2_fail 17 "artifact provenance was recorded from a dirty working tree"
  fi
  if [ "$require_clean" = "1" ] && [ -n "$(git -C "$root" status --porcelain)" ]; then
    p2_fail 19 "working tree is dirty; publish only from a clean tree"
  fi

  printf '%s\n' "$q"
}
