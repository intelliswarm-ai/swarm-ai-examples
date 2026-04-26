package ai.intelliswarm.examples.demorecorder;

import ai.intelliswarm.examples.demorecorder.trace.TraceWriter;
import ai.intelliswarm.swarmai.knowledge.rag.RagAnswer;
import ai.intelliswarm.swarmai.knowledge.rag.RagConfig;
import ai.intelliswarm.swarmai.knowledge.rag.RagPipeline;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * Records the SwarmAI side of a RAG demo: ingests source documents from
 * {@code demos/<slug>/corpus/}, runs the prompt through {@link RagPipeline}
 * with the eval-winning defaults, then writes the trace JSON in the same
 * schema {@link BaselineRunner} produces.
 *
 * <p>Works for any RAG-style demo. The corpus directory is the only
 * demo-specific input; everything else (chunking, retrieval, prompt) comes
 * from {@code RagConfig.defaults()}.
 *
 * <p>Usage (driven by {@code record-demo.sh}):
 * <pre>
 *   mvn spring-boot:run \
 *     -Dspring-boot.run.mainClass=ai.intelliswarm.examples.demorecorder.RagDemoRunner \
 *     -Dspring-boot.run.arguments="--swarmai.demo.slug=rag-knowledge-base --swarmai.demo.model=gpt-4o"
 * </pre>
 */
@SpringBootApplication
@EnableConfigurationProperties(DemoRecorderProperties.class)
public class RagDemoRunner {

    private static final Logger log = LoggerFactory.getLogger(RagDemoRunner.class);

    public static void main(String[] args) {
        SpringApplication.run(RagDemoRunner.class, args);
    }

    /**
     * Always-on Ollama embedding bean — the chat profile (openai/anthropic/ollama)
     * may exclude embeddings, but we always need one for the vector store. Local
     * nomic-embed-text via Ollama is the cost-free default; override via
     * {@code RAG_DEMO_OLLAMA_BASE_URL} / {@code RAG_DEMO_EMBED_MODEL} env vars.
     */
    @Bean
    public EmbeddingModel ragDemoEmbeddingModel(
            @Value("${rag.demo.ollama-base-url:http://localhost:11434}") String baseUrl,
            @Value("${rag.demo.embed-model:nomic-embed-text}") String model) {
        OllamaApi api = OllamaApi.builder().baseUrl(baseUrl).build();
        return OllamaEmbeddingModel.builder()
                .ollamaApi(api)
                .defaultOptions(OllamaOptions.builder().model(model).build())
                .build();
    }

    /**
     * Fallback in-memory vector store for the demo. Production callers should
     * supply their own (Chroma, pgvector, Lucene, Pinecone) but the demo
     * deliberately runs without external infra so it reproduces anywhere.
     */
    @Bean
    public VectorStore demoVectorStore(EmbeddingModel ragDemoEmbeddingModel) {
        return SimpleVectorStore.builder(ragDemoEmbeddingModel).build();
    }

