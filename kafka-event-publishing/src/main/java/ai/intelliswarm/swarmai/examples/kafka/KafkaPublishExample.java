package ai.intelliswarm.swarmai.examples.kafka;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.tool.messaging.KafkaProducerTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * KafkaProducerTool showcase — publish a domain event (JSON) to a topic, including headers
 * like a correlation-id and source system. Exercises the tool directly so a broker misconfig
 * surfaces immediately rather than hiding behind an LLM turn.
 *
 * <p>Run: {@code ./run.sh kafka [topic]}
 */
@Component
public class KafkaPublishExample {

    private static final Logger logger = LoggerFactory.getLogger(KafkaPublishExample.class);

    @SuppressWarnings("unused")
    private final ChatClient.Builder chatClientBuilder;
    @SuppressWarnings("unused")
    private final ApplicationEventPublisher eventPublisher;
    private final KafkaProducerTool kafkaTool;

    public KafkaPublishExample(ChatClient.Builder chatClientBuilder,
                               ApplicationEventPublisher eventPublisher,
                               KafkaProducerTool kafkaTool) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
        this.kafkaTool = kafkaTool;
    }

    public void run(String... args) {
        String topic = args.length > 0 ? args[0]
            : System.getenv().getOrDefault("KAFKA_TEST_TOPIC", "swarmai-events");

        String smoke = kafkaTool.smokeTest();
        if (smoke != null) {
            logger.error("KafkaProducerTool unhealthy: {}", smoke);
            logger.error("Set KAFKA_BOOTSTRAP_SERVERS (e.g. 'localhost:9092').");
            return;
        }

        String correlationId = UUID.randomUUID().toString();
        String value = String.format(
            "{\"event\":\"order.placed\",\"orderId\":\"%s\",\"total\":19.99,\"at\":\"%s\"}",
            UUID.randomUUID(),
            LocalDateTime.now()
        );

        logger.info("Publishing a synthetic order.placed event to topic='{}'", topic);

        Object out = kafkaTool.execute(Map.of(
            "topic", topic,
            "key", correlationId,
            "value", value,
            "headers", Map.of(
                "correlation-id", correlationId,
                "source", "swarmai-kafka-example",
                "schema-version", "1"
            )
        ));

        logger.info("");
        logger.info("=== KafkaProducerTool result ===");
        logger.info("{}", out);
    }

    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
            args.length > 0 ? prepend("kafka", args) : new String[]{"kafka"});
    }

    private static String[] prepend(String first, String[] rest) {
        String[] out = new String[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }
}
