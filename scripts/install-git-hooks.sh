#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOOKS_SRC_DIR="$ROOT_DIR/scripts/git-hooks"
GIT_DIR="$ROOT_DIR/.git"
HOOKS_DST_DIR="$GIT_DIR/hooks"

if [[ ! -d "$GIT_DIR" ]]; then
  echo "Error: .git directory not found at: $GIT_DIR" >&2
  echo "Run this script from the repository root, or keep the default layout." >&2
  exit 1
fi

mkdir -p "$HOOKS_DST_DIR"

install_hook () {
  local name="$1"
  local src="$HOOKS_SRC_DIR/$name"
  local dst="$HOOKS_DST_DIR/$name"

  if [[ ! -f "$src" ]]; then
    echo "Error: missing hook source: $src" >&2
    exit 1
  fi

  cp "$src" "$dst"
  chmod +x "$dst"
  echo "Installed git hook: $name"
}

install_hook "commit-msg"

echo "Done."
