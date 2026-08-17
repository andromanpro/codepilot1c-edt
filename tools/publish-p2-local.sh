#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=tools/lib/p2-publish-lib.sh
. "$ROOT_DIR/tools/lib/p2-publish-lib.sh"

P2_DIR="$ROOT_DIR/repositories/com.codepilot1c.update/target/repository"
WORKTREE_DIR="${WORKTREE_DIR:-$(mktemp -d -t codepilot1c-gh-pages.XXXXXX)}"
WORKTREE_DIR_REAL="$(cd "$WORKTREE_DIR" && pwd -P)"
REMOTE_NAME="${REMOTE_NAME:-origin}"
BRANCH_NAME="${BRANCH_NAME:-gh-pages}"

SKIP_BUILD="${SKIP_BUILD:-0}"
DRY_RUN="${DRY_RUN:-0}"
RECORD_PROVENANCE="${RECORD_PROVENANCE:-0}"
EXPECT_QUALIFIER="${EXPECT_QUALIFIER:-}"
EXPECT_HEAD="${EXPECT_HEAD:-}"

cleanup() {
  if git -C "$ROOT_DIR" worktree list | grep -q "$WORKTREE_DIR_REAL"; then
    git -C "$ROOT_DIR" worktree remove --force "$WORKTREE_DIR_REAL" >/dev/null 2>&1 || true
  fi
  rm -rf "$WORKTREE_DIR_REAL" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [[ "$RECORD_PROVENANCE" == "1" ]]; then
  [[ "$SKIP_BUILD" != "1" ]] || p2_fail 20 "RECORD_PROVENANCE=1 and SKIP_BUILD=1 are mutually exclusive"
  marker="$(p2_write_provenance "$P2_DIR" "$ROOT_DIR")"
  echo "Recorded provenance: $marker"
  cat "$marker"
  exit 0
fi

if [[ "$SKIP_BUILD" == "1" ]]; then
  [[ -n "$EXPECT_QUALIFIER" ]] || p2_fail 20 "SKIP_BUILD=1 requires EXPECT_QUALIFIER=<exact qualifier>"
  [[ -n "$EXPECT_HEAD" ]] || p2_fail 20 "SKIP_BUILD=1 requires EXPECT_HEAD=<commit>"
  echo "[1/5] SKIP_BUILD=1 — publishing the artifact already on disk, no rebuild"
else
  echo "[1/5] Building update site locally..."
  (
    cd "$ROOT_DIR"
    mvn -B -V --no-transfer-progress clean verify
  )
  p2_write_provenance "$P2_DIR" "$ROOT_DIR" >/dev/null
fi

PUBLISH_QUALIFIER="$(p2_validate "$P2_DIR" "$ROOT_DIR" "$EXPECT_QUALIFIER" "$EXPECT_HEAD" "$SKIP_BUILD")"
PUBLISH_HEAD="$(git -C "$ROOT_DIR" rev-parse --short HEAD)"
echo "Validated: qualifier=$PUBLISH_QUALIFIER head=$PUBLISH_HEAD skip_build=$SKIP_BUILD"

if [[ "$DRY_RUN" == "1" ]]; then
  echo "DRY_RUN=1 — validation only, nothing published."
  exit 0
fi

echo "[2/5] Preparing landing pages..."
mkdir -p "$P2_DIR/site"
cp "$ROOT_DIR/site/index.html" "$P2_DIR/site/index.html"
cp "$ROOT_DIR/site/root-index.html" "$P2_DIR/index.html"

echo "[3/5] Preparing $BRANCH_NAME worktree..."
git -C "$ROOT_DIR" fetch "$REMOTE_NAME" "$BRANCH_NAME" >/dev/null 2>&1 || true

if git -C "$ROOT_DIR" show-ref --verify --quiet "refs/remotes/$REMOTE_NAME/$BRANCH_NAME"; then
  git -C "$ROOT_DIR" worktree add --force --detach "$WORKTREE_DIR_REAL" "$REMOTE_NAME/$BRANCH_NAME"
else
  git -C "$ROOT_DIR" worktree add --force --detach "$WORKTREE_DIR_REAL"
  (
    cd "$WORKTREE_DIR_REAL"
    git checkout --orphan "$BRANCH_NAME"
    git rm -rf . >/dev/null 2>&1 || true
  )
fi

echo "[4/5] Syncing p2 content into $BRANCH_NAME..."
rsync -a --delete --exclude='.git' "$P2_DIR/" "$WORKTREE_DIR_REAL/"

(
  cd "$WORKTREE_DIR_REAL"
  WT_TOP="$(git rev-parse --show-toplevel)"
  if [[ "$WT_TOP" != "$WORKTREE_DIR_REAL" ]]; then
    echo "Refusing to publish: unexpected worktree root '$WT_TOP'" >&2
    exit 1
  fi
  git add -A
  if git diff --cached --quiet; then
    echo "No changes to publish."
    exit 0
  fi
  git commit -m "Publish p2 $PUBLISH_QUALIFIER from $PUBLISH_HEAD"
  echo "[5/5] Pushing to $REMOTE_NAME/$BRANCH_NAME..."
  git push "$REMOTE_NAME" HEAD:"$BRANCH_NAME"
)

echo "Done. GitHub Pages branch updated: $REMOTE_NAME/$BRANCH_NAME"
