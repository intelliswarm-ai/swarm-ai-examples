#!/usr/bin/env bash
#
# Gmail browser-agent example launcher — auto-launches your real Chrome.
#
# How this works:
#   1. The example detects Chrome on this OS and spawns it with the debug port
#      open and a dedicated --user-data-dir at ~/.swarmai/gmail-chrome-cdp,
#      so it doesn't touch your everyday Chrome profile.
#   2. First run: a real-Chrome window opens at gmail.com. Sign in there.
#      Press Enter back here when you reach your inbox.
#   3. Subsequent runs: cookies are saved in the profile dir; the example
#      goes straight to the inbox.
#
# Why a real Chrome and not Playwright's bundled Chromium: Google's signin
# flow flags Playwright's Chromium as "not a secure browser" and refuses to
# authenticate. A real Chrome (the one you have installed) gets through.
#
# Phase 1 (always): deterministic browser steps — navigate, scrape, screenshot.
# Phase 2 (when SPRING_PROFILES_ACTIVE=openai-mini + OPENAI_API_KEY set):
#   the LLM-driven Gmail Triage Analyst summarises the inbox.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_DIR"

# Default profile dir for the spawned Chrome. Override by exporting
# SWARMAI_GMAIL_PROFILE_DIR=/some/path.
PROFILE_DIR="${SWARMAI_GMAIL_PROFILE_DIR:-$HOME/.swarmai/gmail-chrome-cdp}"

# Default to OpenAI's low-cost gpt-4o-mini for triage — fast, cheap (~$0.0001/run),
# and produces a useful summary without the cold-start latency of local Ollama.
# Override before invoking:  SPRING_PROFILES_ACTIVE=ollama ./gmail-browser-agent/run.sh
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-openai-mini}"

# Delegate to the project-level run.sh — it handles ollama/openai profile selection
# and forwards arguments. The example itself spawns Chrome and tells the BrowserTool
# what CDP URL to attach to, so we just enable the tool and pass the profile dir.
exec ./run.sh gmail-browser \
    --swarmai.tools.browser.enabled=true \
    --swarmai.tools.browser.user-data-dir="$PROFILE_DIR" \
    --swarmai.tools.browser.allowed-hosts=google.com,gmail.com \
    "$@"
