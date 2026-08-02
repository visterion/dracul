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
  # cd out of any previous case's working directory before removing it —
  # rm -rf-ing the shell's own cwd out from under it produces spurious
  # "fatal: Unable to read current working directory" from every git
  # invocation until the next `cd`.
  cd "$WORK" || exit 1
  rm -rf "$WORK/bare" "$WORK/wc" "$WORK/wc2" "$WORK/other-bare" "$WORK/public-bare"
  git init -q --bare "$WORK/bare"
  # Bare repos default HEAD to refs/heads/master (or init.defaultBranch)
  # regardless of which branch is actually pushed later. Left unfixed, a
  # `git clone` of this bare repo (see case 12) points HEAD at a branch that
  # never exists, git cannot check anything out ("remote HEAD refers to
  # nonexistent ref"), the clone is left on an unborn "master" with no
  # working tree, and any commit made there lands on "master" instead of
  # "main" — silently invalidating whatever that clone was meant to exercise.
  git --git-dir="$WORK/bare" symbolic-ref HEAD refs/heads/main
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

# 7. First push of a NEW branch must exclude commits already published on the
#    remote, even if they happen to match FORBIDDEN — only unpublished
#    commits are in scope. Discriminates a hook that forgot --not/--remotes
#    (which would scan full history, find the already-public CLAUDE.md commit
#    reachable from the new branch, and wrongly BLOCK) from one that excludes
#    correctly (exit 0).
setup
printf 'local operating notes\n' > CLAUDE.md
git add -f CLAUDE.md
git commit -qm "add claude md (already public)"
git push -q --no-verify origin main
git checkout -qb feature
printf 'feature work\n' >> README.md
git add README.md
git commit -qm "feat: work"
check "new branch pushes cleanly (forbidden content already public)" 0 origin feature

# 8. Deleting a remote branch is skipped cleanly. NOTE: this case cannot be
#    made to discriminate hook-vs-no-hook: local_sha is all-zero for a
#    deletion, nothing is being published, and the hook is deliberately a
#    no-op on this path (see the ZERO check in pre-push) — identical
#    behaviour to no hook at all is the correct, intended outcome here, not a
#    gap in coverage.
setup
git checkout -qb doomed
printf 'x\n' >> README.md
git add README.md
git commit -qm "feat: doomed"
git push -q origin doomed
git checkout -q main
check "branch deletion is skipped" 0 origin --delete doomed

# 9. A new branch that itself CARRIES a leak must still block — exclusion of
#    already-published content must not become exclusion of everything.
setup
git checkout -qb feature
printf 'local operating notes\n' > CLAUDE.md
git add -f CLAUDE.md
git commit -qm "feat: work with leak"
check "new branch carrying a leak blocks" 1 origin feature

# 10. A single push touching multiple refs, where only the SECOND ref line
#     carries the leak, must still block. Subshell regression detector: if
#     the ref-line loop were fed via a pipe instead of reading stdin
#     directly, the whole loop body (including this iteration's fail=1) would
#     run in a subshell and be lost when that subshell exits.
setup
git checkout -qb clean-branch
printf 'clean work\n' >> README.md
git add README.md
git commit -qm "feat: clean work"
git checkout -q main
git checkout -qb leaky-branch
printf 'local operating notes\n' > CLAUDE.md
git add -f CLAUDE.md
git commit -qm "feat: leaky work"
git checkout -q main
check "multi-ref push blocks when only the second ref leaks" 1 origin clean-branch leaky-branch

# 11. A file introduced only by a merge commit itself (present on neither
#     parent — the classic "evil merge" / conflict-resolution path) must
#     still block. Plain --name-only gives a merge commit no diff at all; the
#     hook must pass -m to see it.
setup
git checkout -qb side
printf 'side change\n' > side.txt
git add side.txt
git commit -qm "feat: side change"
git checkout -q main
printf 'main change\n' >> README.md
git add README.md
git commit -qm "feat: main change"
git merge --no-commit --no-ff side >/dev/null 2>&1
mkdir -p docs
printf 'private runbook\n' > docs/secret.md
git add -f docs/secret.md
git commit -qm "merge: resolve and add secret"
check "forbidden file introduced only in a merge commit blocks" 1 origin main

