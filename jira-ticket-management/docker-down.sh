#!/usr/bin/env bash
# Tear down the WireMock Jira mock.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
docker compose down -v
echo "Stopped Jira mock."