    @Bean
    CommandLineRunner runRagDemo(VectorStore vectorStore,
                                  ChatClient.Builder chatClientBuilder,
                                  DemoRecorderProperties props) {
        return args -> {
            if (props.getSlug() == null || props.getModel() == null) {
                log.error("RagDemoRunner needs --swarmai.demo.slug and --swarmai.demo.model");
                return;
            }

            Path root     = Paths.get(props.getOutDir()).resolve(props.getSlug());
            Path promptP  = root.resolve("prompt.md");
            Path corpusP  = root.resolve("corpus");
            String prompt = TranscriptRecorder.readFileOrEmpty(promptP);
            if (prompt.isBlank()) {
                log.error("Prompt file empty/missing: {}", promptP.toAbsolutePath());
                return;
            }
            if (!Files.isDirectory(corpusP)) {
                log.error("Corpus directory missing: {}", corpusP.toAbsolutePath());
                return;
            }

            ChatClient chatClient = chatClientBuilder.build();
            RagPipeline rag = RagPipeline.builder()
                    .vectorStore(vectorStore)
                    .chatClient(chatClient)
                    .config(RagConfig.defaults())
                    .build();

            List<Map<String, Object>> steps = new ArrayList<>();
            long t0 = System.currentTimeMillis();
            steps.add(stepStart(0, "rag_started", Map.of("config", configSummary())));

            // ---- Ingestion phase (recorded as one step per file) ----
            int ingested = 0;
            try (Stream<Path> files = Files.walk(corpusP)) {
                List<Path> paths = files.filter(Files::isRegularFile)
                        .filter(p -> {
                            String n = p.getFileName().toString().toLowerCase();
                            return n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".json") || n.endsWith(".csv");
                        }).toList();
                for (Path p : paths) {
                    long si = System.currentTimeMillis();
                    String content = Files.readString(p);
                    rag.ingestText(p.getFileName().toString(), content);
                    ingested++;
                    steps.add(stepStart(System.currentTimeMillis() - t0, "ingest_file",
                            Map.of("file", p.getFileName().toString(),
                                   "chars", content.length(),
                                   "durationMs", System.currentTimeMillis() - si)));
                }
            }
            log.info("RagDemoRunner: ingested {} file(s) from {}", ingested, corpusP);

            // ---- Query phase ----
            long qStart = System.currentTimeMillis();
            steps.add(stepStart(qStart - t0, "rag_query_started",
                    Map.of("question", prompt.length() > 200 ? prompt.substring(0, 200) + "…" : prompt)));
            RagAnswer answer = rag.query(prompt);
            long qElapsed = System.currentTimeMillis() - qStart;

            steps.add(stepStart(System.currentTimeMillis() - t0, "rag_retrieval_done",
                    Map.of("citationCount", answer.citations().size(),
                           "refused", answer.refused())));
            steps.add(stepStart(System.currentTimeMillis() - t0, "llm_request",
                    Map.of("model", props.getModel(),
                           "durationMs", qElapsed,
                           "promptTokens", 0,        // RagPipeline doesn't surface this; left 0 for parity
                           "completionTokens", 0,
                           "costUsd", 0.0)));

            long durationMs = System.currentTimeMillis() - t0;
            steps.add(stepStart(durationMs, "rag_completed", Map.of("totalCitations", answer.citations().size())));
            steps.add(stepStart(durationMs, "final", Map.of("content", answer.answer())));

            // ---- Trace JSON (same schema as BaselineRunner) ----
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("wallTimeMs", durationMs);
            metrics.put("totalTokens", 0);
            metrics.put("inputTokens", 0);
            metrics.put("outputTokens", 0);
            metrics.put("costUsd", 0.0);
            metrics.put("toolCalls", 0);
            metrics.put("agentTurns", 1);
            metrics.put("llmRequests", 1);
            metrics.put("failures", answer.refused() ? 1 : 0);
            metrics.put("retries", 0);
            metrics.put("documentsIngested", ingested);
            metrics.put("citationCount", answer.citations().size());

            Map<String, Object> finalOutput = new LinkedHashMap<>();
            finalOutput.put("format", "markdown");
            finalOutput.put("content", answer.answer());

            Map<String, Object> repro = new LinkedHashMap<>();
            repro.put("modelVersion", props.getModel());
            repro.put("provider", props.getProvider() != null ? props.getProvider() : "unknown");
            repro.put("temperature", RagConfig.defaults().temperature());
            repro.put("seed", props.getSeed());
            repro.put("topP", props.getTopP());
            repro.put("maxTokens", RagConfig.defaults().numPredict());
            repro.put("frameworkVersion", props.getFrameworkVersion());
            repro.put("frameworkGitSha", null);
            repro.put("workflowHash", null);
            repro.put("promptHash", TranscriptRecorder.sha256Hex(prompt));
            repro.put("recorderVersion", "0.1.0");
            Map<String, String> env = new LinkedHashMap<>();
            env.put("os", System.getProperty("os.name", "unknown"));
            env.put("javaVersion", System.getProperty("java.version", "unknown"));
            repro.put("environment", env);
            repro.put("ragConfig", configSummary());

            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("$schema", 1);
            trace.put("demoSlug", props.getSlug());
            trace.put("side", "swarm");
            trace.put("model", props.getModel());
            trace.put("modelDisplayName", props.getModelDisplayName() != null ? props.getModelDisplayName() : props.getModel());
            trace.put("provider", props.getProvider());
            trace.put("frameworkVersion", props.getFrameworkVersion());
            trace.put("recordedAt", Instant.now().toString());
            trace.put("reproducibility", repro);
            trace.put("metrics", metrics);
            trace.put("finalOutput", finalOutput);
            trace.put("steps", steps);

            String version = (props.getFrameworkVersion() != null && !props.getFrameworkVersion().isBlank())
                    ? props.getFrameworkVersion() : "unknown";
            Path target = Paths.get(props.getOutDir())
                    .resolve(props.getSlug()).resolve("runs").resolve(props.getModel())
                    .resolve(version).resolve(props.getSlug() + ".json");

            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            new TraceWriter(mapper).write(trace, target);
            log.info("RagDemoRunner: wrote {} ({} ms, {} citations)",
                    target.toAbsolutePath(), durationMs, answer.citations().size());
        };
    }

    private static Map<String, Object> stepStart(long t, String kind, Map<String, Object> extras) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("t", t);
        s.put("kind", kind);
        s.putAll(extras);
        return s;
    }

    private static Map<String, Object> configSummary() {
        RagConfig c = RagConfig.defaults();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chunkSize", c.chunkSize());
        m.put("chunkOverlap", c.chunkOverlap());
        m.put("topK", c.topK());
        m.put("hybridRetrieval", c.hybridRetrieval());
        m.put("contextualPrefix", c.contextualPrefix());
        m.put("mmrRerank", c.mmrRerank());
        m.put("temperature", c.temperature());
        m.put("numPredict", c.numPredict());
        return m;
    }
}
