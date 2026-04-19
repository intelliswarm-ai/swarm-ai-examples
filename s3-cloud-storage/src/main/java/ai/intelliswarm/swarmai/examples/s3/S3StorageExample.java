package ai.intelliswarm.swarmai.examples.s3;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.tool.cloud.S3Tool;
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
 * S3Tool showcase — put → head → get → list → delete round-trip against a real bucket.
 *
 * <p>Run: {@code ./run.sh s3 [bucket]}  (requires S3_TEST_BUCKET env or CLI arg + AWS creds)
 */
@Component
public class S3StorageExample {

    private static final Logger logger = LoggerFactory.getLogger(S3StorageExample.class);

    @SuppressWarnings("unused")
    private final ChatClient.Builder chatClientBuilder;
    @SuppressWarnings("unused")
    private final ApplicationEventPublisher eventPublisher;
    private final S3Tool s3Tool;

    public S3StorageExample(ChatClient.Builder chatClientBuilder,
                            ApplicationEventPublisher eventPublisher,
                            S3Tool s3Tool) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
        this.s3Tool = s3Tool;
    }

    public void run(String... args) {
        String bucket = args.length > 0 ? args[0] : System.getenv("S3_TEST_BUCKET");
        if (bucket == null || bucket.isBlank()) {
            logger.error("No bucket provided. Pass one as the first argument or set S3_TEST_BUCKET.");
            logger.error("Requires AWS credentials via the default chain (env vars, ~/.aws, IAM role).");
            return;
        }
        String smoke = s3Tool.smokeTest();
        if (smoke != null) {
            logger.error("S3Tool unhealthy: {}", smoke);
            return;
        }

        String key = "swarmai-example/" + UUID.randomUUID() + ".txt";
        String body = "Hello from SwarmAI S3Tool example @ " + LocalDateTime.now();

        logger.info("\n--- PUT ---");
        logger.info("{}", s3Tool.execute(Map.of(
            "operation", "put",
            "bucket", bucket,
            "key", key,
            "content", body,
            "content_type", "text/plain; charset=utf-8")));

        logger.info("\n--- HEAD ---");
        logger.info("{}", s3Tool.execute(Map.of(
            "operation", "head", "bucket", bucket, "key", key)));

        logger.info("\n--- GET ---");
        logger.info("{}", s3Tool.execute(Map.of(
            "operation", "get", "bucket", bucket, "key", key)));

        logger.info("\n--- LIST (prefix='swarmai-example/') ---");
        logger.info("{}", s3Tool.execute(Map.of(
            "operation", "list",
            "bucket", bucket,
            "prefix", "swarmai-example/",
            "max_keys", 20)));

        logger.info("\n--- DELETE ---");
        logger.info("{}", s3Tool.execute(Map.of(
            "operation", "delete", "bucket", bucket, "key", key)));
    }

    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
            args.length > 0 ? prepend("s3", args) : new String[]{"s3"});
    }

    private static String[] prepend(String first, String[] rest) {
        String[] out = new String[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }
}
