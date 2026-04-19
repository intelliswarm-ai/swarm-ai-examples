#!/usr/bin/env bash
# Run this example: ./s3-cloud-storage/run.sh [bucket]
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "s3" "$@"
