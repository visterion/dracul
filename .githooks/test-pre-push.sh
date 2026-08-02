#!/bin/sh
# Exercises .githooks/pre-push against a throwaway bare repo.
# No network, no contact with origin. Run: sh .githooks/test-pre-push.sh
set -u

HOOK_SRC=$(cd "$(dirname "$0")" && pwd)/pre-push
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

passes=0
failures=0

# Fresh bare remote + working clone with the hook installed, one pushed commit.
setup() {
  rm -rf "$WORK/bare" "$WORK/wc"
  git init -q --bare "$WORK/bare"
  git init -q -b main "$WORK/wc"
  cd "$WORK/wc" || exit 1
  git config user.email dev@example.com
  git config user.name "Test Dev"
  git config commit.gpgsign false
  mkdir -p .githooks
  cp "$HOOK_SRC" .githooks/pre-push
  chmod +x .githooks/pre-push
  git config core.hooksPath .githooks
  printf 'hello\n' > README.md
  git add README.md .githooks/pre-push
  git commit -qm "init"
  git remote add origin "$WORK/bare"
  git push -q origin main
}

# check <name> <expected-exit> <push-args...>
check() {
  name=$1
  want=$2
  shift 2
  out=$(git push "$@" 2>&1)
  got=$?
  if [ "$got" -eq "$want" ]; then
    passes=$((passes + 1))
    printf 'PASS  %s\n' "$name"
  else
    failures=$((failures + 1))
    printf 'FAIL  %s (exit %s, wanted %s)\n' "$name" "$got" "$want"
    printf '%s\n' "$out" | sed 's/^/      /'
  fi
}

# 1. A clean push must succeed.
setup
printf 'ordinary change\n' >> README.md
git add README.md
git commit -qm "docs: ordinary change"
check "clean push passes" 0 origin main

# 2. A force-added CLAUDE.md must block.
setup
printf 'local operating notes\n' > CLAUDE.md
git add -f CLAUDE.md
git commit -qm "add claude md"
check "force-added CLAUDE.md blocks" 1 origin main

# 3. A file under docs/ must block.
setup
mkdir -p docs
printf 'private runbook\n' > docs/runbook.md
git add -f docs/runbook.md
git commit -qm "add runbook"
check "docs/ blocks" 1 origin main

# 4. A change under .claude/ must block, even for an already-tracked path.
setup
mkdir -p .claude
printf '{}\n' > .claude/settings.json
git add -f .claude/settings.json
git commit -qm "add settings"
git push -q --no-verify origin main
printf '{"hooks":{}}\n' > .claude/settings.json
git add .claude/settings.json
git commit -qm "modify settings"
check "modifying a tracked .claude/ file blocks" 1 origin main

# 5. .env.example and .env.development must NOT block.
setup
printf 'API_TOKEN=your-token-here\n' > .env.example
printf 'VITE_MOCK=false\n' > .env.development
git add .env.example .env.development
git commit -qm "chore: env examples"
check ".env.example and .env.development pass" 0 origin main

# 6. A bare .env must block.
setup
printf 'API_TOKEN=whatever\n' > .env
git add -f .env
git commit -qm "add env"
check "bare .env blocks" 1 origin main

# 7. First push of a NEW branch inspects only that branch's commits.
setup
git checkout -qb feature
printf 'feature work\n' >> README.md
git add README.md
git commit -qm "feat: work"
check "new branch pushes cleanly" 0 origin feature

# 8. Deleting a remote branch is skipped cleanly.
setup
git checkout -qb doomed
printf 'x\n' >> README.md
git add README.md
git commit -qm "feat: doomed"
git push -q origin doomed
git checkout -q main
check "branch deletion is skipped" 0 origin --delete doomed

printf '\n%s passed, %s failed\n' "$passes" "$failures"
[ "$failures" -eq 0 ] || exit 1
exit 0
