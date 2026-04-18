package ai.intelliswarm.examples.demorecorder;

import ai.intelliswarm.examples.demorecorder.trace.TraceWriter;
import ai.intelliswarm.swarmai.event.SwarmEvent;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens to {@link SwarmEvent}s and writes a single trace JSON file per run.
 *
 * <p>One instance == one swarm run. The first event seen starts the clock; the
 * {@code SWARM_COMPLETED} event triggers the flush.</p>
 *
 * <p>If the run crashes before {@code SWARM_COMPLETED}, {@link #flushOnShutdown()}
 * writes whatever was captured — better a partial trace than nothing.</p>
 */
public class TranscriptRecorder {

    private static final Logger log = LoggerFactory.getLogger(TranscriptRecorder.class);

    private final DemoRecorderProperties props;
    private final TraceWriter writer;
    private final List<Map<String, Object>> steps = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Long> toolInFlight = new ConcurrentHashMap<>(); // key = agent|tool
    private final Map<String, Map<String, Object>> toolStartPayload = new ConcurrentHashMap<>();

    private volatile long startMs = -1;
    private volatile boolean flushed = false;
    private volatile String finalContent = "";
    private volatile int swarmIndex = 0;                 // incremented on each SWARM_STARTED
    private volatile String currentSwarmId = null;

    public TranscriptRecorder(DemoRecorderProperties props, TraceWriter writer) {
        this.props = props;
        this.writer = writer;
        if (props.getSlug() == null || props.getModel() == null) {
            log.warn("DemoRecorder activated but swarmai.demo.slug or swarmai.demo.model is unset — writing under 'unknown'.");
        }
    }

    @EventListener
    public synchronized void onEvent(SwarmEvent event) {
        SwarmEvent.Type type = event.getType();

        // A new swarm is starting. If the previous one never flushed (e.g. it
        // errored before SWARM_COMPLETED), flush what we have, then reset state
        // so each swarm run becomes its own self-contained trace.
        if (type == SwarmEvent.Type.SWARM_STARTED) {
            if (!steps.isEmpty() && !flushed) {
                flush();
            }
            resetForNextSwarm(event.getSwarmId());
        }

        if (startMs < 0) startMs = System.currentTimeMillis();
        long t = System.currentTimeMillis() - startMs;

        String kind = mapKind(type);
        Map<String, Object> md = safeMetadata(event);

        if (type == SwarmEvent.Type.TOOL_STARTED) {
            String key = toolKey(md);
            toolInFlight.put(key, t);
            toolStartPayload.put(key, md);
            return; // we emit the paired tool_call on TOOL_COMPLETED
        }

        if (type == SwarmEvent.Type.TOOL_COMPLETED || type == SwarmEvent.Type.TOOL_FAILED) {
            String key = toolKey(md);
            Long started = toolInFlight.remove(key);
            Map<String, Object> start = toolStartPayload.remove(key);
            long durationMs = (started != null) ? (t - started) : 0;

            Map<String, Object> step = new LinkedHashMap<>();
            step.put("t", started != null ? started : t);
            step.put("kind", "tool_call");
            if (start != null) {
                putIfAbsent(step, "tool", start.get("tool"));
                putIfAbsent(step, "agent", start.get("agent"));
                putIfAbsent(step, "input", start.get("input"));
            }
            putIfAbsent(step, "output", md.get("output"));
            if (type == SwarmEvent.Type.TOOL_FAILED) {
                step.put("error", firstNonNull(md.get("error"), event.getMessage()));
            }
            step.put("durationMs", durationMs);
            steps.add(step);
            return;
        }

        // Generic step
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("t", t);
        step.put("kind", kind);
        // Pass through known fields from metadata
        for (Map.Entry<String, Object> e : md.entrySet()) {
            step.putIfAbsent(e.getKey(), e.getValue());
        }
        // Surface the event message as content when useful
        if (event.getMessage() != null && !step.containsKey("content") && isContentEvent(type)) {
            step.put("content", event.getMessage());
        }
        steps.add(step);

        if (type == SwarmEvent.Type.SWARM_COMPLETED) {
            // capture final artifact if framework put it in metadata
            Object output = md.get("output");
            if (output instanceof String s) finalContent = s;
            else if (output != null)        finalContent = String.valueOf(output);
            flush();
        }
    }

    @PreDestroy
    public void flushOnShutdown() {
        if (!flushed && !steps.isEmpty()) {
            log.warn("DemoRecorder: SWARM_COMPLETED never arrived — writing partial trace.");
            flush();
        }
    }

    private void resetForNextSwarm(String newSwarmId) {
        steps.clear();
        toolInFlight.clear();
        toolStartPayload.clear();
        startMs = -1;
        flushed = false;
        finalContent = "";
        swarmIndex++;
        currentSwarmId = newSwarmId;
    }

    private synchronized void flush() {
        if (flushed) return;
        flushed = true;

        String slug = nz(props.getSlug(), "unknown");
        String model = nz(props.getModel(), "unknown");

        // If SWARM_COMPLETED didn't carry an "output" payload (common — the
        // framework's Swarm.java doesn't currently publish final text in event
        // metadata), fall back to the last agent_completed's output. This keeps
        // the trace self-contained so the website's final-output card renders
        // real content instead of an empty box.
        if (finalContent == null || finalContent.isEmpty()) {
            for (int i = steps.size() - 1; i >= 0; i--) {
                Map<String, Object> s = steps.get(i);
                if ("agent_completed".equals(s.get("kind"))) {
                    Object out = s.get("output");
                    if (out instanceof String str && !str.isBlank()) {
                        finalContent = str;
                        break;
                    }
                }
            }
        }

        // Synthesize a final step if the framework didn't emit one
        boolean hasFinal = steps.stream().anyMatch(s -> "final".equals(s.get("kind")));
        if (!hasFinal && finalContent != null && !finalContent.isEmpty()) {
            long maxT = steps.stream().mapToLong(s -> ((Number) s.get("t")).longValue()).max().orElse(0);
            Map<String, Object> finalStep = new LinkedHashMap<>();
            finalStep.put("t", maxT + 10);
            finalStep.put("kind", "final");
            finalStep.put("content", finalContent);
            steps.add(finalStep);
        }

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("$schema", 1);
        trace.put("demoSlug", slug);
        trace.put("side", "swarm");
        trace.put("model", model);
        trace.put("modelDisplayName", nz(props.getModelDisplayName(), model));
        trace.put("provider", nz(props.getProvider(), guessProvider(model)));
        trace.put("frameworkVersion", props.getFrameworkVersion());
        trace.put("recordedAt", Instant.now().toString());
        trace.put("reproducibility", buildReproducibility());
        trace.put("metrics", computeMetrics());
        trace.put("finalOutput", buildFinalOutput());
        trace.put("steps", new ArrayList<>(steps));

        // Path layout: demos/<slug>/runs/<model>/<framework-version>/<slug>.json
        // Including the framework version lets us keep old recordings and compare
        // across releases (regression CLI + v1.0-vs-v1.3 self-improvement demo).
        // Multi-swarm runs in one JVM get suffixed: <slug>-2.json, <slug>-3.json, …
        String fileName = swarmIndex <= 1 ? slug + ".json" : slug + "-" + swarmIndex + ".json";
        String version = (props.getFrameworkVersion() != null && !props.getFrameworkVersion().isBlank())
                ? props.getFrameworkVersion() : "unknown";
        Path target = Paths.get(props.getOutDir())
                .resolve(slug).resolve("runs").resolve(model).resolve(version).resolve(fileName);
        try {
            writer.write(trace, target);
            log.info("DemoRecorder: wrote {} ({} steps, swarmIndex={}, swarmId={})",
                    target.toAbsolutePath(), steps.size(), swarmIndex, currentSwarmId);
        } catch (IOException e) {
            log.error("DemoRecorder: failed to write trace to {}", target, e);
        }
    }

    // --- helpers ---

    private Map<String, Object> buildReproducibility() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("modelVersion", props.getModel());
        r.put("provider", nz(props.getProvider(), guessProvider(props.getModel())));
        r.put("temperature", props.getTemperature());
        r.put("seed", props.getSeed());
        r.put("topP", props.getTopP());
        r.put("maxTokens", props.getMaxTokens());
        r.put("frameworkVersion", props.getFrameworkVersion());
        r.put("frameworkGitSha", props.getFrameworkGitSha());
        r.put("recorderVersion", "0.1.0");
        Map<String, String> env = new LinkedHashMap<>();
        env.put("os", System.getProperty("os.name", "unknown"));
        env.put("javaVersion", System.getProperty("java.version", "unknown"));
        r.put("environment", env);
        return r;
    }

    private Map<String, Object> computeMetrics() {
        long wallTime = steps.stream().mapToLong(s -> ((Number) s.get("t")).longValue()).max().orElse(0);
        int toolCalls = 0;
        int llmRequests = 0;
        int failures = 0;
        int retries = 0;
        int promptTokens = 0;
        int completionTokens = 0;
        double costUsd = 0.0;
        Set<String> agents = new HashSet<>();

        for (Map<String, Object> s : steps) {
            String kind = (String) s.get("kind");
            if ("tool_call".equals(kind)) toolCalls++;
            if ("llm_request".equals(kind)) {
                llmRequests++;
                promptTokens     += intVal(s.get("promptTokens"));
                completionTokens += intVal(s.get("completionTokens"));
                costUsd          += doubleVal(s.get("costUsd"));
            }
            if ("tool_failed".equals(kind) || (s.containsKey("error") && s.get("error") != null)) failures++;
            if ("retry".equals(kind)) retries++;
            Object agent = s.get("agent");
            if (agent instanceof String agentName) agents.add(agentName);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("wallTimeMs", wallTime);
        m.put("totalTokens", promptTokens + completionTokens);
        m.put("inputTokens", promptTokens);
        m.put("outputTokens", completionTokens);
        m.put("costUsd", costUsd);
        m.put("toolCalls", toolCalls);
        m.put("agentTurns", agents.size());
        m.put("llmRequests", llmRequests);
        m.put("failures", failures);
        m.put("retries", retries);
        return m;
    }

    private Map<String, Object> buildFinalOutput() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", "markdown");
        out.put("content", finalContent);
        return out;
    }

    private static String mapKind(SwarmEvent.Type t) {
        return t.name().toLowerCase();
    }

    private static boolean isContentEvent(SwarmEvent.Type t) {
        return switch (t) {
            case AGENT_COMPLETED, TASK_COMPLETED, SKILL_GENERATED -> true;
            default -> false;
        };
    }

    private static String toolKey(Map<String, Object> md) {
        return String.valueOf(md.get("agent")) + "|" + String.valueOf(md.get("tool"));
    }

    private static Map<String, Object> safeMetadata(SwarmEvent event) {
        Map<String, Object> md = event.getMetadata();
        return md != null ? md : Collections.emptyMap();
    }

    private static void putIfAbsent(Map<String, Object> map, String key, Object value) {
        if (value != null) map.putIfAbsent(key, value);
    }

    private static Object firstNonNull(Object a, Object b) { return a != null ? a : b; }
    private static String nz(String s, String fallback) { return (s == null || s.isBlank()) ? fallback : s; }
    private static int intVal(Object o) { return o instanceof Number n ? n.intValue() : 0; }
    private static double doubleVal(Object o) { return o instanceof Number n ? n.doubleValue() : 0.0; }

    private static String guessProvider(String model) {
        if (model == null) return "unknown";
        if (model.contains("mistral") || model.contains("ollama") || model.contains("llama")) return "ollama";
        if (model.contains("gpt")) return "openai";
        if (model.contains("claude")) return "anthropic";
        return "unknown";
    }

    // SHA-256 hex — used by the baseline runner for prompt/workflow hashes.
    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return "sha256:" + sb;
        } catch (Exception e) {
            return "sha256:unknown";
        }
    }

    public static String readFileOrEmpty(Path p) {
        try { return Files.readString(p); } catch (Exception e) { return ""; }
    }
}
