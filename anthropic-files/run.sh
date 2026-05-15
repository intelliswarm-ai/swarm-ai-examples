#!/usr/bin/env bash
# Run this example: ANTHROPIC_API_KEY=sk-ant-... ./anthropic-files/run.sh
# Upload-metadata-list-download-verify-delete round-trip on the Anthropic Files API.
cd "$(dirname "${BASH_SOURCE[0]}")/.."
DEMO="${DEMO:-1}" exec ./run.sh "anthropic-files" "$@"
