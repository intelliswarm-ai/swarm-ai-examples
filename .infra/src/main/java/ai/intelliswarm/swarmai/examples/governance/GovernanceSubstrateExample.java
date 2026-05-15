package ai.intelliswarm.swarmai.examples.governance;

import ai.intelliswarm.swarmai.agent.hook.HookAction;
import ai.intelliswarm.swarmai.agent.hook.HookResult;
import ai.intelliswarm.swarmai.agent.hook.LifecycleEvent;
import ai.intelliswarm.swarmai.agent.hook.LifecycleHook;
import ai.intelliswarm.swarmai.agent.hook.LifecycleHookRegistry;
import ai.intelliswarm.swarmai.agent.permission.ToolCallRiskClassifier;
import ai.intelliswarm.swarmai.agent.permission.ToolCallRiskClassifier.Tier;
import ai.intelliswarm.swarmai.settings.MergedSettings;
import ai.intelliswarm.swarmai.settings.SettingsLayer;
import ai.intelliswarm.swarmai.settings.SettingsMerger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SwarmAI 1.0.24 — Governance substrate self-test.
 *
 * <p>Walks through every Phase-2 follow-up primitive that ships in 1.0.24
 * and asserts a property of each, printing ✓/✗ per axis. Runs entirely
 * offline.
 *
 * <ol>
 *   <li>{@link LifecycleHookRegistry} — registers a counting hook + a PII-redacting
 *       hook, dispatches a {@link LifecycleEvent.UserPromptSubmit} carrying a fake
 *       SSN, verifies the redactor MODIFY-ed the prompt and the counter saw 1
 *       event before the modify short-circuited the chain.</li>
 *   <li>{@link SettingsMerger} — three layers (PROJECT &gt; USER &gt; MACHINE)
 *       with overlapping keys; verifies project-layer wins where it sets a key,
 *       user where it sets a key project doesn't, machine for the rest, and that
 *       provenance is correctly tracked.</li>
 *   <li>{@link ToolCallRiskClassifier} — feeds 8 representative tool calls and
 *       asserts each is categorised at the expected tier
 *       (READ_ONLY / NETWORK / MUTATION / DESTRUCTIVE).</li>
 * </ol>
 */
@Component
public class GovernanceSubstrateExample {

    private static final Logger logger = LoggerFactory.getLogger(GovernanceSubstrateExample.class);

