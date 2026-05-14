#!/usr/bin/env bash
# Run this example: ./financial-model-builder/run.sh [TICKER]
# Pure deterministic math — no API keys required.
# DEMO=1 silences Spring Boot startup chatter so the workflow output is first.
cd "$(dirname "${BASH_SOURCE[0]}")/.."
DEMO="${DEMO:-1}" exec ./run.sh "financial-model-builder" "$@"
