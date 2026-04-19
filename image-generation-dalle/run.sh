#!/usr/bin/env bash
# Run this example: ./image-generation-dalle/run.sh [prompt]
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "image-gen" "$@"
