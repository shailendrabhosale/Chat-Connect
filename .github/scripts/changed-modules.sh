#!/usr/bin/env bash
# Prints "modules=<JSON array>" (for $GITHUB_OUTPUT): the Maven modules a change touches (ADR-024).
#   changed-modules.sh <base-sha> <head-sha>
# - Shared build inputs (root POM, wrapper, CI itself) rebuild every module (ADR-001).
# - A file under services/<m>/ rebuilds <m>; deleted modules are ignored.
# - Anything else (docs, compose, ADRs) builds nothing; the gitleaks scan still runs.
# - A missing or all-zero base (first push of a branch) rebuilds everything.
set -euo pipefail
base=${1:-}
head=${2:-HEAD}

all_modules() { for pom in services/*/pom.xml; do dirname "$pom"; done; }
to_json() { printf '%s\n' "$@" | sed '/^$/d' | sort -u | awk 'BEGIN { printf "[" } { printf "%s\"%s\"", (NR > 1 ? "," : ""), $0 } END { print "]" }'; }

if [[ -z $base || $base =~ ^0+$ ]] || ! git cat-file -e "${base}^{commit}" 2>/dev/null; then
  modules=$(all_modules)
else
  changed=$(git diff --name-only "${base}...${head}")
  if grep -qE '^(pom\.xml|mvnw|mvnw\.cmd|\.mvn/|\.github/)' <<<"$changed"; then
    modules=$(all_modules)
  else
    modules=$( (grep -oE '^services/[^/]+' <<<"$changed" || true) | sort -u | while read -r m; do
      if [[ -f $m/pom.xml ]]; then echo "$m"; fi
    done)
  fi
fi
# shellcheck disable=SC2086  # word-splitting the newline-separated list is intended
echo "modules=$(to_json $modules)"
