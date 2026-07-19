#!/usr/bin/env bash
# Public file — contains only public repo paths, no secrets, no diff content.

# Files that DEFINE the concepts the (i) explainers describe.
CONCEPT_PATTERNS='^java-server/src/main/java/de/visterion/dracul/strigoi/|^java-server/src/main/resources/prompts/strigoi-.*\.md$|^java-server/src/main/java/de/visterion/dracul/executor/RejectReason\.java$|^java-server/src/main/java/de/visterion/dracul/executor/VetoService\.java$|^java-server/src/main/java/de/visterion/dracul/executor/broker/OrderRole\.java$|CalibrationService\.java$|^java-server/src/main/java/de/visterion/dracul/depot/DepotOrder\.java$'

# The explainer content files. A change here is proof the texts were revisited.
EXPLAINER_PATTERNS='^chronicle/src/i18n/explainers\.(de|en)\.ts$'

# explainer_drift_verdict <changed-files-newline-list> <override 0|1>
# returns 0 = ok to push, 1 = block (concept changed, explainers did not).
explainer_drift_verdict() {
  local changed="$1" override="${2:-0}"
  [ "$override" = "1" ] && return 0
  local concept explainer
  concept="$(printf '%s\n' "$changed" | grep -E "$CONCEPT_PATTERNS" || true)"
  explainer="$(printf '%s\n' "$changed" | grep -E "$EXPLAINER_PATTERNS" || true)"
  if [ -n "$concept" ] && [ -z "$explainer" ]; then return 1; fi
  return 0
}
