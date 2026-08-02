#!/bin/sh
# Exercises .githooks/pre-push against a throwaway bare repo.
# No network, no contact with origin. Run: sh .githooks/test-pre-push.sh
set -u

# ------------------------------------------------------------------ hermetic
# setup() pins user.email/user.name/commit.gpgsign per repo, but that only
# covers three keys of ONE config level. Everything else was inherited from the
# contributor's ~/.gitconfig and environment, so a green 62/62 was not proof:
# core.quotePath, core.autocrlf, diff.*.textconv, merge.ff or init.defaultBranch
# set globally all change what the hook sees, and cases 13, 20-22 and 35-37
# exist precisely because this behaviour is config-sensitive. Neutralise the
# ambient state instead of hoping it is empty.
GIT_CONFIG_GLOBAL=/dev/null
GIT_CONFIG_SYSTEM=/dev/null
export GIT_CONFIG_GLOBAL GIT_CONFIG_SYSTEM
# GIT_DIR/GIT_WORK_TREE would point every git call in this script at the
# INVOKING repository instead of the throwaway fixture — e.g. when the suite is
# run from a hook or a wrapper that exports them.
unset GIT_DIR GIT_WORK_TREE

# Pin the locale — but to a UTF-8 one where possible, NOT to C. The
# invalid-UTF-8 (case 20) and non-ASCII (case 13) path cases only discriminate
# when the hook INHERITS a UTF-8 locale, because that is the condition under
# which GNU grep declares such input binary and emits nothing; running the
# whole suite under LC_ALL=C would make those cases pass against a hook that
# had lost its own LC_ALL=C guards.
if locale -a 2>/dev/null | grep -qix 'C\.utf-\?8'; then
  LC_ALL=$(locale -a 2>/dev/null | grep -ix 'C\.utf-\?8' | head -1)
elif locale -a 2>/dev/null | grep -qix 'en_US\.utf-\?8'; then
  LC_ALL=$(locale -a 2>/dev/null | grep -ix 'en_US\.utf-\?8' | head -1)
else
  LC_ALL=C
fi
LANG=$LC_ALL
export LC_ALL LANG
unset LC_CTYPE LC_COLLATE LC_MESSAGES 2>/dev/null || true

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

# check_silent <name> <push-args...>
# Stronger than check(<name> 0 ...): a clean push must exit 0 AND print
# NOTHING. Exit 0 alone is what let the hook ship while it warned about its own
# test fixtures on every push that touched .githooks/ — unavoidable,
# unsuppressable noise trains the operator into --no-verify, which is the one
# failure mode that turns the entire guard off. `git push -q` silences git's
# own progress/summary lines; anything left on stdout or stderr came from the
# hook.
check_silent() {
  name=$1
  shift
  out=$(git push -q "$@" 2>&1)
  got=$?
  if [ "$got" -eq 0 ] && [ -z "$out" ]; then
    passes=$((passes + 1))
    printf 'PASS  %s\n' "$name"
  else
    failures=$((failures + 1))
    printf 'FAIL  %s (exit %s, wanted 0 with no output)\n' "$name" "$got"
    printf '%s\n' "$out" | sed 's/^/      /'
  fi
}

# 1. A clean push must succeed — silently.
setup
printf 'ordinary change\n' >> README.md
git add README.md
git commit -qm "docs: ordinary change"
check_silent "clean push passes with no output" origin main

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

# 18. pushurl divergence: refs/remotes/<name>/* reflect the FETCH url, so once
#     remote.<name>.pushurl points somewhere else they say nothing about what
#     the push TARGET already has. Here the leak is genuinely published on the
#     private fetch remote (origin's tracking refs carry it), then origin's
#     pushurl is repointed at a PUBLIC bare repo and a new branch is pushed:
#     scoping the exclusion by remote NAME alone would drop the leaking commit
#     as "already published" and let it land in the public repo with exit 0.
#     The hook must notice push-url != fetch-url, de-narrow to the
#     full-history fallback, and block.
setup
git init -q --bare "$WORK/public-bare"
printf 'local operating notes\n' > CLAUDE.md
git add -f CLAUDE.md
git commit -qm "feat: leak published on the private fetch remote"
git push -q --no-verify origin main
git fetch -q origin
git config remote.origin.pushurl "$WORK/public-bare"
git checkout -qb feat-pushurl
printf 'feature work\n' >> README.md
git add README.md
git commit -qm "feat: work"
check "pushurl divergence to a different repo still blocks" 1 origin feat-pushurl
# Prove the leak really would have landed: the public bare must NOT have it.
if git --git-dir="$WORK/public-bare" ls-tree -r --name-only feat-pushurl 2>/dev/null | grep -q '^CLAUDE\.md$'; then
  failures=$((failures + 1))
  printf 'FAIL  %s\n' "pushurl divergence: CLAUDE.md reached the public bare repo"