# 12. An unresolvable remote_sha (force-push from a stale clone that never
#     fetched the remote's current tip) must fail closed and still scan for
#     forbidden content, not silently no-op via a discarded "fatal: Invalid
#     revision range" from the range form.
setup
git clone -q "$WORK/bare" "$WORK/wc2"
cd "$WORK/wc2" || exit 1
git config user.email dev@example.com
git config user.name "Test Dev"
git config commit.gpgsign false
git config core.hooksPath .githooks
cd "$WORK/wc" || exit 1
printf 'advance main\n' >> README.md
git add README.md
git commit -qm "docs: advance main"
git push -q origin main
cd "$WORK/wc2" || exit 1
printf 'local operating notes\n' > CLAUDE.md
git add -f CLAUDE.md
git commit -qm "feat: leak from stale clone"
check "unresolvable remote_sha fails closed and blocks" 1 --force origin main
cd "$WORK/wc" || exit 1

# 13. A non-ASCII forbidden path must not slip through core.quotePath's
#     quoted, octal-escaped rendering of the path.
setup
mkdir -p docs
printf 'private runbook\n' > 'docs/prüfung.md'
git add -f 'docs/prüfung.md'
git commit -qm "add non-ascii runbook"
check "non-ASCII forbidden path blocks" 1 origin main

# 14. .env.local and .env.production.local must hard-block too — case 6 only
#     exercised bare .env, leaving two-thirds of ENV_HARD untested.
setup
printf 'API_TOKEN=whatever\n' > .env.local
printf 'API_TOKEN=whatever\n' > .env.production.local
git add -f .env.local .env.production.local
git commit -qm "add local env files"
check ".env.local and .env.production.local block" 1 origin main

# 15. Finding 4 coverage: pushing by an entirely unconfigured URL (no remote
#     in this repo points at it) carrying a forbidden file must still block.
#     resolve_remote_name() cannot resolve this URL to a configured remote,
#     so EXCLUDE falls back to the bare, unnamed --remotes form — it must
#     still find and flag the leak rather than silently passing it through.
setup
git init -q --bare "$WORK/other-bare"
git checkout -qb urlbranch
printf 'local operating notes\n' > CLAUDE.md
git add -f CLAUDE.md
git commit -qm "feat: leak via unconfigured url push"
check "push by unconfigured URL with a leak blocks" 1 "$WORK/other-bare" urlbranch

# 16. Finding 5 coverage: an emptied/pruned refs/remotes/origin (never
#     fetched, or manually deleted) carrying a forbidden file on the branch
#     being pushed must still block. REMOTE_NAME resolves to "origin" fine,
#     but there is no tracking data left to narrow against, so EXCLUDE falls
#     back to the bare --remotes form and must still catch the leak.
setup
git update-ref -d refs/remotes/origin/main
git checkout -qb feature-pruned
printf 'local operating notes\n' > CLAUDE.md
git add -f CLAUDE.md
git commit -qm "feat: leak with pruned tracking ref"
check "pruned refs/remotes/origin with a leak blocks" 1 origin feature-pruned

# 17. Cross-remote coverage: a forbidden commit fetched via ONE remote's
#     tracking refs ("private") must not hide the same commit when pushed to
#     a DIFFERENT remote ("public") that has no tracking data of its own
#     (never fetched). The unnamed `--not --remotes` fallback excluded every
#     configured remote's tracking refs, so the leak — visible only via
#     private/main — was invisible when finally pushed to public. The fix is
#     EXCLUDE="" (exclude nothing) in the no-tracking-data fallback; this
#     case must still block.
setup
git remote rename origin private
git init -q --bare "$WORK/public-bare"
git remote add public "$WORK/public-bare"
printf 'local operating notes\n' > CLAUDE.md
git add -f CLAUDE.md
git commit -qm "feat: leak fetched via private remote"
git push -q --no-verify private main
check "leak visible only via a different remote's tracking refs still blocks" 1 public main

printf '\n%s passed, %s failed\n' "$passes" "$failures"
[ "$failures" -eq 0 ] || exit 1
exit 0
