#!/usr/bin/env bash
# Bring up a single-node Kafka broker for the example, create the demo topic.
# Usage: ./docker-up.sh              # default topic 'swarmai-events'
#        ./docker-up.sh orders.v1    # custom topic
set -euo pipefail

TOPIC="${1:-${KAFKA_TEST_TOPIC:-swarmai-events}}"
cd "$(dirname "${BASH_SOURCE[0]}")"

docker compose up -d
echo "Waiting for Kafka to become healthy..."
for _ in $(seq 1 30); do
  status=$(docker inspect -f '{{.State.Health.Status}}' swarmai-kafka 2>/dev/null || echo starting)
  [ "$status" = "healthy" ] && break
  sleep 2
done
if [ "$(docker inspect -f '{{.State.Health.Status}}' swarmai-kafka)" != "healthy" ]; then
  echo "Kafka did not become healthy within 60s" >&2
  exit 1
fi

echo "Creating topic '$TOPIC' (idempotent)..."
docker exec swarmai-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --create --if-not-exists \
  --topic "$TOPIC" \
  --partitions 1 --replication-factor 1

echo "Ready. Broker: localhost:9092, Topic: $TOPIC"
echo "Run the example:  KAFKA_BOOTSTRAP_SERVERS=localhost:9092 ./run.sh $TOPIC"