else
  passes=$((passes + 1))
  printf 'PASS  %s\n' "pushurl divergence: public bare repo has no CLAUDE.md"
fi
git config --unset remote.origin.pushurl

# 19. Type change (git diff status T): a forbidden path already published as a
#     harmless SYMLINK (AGENTS.md -> CLAUDE.md is exactly this repo's layout)
#     replaced by a real file carrying the private operating detail. The change
#     is neither an add nor a modify, so an ACMR allow-list never saw it and
#     the content landed on the remote with exit 0. Must block.
setup
ln -s README.md AGENTS.md
git add -f AGENTS.md
git commit -qm "add agents symlink"
git push -q --no-verify origin main
rm AGENTS.md
printf 'synthetic operating notes\n' > AGENTS.md
git add AGENTS.md
git commit -qm "replace symlink with a real file"
check "symlink-to-file type change on a forbidden path blocks" 1 origin main

# assert_absent <name> <git-dir> <ref> <fixed-string>
# An exit code does not prove the file never landed: assert on the TARGET
# repo's tree. Reads paths NUL-separated and matches in the C locale, because
# the very paths under test are the ones that make a UTF-8 grep give up.
assert_absent() {
  if git --git-dir="$2" ls-tree -r --name-only -z "$3" 2>/dev/null \
     | tr '\0' '\n' | LC_ALL=C grep -qF "$4"; then
    failures=$((failures + 1))
    printf 'FAIL  %s (found %s in the remote tree)\n' "$1" "$4"
  else
    passes=$((passes + 1))
    printf 'PASS  %s\n' "$1"
  fi
}

# assert_content_absent <name> <git-dir> <ref> <path> <fixed-string>
# For cases where the PATH legitimately already exists in the remote tree
# (e.g. published earlier as a harmless symlink) and a path-only check like
# assert_absent would pass vacuously — the content at that path is what must
# never carry the leaking string.
assert_content_absent() {
  if git --git-dir="$2" show "$3:$4" 2>/dev/null | LC_ALL=C grep -qF "$5"; then
    failures=$((failures + 1))
    printf 'FAIL  %s (found %s in %s at %s)\n' "$1" "$5" "$4" "$3"
  else
    passes=$((passes + 1))
    printf 'PASS  %s\n' "$1"
  fi
}

# 20. A path containing an INVALID UTF-8 byte. GNU grep in a UTF-8 locale (the
#     hook inherits the user's LANG) treats such input as binary: it prints
#     "binary file matches" to stderr and emits NO lines, so HITS came back
#     empty for the WHOLE range — one such filename disabled every rule at
#     once. The file reached the remote at exit 0. Must block.
setup
mkdir -p docs
printf 'synthetic notes\n' > "$(printf 'docs/le\377ak.md')"
git add -f docs
git commit -qm "add path with an invalid utf-8 byte"
check "invalid-UTF-8 byte in a path blocks" 1 origin main
assert_absent "invalid-UTF-8 path never reached the remote tree" "$WORK/bare" main "ak.md"

# 21. A path containing a NEWLINE. core.quotePath=false only suppresses quoting
#     of high-bit bytes; newlines, quotes, backslashes and control characters
#     are ALWAYS C-quoted, and the leading `"` defeats the ^ anchor. Pushed at
#     exit 0 and landed in the remote tree. Must block.
setup
mkdir -p docs
printf 'synthetic notes\n' > "$(printf 'docs/leak\ntwo.md')"
git add -f docs
git commit -qm "add path with an embedded newline"
check "newline in a path blocks" 1 origin main
assert_absent "newline path never reached the remote tree" "$WORK/bare" main "two.md"

# 22. A path containing a DOUBLE QUOTE — same C-quoting bypass as case 21, via
#     a different trigger character.
setup
mkdir -p docs
printf 'synthetic notes\n' > 'docs/qu"ote.md'
git add -f 'docs/qu"ote.md'
git commit -qm "add path with a double quote"
check "double quote in a path blocks" 1 origin main
assert_absent "double-quote path never reached the remote tree" "$WORK/bare" main 'qu"ote.md'

# 23. Owner ruling: CLAUDE.md is local-only at ANY depth, not just at the root.
setup
mkdir -p java-server
printf 'synthetic module notes\n' > java-server/CLAUDE.md
git add -f java-server/CLAUDE.md
git commit -qm "add nested claude md"
check "nested CLAUDE.md blocks" 1 origin main

# 24. Owner ruling: a nested .claude/ directory is local-only too.
setup
mkdir -p chronicle/.claude
printf '{}\n' > chronicle/.claude/settings.json
git add -f chronicle/.claude/settings.json
git commit -qm "add nested claude dir"
check "nested .claude/ file blocks" 1 origin main

