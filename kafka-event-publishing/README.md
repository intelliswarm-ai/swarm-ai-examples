# Kafka Event Publishing Example

Exercises **`KafkaProducerTool`** — publishes a synthetic `order.placed` event (JSON value +
correlation-id header) to a Kafka topic. This is the canonical demo for the Spring + Kafka
moat: no Python agent framework credibly integrates with enterprise event-driven infra.

## Prerequisites

**API keys / env vars:**

| Env var                     | Purpose                                                   |
|-----------------------------|-----------------------------------------------------------|
| `KAFKA_BOOTSTRAP_SERVERS`   | Comma-separated broker list, e.g. `localhost:9092`        |
| `KAFKA_TEST_TOPIC` (opt.)   | Target topic. Default: `swarmai-events`                   |

```bash
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export KAFKA_TEST_TOPIC=swarmai-events
```

### Spin up Kafka in Docker

The official Apache Kafka image works out of the box for a single-broker dev setup:

```bash
docker run --rm -d --name kafka \
  -p 9092:9092 \
  apache/kafka:3.7.1
```

Create the topic (inside the container):

```bash
docker exec -it kafka \
  /opt/kafka/bin/kafka-topics.sh --create \
    --topic swarmai-events \
    --bootstrap-server localhost:9092 \
    --partitions 1 \
    --replication-factor 1
```

**Alternative — Redpanda** (drop-in Kafka API, faster to boot):

```bash
docker run --rm -d --name redpanda \
  -p 9092:9092 \
  redpandadata/redpanda:latest \
    redpanda start --overprovisioned --smp 1 \
      --memory 1G --reserve-memory 0M --node-id 0 --check=false \
      --kafka-addr PLAINTEXT://0.0.0.0:9092 \
      --advertise-kafka-addr PLAINTEXT://localhost:9092
```

**Verify topic contents** (consume while you re-run the example):

```bash
docker exec -it kafka \
  /opt/kafka/bin/kafka-console-consumer.sh \
    --topic swarmai-events \
    --from-beginning \
    --bootstrap-server localhost:9092 \
    --property print.key=true --property print.headers=true
```

## Run

```bash
./run.sh kafka                         # uses $KAFKA_TEST_TOPIC or 'swarmai-events'
./run.sh kafka orders.v1               # override topic
```

## What this proves about the tool

- Idempotent producer with `acks=all` + 3 retries is the default — safe for exactly-once-ish semantics.
- Custom headers attach as UTF-8 bytes and survive round-trip through the broker.
- Record `key` is used for partition hashing (same order-id always lands on the same partition).
- Extra producer properties (SASL, SSL, compression) are passed via the `config` map param.
- Missing `KAFKA_BOOTSTRAP_SERVERS` surfaces a clean setup error — not a Kafka internal exception.
- Broker unreachability triggers a bounded timeout with a readable error message.
