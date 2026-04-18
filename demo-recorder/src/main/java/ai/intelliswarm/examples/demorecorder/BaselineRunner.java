package ai.intelliswarm.examples.demorecorder;

import ai.intelliswarm.examples.demorecorder.trace.TraceWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;

/**
 * Runs the raw-LLM baseline for a demo: loads {@code demos/<slug>/prompt.md},
 * sends it to the configured {@link ChatClient} with the same model params as the swarm run,
 * writes {@code demos/<slug>/runs/<model>/baseline.json}.
 *
 * Usage:
 * <pre>
 *   mvn spring-boot:run \
 *     -Dspring-boot.run.mainClass=ai.intelliswarm.examples.demorecorder.BaselineRunner \
 *     -Dspring-boot.run.arguments="--swarmai.demo.slug=stock-market-analysis --swarmai.demo.model=mistral-ollama"
 * </pre>
 */
@SpringBootApplication
@EnableConfigurationProperties(DemoRecorderProperties.class)
public class BaselineRunner {
    // Register DemoRecorderProperties here (not via DemoRecorderAutoConfiguration)
    // because the auto-config is @ConditionalOnProperty(swarmai.demo.record=true),
    // which BaselineRunner never sets — it only needs the properties for --slug,
    // --model, etc., it doesn't record swarm events.


    private static final Logger log = LoggerFactory.getLogger(BaselineRunner.class);

    public static void main(String[] args) {
        SpringApplication.run(BaselineRunner.class, args);
    }

    @Bean
    CommandLineRunner runBaseline(ChatClient.Builder chatClientBuilder, DemoRecorderProperties props) {
        return args -> {
            if (props.getSlug() == null || props.getModel() == null) {
                log.error("Baseline needs --swarmai.demo.slug and --swarmai.demo.model");
                return;
            }

            Path root     = Paths.get(props.getOutDir()).resolve(props.getSlug());
            Path promptP  = root.resolve("prompt.md");
            String prompt = TranscriptRecorder.readFileOrEmpty(promptP);
            if (prompt.isBlank()) {
                log.error("Prompt file empty/missing: {}", promptP.toAbsolutePath());
                return;
            }

            ChatClient client = chatClientBuilder.build();

            long t0 = System.currentTimeMillis();
            ChatResponse response = client.prompt().user(prompt).call().chatResponse();
            long durationMs = System.currentTimeMillis() - t0;

            String content = response.getResult().getOutput().getText();
            int promptTokens = tokens(response, true);
            int completionTokens = tokens(response, false);

            Map<String, Object> llmStep = new LinkedHashMap<>();
            llmStep.put("t", 0);
            llmStep.put("kind", "llm_request");
            llmStep.put("model", props.getModel());
            llmStep.put("promptTokens", promptTokens);
            llmStep.put("completionTokens", completionTokens);
            llmStep.put("costUsd", 0.0); // refined when a cost oracle is wired in
            llmStep.put("durationMs", durationMs);

            Map<String, Object> finalStep = new LinkedHashMap<>();
            finalStep.put("t", durationMs);
            finalStep.put("kind", "final");
            finalStep.put("content", content);

            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("wallTimeMs", durationMs);
            metrics.put("totalTokens", promptTokens + completionTokens);
            metrics.put("inputTokens", promptTokens);
            metrics.put("outputTokens", completionTokens);
            metrics.put("costUsd", 0.0);
            metrics.put("toolCalls", 0);
            metrics.put("agentTurns", 1);
            metrics.put("llmRequests", 1);
            metrics.put("failures", 0);
            metrics.put("retries", 0);

            Map<String, Object> finalOutput = new LinkedHashMap<>();
            finalOutput.put("format", "markdown");
            finalOutput.put("content", content);

            Map<String, Object> repro = new LinkedHashMap<>();
            repro.put("modelVersion", props.getModel());
            repro.put("provider", props.getProvider() != null ? props.getProvider() : "unknown");
            repro.put("temperature", props.getTemperature());
            repro.put("seed", props.getSeed());
            repro.put("topP", props.getTopP());
            repro.put("maxTokens", props.getMaxTokens());
            repro.put("frameworkVersion", null);
            repro.put("frameworkGitSha", null);
            repro.put("workflowHash", null);
            repro.put("promptHash", TranscriptRecorder.sha256Hex(prompt));
            repro.put("recorderVersion", "0.1.0");
            Map<String, String> env = new LinkedHashMap<>();
            env.put("os", System.getProperty("os.name", "unknown"));
            env.put("javaVersion", System.getProperty("java.version", "unknown"));
            repro.put("environment", env);

            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("$schema", 1);
            trace.put("demoSlug", props.getSlug());
            trace.put("side", "baseline");
            trace.put("model", props.getModel());
            trace.put("modelDisplayName", props.getModelDisplayName() != null ? props.getModelDisplayName() : props.getModel());
            trace.put("provider", props.getProvider());
            trace.put("frameworkVersion", null);
            trace.put("recordedAt", Instant.now().toString());
            trace.put("reproducibility", repro);
            trace.put("metrics", metrics);
            trace.put("finalOutput", finalOutput);
            trace.put("steps", List.of(llmStep, finalStep));

            // Baseline sits next to the swarm trace under the same framework-version
            // directory so a "run set" for (model, version) is self-contained.
            // For baseline the version reflects when the recording was taken, not
            // that the framework was involved in the call.
            String version = (props.getFrameworkVersion() != null && !props.getFrameworkVersion().isBlank())
                    ? props.getFrameworkVersion() : "unknown";
            Path target = Paths.get(props.getOutDir())
                    .resolve(props.getSlug()).resolve("runs").resolve(props.getModel())
                    .resolve(version).resolve("baseline.json");

            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            new TraceWriter(mapper).write(trace, target);
            log.info("BaselineRunner: wrote {} ({} tokens, {} ms)", target.toAbsolutePath(), promptTokens + completionTokens, durationMs);
        };
    }

    private static int tokens(ChatResponse response, boolean input) {
        try {
            var usage = response.getMetadata().getUsage();
            return input
                    ? Math.toIntExact(usage.getPromptTokens())
                    : Math.toIntExact(usage.getCompletionTokens());
        } catch (Exception e) {
            return 0;
        }
    }
}