# 25. The deliberate asymmetry: docs/ stays ROOT-anchored. A vendored docs/
#     tree inside a subproject is legitimate content and must NOT block — a
#     hook that blocks legitimate work trains the operator into --no-verify.
setup
mkdir -p some-module/docs
printf 'public module docs\n' > some-module/docs/x.md
git add some-module/docs/x.md
git commit -qm "docs: module documentation"
check "nested some-module/docs/ does NOT block" 0 origin main

# check_warns <name> <expected-substring> <push-args...>
# Same as check() but for exit-0 cases where a WARN: line must actually have
# printed — an exit code alone cannot prove a warning ever fired.
check_warns() {
  name=$1
  substr=$2
  shift 2
  out=$(git push "$@" 2>&1)
  got=$?
  if [ "$got" -eq 0 ] && printf '%s\n' "$out" | grep -qF "$substr"; then
    passes=$((passes + 1))
    printf 'PASS  %s\n' "$name"
  else
    failures=$((failures + 1))
    printf 'FAIL  %s (exit %s, wanted 0 with warning %s)\n' "$name" "$got" "$substr"
    printf '%s\n' "$out" | sed 's/^/      /'
  fi
}

# 26. A credential-shaped token in an added line must block.
setup
printf 'token = "ghp_0000000000000000000000000000000000"\n' > config.txt
git add config.txt
git commit -qm "chore: config"
check "credential pattern blocks" 1 origin main
assert_absent "credential-bearing config.txt never reached the remote tree" "$WORK/bare" main "config.txt"

# 27. Credential patterns inside .githooks/ must NOT block (the hook itself
#     legitimately contains them, to define the very regex above).
setup
printf '# matches ghp_ and sk-ant- deliberately\n' >> .githooks/pre-push
git add .githooks/pre-push
git commit -qm "chore: hook comment"
check "credential patterns in .githooks/ pass" 0 origin main

# 28. Internal infrastructure warns but does not block.
setup
printf 'host: 192.168.1.10\n' > notes.txt
git add notes.txt
git commit -qm "chore: notes"
check_warns "internal infrastructure warns only" "WARN: outgoing commits mention internal infrastructure." origin main

# 29. A non-example email address warns but does not block.
setup
printf 'contact: jane.doe@personalmail.invalid\n' > contact.txt
git add contact.txt
git commit -qm "chore: contact"
check_warns "personal email warns only" "jane.doe@personalmail.invalid" origin main

# 30. Owner ruling: FORBIDDEN/ENV_HARD match case-insensitively. `claude.md` IS
#     `CLAUDE.md` on a case-insensitive filesystem (macOS) and must block too.
setup
printf 'local operating notes\n' > claude.md
git add -f claude.md
git commit -qm "add lowercase claude md"
check "lowercase claude.md blocks" 1 origin main
assert_absent "lowercase claude.md never reached the remote tree" "$WORK/bare" main "claude.md"

# 31. Owner ruling: a differently-cased root Docs/ must block under the same
#     case-insensitive match — accepted trade-off, no such directory exists in
#     this repo.
setup
mkdir -p Docs
printf 'private runbook\n' > Docs/runbook.md
git add -f Docs/runbook.md
git commit -qm "add capitalized docs runbook"
check "Docs/runbook.md blocks" 1 origin main
assert_absent "Docs/runbook.md never reached the remote tree" "$WORK/bare" main "Docs/runbook.md"

# 32. The case-insensitive match must NOT widen the deliberate nested-docs
#     asymmetry: some-module/docs/ (any case) still only blocks when the
#     ROOT-anchored docs/ prefix matches, so a differently-cased nested docs
#     dir must still pass.
setup
mkdir -p some-module/Docs
printf 'public module docs\n' > some-module/Docs/x.md
git add some-module/Docs/x.md
git commit -qm "docs: module documentation, capitalized dir"
check "nested some-module/Docs/ does NOT block" 0 origin main

# 33. Fix-round-1 CRITICAL 1: a credential introduced only by a MERGE commit
#     (added during conflict resolution, present on neither parent) must
#     still block the content scan — plain `git log -p` gives a merge commit
#     no diff at all without `-m`, silencing HARD BLOCK 2 for the whole
#     merge-introduced range.
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
printf 'token = "ghp_0000000000000000000000000000000000"\n' > config.txt
git add config.txt
git commit -qm "merge: resolve and add a credential"
check "credential introduced only in a merge commit blocks" 1 origin main
assert_absent "merge-introduced credential never reached the remote tree" "$WORK/bare" main "config.txt"

