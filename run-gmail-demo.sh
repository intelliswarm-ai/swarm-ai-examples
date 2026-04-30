#!/usr/bin/env bash
#
# Side-by-side Gmail demo:
#   • gmail-browser-agent  → runs first, launches the shared Chrome window,
#                            prints the deterministic + LLM triage to your terminal
#   • gmail-dashboard      → runs second, reuses the same Chrome,
#                            serves the IntelliMail web UI on http://localhost:8090
#
# Both share:
#   - Chrome:   http://localhost:9222
#   - Profile:  ~/.swarmai/gmail-chrome-cdp
# So you sign in to Gmail ONCE in the Chrome window the first one pops up,
# and both demos see the same logged-in inbox.
#
# Usage:
#   ./run-gmail-demo.sh                        # both, local Ollama for triage
#   SPRING_PROFILES_ACTIVE=openai-mini \
#   OPENAI_API_KEY=sk-… ./run-gmail-demo.sh    # both, gpt-4o-mini for triage
#
# Stop with Ctrl+C — kills both children cleanly.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

PIDS=()

cleanup() {
    echo ""
    echo "[demo] Stopping…"
    for pid in "${PIDS[@]}"; do
        kill "$pid" 2>/dev/null || true
    done
}
trap cleanup EXIT INT TERM

# 1. CLI demo. Runs in the foreground for ~10 s while it scrapes + triages,
#    then we move on. Captures its own output.
echo "[demo] Starting gmail-browser-agent (CLI) — Chrome window opens, sign in if needed."
./gmail-browser-agent/run.sh &
PIDS+=($!)

# 2. IntelliMail web UI on :8090 — reuses the Chrome the first one started.
echo "[demo] Starting IntelliMail web UI on http://localhost:8090"
echo "[demo] (the UI shares the same Chrome session — no second signin)"
sleep 3   # let gmail-browser-agent's RealChromeLauncher get Chrome up first
./gmail-dashboard/run.sh &
PIDS+=($!)

echo ""
echo "[demo] Both demos running. Open http://localhost:8090 in any browser for the UI."
echo "[demo] Ctrl+C here to stop everything."
echo ""

# Wait on either child — whichever exits first triggers cleanup.
wait -n
