#!/usr/bin/env bash
# Run this example: ./financial-model-builder/run.sh [TICKER]
# Pure deterministic math — no API keys required.
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "financial-model-builder" "$@"
