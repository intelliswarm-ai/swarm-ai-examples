#!/usr/bin/env bash
# Run this example: ANTHROPIC_API_KEY=sk-ant-... ./citation-required-pipeline/run.sh
# Calls the native Anthropic SDK — prompt caching + extended thinking + citations + compliance gate.
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "citation-required-pipeline" "$@"
