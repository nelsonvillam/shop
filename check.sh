#!/usr/bin/env bash
# Runs the same checks the Jenkins pipeline runs, in the same order.
# Usage:
#   ./check.sh              → lint + unit tests + integration tests + coverage + build
#   ./check.sh --skip-it    → skip integration tests (faster, no Docker required)
#   ./check.sh --skip-build → skip the final bootJar

set -euo pipefail

# ── colours ──────────────────────────────────────────────────────────────────
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
RESET='\033[0m'

SKIP_IT=false
SKIP_BUILD=false

for arg in "$@"; do
  case $arg in
    --skip-it)    SKIP_IT=true ;;
    --skip-build) SKIP_BUILD=true ;;
    *) echo "Unknown option: $arg"; exit 1 ;;
  esac
done

# ── helpers ───────────────────────────────────────────────────────────────────
step() { echo -e "\n${CYAN}▶ $1${RESET}"; }
ok()   { echo -e "${GREEN}✔ $1${RESET}"; }
fail() { echo -e "${RED}✘ $1${RESET}"; }

FAILED_STEPS=()

run() {
  local label="$1"; shift
  step "$label"
  if "$@"; then
    ok "$label passed"
  else
    fail "$label failed"
    FAILED_STEPS+=("$label")
    # mirror Jenkins failFast: stop immediately
    echo -e "${RED}\nStopping — fix the failure above before continuing.${RESET}"
    exit 1
  fi
}

# ── pipeline steps ────────────────────────────────────────────────────────────
echo -e "${CYAN}╔══════════════════════════════════════╗"
echo -e "║        Local pipeline check          ║"
echo -e "╚══════════════════════════════════════╝${RESET}"

run "Lint (Checkstyle + PMD + SpotBugs)" \
    ./gradlew checkstyleMain pmdMain spotbugsMain --no-daemon

run "Unit tests" \
    ./gradlew test --no-daemon

if [ "$SKIP_IT" = false ]; then
  run "Integration tests" \
      ./gradlew integrationTest --no-daemon
else
  echo -e "${YELLOW}⚠ Integration tests skipped (--skip-it)${RESET}"
fi

run "Coverage report" \
    ./gradlew jacocoTestReport --no-daemon

if [ "$SKIP_BUILD" = false ]; then
  run "Build (bootJar)" \
      ./gradlew bootJar --no-daemon
else
  echo -e "${YELLOW}⚠ Build skipped (--skip-build)${RESET}"
fi

# ── summary ───────────────────────────────────────────────────────────────────
echo -e "\n${GREEN}╔══════════════════════════════════════╗"
echo -e "║          All checks passed ✔         ║"
echo -e "╚══════════════════════════════════════╝${RESET}"
echo ""
echo "Reports:"
echo "  Checkstyle  → build/reports/checkstyle/main.html"
echo "  PMD         → build/reports/pmd/main.html"
echo "  SpotBugs    → build/reports/spotbugs/main.html"
echo "  Unit tests  → build/reports/tests/test/index.html"
if [ "$SKIP_IT" = false ]; then
echo "  IT tests    → build/reports/tests/integrationTest/index.html"
fi
echo "  Coverage    → build/reports/jacoco/test/html/index.html"
if [ "$SKIP_BUILD" = false ]; then
echo "  JAR         → build/libs/shop-0.0.1-SNAPSHOT.jar"
fi
echo ""
