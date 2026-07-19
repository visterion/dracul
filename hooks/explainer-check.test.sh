#!/usr/bin/env bash
# Pure-logic test for the drift verdict. No git needed.
set -u
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$DIR/lib/explainer-check.sh"

fail=0
check() { # desc  changed  override  expected(0=pass,1=block)
  local desc="$1" changed="$2" override="$3" expected="$4"
  if explainer_drift_verdict "$changed" "$override"; then got=0; else got=1; fi
  if [ "$got" != "$expected" ]; then echo "FAIL: $desc (got $got want $expected)"; fail=1
  else echo "ok: $desc"; fi
}

CONCEPT="java-server/src/main/java/de/visterion/dracul/executor/RejectReason.java"
EXPL="chronicle/src/i18n/explainers.de.ts"

check "concept without explainer blocks" "$CONCEPT" "0" "1"
check "concept with explainer passes" "$CONCEPT
$EXPL" "0" "0"
check "concept with override passes" "$CONCEPT" "1" "0"
check "unrelated files pass" "README.md
chronicle/src/App.vue" "0" "0"
check "strigoi change without explainer blocks" "java-server/src/main/java/de/visterion/dracul/strigoi/echo/EchoPeadScreener.java" "0" "1"

exit $fail
