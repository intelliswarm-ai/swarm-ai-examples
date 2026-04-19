#!/usr/bin/env bash
# Run this example: ./openapi-universal-client/run.sh [spec-url]
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "openapi" "$@"
