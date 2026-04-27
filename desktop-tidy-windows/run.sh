#!/usr/bin/env bash
# Run this example: ./desktop-tidy-windows/run.sh [folder]
#
# Asks an agent to tidy the contents of a folder (default: ~/Desktop) by
# proposing a category structure and routing each move through the supervised
# approval gate. Cross-platform — works on Windows, macOS, and Linux via the
# swarmai.tools.os tool category. (The directory name is preserved from when
# this example was Windows-only.)
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./run.sh "desktop-tidy" "$@"
