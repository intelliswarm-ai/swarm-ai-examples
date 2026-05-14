#!/usr/bin/env bash
# Run this example: ./mcp-connector-status/run.sh [EDITION] [comma,sep,features]
# No API keys required — uses an in-memory fake license + demo credentials.
cd "$(dirname "${BASH_SOURCE[0]}")/.."
DEMO="${DEMO:-1}" exec ./run.sh "mcp-connector-status" "$@"