# 34. Fix-round-1 CRITICAL 2: a credential arriving via a type change (a
#     committed SYMLINK replaced by a real file) must still block —
#     --diff-filter=ACMR silently drops T (type change).
setup
ln -s README.md config.txt
git add -f config.txt
git commit -qm "add config symlink"
git push -q --no-verify origin main
rm config.txt
printf 'token = "ghp_0000000000000000000000000000000000"\n' > config.txt
git add config.txt
git commit -qm "replace symlink with a credential-bearing file"
check "credential via symlink-to-file type change blocks" 1 origin main
# config.txt as a PATH is already legitimately in the remote tree (published
# earlier as the harmless symlink) — assert_absent would pass vacuously.
# What must be absent is the credential CONTENT at that path.
assert_content_absent "type-change credential never reached the remote tree" "$WORK/bare" main "config.txt" "ghp_0000000000000000000000000000000000"

# 35. Fix-round-1 CRITICAL 3: a NUL byte ANYWHERE in the pushed range — not
#     necessarily in the leaking file — must not blind the content scan for
#     the whole ref. asset.dat's NUL sits past git's ~8000-byte binary-sniff
#     window, so git itself diffs it as text; without `grep -a` a bare NUL in
#     that text still makes grep declare the WHOLE captured stream binary and
#     emit nothing, disabling HARD BLOCK 2 for config.txt's credential too.
setup
awk 'BEGIN{for(i=0;i<8500;i++) printf "a"}' > asset.dat
printf '\0' >> asset.dat
printf 'token = "ghp_0000000000000000000000000000000000"\n' > config.txt
git add asset.dat config.txt
git commit -qm "add a large NUL-suffixed asset alongside a credential"
check "NUL byte elsewhere in the range does not blind the credential scan" 1 origin main
assert_absent "credential alongside a large NUL-suffixed asset never reached the remote tree" "$WORK/bare" main "config.txt"

# 36. Fix-round-1 IMPORTANT 4a: a file git itself classifies as binary (a NUL
#     within the first ~8000 bytes) must still be content-scanned — without
#     `--text`, git emits "Binary files ... differ" instead of a patch and any
#     credential inside is invisible.
setup
printf 'token = "ghp_0000000000000000000000000000000000"\n' > config.bin
printf '\0' >> config.bin
git add -f config.bin
git commit -qm "add a git-classified-binary credential file"
check "credential in a git-classified-binary file blocks" 1 origin main
assert_absent "binary-classified credential file never reached the remote tree" "$WORK/bare" main "config.bin"

# 37. Fix-round-1 IMPORTANT 4b: a `-diff` gitattribute forces git to treat an
#     otherwise plain-text file as binary regardless of content — a one-line,
#     entirely plausible way to deliberately defeat the guard without `--text`.
setup
printf 'secret.conf -diff\n' > .gitattributes
printf 'token = "ghp_0000000000000000000000000000000000"\n' > secret.conf
git add .gitattributes secret.conf
git commit -qm "add gitattributes-forced-binary credential file"
check "credential in a -diff gitattributes file blocks" 1 origin main
assert_absent "gitattributes-forced-binary credential file never reached the remote tree" "$WORK/bare" main "secret.conf"

# 38. A forbidden path already in the REMOTE's tree but NOT in the pushed range
#     warns only — this is the one check that can surface a leak already on the
#     remote, since it reads the tree, not a commit range.
setup
mkdir -p .claude
printf '{}\n' > .claude/settings.json
git add -f .claude/settings.json
git commit -qm "add settings"
git push -q --no-verify origin main
printf 'unrelated change\n' >> README.md
git add README.md
git commit -qm "docs: unrelated"
check_warns "pre-existing tree leak warns only" "WARN: local-only paths are ALREADY published on the remote" origin main

# 39. An oversized CLAUDE.md warns but does not block. Written into the
#     working tree WITHOUT staging it — CLAUDE.md is gitignored and the hook
#     reads it from disk, so it must never be committed by this test either.
#     21000 bytes, comfortably over the 20 KB (20480-byte) budget.
setup
awk 'BEGIN { for (i = 0; i < 21000; i++) printf "x" }' > CLAUDE.md
printf 'unrelated change\n' >> README.md
git add README.md
git commit -qm "docs: unrelated"
check_warns "oversized CLAUDE.md warns only" "WARN: CLAUDE.md is" origin main
rm -f CLAUDE.md

