#!/usr/bin/env bash
#
# IntelliMail launcher — AI inbox triage on top of your real Gmail.
#
# What it does:
#   1. Auto-launches your real Chrome with a dedicated profile at
#      ~/.swarmai/intellimail-chrome (separate from your everyday Chrome).
#   2. Boots a Spring Boot web app on http://localhost:8090 with the
#      IntelliMail UI.
#   3. Reads your inbox via the BrowserTool and runs LLM triage on each email.
#
# Default LLM:  local Ollama (privacy by default, nothing leaves your machine).
# To switch to OpenAI's gpt-4o-mini for sharper (and cheaper) triage:
#   SPRING_PROFILES_ACTIVE=openai-mini OPENAI_API_KEY=sk-… ./gmail-dashboard/run.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_DIR"

# Shared with gmail-browser-agent on purpose: when you run both side-by-side for a demo,
# the second one (whichever it is) sees Chrome already up on 9222 and reuses it.
PROFILE_DIR="${SWARMAI_GMAIL_PROFILE_DIR:-$HOME/.swarmai/gmail-chrome-cdp}"

# Local Ollama by default — privacy-by-default for an email-triage UI.
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-ollama}"

exec ./run.sh intellimail \
    --swarmai.tools.browser.enabled=true \
    --swarmai.tools.browser.user-data-dir="$PROFILE_DIR" \
    --swarmai.tools.browser.allowed-hosts=google.com,gmail.com \
    --spring.main.web-application-type=servlet \
    --server.port=8090 \
    --swarmai.intellimail.enabled=true \
    "$@"
