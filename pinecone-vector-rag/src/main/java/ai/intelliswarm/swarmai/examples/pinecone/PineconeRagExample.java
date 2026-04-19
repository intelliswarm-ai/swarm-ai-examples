package ai.intelliswarm.swarmai.examples.pinecone;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.tool.vector.PineconeVectorTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * PineconeVectorTool showcase — a direct-invocation walkthrough that upserts synthetic vectors,
 * queries the nearest neighbor, and cleans up. Exercises the tool end-to-end against a real
 * Pinecone index so failures in the live integration surface here.
 *
 * <p>This example does NOT need an LLM — the value is in the raw tool integration.
 *
 * <p>Run: {@code ./run.sh pinecone} (requires PINECONE_API_KEY + PINECONE_INDEX_HOST)
 */
@Component
public class PineconeRagExample {

    private static final Logger logger = LoggerFactory.getLogger(PineconeRagExample.class);

    @SuppressWarnings("unused") // kept for consistency with other showcase examples
    private final ChatClient.Builder chatClientBuilder;
    @SuppressWarnings("unused")
    private final ApplicationEventPublisher eventPublisher;
    private final PineconeVectorTool pineconeTool;

    public PineconeRagExample(ChatClient.Builder chatClientBuilder,
                              ApplicationEventPublisher eventPublisher,
                              PineconeVectorTool pineconeTool) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
        this.pineconeTool = pineconeTool;
    }

    public void run(String... args) throws InterruptedException {
        String smoke = pineconeTool.smokeTest();
        if (smoke != null) {
            logger.error("PineconeVectorTool unhealthy: {}", smoke);
            logger.error("Set PINECONE_API_KEY and PINECONE_INDEX_HOST env vars.");
            return;
        }

        int dim = parseIntEnv("PINECONE_INDEX_DIM", 8);
        String namespace = System.getenv().getOrDefault("PINECONE_TEST_NAMESPACE", "swarmai-example-" +
            UUID.randomUUID().toString().substring(0, 8));
        Random rnd = new Random(42);

        logger.info("Index dim={} namespace={}", dim, namespace);

        // 1) Stats
        logger.info("\n--- STATS ---");
        logger.info("{}", pineconeTool.execute(Map.of("operation", "stats")));

        // 2) Upsert 3 vectors: two near each other, one far away.
        List<Double> a  = fill(dim, 1.0, 0.0);
        List<Double> b  = fill(dim, 0.0, 1.0);
        List<Double> a2 = jitter(a, rnd, 0.03);

        logger.info("\n--- UPSERT 3 vectors ---");
        Object upsert = pineconeTool.execute(Map.of(
            "operation", "upsert",
            "namespace", namespace,
            "vectors", List.of(
                Map.of("id", "alpha",  "values", a,  "metadata", Map.of("label", "pole-A", "doc", "SwarmAI overview")),
                Map.of("id", "beta",   "values", b,  "metadata", Map.of("label", "pole-B", "doc", "Unrelated document")),
                Map.of("id", "alpha2", "values", a2, "metadata", Map.of("label", "near-A",  "doc", "Another SwarmAI doc"))
            )));
        logger.info("{}", upsert);

        // Pinecone is eventually consistent — give indexing a beat.
        Thread.sleep(2_000);

        // 3) Query: neighbors of poleA should include alpha and alpha2, NOT beta.
        logger.info("\n--- QUERY (nearest neighbors of pole-A) ---");
        Object q = pineconeTool.execute(Map.of(
            "operation", "query",
            "namespace", namespace,
            "vector", a,
            "top_k", 2,
            "include_metadata", true));
        logger.info("{}", q);

        // 4) Cleanup — delete_all in the test namespace.
        logger.info("\n--- DELETE namespace ---");
        Object del = pineconeTool.execute(Map.of(
            "operation", "delete",
            "namespace", namespace,
            "delete_all", true));
        logger.info("{}", del);
    }

    private static List<Double> fill(int dim, double first, double second) {
        List<Double> v = new ArrayList<>(dim);
        for (int i = 0; i < dim; i++) v.add(i == 0 ? first : i == 1 ? second : 0.0);
        return v;
    }

    private static List<Double> jitter(List<Double> base, Random r, double mag) {
        List<Double> out = new ArrayList<>(base.size());
        for (Double d : base) out.add(d + (r.nextDouble() - 0.5) * 2 * mag);
        return out;
    }

    private static int parseIntEnv(String name, int def) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) return def;
        try { return Integer.parseInt(raw.trim()); } catch (NumberFormatException e) { return def; }
    }

    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
            args.length > 0 ? prepend("pinecone", args) : new String[]{"pinecone"});
    }

    private static String[] prepend(String first, String[] rest) {
        String[] out = new String[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }
}