# 40. Fix-round-2 MINOR 3: a branch DELETION (local_sha all-zero) must not
#     trigger the CLAUDE.md size WARN even with an oversized CLAUDE.md present
#     in the working tree — that path is documented (see the ZERO check near
#     the top of the loop) to behave exactly like no hook installed at all;
#     the size check must live inside the ref loop, past that `continue`, not
#     after `done` where it would run unconditionally once per hook
#     invocation regardless of what (if anything) was actually pushed.
setup
awk 'BEGIN { for (i = 0; i < 21000; i++) printf "x" }' > CLAUDE.md
git checkout -qb doomed-size
printf 'x\n' >> README.md
git add README.md
git commit -qm "feat: doomed"
git push -q origin doomed-size
git checkout -q main
out=$(git push origin --delete doomed-size 2>&1)
got=$?
if [ "$got" -eq 0 ] && ! printf '%s\n' "$out" | grep -q "WARN: CLAUDE.md is"; then
  passes=$((passes + 1))
  printf 'PASS  %s\n' "branch deletion does not trigger the CLAUDE.md size WARN"
else
  failures=$((failures + 1))
  printf 'FAIL  %s (exit %s)\n' "branch deletion does not trigger the CLAUDE.md size WARN" "$got"
  printf '%s\n' "$out" | sed 's/^/      /'
fi
rm -f CLAUDE.md

# --- optional local email allow-list: all four states ---
# allowed-emails lives at --git-common-dir, never staged/committed by design.

# 41. State 1/4: no allow-list file at all — behaves exactly as before.
setup
printf 'contact: jane.doe@personalmail.invalid\n' > contact.txt
git add contact.txt
git commit -qm "chore: contact"
check_warns "no allow-list file: personal email still warns" "jane.doe@personalmail.invalid" origin main

# 42. State 2/4: allow-list file present but does NOT contain the address —
#     still warns.
setup
printf 'other.person@example.invalid\n' > "$(git rev-parse --git-common-dir)/allowed-emails"
printf 'contact: jane.doe@personalmail.invalid\n' > contact.txt
git add contact.txt
git commit -qm "chore: contact"
check_warns "allow-list without the address: personal email still warns" "jane.doe@personalmail.invalid" origin main

# 43. State 3/4: allow-list file present WITH the address (case-insensitive,
#     plus comment/blank-line/whitespace noise to exercise the parsing rules)
#     — that address's warning is suppressed, but a SECOND, non-allow-listed
#     address in the SAME push still warns. Fix-round-2 MINOR 1: the previous
#     version of this case only asserted the allow-listed address's absence,
#     which "no output at all" (e.g. a no-op hook) satisfies vacuously; a
#     second address that must actually be printed is what makes this
#     discriminate.
setup
printf '# personal allow-list, local only\n\n  Jane.Doe@PersonalMail.invalid  \n' \
  > "$(git rev-parse --git-common-dir)/allowed-emails"
printf 'contact: jane.doe@personalmail.invalid\nother: other.person@personalmail.invalid\n' > contact.txt
git add contact.txt
git commit -qm "chore: contact"
out=$(git push origin main 2>&1)
got=$?
if [ "$got" -eq 0 ] \
   && ! printf '%s\n' "$out" | grep -qF "jane.doe@personalmail.invalid" \
   && printf '%s\n' "$out" | grep -qF "other.person@personalmail.invalid"; then
  passes=$((passes + 1))
  printf 'PASS  %s\n' "allow-listed address suppressed, a different address in the same push still warns"
else
  failures=$((failures + 1))
  printf 'FAIL  %s (exit %s)\n' "allow-listed address suppressed, a different address in the same push still warns" "$got"
  printf '%s\n' "$out" | sed 's/^/      /'
fi

# 44. State 4/4: allow-list path exists but is unreadable as a file (here: a
#     directory sits at that path — reproducible under any uid, including
#     root, unlike a permission bit) — behaves exactly like absent, still
#     warns.
setup
allowpath="$(git rev-parse --git-common-dir)/allowed-emails"
mkdir -p "$allowpath"
printf 'contact: jane.doe@personalmail.invalid\n' > contact.txt
git add contact.txt
git commit -qm "chore: contact"
check_warns "unreadable allow-list (directory in the way): personal email still warns" "jane.doe@personalmail.invalid" origin main

# 45. Fix-round-2 CRITICAL 1a: a malformed allow-list line (a bare regex
#     metacharacter) must not silence the MAILS warning for the WHOLE push.
#     Reproduced pre-fix against GNU grep 3.12: matching one allow-list line
#     at a time via `grep -vix "$line"` made `[` trip "Invalid regular
#     expression" (exit 2, no output), and the old `|| true` swallowed that
#     and assigned the empty output back over $MAILS — blanking the warning
#     for every address in the range, not just the malformed line's own.
setup
printf '[\n' > "$(git rev-parse --git-common-dir)/allowed-emails"
printf 'contact: jane.doe@personalmail.invalid\n' > contact.txt
git add contact.txt
git commit -qm "chore: contact"
check_warns "malformed allow-list line ([) does not silence the MAILS warning" "jane.doe@personalmail.invalid" origin main

