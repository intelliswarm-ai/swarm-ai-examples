#!/usr/bin/env bash
# Run this example: ANTHROPIC_API_KEY=sk-ant-... ./anthropic-batch/run.sh
# Submits 5 financial filings as one Anthropic Message Batch (50% cost), polls,
# joins per-item results back to customIds. DEMO=1 silences Spring Boot chatter.
cd "$(dirname "${BASH_SOURCE[0]}")/.."
DEMO="${DEMO:-1}" exec ./run.sh "anthropic-batch" "$@"