    public void run(String[] args) {
        logger.info("");
        logger.info("================================================================");
        logger.info("  SwarmAI 1.0.24 — Governance substrate self-test");
        logger.info("================================================================");
        logger.info("");

        int pass = 0;
        int total = 0;

        // ---- [1/3] LifecycleHookRegistry — modify-and-short-circuit ----
        logger.info("[1/3] LifecycleHookRegistry — counting hook + PII redactor");
        AtomicInteger counter = new AtomicInteger();
        // LifecycleHook isn't a SAM interface (it has per-event default methods),
        // so use an anonymous class overriding the dispatch entry point.
        LifecycleHook countingHook = new LifecycleHook() {
            @Override
            public HookResult onEvent(LifecycleEvent event) {
                counter.incrementAndGet();
                return HookResult.proceed();
            }
        };
        LifecycleHook redactor = new LifecycleHook() {
            @Override
            public HookResult onUserPromptSubmit(LifecycleEvent.UserPromptSubmit ups) {
                if (ups.prompt().matches("(?s).*\\b\\d{3}-\\d{2}-\\d{4}\\b.*")) {
                    String redacted = ups.prompt()
                            .replaceAll("\\b\\d{3}-\\d{2}-\\d{4}\\b", "[SSN-REDACTED]");
                    return HookResult.modify(redacted, "PII (SSN) redacted before LLM dispatch");
                }
                return HookResult.proceed();
            }
        };
        LifecycleHookRegistry registry = new LifecycleHookRegistry();
        registry.register(countingHook);
        registry.register(redactor);

        LifecycleEvent.UserPromptSubmit evt = new LifecycleEvent.UserPromptSubmit(
                "corr-1", "agent-1", Instant.now(),
                "Look up customer with SSN 123-45-6789 please", Map.of());
        HookResult res = registry.dispatch(evt);

        boolean modified = res.action() == HookAction.MODIFY;
        boolean redacted = modified && res.modifiedPayload() instanceof String s && s.contains("[SSN-REDACTED]");
        boolean counterFired = counter.get() == 1;
        total += 3;
        if (modified) pass++;
        if (redacted) pass++;
        if (counterFired) pass++;
        logger.info("      Action returned MODIFY:                  {}", modified ? "✓" : "✗");
        logger.info("      Modified payload contains [SSN-REDACTED]: {}", redacted ? "✓" : "✗");
        logger.info("      Counting hook saw exactly 1 event:       {}", counterFired ? "✓" : "✗");
        logger.info("      Modified prompt: {}",
                res.modifiedPayload() instanceof String mp ? mp : "(none)");

        // ---- [2/3] SettingsMerger — three-layer precedence ----
        logger.info("");
        logger.info("[2/3] SettingsMerger — PROJECT > USER > MACHINE precedence");
        Map<String, Object> machine = Map.of(
                "llm", Map.of("model", "gpt-4o-mini", "temperature", 0.5),
                "telemetry", Map.of("endpoint", "https://telemetry.corp.local"));
        Map<String, Object> user = Map.of(
                "llm", Map.of("temperature", 0.2),    // override only temperature
                "verbose", true);
        Map<String, Object> project = Map.of(
                "llm", Map.of("model", "claude-sonnet-4-5"));   // override only model

        MergedSettings merged = new SettingsMerger()
                .with(SettingsLayer.MACHINE, machine)
                .with(SettingsLayer.USER, user)
                .with(SettingsLayer.PROJECT, project)
                .merge();

        // Expectations:
        //   llm.model       = "claude-sonnet-4-5"  (PROJECT)
        //   llm.temperature = 0.2                  (USER)
        //   telemetry.endpoint = "https://telemetry.corp.local" (MACHINE)
        //   verbose = true                         (USER)
        record Expect(String key, Object value, SettingsLayer layer) {}
        List<Expect> expectations = List.of(
                new Expect("llm.model", "claude-sonnet-4-5", SettingsLayer.PROJECT),
                new Expect("llm.temperature", 0.2, SettingsLayer.USER),
                new Expect("telemetry.endpoint", "https://telemetry.corp.local", SettingsLayer.MACHINE),
                new Expect("verbose", true, SettingsLayer.USER));
        logger.info(String.format(Locale.ROOT, "      %-22s %-30s %-10s %s",
                "key", "value", "layer", "verdict"));
        for (Expect e : expectations) {
            Object val = merged.get(e.key()).orElse(null);
            SettingsLayer prov = merged.provenance(e.key()).orElse(null);
            boolean ok = e.value().equals(val) && e.layer() == prov;
            total++;
            if (ok) pass++;
            logger.info(String.format(Locale.ROOT, "      %-22s %-30s %-10s %s",
                    e.key(), val, prov, ok ? "✓" : "✗ (expected " + e.value() + " from " + e.layer() + ")"));
        }

        // ---- [3/3] ToolCallRiskClassifier — 8-row table ----
        logger.info("");
        logger.info("[3/3] ToolCallRiskClassifier — tool name + args → risk tier");
        ToolCallRiskClassifier classifier = new ToolCallRiskClassifier();
        record Row(String tool, Map<String, Object> args, Tier expected) {}
        List<Row> rows = List.of(
                new Row("file_read", Map.of("path", "/etc/hosts"), Tier.READ_ONLY),
                new Row("grep", Map.of("pattern", "TODO"), Tier.READ_ONLY),
                new Row("web_search", Map.of("q", "swarmai"), Tier.READ_ONLY),
                new Row("http_fetch", Map.of("url", "https://api.example.com"), Tier.NETWORK),
                new Row("file_write", Map.of("path", "/tmp/x", "content", "hi"), Tier.MUTATION),
                new Row("kafka_publish", Map.of("topic", "events"), Tier.MUTATION),
                new Row("file_delete", Map.of("path", "/tmp/x"), Tier.DESTRUCTIVE),
                new Row("drop_table", Map.of("table", "users"), Tier.DESTRUCTIVE));
        logger.info(String.format(Locale.ROOT, "      %-18s %-30s %-14s %-14s %s",
                "tool", "args", "expected", "actual", "verdict"));
        for (Row r : rows) {
            Tier actual = classifier.classify(r.tool(), r.args());
            boolean ok = actual == r.expected();
            total++;
            if (ok) pass++;
            logger.info(String.format(Locale.ROOT, "      %-18s %-30s %-14s %-14s %s",
                    r.tool(), abbrev(r.args().toString(), 28), r.expected(), actual, ok ? "✓" : "✗"));
        }

        // ---- Summary ----
        logger.info("");
        logger.info("================================================================");
        logger.info("  Governance substrate: {}/{} assertions passed", pass, total);
        logger.info("================================================================");
        logger.info("");
        logger.info("How these compose in production:");
        logger.info("  - Agent.builder().lifecycleHookRegistry(registry) wires the registry into");
        logger.info("    Agent.callLlm — every prompt now passes through registered hooks before");
        logger.info("    dispatch. PII redactors, prompt-injection sanitisers, audit-log emitters,");
        logger.info("    and rate-limit gates all plug in via this single hook chain.");
        logger.info("  - SettingsMerger lets ops define machine-wide defaults (telemetry endpoint,");
        logger.info("    proxy URL), users override personal preferences (verbose mode), and");
        logger.info("    projects pin model + tool allow-list — all from layered YAML files");
        logger.info("    without code changes. Provenance lookup answers 'where did this value");
        logger.info("    come from?' for support cases.");
        logger.info("  - ToolCallRiskClassifier feeds permission gates: a READ_ONLY tool can");
        logger.info("    autorun, NETWORK requires user opt-in once per session, MUTATION asks");
        logger.info("    every time, DESTRUCTIVE asks + requires a typed confirmation. The");
        logger.info("    tier is recomputed from (toolName, args) on every call so an opaque");
        logger.info("    SQL tool with 'DROP TABLE' in its query still classifies DESTRUCTIVE.");
        logger.info("");
    }

    private static String abbrev(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