# 46. Fix-round-2 CRITICAL 1b: an over-long allow-list line (~200000 bytes)
#     must not silence the MAILS warning either — the old per-line
#     `grep -vix "$line"` passed the line as an argv element and could trip
#     E2BIG, with the same swallow-and-blank consequence as case 45.
setup
awk 'BEGIN { for (i = 0; i < 200000; i++) printf "x" }' > "$(git rev-parse --git-common-dir)/allowed-emails"
printf 'contact: jane.doe@personalmail.invalid\n' > contact.txt
git add contact.txt
git commit -qm "chore: contact"
check_warns "over-long allow-list line does not silence the MAILS warning" "jane.doe@personalmail.invalid" origin main

# 47. Fix-round-2 IMPORTANT 1: a `.` in an allow-listed address must not act
#     as a regex wildcard onto a DIFFERENT address — allow-listing
#     jane.doe@corp.invalid must not also suppress jane-doe@corp.invalid.
setup
printf 'jane.doe@corp.invalid\n' > "$(git rev-parse --git-common-dir)/allowed-emails"
printf 'contact: jane-doe@corp.invalid\n' > contact.txt
git add contact.txt
git commit -qm "chore: contact"
check_warns "dot in an allow-listed address is literal, not a wildcard" "jane-doe@corp.invalid" origin main

# 48. The allow-list must never suppress a HARD BLOCK — only the MAILS
#     warning. Same push carries an allow-listed address AND a credential;
#     the credential must still block.
setup
printf 'jane.doe@personalmail.invalid\n' > "$(git rev-parse --git-common-dir)/allowed-emails"
printf 'contact: jane.doe@personalmail.invalid\ntoken = "ghp_0000000000000000000000000000000000"\n' > contact.txt
git add contact.txt
git commit -qm "chore: contact with credential"
check "allow-listed email cannot suppress the credential hard block" 1 origin main

# 49. The allow-list must never suppress HARD BLOCK 1 (forbidden path), even
#     with a deliberately hostile list naming the exact forbidden file.
setup
printf 'CLAUDE.md\nAGENTS.md\n' > "$(git rev-parse --git-common-dir)/allowed-emails"
printf 'local operating notes\n' > CLAUDE.md
git add -f CLAUDE.md
git commit -qm "add claude md despite hostile allow-list"
check "hostile allow-list cannot suppress the forbidden-path hard block" 1 origin main
rm -f CLAUDE.md

# 50. The allow-list must never suppress the INFRA warning, even with a
#     deliberately hostile list naming the exact infrastructure string.
setup
printf '192.168.1.10\n' > "$(git rev-parse --git-common-dir)/allowed-emails"
printf 'host: 192.168.1.10\n' > notes.txt
git add notes.txt
git commit -qm "chore: notes"
check_warns "hostile allow-list cannot suppress the INFRA warning" "WARN: outgoing commits mention internal infrastructure." origin main

# check_absent <name> <expected-exit> <forbidden-substring> <push-args...>
# For assertions about what the hook must NOT say. Pins the exit code too, so
# "no output at all" cannot satisfy it vacuously.
check_absent() {
  name=$1
  want=$2
  substr=$3
  shift 3
  out=$(git push "$@" 2>&1)
  got=$?
  if [ "$got" -eq "$want" ] && ! printf '%s\n' "$out" | grep -qF "$substr"; then
    passes=$((passes + 1))
    printf 'PASS  %s\n' "$name"
  else
    failures=$((failures + 1))
    printf 'FAIL  %s (exit %s, wanted %s without %s)\n' "$name" "$got" "$want" "$substr"
    printf '%s\n' "$out" | sed 's/^/      /'
  fi
}

# 51. I3: this repo's own hook fixtures must not warn on this repo's own push.
#     The INFRA and MAILS warnings used to read an UNEXCLUDED `git log -p` pass,
#     so the synthetic 192.168.x address and .invalid mailbox that the cases
#     above deliberately contain fired on every push touching .githooks/ —
#     permanent, unsuppressable noise. Both warnings now read the
#     ':(exclude).githooks/' stream that the credential scan already used.
setup
printf '# fixture: host 192.168.1.10, contact jane.doe@personalmail.invalid\n' >> .githooks/test-pre-push.sh
git add .githooks/test-pre-push.sh
git commit -qm "test: hook fixtures"
check_silent "infra/email fixtures inside .githooks/ produce no warning" origin main

# 52. I1: the tree audit must never describe a path this very push is BLOCKING
#     as "already published". It read the tree at $local_sha, which contains
#     the unpushed commits, so a blocked .claude/ push printed the block AND a
#     warning claiming the same path was already public and not being blocked —
#     untrue, and it undercuts the block that sits four lines above it.
setup
mkdir -p .claude
printf '{}\n' > .claude/settings.json
git add -f .claude/settings.json
git commit -qm "add settings"
check_absent "blocked path is not also reported as already published" 1 "ALREADY published" origin main

