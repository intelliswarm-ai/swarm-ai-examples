#!/usr/bin/env bash
# Run this example: ./ocr-document-extraction/run.sh [text-to-render]
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "ocr" "$@"
