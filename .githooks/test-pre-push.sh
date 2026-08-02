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

printf '\n%s passed, %s failed\n' "$passes" "$failures"
[ "$failures" -eq 0 ] || exit 1
exit 0