# 53. I1: with no usable $remote_sha (brand-new branch) there is no baseline of
#     what the remote already has, so the audit is SKIPPED rather than falling
#     back to $local_sha. Must be completely silent.
setup
mkdir -p .claude
printf '{}\n' > .claude/settings.json
git add -f .claude/settings.json
git commit -qm "add settings"
git push -q --no-verify origin main
git checkout -qb fresh
printf 'feature work\n' >> README.md
git add README.md
git commit -qm "feat: work"
check_silent "new branch with no remote baseline skips the tree audit" origin fresh

# 54. M2: the tree audit applied FORBIDDEN but not ENV_HARD, so a pre-existing
#     tracked .env — the single worst thing to find already published — was the
#     one class it never surfaced.
setup
printf 'API_TOKEN=whatever\n' > .env
git add -f .env
git commit -qm "add env"
git push -q --no-verify origin main
printf 'unrelated change\n' >> README.md
git add README.md
git commit -qm "docs: unrelated"
check_warns "pre-existing tracked .env is surfaced by the tree audit" "WARN: local-only paths are ALREADY published on the remote" origin main

# 55. M3: the blocked-path list was printed via unquoted `printf '  %s\n' $HITS`,
#     so a path with a space split across two lines AND every path underwent
#     pathname expansion — `docs/a b*.md` was reported as whatever `b*.md`
#     happened to match in the working directory, i.e. the block named files
#     that do not exist. bogus.md below is that decoy.
setup
mkdir -p docs
printf 'decoy\n' > bogus.md
printf 'private runbook\n' > 'docs/a b*.md'
git add -f 'docs/a b*.md'
git commit -qm "add runbook with a space and a glob char"
out=$(git push origin main 2>&1)
got=$?
if [ "$got" -eq 1 ] \
   && printf '%s\n' "$out" | grep -qF 'docs/a b*.md' \
   && ! printf '%s\n' "$out" | grep -qF 'bogus.md'; then
  passes=$((passes + 1))
  printf 'PASS  %s\n' "space/glob path is listed verbatim, not word-split or glob-expanded"
else
  failures=$((failures + 1))
  printf 'FAIL  %s (exit %s)\n' "space/glob path is listed verbatim, not word-split or glob-expanded" "$got"
  printf '%s\n' "$out" | sed 's/^/      /'
fi
rm -f bogus.md

# 56. M5: the four-line "-m can resurface already-public content" caveat is only
#     true for a merge, and printed on EVERY block. On a range with no merge
#     commit at all it must not appear.
setup
mkdir -p docs
printf 'private runbook\n' > docs/runbook.md
git add -f docs/runbook.md
git commit -qm "add runbook"
check_absent "no merge in range: the -m caveat is not printed" 1 "git log -m diffs merges" origin main

# 57. M5, other half: on a range that DOES contain a merge the caveat must still
#     print — gating it must not delete it.
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
out=$(git push origin main 2>&1)
got=$?
if [ "$got" -eq 1 ] && printf '%s\n' "$out" | grep -qF "git log -m diffs merges"; then
  passes=$((passes + 1))
  printf 'PASS  %s\n' "merge in range: the -m caveat is still printed"
else
  failures=$((failures + 1))
  printf 'FAIL  %s (exit %s)\n' "merge in range: the -m caveat is still printed" "$got"
  printf '%s\n' "$out" | sed 's/^/      /'
fi

# 58. M1: `[ -n "$FILES" ] || continue` also skipped the whole-tree checks below
#     it. --diff-filter=d excludes deletions, so a DELETION-ONLY commit yields
#     an empty FILES and silently disabled the tree audit and the size WARN.
#     Here the remote already carries .claude/settings.json, so the audit must
#     still speak up on a push whose only change is a removal.
setup
mkdir -p .claude
printf '{}\n' > .claude/settings.json
printf 'doomed\n' > todelete.txt
git add -f .claude/settings.json todelete.txt
git commit -qm "add settings and a file"
git push -q --no-verify origin main
git rm -q todelete.txt
git commit -qm "chore: remove a file"
check_warns "deletion-only commit still runs the tree audit" "WARN: local-only paths are ALREADY published on the remote" origin main

