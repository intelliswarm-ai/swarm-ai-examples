#!/usr/bin/env bash
# Run this example: ./eodhd-global-markets/run.sh [symbol]
# Defaults to BMW.XETRA. Try AAPL.US, VOD.LSE, 7203.TSE (Toyota), CBA.AU, etc.
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "eodhd" "$@"
