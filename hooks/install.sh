#!/usr/bin/env bash
# Installs the pre-push drift guard WITHOUT touching core.hooksPath, so any
# existing pre-commit (code-review-graph) keeps working. The installed hook is
# a one-line shim that always execs the tracked hooks/pre-push of the pushed
# worktree — so updates to the tracked hook need no re-install.
set -euo pipefail
hooks_dir="$(git rev-parse --git-path hooks)"
target="$hooks_dir/pre-push"
if [ -e "$target" ] && ! grep -q 'hooks/pre-push' "$target" 2>/dev/null; then
  echo "Refusing to overwrite an unrelated existing pre-push at $target" >&2
  echo "Chain it manually or remove it first." >&2
  exit 1
fi
cat > "$target" <<'SHIM'
#!/usr/bin/env bash
exec "$(git rev-parse --show-toplevel)/hooks/pre-push" "$@"
SHIM
chmod +x "$target"
echo "Installed pre-push drift guard → $target (core.hooksPath untouched)"