# 59. M4: the size WARN read one file on disk but printed once per pushed REF,
#     so a two-ref push emitted the identical two lines twice.
setup
awk 'BEGIN { for (i = 0; i < 21000; i++) printf "x" }' > CLAUDE.md
git checkout -qb b1
printf 'one\n' >> README.md
git add README.md
git commit -qm "feat: one"
git checkout -q main
git checkout -qb b2
printf 'two\n' >> README.md
git add README.md
git commit -qm "feat: two"
git checkout -q main
out=$(git push origin b1 b2 2>&1)
got=$?
n=$(printf '%s\n' "$out" | grep -cF "WARN: CLAUDE.md is")
if [ "$got" -eq 0 ] && [ "$n" -eq 1 ]; then
  passes=$((passes + 1))
  printf 'PASS  %s\n' "size WARN prints once for a two-ref push"
else
  failures=$((failures + 1))
  printf 'FAIL  %s (exit %s, %s size warnings, wanted 1)\n' "size WARN prints once for a two-ref push" "$got" "$n"
  printf '%s\n' "$out" | sed 's/^/      /'
fi
rm -f CLAUDE.md

# --- optional tree-audit acknowledgement list ---
# acknowledged-leaks lives at --git-common-dir, never staged/committed by design.
# It exists because a leak already on the remote is permanent (a history rewrite
# does not remove it from GitHub), so an unsuppressable warning about one fires
# on every push forever — the exact --no-verify training this hook must avoid.

# 60. Acknowledging a path suppresses the tree audit for THAT path, while a
#     second, unacknowledged local-only path in the same tree still warns. The
#     second path is what makes this discriminate: "no output at all" (a no-op
#     hook) would satisfy the suppression half vacuously.
setup
mkdir -p .claude
printf '{}\n' > .claude/settings.json
printf 'local operating notes\n' > CLAUDE.md
git add -f .claude/settings.json CLAUDE.md
git commit -qm "add settings and claude md"
git push -q --no-verify origin main
rm -f CLAUDE.md
printf '.claude/settings.json\n' > "$(git rev-parse --git-common-dir)/acknowledged-leaks"
printf 'unrelated change\n' >> README.md
git add README.md
git commit -qm "docs: unrelated"
out=$(git push origin main 2>&1)
got=$?
if [ "$got" -eq 0 ] \
   && ! printf '%s\n' "$out" | grep -qF ".claude/settings.json" \
   && printf '%s\n' "$out" | grep -qF "CLAUDE.md"; then
  passes=$((passes + 1))
  printf 'PASS  %s\n' "acknowledged tree path suppressed, an unacknowledged one still warns"
else
  failures=$((failures + 1))
  printf 'FAIL  %s (exit %s)\n' "acknowledged tree path suppressed, an unacknowledged one still warns" "$got"
  printf '%s\n' "$out" | sed 's/^/      /'
fi

# 61. Acknowledging a path must NOT let a new commit touching it through —
#     HARD BLOCK 1 is untouched by the acknowledgement list, which suppresses
#     the tree audit and nothing else.
setup
mkdir -p .claude
printf '{}\n' > .claude/settings.json
git add -f .claude/settings.json
git commit -qm "add settings"
git push -q --no-verify origin main
printf '.claude/settings.json\nCLAUDE.md\ndocs/\n' > "$(git rev-parse --git-common-dir)/acknowledged-leaks"
printf '{"hooks":{}}\n' > .claude/settings.json
git add .claude/settings.json
git commit -qm "modify settings"
check "acknowledgement list cannot suppress the forbidden-path hard block" 1 origin main

# 62. A malformed / hostile acknowledgement list must not silence the audit
#     wholesale: entries are literal whole-line paths, so a bare regex
#     metacharacter matches nothing and `.claude/` (a prefix, not a full path)
#     does not suppress `.claude/settings.json`.
setup
mkdir -p .claude
printf '{}\n' > .claude/settings.json
git add -f .claude/settings.json
git commit -qm "add settings"
git push -q --no-verify origin main
printf '[\n.*\n.claude/\n' > "$(git rev-parse --git-common-dir)/acknowledged-leaks"
printf 'unrelated change\n' >> README.md
git add README.md
git commit -qm "docs: unrelated"
check_warns "hostile acknowledgement list does not silence the tree audit" ".claude/settings.json" origin main

# 63. An unreadable acknowledgement list (a directory in the way) behaves
#     exactly like absent — a read failure must never be the thing that
#     silences a warning.
setup
mkdir -p .claude
printf '{}\n' > .claude/settings.json
git add -f .claude/settings.json
git commit -qm "add settings"
git push -q --no-verify origin main
mkdir -p "$(git rev-parse --git-common-dir)/acknowledged-leaks"
printf 'unrelated change\n' >> README.md
git add README.md
git commit -qm "docs: unrelated"
check_warns "unreadable acknowledgement list still warns" ".claude/settings.json" origin main

printf '\n%s passed, %s failed\n' "$passes" "$failures"
[ "$failures" -eq 0 ] || exit 1
exit 0
