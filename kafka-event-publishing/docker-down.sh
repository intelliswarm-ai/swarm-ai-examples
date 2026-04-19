#!/usr/bin/env bash
# Tear down the Kafka broker started by docker-up.sh.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
docker compose down -v
echo "Stopped Kafka broker and removed volumes."
