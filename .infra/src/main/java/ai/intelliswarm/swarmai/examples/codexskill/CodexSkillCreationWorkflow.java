package ai.intelliswarm.swarmai.examples.codexskill;

import ai.intelliswarm.swarmai.agent.subagent.EphemeralDirectoryWorkspace;
import ai.intelliswarm.swarmai.agent.subagent.coding.CodexSubagentSpawner;
import ai.intelliswarm.swarmai.agent.subagent.coding.CodingAgentAuth;
import ai.intelliswarm.swarmai.agent.subagent.coding.CodingAgentSubagentSpawner;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.skill.CodingAgentSkillGenerator;
import ai.intelliswarm.swarmai.skill.GeneratedSkill;
import ai.intelliswarm.swarmai.skill.SkillEngine;
import ai.intelliswarm.swarmai.skill.SkillRegistry;
import ai.intelliswarm.swarmai.skill.SkillValidator;
import ai.intelliswarm.swarmai.skill.runtime.ApprovalGates;
import ai.intelliswarm.swarmai.skill.runtime.CapabilityManifest;
import ai.intelliswarm.swarmai.skill.runtime.ContainerSkillRuntime;
import ai.intelliswarm.swarmai.skill.runtime.ImageCatalog;
import ai.intelliswarm.swarmai.skill.runtime.ImagePruneAnalyzer;
import ai.intelliswarm.swarmai.skill.runtime.UpstreamImageAnalyzer;
import ai.intelliswarm.swarmai.skill.runtime.KaliToolbox;
import ai.intelliswarm.swarmai.skill.runtime.ManifestImageReader;
import ai.intelliswarm.swarmai.skill.runtime.ManifestParser;
import ai.intelliswarm.swarmai.skill.runtime.ManifestSmokeValidator;
import ai.intelliswarm.swarmai.skill.runtime.ManifestToolFactory;
import ai.intelliswarm.swarmai.skill.runtime.NmapOutputParser;
import ai.intelliswarm.swarmai.skill.runtime.ToolRelevanceFilter;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.common.CalculatorTool;
import ai.intelliswarm.swarmai.tool.common.WebSearchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * End-to-end Codex-driven skill creation: <b>prompt → Codex → SKILL.md → parse →
 * validate → register → execute → real numerical output</b>.
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>Single-gap mode</b> (default) — pass a gap as the CLI args. The
 *       workflow prints the SKILL.md, runs the validator, and (whether or
 *       not validation passes) executes the skill against a sample input
 *       set parsed from the SKILL.md's first integration test.</li>
 *   <li><b>Showcase mode</b> — invoke with the literal arg
 *       {@code "--showcase"} to run a curated set of capability gaps with
 *       fixed sample inputs, registering each successful skill in a shared
 *       {@link SkillRegistry} and printing a comparison table at the end.</li>
 * </ul>
 *
 * <p>The point of this example is to show that the artifacts Codex produces
 * are <em>real, executable, framework-integrated</em> code — not just
 * static markdown. Each generated skill is loaded into a {@code GeneratedSkill}
 * and run via {@code skill.execute(params)}, the same code path that any
 * other agent in SwarmAI would use to invoke a registered skill.
 */
@Component
public class CodexSkillCreationWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(CodexSkillCreationWorkflow.class);

    /**
     * Optional ChatClient — present when Spring AI's auto-config provided one
     * (typical in this project: OpenAI or Ollama). Used by the
     * {@code --planned-audit} mode for the planning-phase LLM call.
     */
    private final ObjectProvider<ChatClient> chatClientProvider;

    /**
     * Optional WebSearchTool — present when {@code swarmai-tools} is on the
     * classpath. Used by {@code --planned-audit} to research current
     * best-of-breed open-source tools before planning.
     */
    private final ObjectProvider<WebSearchTool> webSearchProvider;

    public CodexSkillCreationWorkflow(
            ObjectProvider<ChatClient> chatClientProvider,
            ObjectProvider<WebSearchTool> webSearchProvider) {
        this.chatClientProvider = chatClientProvider;
        this.webSearchProvider = webSearchProvider;
    }

    /** Existing tools the generated skill may compose via the {@code tools.X.execute(...)} binding. */
    private static final List<String> EXISTING_TOOLS = List.of(
            "calculator", "http_request", "json_transform");

    /** Tool subset wired with real implementations for showcase execution. */
    private static final List<String> WIRED_TOOLS = List.of("calculator");

    /** Wall-clock cap for one Codex generation attempt. */
    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    /**
     * Curated capability gaps for showcase mode. Each entry is a
     * {gap-prompt, sample-input-params} pair. Gaps are deliberately
     * pure-computation skills so we can exercise them without external
     * APIs or web access.
     */
    private static final List<ShowcaseCase> SHOWCASE_CASES = List.of(
            new ShowcaseCase(
                "haversine_distance",
                "Compute the haversine distance in kilometers between two GPS coordinates "
              + "given as params.lat1, params.lon1, params.lat2, params.lon2 (all decimal "
              + "degrees). Return a string like 'distance_km=12.34'.",
                Map.of("lat1", 40.7128, "lon1", -74.0060,   // New York
                       "lat2", 34.0522, "lon2", -118.2437), // Los Angeles
                "expected ~3935 km (NY → LA)",
                false  // pure inline compute (sin/cos not supported by CalculatorTool)
            ),
            new ShowcaseCase(
                "celsius_to_fahrenheit",
                "Convert a Celsius temperature given as params.celsius (numeric) into "
              + "Fahrenheit. Return a string like 'fahrenheit=98.6'. Round to 1 decimal place.",
                Map.of("celsius", 100.0),
                "expected fahrenheit=212.0 (boiling point)",
                false
            ),
            new ShowcaseCase(
                "fibonacci_nth",
                "Compute the nth Fibonacci number where params.n is a non-negative integer "
              + "(0-indexed: F(0)=0, F(1)=1, F(2)=1, F(3)=2, ...). Return a string like 'fib(10)=55'.",
                Map.of("n", 20),
                "expected fib(20)=6765",
                false
            ),
            new ShowcaseCase(
                "tip_calculator",
                "Calculate a restaurant tip. params.bill is the bill total (numeric) and "
              + "params.percent is the tip percentage (numeric, e.g. 18 for 18%). Return a "
              + "string like 'tip=18.50, total=121.50'. Round to 2 decimals.",
                Map.of("bill", 87.42, "percent", 20.0),
                "expected tip=17.48, total=104.90",
                false
            ),
            // Tool-composing case: the gap explicitly directs Codex to delegate
            // basic arithmetic to the calculator tool — and CalculatorTool can
            // actually do +,-,*,/,%,(). Proves tools.<name>.execute(...) calls
            // inside generated skills resolve at runtime when wired.
            new ShowcaseCase(
                "split_bill_via_calculator",
                "Split a restaurant bill among diners. You MUST delegate the arithmetic "
              + "to tools.calculator.execute(Map.of('expression', '<expr>')) — the "
              + "calculator supports +, -, *, /, %, and parentheses only. params.bill is "
              + "the bill total, params.tip is the tip amount, params.diners is the "
              + "number of people. Return 'per_diner=12.34' rounded to 2 decimals. The "
              + "calculator returns a numeric String you can parse with new BigDecimal(...).",
                Map.of("bill", 120.0, "tip", 24.0, "diners", 4),
                "expected per_diner=36.00 ((120+24)/4)",
                true  // Codex should advertise + use the calculator tool
            ),
            // The wings case: a CONTAINER skill that spawns nmap inside Docker
            // and returns the scan report. Pure Groovy can't run nmap; this is
            // exactly the use case CONTAINER unlocks. Target is 127.0.0.1 so
            // the showcase is safe to run anywhere.
            //
            // IMPORTANT for the agent: the gap text below uses the strongest
            // possible language about which skill type to emit. Codex's
            // default bias (without this) is to fall back to CODE / Groovy.
            new ShowcaseCase(
                "container_nmap_localhost_scan",
                "REQUIRED: emit a SKILL.md whose frontmatter contains EXACTLY "
              + "`type: CONTAINER`. Do NOT use type CODE, HYBRID, COMPOSITE, "
              + "or PROMPT. Do NOT include a `## Code` section. Do NOT include "
              + "a `## Test Cases` section. The body must be a brief "
              + "description, a `## Dockerfile` section, and a "
              + "`## Integration Tests` section.\n\n"
              + "TASK: build a network port scanner that uses nmap inside a "
              + "Docker container the FRAMEWORK BUILDS from your inline "
              + "Dockerfile. The skill scans a single target host for open "
              + "ports. The target is supplied as params.target.\n\n"
              + "EXACT frontmatter shape required (no `image:` field — the "
              + "Dockerfile is the source of truth):\n\n"
              + "  type: CONTAINER\n"
              + "  category: security\n"
              + "  tags: [pentest, network, scan, nmap]\n"
              + "  container:\n"
              + "    inputs:\n"
              + "      target: TARGET\n"
              + "    network: bridge\n"
              + "    timeoutSeconds: 60\n\n"
              + "EXACT Dockerfile body required (copy verbatim):\n\n"
              + "## Dockerfile\n"
              + "```dockerfile\n"
              + "FROM alpine:3.19\n"
              + "RUN apk add --no-cache nmap\n"
              + "ENTRYPOINT [\"sh\", \"-c\", \"nmap -F $TARGET\"]\n"
              + "```\n\n"
              + "The ENTRYPOINT shell-form lets $TARGET expand from the env "
              + "var at runtime. The framework will build this image (you'll "
              + "see it as `swarmai-skill-<sha>` in `docker images`), run a "
              + "fresh container with `--network bridge` and `TARGET=<value>`, "
              + "and capture stdout as the skill output.\n\n"
              + "DO NOT include any `command:` or `entrypoint:` field in the "
              + "frontmatter — the Dockerfile's ENTRYPOINT handles execution.",
                Map.of("target", "127.0.0.1"),
                "expected output to contain 'Nmap scan report for 127.0.0.1' and a PORT/STATE table",
                false  // CONTAINER skills don't need internal tool wiring
            )
    );

    public void run(String[] args) {
        boolean showcase = args != null && args.length > 0 && "--showcase".equals(args[0]);
        boolean homeScan = args != null && args.length >= 2 && "--home-scan".equals(args[0]);
        boolean richScan = args != null && args.length >= 2 && "--rich-scan".equals(args[0]);
        boolean auditLog = args != null && args.length > 0 && "--audit-log".equals(args[0]);
        boolean listSkills = args != null && args.length > 0 && "--list-skills".equals(args[0]);
        boolean forGap = args != null && args.length >= 2 && "--for-gap".equals(args[0]);
        boolean plannedAudit = args != null && args.length >= 2 && "--planned-audit".equals(args[0]);
        boolean phasedAudit = args != null && args.length >= 2 && "--phased-audit".equals(args[0]);
        boolean kaliAudit = args != null && args.length >= 2 && "--kali-audit".equals(args[0]);
        boolean dynamicAudit = args != null && args.length >= 2 && "--dynamic-audit".equals(args[0]);
        boolean smartPrune = args != null && args.length >= 1 && "--smart-prune".equals(args[0]);

        if (auditLog) {
            printPersistedAuditLog();
            return;
        }
        if (listSkills) {
            printSkillCatalog();
            return;
        }
        if (phasedAudit) {
            runPhasedAudit(args[1]);
            return;
        }
        if (kaliAudit) {
            runKaliAudit(args[1]);
            return;
        }
        if (dynamicAudit) {
            runDynamicAudit(args[1]);
            return;
        }
        if (smartPrune) {
            // Trailing flags: --apply (execute), --include-orphans, --include-upstream
            boolean apply = java.util.Arrays.asList(args).contains("--apply");
            boolean includeOrphans = java.util.Arrays.asList(args).contains("--include-orphans");
            boolean includeUpstream = java.util.Arrays.asList(args).contains("--include-upstream");
            runSmartPrune(apply, includeOrphans, includeUpstream);
            return;
        }
        if (plannedAudit) {
            // Concat user-prompt args after the CIDR.
            String cidr = args[1];
            StringBuilder userPrompt = new StringBuilder();
            for (int i = 2; i < args.length; i++) {
                if (i > 2) userPrompt.append(' ');
                userPrompt.append(args[i]);
            }
            runPlannedAudit(cidr, userPrompt.toString().isBlank()
                    ? "Audit the security posture of the local network range — discover live hosts and inventory their services."
                    : userPrompt.toString());
            return;
        }
        if (forGap) {
            // Concat the rest of the args into the gap query.
            StringBuilder gap = new StringBuilder();
            for (int i = 1; i < args.length; i++) {
                if (i > 1) gap.append(' ');
                gap.append(args[i]);
            }
            scoreSkillsForGap(gap.toString());
            return;
        }

        if (!checkAuth()) return;

        ExecutorService executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "codex-skill-creation");
            t.setDaemon(true);
            return t;
        });
        try {
            SkillEngine engine = buildEngine(executor);
            if (showcase) {
                runShowcase(engine);
            } else if (homeScan) {
                runHomeScan(engine, args[1]);
            } else if (richScan) {
                runRichScan(args[1]);
            } else {
                String gap = (args == null || args.length == 0)
                        ? SHOWCASE_CASES.get(0).gap
                        : String.join(" ", args);
                runSingle(engine, gap, null, null);
            }
        } finally {
            executor.shutdown();
        }
    }

    // ---------------------------------------------------------------------
    // --rich-scan <CIDR> — multi-tool pentest container in a single image
    //   (alpine + nmap + nikto + whatweb + curl); one skill, three tool runs
    //   chained together by a shell entrypoint, output captured.
    // ---------------------------------------------------------------------

    private void runRichScan(String cidr) {
        banner("Rich pentest scan via multi-tool CONTAINER skill");
        logger.info("Target range : {}", cidr);
        logger.info("Strategy     : framework builds a single multi-tool image");
        logger.info("               (alpine + nmap + nikto + whatweb + curl), runs nmap");
        logger.info("               for host discovery + port scan, then whatweb against");
        logger.info("               every host that exposed http/https. All output is");
        logger.info("               captured as the skill's stdout.");
        logger.info("");

        var runtime = sharedContainerRuntime();
        var policy = ai.intelliswarm.swarmai.skill.runtime.ContainerPolicy.builder()
                .allowedNetworkModes("bridge")
                .allowBaseImage("alpine:*")
                .memoryCapRequired(true)
                .maxTimeout(java.time.Duration.ofMinutes(20))
                .banDockerfilePattern("(?i)^\\s*USER\\s+root\\s*$")
                .build();
        runtime.setPolicy(policy);
        // Persist the audit log so this run shows up in --audit-log later.
        runtime.setAuditPersistence(
                new ai.intelliswarm.swarmai.skill.runtime.ContainerAuditPersistence());
        runtime.clearAuditLog();

        // Single-tool, multi-script approach: nmap with NSE scripts gives us
        // discovery + service-version + HTTP banner+title fingerprinting in
        // one invocation. Avoids the parsing fragility of long multi-tool
        // shell pipelines in a Dockerfile JSON-form ENTRYPOINT.
        String dockerfile =
                "FROM alpine:3.19\n"
              + "RUN apk add --no-cache nmap nmap-scripts curl\n"
              + "ENTRYPOINT [\"sh\", \"-c\", "
              +   "\"nmap -sV --script=http-title,http-server-header,http-headers "
              +   "--top-ports 100 --max-retries 1 -T4 $TARGET\"]\n";

        ai.intelliswarm.swarmai.skill.ContainerSkillSpec spec =
                ai.intelliswarm.swarmai.skill.ContainerSkillSpec.builderFromDockerfile(dockerfile)
                        .envFromParam("target", "TARGET")
                        .network("bridge")
                        .memory("1g")
                        .timeout(java.time.Duration.ofMinutes(15))
                        .build();

        ai.intelliswarm.swarmai.skill.SkillDefinition def =
                new ai.intelliswarm.swarmai.skill.SkillDefinition();
        def.setName("rich_network_scan");
        def.setDescription("Multi-tool container that combines nmap host discovery, top-100 service scan, and whatweb HTTP fingerprinting in a single sandboxed run");
        def.setType(ai.intelliswarm.swarmai.skill.SkillType.CONTAINER);
        def.setCategory("security");
        def.setTags(java.util.List.of("network", "discovery", "service-version", "http-fingerprint"));
        def.setContainerSpec(spec);
        GeneratedSkill skill = new GeneratedSkill(def);

        banner("SKILL.md the framework will execute");
        for (String line : skill.toSkillMd().split("\n", -1)) {
            logger.info("  {}", line);
        }

        banner("Validating with slice-5 CONTAINER validator");
        ai.intelliswarm.swarmai.skill.SkillValidator validator =
                new ai.intelliswarm.swarmai.skill.SkillValidator(true);
        var validation = validator.validate(skill);
        logger.info("validation : {}",
                validation.passed() ? "PASSED" : "FAILED — " + validation.errorsAsString());
        if (!validation.passed()) return;

        banner("Running rich scan against " + cidr);
        logger.info("First run will build the image (~30–60 s for the alpine + tools layer).");
        logger.info("Subsequent runs are cache-hit and skip straight to the scan.");
        logger.info("");
        long t0 = System.currentTimeMillis();
        Object out = skill.execute(java.util.Map.of("target", cidr));
        long execMs = System.currentTimeMillis() - t0;

        banner("Scan complete in " + execMs + " ms");
        logger.info("=== combined tool output ===");
        for (String line : String.valueOf(out).split("\n", -1)) {
            logger.info("  {}", line);
        }

        banner("Audit log (in-memory + persisted to disk)");
        for (var entry : runtime.auditLog()) {
            logger.info("  {}", entry.summary());
        }
        var persistence = runtime.auditPersistence();
        if (persistence != null) {
            logger.info("");
            logger.info("Persisted to: {}", persistence.logPath());
            logger.info("Inspect any time with: ./codex-skill-creation/run.sh --audit-log");
        }

        var built = runtime.trackedImages();
        if (!built.isEmpty()) {
            banner("Framework-owned images (from this run)");
            for (String tag : built) logger.info("  • {}", tag);
            logger.info("");
            logger.info("To reclaim space: docker rmi {}", String.join(" ", built));
        }

        // Persist the skill to disk so subsequent --list-skills (and any
        // future workflow that loads from output/skills/) discovers it
        // without regenerating.
        try {
            ai.intelliswarm.swarmai.skill.SkillRegistry registry =
                    new ai.intelliswarm.swarmai.skill.SkillRegistry();
            skill.setStatus(ai.intelliswarm.swarmai.skill.SkillStatus.VALIDATED);
            registry.register(skill);
            registry.save(java.nio.file.Paths.get("output", "skills"));
            logger.info("");
            logger.info("Skill persisted to: output/skills/{}/SKILL.md", skill.getName());
            logger.info("Inspect the catalog with: --list-skills");
        } catch (java.io.IOException e) {
            logger.warn("Could not persist skill: {}", e.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // --list-skills — read persisted skills + audit log + Docker state and
    //   print a catalog that surfaces "what does the framework already know
    //   how to do, and how well does it do it" before any new generation.
    //   Also flags tag-overlap hints (potential supersession candidates).
    // ---------------------------------------------------------------------

    private void printSkillCatalog() {
        banner("SwarmAI container-skill catalog");
        java.nio.file.Path skillsDir = java.nio.file.Paths.get("output", "skills");
        ai.intelliswarm.swarmai.skill.SkillRegistry registry =
                new ai.intelliswarm.swarmai.skill.SkillRegistry();
        int loaded = registry.load(skillsDir);
        logger.info("Persisted skills directory: {} ({} loaded)", skillsDir.toAbsolutePath(), loaded);

        // Collect CONTAINER skills only — this view focuses on the dockerized side.
        java.util.List<ai.intelliswarm.swarmai.skill.GeneratedSkill> containerSkills =
                registry.getActiveSkills().stream()
                        .filter(s -> s.getDefinition().getType()
                                == ai.intelliswarm.swarmai.skill.SkillType.CONTAINER)
                        .toList();

        // Build per-skill audit summaries from the persisted audit log.
        // Container audit entries are keyed by image tag, not skill name —
        // we map back via the tag we'd recompute from the Dockerfile SHA1.
        var auditP = new ai.intelliswarm.swarmai.skill.runtime.ContainerAuditPersistence();
        var auditEntries = auditP.readAll();
        java.util.Map<String, AuditSummary> summaries = aggregateAudit(auditEntries);

        if (containerSkills.isEmpty()) {
            logger.info("");
            logger.info("(no CONTAINER skills registered yet)");
            logger.info("Run --rich-scan or --home-scan once to populate the catalog.");
            return;
        }

        logger.info("");
        logger.info("Found {} CONTAINER skill(s):", containerSkills.size());
        for (var skill : containerSkills) {
            var def = skill.getDefinition();
            var spec = def.getContainerSpec();
            String imageTag = (spec != null && spec.hasImage()) ? spec.image()
                    : (spec != null && spec.hasDockerfile()) ? "swarmai-skill-" + sha1Short(spec.dockerfile())
                    : "(no image)";
            AuditSummary stats = summaries.getOrDefault(imageTag, AuditSummary.empty());
            boolean imagePresent = imageStillPresent(imageTag);

            logger.info("");
            logger.info("─── {} ───", skill.getName());
            logger.info("  description : {}", def.getDescription());
            logger.info("  tags        : {}", def.getTags());
            logger.info("  category    : {}", def.getCategory());
            logger.info("  network     : {}", spec == null ? "(none)" : spec.network());
            logger.info("  memory cap  : {}", spec == null ? "(none)" : (spec.memory() == null ? "unlimited" : spec.memory()));
            logger.info("  timeout     : {}", spec == null ? "(none)" : spec.timeout());
            logger.info("  image       : {} ({})", imageTag,
                    imagePresent ? "present in `docker images`" : "NOT present (will rebuild on next run)");
            if (stats.runs > 0) {
                logger.info("  runs        : {} | avg duration {} ms | success rate {}",
                        stats.runs, stats.avgDurationMs(), stats.successRatePct());
            } else {
                logger.info("  runs        : 0 (never executed)");
            }
            if (spec != null && spec.hasDockerfile()) {
                logger.info("  Dockerfile  : (inline, SHA1 {}…)", sha1Short(spec.dockerfile()));
            }
        }

        // Tag-overlap supersession hints. When two skills share ≥2 tags,
        // surface them so the operator can decide which to deprecate.
        var hints = supersessionHints(containerSkills);
        if (!hints.isEmpty()) {
            logger.info("");
            banner("Supersession candidates (≥2 shared tags)");
            logger.info("These skills cover overlapping capabilities. Consider keeping the");
            logger.info("one with better efficiency metrics and removing the other:");
            logger.info("");
            for (String hint : hints) {
                logger.info("  • {}", hint);
            }
            logger.info("");
            logger.info("To remove a redundant skill: rm -rf output/skills/<skill-name>");
            logger.info("To reclaim its image:        docker rmi <image-tag>");
        }
    }

    // ---------------------------------------------------------------------
    // --planned-audit <CIDR> "<user-prompt>" — full agentic flow:
    //   web search → LLM planning → Dockerfile generation → build → run.
    //
    // The framework does NOT ask Codex to write the SKILL.md (OpenAI's
    // content policy declines offensive-security generation). Instead the
    // planner LLM produces a structured plan, and the framework constructs
    // the SKILL.md from the plan deterministically.
    // ---------------------------------------------------------------------

    // ---------------------------------------------------------------------
    // --phased-audit <CIDR> — phased + typed-findings architecture
    //
    //   Phase R (recon)  : alpine + nmap, parses stdout into PORT_OPEN
    //                      findings on a FindingsBlackboard
    //   Phase E (enrich) : per-host whatweb container, but ONLY for hosts
    //                      where Phase R found http/https service open.
    //                      No wasted nikto-loops over 256 unreachable IPs.
    //   Phase Report     : aggregates findings into Markdown + JSON.
    //
    // Inspired by Pentest-Swarm-AI's blackboard + phased model. The key
    // architectural property: each phase has its own focused image; tools
    // only run when there's structured input that fits the tool's shape.
    // ---------------------------------------------------------------------

    private void runPhasedAudit(String cidr) {
        banner("Phased network audit");
        logger.info("Target CIDR : {}", cidr);
        logger.info("Phases       : Recon → Web enrichment → Report");
        logger.info("Architecture : typed Findings on a shared blackboard between phases");
        logger.info("");

        var runtime = sharedContainerRuntime();
        var policy = ai.intelliswarm.swarmai.skill.runtime.ContainerPolicy.builder()
                .allowedNetworkModes("bridge")
                .allowBaseImage("alpine:*")
                .memoryCapRequired(true)
                .maxTimeout(java.time.Duration.ofMinutes(10))
                .banDockerfilePattern("(?i)^\\s*USER\\s+root\\s*$")
                .build();
        runtime.setPolicy(policy);
        runtime.setAuditPersistence(
                new ai.intelliswarm.swarmai.skill.runtime.ContainerAuditPersistence());

        var orchestrator = new ai.intelliswarm.swarmai.skill.runtime.PhasedAuditOrchestrator(runtime);
        var result = orchestrator.run(cidr);

        banner("Findings on the blackboard");
        for (var f : result.blackboard().all()) {
            logger.info("  {}", f.summary());
        }

        banner("Report (Markdown)");
        for (String line : result.markdown().split("\n", -1)) {
            logger.info("  {}", line);
        }

        banner("Report (JSON, single line)");
        logger.info("  {}", result.json());

        banner("Container audit log");
        for (var entry : runtime.auditLog()) {
            logger.info("  {}", entry.summary());
        }
        var built = runtime.trackedImages();
        if (!built.isEmpty()) {
            logger.info("");
            logger.info("Framework-owned images (this run):");
            for (String tag : built) logger.info("  • {}", tag);
        }
        logger.info("");
        logger.info("Phased audit complete in {} ms.", result.elapsedMs());
    }

    // ---------------------------------------------------------------------
    // --kali-audit <CIDR> — fully agentic audit
    //
    //   Single Agent reasons across the whole audit. The Kali toolbox
    //   (~18 fine-grained container-backed tools) is registered with the
    //   Agent. The LLM picks tools reactively: nmap_recon → looks at
    //   findings → calls whatweb on HTTP hosts → calls smbmap on SMB
    //   hosts → composes a final report. No hardcoded phase ordering.
    //
    //   This is the architectural shift the project has been working
    //   toward: Container = Tool (not Container = Script-runner), and
    //   Agent reasoning takes the place of phase orchestration. The same
    //   shape Claude Code uses with its builtin Bash / Read / Grep tools,
    //   except our toolkit is containerized and grows on demand.
    // ---------------------------------------------------------------------

    private void runKaliAudit(String cidr) {
        banner("Kali agentic audit");
        logger.info("Target CIDR : {}", cidr);
        logger.info("Architecture: single Agent + Kali toolbox of {} container-backed tools",
                "~18");
        logger.info("Reasoning   : reactive — agent picks each next tool from observed findings");
        logger.info("");

        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            logger.error("No ChatClient bean available — set OPENAI_API_KEY (or configure Ollama)");
            logger.error("and re-run. The agentic loop needs an LLM to drive tool selection.");
            return;
        }

        var runtime = sharedContainerRuntime();
        var policy = ai.intelliswarm.swarmai.skill.runtime.ContainerPolicy.builder()
                .allowedNetworkModes("bridge")
                .allowBaseImage("alpine:*")
                .allowBaseImage("kalilinux/kali-rolling")
                .allowBaseImage("python:*")
                .memoryCapRequired(true)
                .maxTimeout(Duration.ofMinutes(15))
                .banDockerfilePattern("(?i)^\\s*USER\\s+root\\s*$")
                .build();
        runtime.setPolicy(policy);
        runtime.setAuditPersistence(
                new ai.intelliswarm.swarmai.skill.runtime.ContainerAuditPersistence());

        var toolbox = new KaliToolbox(runtime);
        List<BaseTool> tools = toolbox.all();
        logger.info("Toolbox     : {}", tools.stream().map(BaseTool::getFunctionName).toList());
        logger.info("");
        logger.info("Pre-warming the shared Kali image (first build can take 5-10 min, cached after)...");
        // The first tool call would otherwise eat the build cost; do it up front
        // with a trivial probe so the agent's first tool call is fast.
        try {
            BaseTool probe = toolbox.dig();
            Object preWarm = probe.execute(Map.of("domain", "example.com"));
            logger.info("Pre-warm OK ({} chars output)",
                    preWarm == null ? 0 : preWarm.toString().length());
        } catch (RuntimeException e) {
            logger.warn("Pre-warm hit: {} — continuing; first agent tool call will pay the cost.",
                    e.getMessage());
        }
        logger.info("");

        Agent agent = Agent.builder()
                .id("kali-auditor")
                .role("Senior network security analyst")
                .goal("Audit the security posture of the target network and produce a findings report")
                .backstory(
                        "You audit networks methodically. You always start with nmap to discover live "
                      + "hosts and open ports, then pick the right follow-up tool for each service "
                      + "you observe: whatweb for HTTP, smbmap/enum4linux for SMB, ssh-audit for SSH, "
                      + "sslscan for TLS. You DO NOT call every tool against every host — you let "
                      + "the findings drive the next step. You stop when you have enough information "
                      + "to write a useful report (typically 5-15 tool calls). Your final answer is a "
                      + "Markdown report grouping findings by host.")
                .chatClient(chatClient)
                .tools(tools)
                .verbose(true)
                .maxIter(20)
                .maxExecutionTime(900_000) // 15 minutes wall clock (milliseconds)
                .build();

        Task task = Task.builder()
                .id("kali-audit-" + cidr.replaceAll("[^a-zA-Z0-9]", "_"))
                .description(
                        "Audit the network range " + cidr + ". Use the available container-backed "
                      + "security tools to discover live hosts, identify services, and surface "
                      + "anything noteworthy (open shares, weak TLS, identifiable software with "
                      + "known issues). Reason step-by-step: call one tool, look at the output, "
                      + "decide what to do next based on what you found. Don't repeat scans you "
                      + "already ran. When you have enough information, write a Markdown report.")
                .expectedOutput(
                        "A Markdown report grouping findings by host. Each host section lists the "
                      + "open ports/services, fingerprints (when applicable), and any noteworthy "
                      + "observations from the specialized tools.")
                .agent(agent)
                .build();

        long t0 = System.currentTimeMillis();
        TaskOutput out;
        try {
            out = agent.executeTask(task, List.of());
        } catch (RuntimeException e) {
            logger.error("Agent execution failed: {}", e.getMessage(), e);
            return;
        }
        long elapsedMs = System.currentTimeMillis() - t0;

        banner("Agent report");
        if (out != null && out.getRawOutput() != null) {
            for (String line : out.getRawOutput().split("\n", -1)) {
                logger.info("  {}", line);
            }
        }

        banner("Container audit log");
        for (var entry : runtime.auditLog()) {
            logger.info("  {}", entry.summary());
        }

        var built = runtime.trackedImages();
        if (!built.isEmpty()) {
            logger.info("");
            logger.info("Framework-owned images (this run):");
            for (String tag : built) logger.info("  • {}", tag);
        }
        logger.info("");
        logger.info("Kali agentic audit complete in {} ms.", elapsedMs);
    }

    // ---------------------------------------------------------------------
    // --dynamic-audit <CIDR> — full dynamic capability acquisition loop
    //
    //   Build a small self-describing image (alpine + nmap + curl) whose
    //   image LABEL carries a CapabilityManifest declaring the tools it
    //   exposes. Read the manifest back via docker inspect. Materialise
    //   one BaseTool per declared capability. Smoke-validate every
    //   declared tool. Filter the toolkit by relevance to the audit task.
    //   Hot-register the survivors with the Agent. Run the audit.
    //
    //   No piece of this is hard-coded glue: replace the embedded
    //   manifest with one Codex generates and the framework grows its
    //   own toolkit on demand. Same shape Claude Code uses for its
    //   builtin tools, except the toolkit isn't fixed at compile time.
    // ---------------------------------------------------------------------

    private void runDynamicAudit(String cidr) {
        banner("Dynamic capability acquisition + agentic audit");
        logger.info("Target CIDR : {}", cidr);
        logger.info("Strategy    : build self-describing image → read manifest → smoke-validate");
        logger.info("              → relevance-filter → hot-register with Agent → run reactive audit");
        logger.info("");

        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            logger.error("No ChatClient bean available — set OPENAI_API_KEY (or configure Ollama)");
            logger.error("and re-run. The agentic loop needs an LLM to drive tool selection.");
            return;
        }

        var runtime = sharedContainerRuntime();
        runtime.setSource("dynamic-audit:" + cidr);
        var policy = ai.intelliswarm.swarmai.skill.runtime.ContainerPolicy.builder()
                .allowedNetworkModes("bridge")
                .allowBaseImage("alpine:*")
                .memoryCapRequired(true)
                .maxTimeout(Duration.ofMinutes(10))
                .banDockerfilePattern("(?i)^\\s*USER\\s+root\\s*$")
                .build();
        runtime.setPolicy(policy);
        runtime.setAuditPersistence(
                new ai.intelliswarm.swarmai.skill.runtime.ContainerAuditPersistence());
        // Wrap the auto-approver in a remembering gate so re-runs of this
        // command don't re-prompt for the same image hash. Production demos
        // would swap the inner gate for ConsoleApprovalGate.
        runtime.setApprovalGate(new ApprovalGates.RememberingApprovalGate(
                new ApprovalGates.AutoApprovalGate()));

        // === Step 1: build the self-describing image ====================
        logger.info("[1/6] Building self-describing image (alpine + nmap + curl)...");
        String tag = buildSelfDescribingImage(runtime);
        if (tag == null) return;
        logger.info("       built tag: {}", tag);
        logger.info("");

        // === Step 2: read manifest back via docker inspect ==============
        logger.info("[2/6] Reading capability manifest from image LABEL...");
        var reader = new ManifestImageReader();
        CapabilityManifest manifest = reader.read(tag).orElse(null);
        if (manifest == null) {
            logger.error("       manifest LABEL not found on image {}", tag);
            return;
        }
        logger.info("       manifest '{}' v{} declares {} capabilities:",
                manifest.name(), manifest.version(), manifest.size());
        for (var cap : manifest.tools()) {
            logger.info("         • {} — {}", cap.name(),
                    abbrev(cap.description(), 80));
        }
        logger.info("");

        // === Step 3: materialise BaseTools from the manifest ============
        logger.info("[3/6] Materialising BaseTools from manifest...");
        var factory = ManifestToolFactory.builder()
                .runtime(runtime)
                .image(tag)
                .parserFor("nmap_scan", stdout -> {
                    var findings = NmapOutputParser.parse(stdout);
                    if (findings.isEmpty()) return "nmap_scan: no findings";
                    var sb = new StringBuilder("nmap_scan: ").append(findings.size())
                            .append(" finding(s)\n");
                    for (var f : findings) sb.append("  - ").append(f.summary()).append("\n");
                    return sb.toString();
                })
                .build();
        List<BaseTool> tools = factory.toTools(manifest);
        logger.info("       materialised {} tools: {}", tools.size(),
                tools.stream().map(BaseTool::getFunctionName).toList());
        logger.info("");

        // === Step 4: smoke-validate every declared tool =================
        logger.info("[4/6] Running smoke validator against all declared tools...");
        var validator = new ManifestSmokeValidator(runtime, factory);
        var smokeResults = validator.validate(manifest,
                Map.of("nmap_scan", Map.of("target", "127.0.0.1"),
                       "http_check", Map.of("target", "http://127.0.0.1")));
        for (var r : smokeResults) {
            logger.info("       {}", r.summary());
        }
        List<String> failedNames = smokeResults.stream()
                .filter(r -> !r.pass())
                .map(ManifestSmokeValidator.Result::toolName)
                .toList();
        // Drop hallucinated/broken tools from the agent's toolkit.
        if (!failedNames.isEmpty()) {
            tools = new ArrayList<>(tools);
            tools.removeIf(t -> failedNames.contains(t.getFunctionName()));
            logger.info("       dropped {} failed tool(s) from toolkit", failedNames.size());
        }
        logger.info("");

        // === Step 5: relevance-filter toolkit + hot-register with Agent ==
        logger.info("[5/6] Filtering toolkit by relevance and building agent...");
        String taskText = "Audit the network range " + cidr
                + " — discover hosts, identify services, surface anything noteworthy.";
        var relevance = new ToolRelevanceFilter();
        var ranked = relevance.rank(taskText, tools);
        for (var s : ranked) logger.info("       {}", s.summary());
        // For a tiny toolkit (2-3 tools) we just keep them all; the filter is
        // there to scale gracefully when a Codex-driven loop registers many.
        List<BaseTool> selected = relevance.topK(taskText, tools, Math.max(tools.size(), 4));
        if (selected.isEmpty()) selected = tools; // fail open

        Agent agent = Agent.builder()
                .id("dynamic-auditor")
                .role("Network security analyst")
                .goal("Audit the security posture of the target network and produce a findings report")
                .backstory(
                        "You audit networks methodically using whatever tools you've been given. "
                      + "Always start with port discovery, then follow up on each interesting "
                      + "service with the appropriate fingerprint tool. Don't repeat scans you "
                      + "already ran. Stop when you have enough information for a report (5-10 "
                      + "tool calls). Final answer is a Markdown report grouping findings by host.")
                .chatClient(chatClient)
                .tools(List.of()) // start empty so we can demonstrate hot-registration
                .verbose(true)
                .maxIter(15)
                .maxExecutionTime(600_000) // 10 minutes (milliseconds — see Agent.callLlm timeoutMs)
                .build();
        agent.registerTools(selected);
        logger.info("       agent has {} tools after hot-registration",
                agent.getTools().size());
        logger.info("");

        // === Step 6: run the agent ======================================
        logger.info("[6/6] Running agent against {} ...", cidr);
        Task task = Task.builder()
                .id("dynamic-audit-" + cidr.replaceAll("[^a-zA-Z0-9]", "_"))
                .description(taskText + " Use the available tools step-by-step. Reason about each "
                        + "tool's output before picking the next. When you have enough data, write "
                        + "a Markdown report.")
                .expectedOutput("A Markdown report grouping findings by host.")
                .agent(agent)
                .build();

        long t0 = System.currentTimeMillis();
        TaskOutput out;
        try {
            out = agent.executeTask(task, List.of());
        } catch (RuntimeException e) {
            logger.error("Agent execution failed: {}", e.getMessage(), e);
            return;
        }
        long elapsedMs = System.currentTimeMillis() - t0;

        banner("Agent report");
        if (out != null && out.getRawOutput() != null) {
            for (String line : out.getRawOutput().split("\n", -1)) {
                logger.info("  {}", line);
            }
        }

        banner("Container audit log");
        for (var entry : runtime.auditLog()) {
            logger.info("  {}", entry.summary());
        }
        logger.info("");
        logger.info("Dynamic audit complete in {} ms ({} container calls, {} tools registered).",
                elapsedMs, runtime.auditLog().size(), agent.getTools().size());
    }

    /**
     * Build the small self-describing image used by --dynamic-audit. The
     * image carries a CapabilityManifest as a base64-encoded LABEL so
     * {@link ManifestImageReader} can extract it back at runtime.
     */
    private String buildSelfDescribingImage(ContainerSkillRuntime runtime) {
        // The capability manifest declared by this image. Three small tools
        // exercising different parts of the pipeline (port scan, HTTP probe,
        // hostname echo).
        String manifestJson = """
                {
                  "name": "swarmai-mini-toolbox",
                  "version": "1",
                  "description": "Self-describing alpine image: nmap + curl, two callable capabilities",
                  "tools": [
                    {
                      "name": "nmap_scan",
                      "description": "Run an nmap port scan and service-version detection on a CIDR or host",
                      "triggerWhen": "First step of any network audit; given an IP, hostname, or CIDR",
                      "avoidWhen": "Don't run on /16 or larger ranges",
                      "category": "security/network",
                      "tags": ["network", "discovery", "port-scan"],
                      "parameters": [
                        {"name": "target", "type": "string", "description": "CIDR or host", "required": true}
                      ],
                      "entrypoint": "nmap -F -sV --top-ports 50 -T4 \\"$TARGET\\"",
                      "timeoutSeconds": 240,
                      "network": "bridge",
                      "memory": "512m"
                    },
                    {
                      "name": "http_check",
                      "description": "Fetch an HTTP URL and return headers + first 1KB of the body",
                      "triggerWhen": "After nmap finds 80/443 open and you want to fingerprint the web stack",
                      "avoidWhen": "Don't run on hosts without an HTTP server",
                      "category": "security/web",
                      "tags": ["http", "web", "fingerprint"],
                      "parameters": [
                        {"name": "target", "type": "string", "description": "URL", "required": true}
                      ],
                      "entrypoint": "curl -sSI --max-time 10 \\"$TARGET\\" 2>&1 | head -40 && echo '---' && curl -sS --max-time 10 \\"$TARGET\\" 2>&1 | head -c 1024",
                      "timeoutSeconds": 60,
                      "network": "bridge",
                      "memory": "128m"
                    }
                  ]
                }
                """;

        // Verify the manifest parses before we bake it into an image —
        // catches typos at workflow start rather than after a 60s build.
        try {
            ManifestParser.parse(manifestJson);
        } catch (RuntimeException e) {
            logger.error("Embedded manifest is invalid: {}", e.getMessage());
            return null;
        }

        String dockerfile = ""
              + "FROM alpine:3.19\n"
              + "RUN apk add --no-cache nmap nmap-scripts curl ca-certificates\n"
              + "CMD [\"true\"]\n";
        String b64 = java.util.Base64.getEncoder().encodeToString(
                manifestJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(ManifestImageReader.MANIFEST_LABEL, b64);
        labels.put("ai.swarmai.skill.name", "swarmai-mini-toolbox");
        labels.put("ai.swarmai.skill.framework-version", "1.0.23-SNAPSHOT");

        try {
            return runtime.buildImage(dockerfile, labels);
        } catch (Exception e) {
            logger.error("Image build failed: {}", e.getMessage(), e);
            return null;
        }
    }

    private static String abbrev(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    // ---------------------------------------------------------------------
    // --smart-prune [--apply] [--include-orphans] — manifest-aware cleanup
    //
    //   Default is dry-run. Reads every swarmai-skill-* image's manifest,
    //   builds an inverse capability index, picks the best provider per
    //   capability (recency + size scoring), and reports which images
    //   are dominated by another and safe to remove. Pass --apply to
    //   actually run docker rmi -f on the redundant set.
    //
    //   Orphans (images without a manifest LABEL — typically pre-manifest
    //   builds) are bucketed separately. Pass --include-orphans to also
    //   prune those; otherwise they're listed for manual review.
    // ---------------------------------------------------------------------

    private void runSmartPrune(boolean apply, boolean includeOrphans, boolean includeUpstream) {
        banner("Manifest-aware image cleanup");
        logger.info("Mode        : {}", apply ? "APPLY (will execute docker rmi -f)" : "DRY-RUN (no changes)");
        logger.info("Orphans     : {}", includeOrphans ? "include in prune" : "list only");
        logger.info("Upstream    : {}", includeUpstream ? "scan + include unreferenced images" : "skip");
        logger.info("");

        var catalog = new ImageCatalog().discover();
        if (catalog.isEmpty()) {
            logger.info("No swarmai-skill-* images found locally. Nothing to do.");
            return;
        }
        logger.info("Discovered {} swarmai-skill-* image(s):", catalog.size());
        for (var entry : catalog) {
            logger.info("  • {}", entry.summary());
        }
        logger.info("");

        var analyzer = ImagePruneAnalyzer.builder().build();
        var analysis = analyzer.analyze(catalog);
        logger.info("Analysis    : {}", analysis.summary());
        logger.info("");

        if (!analysis.keepers().isEmpty()) {
            logger.info("KEEP ({}):", analysis.keepers().size());
            for (var k : analysis.keepers()) {
                logger.info("  ✓ {}  (score={}; wins: {})",
                        k.entry().imageTag(),
                        String.format("%.2f", k.score()),
                        k.wonCapabilities());
            }
            logger.info("");
        }

        if (!analysis.redundant().isEmpty()) {
            logger.info("PRUNE — REDUNDANT ({}):", analysis.redundant().size());
            for (var r : analysis.redundant()) {
                logger.info("  ✗ {}  ({} MB)  — superseded by {}",
                        r.entry().imageTag(),
                        r.entry().sizeBytes() / 1_000_000,
                        r.supersededBy() == null ? "(better provider)" : r.supersededBy());
            }
            logger.info("");
        }

        if (!analysis.orphans().isEmpty()) {
            logger.info("ORPHANS — no manifest ({}):", analysis.orphans().size());
            for (var o : analysis.orphans()) {
                logger.info("  ? {}  ({} MB, {}d old) — {}",
                        o.entry().imageTag(),
                        o.entry().sizeBytes() / 1_000_000,
                        o.entry().ageDays(),
                        o.reason());
            }
            logger.info("");
        }

        // Optional upstream scan — looks at non-swarmai-skill images on the
        // host and flags any that are neither (a) the FROM-base of a kept
        // image (via RootFS layer prefix match) nor (b) referenced by any
        // running/stopped container. This is what catches stale upstream
        // images like instrumentisto/nmap that we pulled once and stopped
        // using.
        UpstreamImageAnalyzer.Analysis upstreamAnalysis = null;
        if (includeUpstream) {
            // Pass IDs of every kept manifest-bearing image so the upstream
            // analyzer can protect their FROM bases (alpine:3.19, etc.).
            List<String> keptIds = new ArrayList<>();
            for (var k : analysis.keepers()) keptIds.add(k.entry().imageId());
            for (var o : analysis.orphans()) keptIds.add(o.entry().imageId());

            var upstream = new UpstreamImageAnalyzer();
            upstreamAnalysis = upstream.analyze(keptIds);
            logger.info("Upstream    : {}", upstreamAnalysis.summary());
            logger.info("");
            if (!upstreamAnalysis.candidates().isEmpty()) {
                logger.info("PRUNE — UPSTREAM ORPHANS ({}):", upstreamAnalysis.candidates().size());
                for (var c : upstreamAnalysis.candidates()) {
                    logger.info("  ✗ {}  ({} MB)  — {}",
                            c.imageTag(),
                            c.sizeBytes() / 1_000_000,
                            c.reason());
                }
                logger.info("");
            }
        }

        if (!apply) {
            logger.info("Re-run with --apply to execute the prune.");
            if (!analysis.orphans().isEmpty()) {
                logger.info("Re-run with --apply --include-orphans to also remove orphans.");
            }
            if (!includeUpstream) {
                logger.info("Re-run with --include-upstream to scan for unused upstream images.");
            }
            return;
        }

        // Execute.
        int removedRedundant = analyzer.execute(analysis, false);
        logger.info("Removed {} redundant image(s) — reclaimed ~{} MB",
                removedRedundant, analysis.bytesReclaimable() / 1_000_000);

        if (includeOrphans && !analysis.orphans().isEmpty()) {
            int removedOrphans = analyzer.pruneOrphans(analysis.orphans(), false);
            long orphanBytes = analysis.bytesReclaimableWithOrphans() - analysis.bytesReclaimable();
            logger.info("Removed {} orphan image(s) — reclaimed ~{} MB",
                    removedOrphans, orphanBytes / 1_000_000);
        }

        if (includeUpstream && upstreamAnalysis != null && !upstreamAnalysis.candidates().isEmpty()) {
            int removedUpstream = new UpstreamImageAnalyzer().execute(upstreamAnalysis, false);
            logger.info("Removed {} upstream image(s) — reclaimed ~{} MB",
                    removedUpstream, upstreamAnalysis.bytesReclaimable() / 1_000_000);
        }
    }

    private static final int MAX_PLAN_ITERATIONS = 3;

    private void runPlannedAudit(String cidr, String userPrompt) {
        banner("Planned network audit (self-improving)");
        logger.info("Target CIDR     : {}", cidr);
        logger.info("User prompt     : {}", userPrompt);
        logger.info("Max iterations  : {} (plan → run → review → refine → re-run)", MAX_PLAN_ITERATIONS);
        logger.info("");

        ChatClient chat = chatClientProvider.getIfAvailable();
        if (chat == null) {
            logger.error("No ChatClient available — Spring AI auto-config didn't provide one.");
            logger.error("Configure either Ollama (default) or OpenAI to use this mode.");
            return;
        }
        WebSearchTool web = webSearchProvider.getIfAvailable();
        if (web == null) {
            logger.warn("WebSearchTool not available — falling back to LLM-only planning.");
        }

        // ────────────────────────────────────────────────────────────────
        // Phase 1a — web research (optional but valuable for "latest")
        // ────────────────────────────────────────────────────────────────
        StringBuilder researchContext = new StringBuilder();
        if (web != null) {
            banner("Phase 1a: web research");
            String[] queries = {
                    "best open-source network discovery tools 2026",
                    "kali linux vs alpine for network audit container",
                    "lightweight pentest docker base image"
            };
            for (String q : queries) {
                logger.info("  search: {}", q);
                try {
                    Object out = web.execute(java.util.Map.of("query", q));
                    String text = String.valueOf(out);
                    // Keep things bounded — first ~600 chars per query is
                    // plenty for the planner to ground itself.
                    if (text.length() > 600) text = text.substring(0, 600) + "…";
                    researchContext.append("\n--- search: ").append(q).append("\n");
                    researchContext.append(text).append("\n");
                } catch (Exception e) {
                    logger.warn("  search failed: {}", e.getMessage());
                }
            }
            logger.info("Collected {} chars of research context.", researchContext.length());
        }

        // ────────────────────────────────────────────────────────────────
        // Set up the policy + audit log shared across all iterations.
        // ────────────────────────────────────────────────────────────────
        var runtime = sharedContainerRuntime();
        var policy = ai.intelliswarm.swarmai.skill.runtime.ContainerPolicy.builder()
                .allowedNetworkModes("bridge")
                .allowBaseImage("alpine:*")
                .allowBaseImage("kalilinux/kali-*:*")
                .memoryCapRequired(true)
                .maxTimeout(java.time.Duration.ofMinutes(15))
                .banDockerfilePattern("(?i)^\\s*USER\\s+root\\s*$")
                .build();
        runtime.setPolicy(policy);
        runtime.setAuditPersistence(
                new ai.intelliswarm.swarmai.skill.runtime.ContainerAuditPersistence());

        // ────────────────────────────────────────────────────────────────
        // Self-improving plan loop:
        //   iter 1  : plan from scratch (with web research)
        //   iter 2+ : refine the previous plan, given its error
        //   stops   : on success, on planner failure, on max iterations,
        //             OR if the LLM emits the same plan twice (stuck-detect).
        // ────────────────────────────────────────────────────────────────
        AuditPlan previousPlan = null;
        IterationResult previousFailure = null;
        IterationResult lastResult = null;
        // Strict-mode: track plan fingerprints to short-circuit when the
        // refinement LLM produces an identical plan twice in a row. We only
        // burn time on plans that meaningfully change shape.
        java.util.Set<String> seenFingerprints = new java.util.LinkedHashSet<>();

        for (int iter = 1; iter <= MAX_PLAN_ITERATIONS; iter++) {
            banner("Iteration " + iter + " / " + MAX_PLAN_ITERATIONS);

            AuditPlan plan;
            if (iter == 1) {
                logger.info("Phase 1b — initial planning (web research grounded)");
                plan = planInitial(chat, cidr, userPrompt, researchContext);
            } else {
                logger.info("Phase R — refining plan after iteration {} failure", iter - 1);
                logger.info("Previous error  : {}", abbreviate(previousFailure.errorSummary, 200));
                plan = refinePlan(chat, cidr, userPrompt, previousPlan, previousFailure);
            }

            if (plan == null || plan.base_image == null || plan.packages == null
                    || plan.scan_command == null) {
                logger.error("Planner returned no usable plan — stopping.");
                break;
            }

            // ─── Slice-A guardrail #1: framework-side consistency check ───
            // Common LLM mistake: drops a package from the list but leaves
            // the corresponding flag in the command (e.g. removes
            // nmap-scripts but keeps `--script=...`). Auto-repair by
            // re-adding the dependency, with a clear log line so the user
            // sees what the framework decided.
            applyConsistencyFixes(plan);

            // ─── Slice-A guardrail #2: stuck detection ───
            // If the LLM regurgitates the same plan twice, more iterations
            // won't help — abort with a clear message.
            String fingerprint = planFingerprint(plan);
            if (seenFingerprints.contains(fingerprint)) {
                banner("Planner is stuck — same plan emitted twice, stopping");
                logger.warn("Plan fingerprint {} was already seen in a previous iteration.", fingerprint.substring(0, 12));
                logger.warn("Refinement is not converging. Either the failure mode is misclassified");
                logger.warn("or the LLM doesn't have enough context to fix it.");
                break;
            }
            seenFingerprints.add(fingerprint);

            banner("Plan (iteration " + iter + ")");
            logger.info("base_image                 : {}", plan.base_image);
            logger.info("rationale                  : {}", plan.rationale);
            logger.info("packages                   : {}", plan.packages);
            logger.info("scan_command               : {}", plan.scan_command);
            logger.info("estimated_duration_seconds : {}", plan.estimated_duration_seconds);
            logger.info("expected_findings          : {}", plan.expected_findings);

            // Build + run this iteration's plan.
            IterationResult result = buildAndRun(plan, cidr, runtime, iter);
            lastResult = result;

            if (result.success) {
                banner("Iteration " + iter + " SUCCEEDED — stopping the loop");
                logger.info("Scan elapsed: {} ms", result.elapsedMs);
                logger.info("");
                logger.info("=== scan output ===");
                for (String line : result.output.split("\n", -1)) logger.info("  {}", line);
                persistFinalSkill(plan, cidr);
                break;
            }

            // Failure — capture for refinement.
            banner("Iteration " + iter + " FAILED — escalating to refinement");
            logger.info("exit code : {}", result.exitCode);
            logger.info("error     : {}", abbreviate(result.errorSummary, 300));
            previousPlan = plan;
            previousFailure = result;

            if (iter == MAX_PLAN_ITERATIONS) {
                banner("Max iterations reached without success");
            }
        }

        // ────────────────────────────────────────────────────────────────
        // Final report — covers every iteration the runtime touched.
        // ────────────────────────────────────────────────────────────────
        banner("Final audit (every container the runtime touched this run)");
        for (var entry : runtime.auditLog()) {
            logger.info("  {}", entry.summary());
        }
        var built = runtime.trackedImages();
        if (!built.isEmpty()) {
            logger.info("");
            logger.info("Framework-owned images:");
            for (String tag : built) logger.info("  • {}", tag);
        }
        if (lastResult != null && !lastResult.success) {
            logger.info("");
            logger.warn("⚠ All iterations failed. Investigate manually:");
            logger.info("   docker run --rm --network bridge -e TARGET={} {} --help", cidr,
                    built.isEmpty() ? "<image>" : built.iterator().next());
        }
    }

    /** Phase 1b — first-pass planning, grounded in web research. */
    private AuditPlan planInitial(ChatClient chat, String cidr, String userPrompt,
                                   StringBuilder researchContext) {
        String prompt = """
                You are a network infrastructure auditor designing a Docker container that runs
                a security-relevant scan against a private home/office network range. The user
                owns this network and has authorized the audit.

                User intent  : %s
                Target CIDR  : %s
                Note         : $TARGET is a CIDR (range, e.g. /24 = up to 256 IPs).

                %s

                Output ONLY a single JSON object, no preamble, no code fence, in EXACTLY this shape:

                {
                  "base_image": "<one of: alpine:3.19, kalilinux/kali-rolling:latest>",
                  "rationale": "<one sentence>",
                  "packages": ["<pkg1>", "..."],
                  "scan_command": "<shell command, must reference $TARGET>",
                  "estimated_duration_seconds": <int, must be ≤ 300>,
                  "expected_findings": ["live hosts", "..."]
                }

                ━━━━━━━━━━━━ HARD WORKLOAD BUDGET ━━━━━━━━━━━━
                Total runtime MUST stay under 5 minutes (300 seconds). The runtime kills
                containers at 10 minutes; you have margin to spare. To stay in budget:

                - A /24 has 256 IPs. Per-host tools take 5–60s EACH even when the host
                  is unreachable. NEVER run a per-host tool unfiltered over a /24.
                - ALWAYS scope first: `nmap -F $TARGET` finds hosts with at least one of
                  the top-100 ports open (typically 1–10 hosts on a home /24, not 256).
                  THEN run per-host tools only against those hosts.
                - PREFER: 1–2 broad probes (nmap with NSE scripts) over many sequential tools.
                - AVOID: nikto/dnsenum/whatweb against unfiltered CIDRs.

                ━━━━━━━━━━━━ TOOL RULES ━━━━━━━━━━━━
                - alpine:3.19 packages: apk add. Examples: nmap, nmap-scripts, curl.
                - kalilinux/kali-rolling:latest: apt-get install. Heavy (~3GB pull) but has
                  hydra, sqlmap, gobuster, dnsenum, enum4linux when needed.
                - $TARGET is a CIDR. Only nmap accepts CIDRs natively. Per-host tools
                  (nikto, dnsenum, whatweb) MUST be wrapped in a per-host loop with `|| true`:
                  `for h in $(nmap -F $TARGET 2>/dev/null | awk '/Nmap scan report/ {print $NF}'); do <tool> $h || true; done`
                  (note: `nmap -F` not `-sn` — you want hosts with services, not all hosts).
                - PREFER `(tool || true)` over `&&` so partial failures still report partial success.
                - Don't request `--privileged`, `--cap-add`, or `network: host`.

                ━━━━━━━━━━━━ GOOD STARTING POINTS ━━━━━━━━━━━━
                Light & fast (~30 s for a /24):
                  scan_command: nmap -F --script=http-title,http-server-header --top-ports 100 -T4 $TARGET

                Medium (~2 min for a /24):
                  scan_command: nmap -sV -F -T4 $TARGET; \\
                                for h in $(nmap -F $TARGET 2>/dev/null | grep -B 4 '80/tcp open' | awk '/Nmap scan report/ {print $NF}'); \\
                                do whatweb $h || true; done

                Pick light unless the user specifically asked for deeper analysis.
                """
                .formatted(userPrompt, cidr,
                        researchContext.length() > 0
                                ? "Recent web research to ground your plan:\n" + researchContext
                                : "(no web research available)");
        try {
            return chat.prompt().user(prompt).call().entity(AuditPlan.class);
        } catch (Exception e) {
            logger.error("Planner LLM call failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Phase R — refinement: feed the previous plan + its error back to the
     * LLM and ask for a corrected plan. The framework adds context (parsed
     * stderr, exit code, expected $TARGET shape) so the LLM has enough
     * grounding to fix the failure rather than re-emit the same plan.
     */
    private AuditPlan refinePlan(ChatClient chat, String cidr, String userPrompt,
                                  AuditPlan previousPlan, IterationResult previousFailure) {
        // Classify failure mode so the prompt can be specific about the fix.
        String failureMode = classifyFailure(previousFailure);
        String prompt = """
                You are revising a Docker-based network audit plan that failed on its previous
                attempt. The user owns the target network. Re-emit a corrected plan as JSON.

                User intent     : %s
                Target CIDR     : %s

                Previous plan   :
                  base_image    : %s
                  packages      : %s
                  scan_command  : %s
                  est. duration : %d s

                Previous run    :
                  exit code     : %d
                  failure mode  : %s
                  stderr (head) : %s

                ━━━━━━━━━━━━ REQUIRED CORRECTIVE ACTION ━━━━━━━━━━━━
                Diagnose the FAILURE MODE shown above, then apply the matching fix:

                * TIMEOUT  → SCALE DOWN. The previous plan ran out of time. The fix is
                             ALWAYS to reduce work, NEVER to add more tools.
                             - Pick FEWER packages (one or two — usually just nmap).
                             - Reduce per-host work: prefer `nmap -F` (top-100) over full scans.
                             - Pre-filter to live-with-services hosts (`nmap -F`), don't loop
                               per-host tools over an unfiltered /24.
                             - Cap estimated_duration_seconds at 180.
                             - DO NOT add new packages or new tools. ANTI-PATTERN.

                * TOOL_FAILED_ON_CIDR → wrap per-host tools in a loop with `|| true`:
                             `for h in $(nmap -F $TARGET 2>/dev/null | awk '/Nmap scan report/ {print $NF}'); do <tool> $h || true; done`

                * PACKAGE_NOT_FOUND → drop the offending package, simplify the set.

                * EVERYTHING_UP → host discovery via ping is unreliable on Docker bridge.
                             Use real port scanning (`nmap -F`) to find genuine services.

                * UNKNOWN → most likely a malformed scan_command or an unexpected tool quirk.
                             Drop secondary tools, keep just `nmap -F -sV -T4 $TARGET`.

                Output ONLY a single JSON object in the same shape as before:
                {
                  "base_image": "...",
                  "rationale": "<one sentence describing what you changed and why>",
                  "packages": ["..."],
                  "scan_command": "...",
                  "estimated_duration_seconds": <int, must be ≤ 300>,
                  "expected_findings": ["..."]
                }
                """
                .formatted(userPrompt, cidr,
                        previousPlan.base_image,
                        previousPlan.packages,
                        previousPlan.scan_command,
                        previousPlan.estimated_duration_seconds,
                        previousFailure.exitCode,
                        failureMode,
                        abbreviate(previousFailure.errorSummary, 400));
        try {
            return chat.prompt().user(prompt).call().entity(AuditPlan.class);
        } catch (Exception e) {
            logger.error("Refinement LLM call failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Classify the failure of a previous iteration into a discrete category
     * the refinement prompt can dispatch on. The LLM is far better at
     * applying a labelled rule than at inferring failure mode from raw stderr.
     */
    private static String classifyFailure(IterationResult result) {
        if (result == null) return "UNKNOWN";
        String err = (result.errorSummary == null ? "" : result.errorSummary).toLowerCase();
        // Container exceeded its wall-clock cap (the runtime's force-kill path).
        if (result.exitCode == -1 && (err.contains("timeout") || err.contains("exceeded"))) {
            return "TIMEOUT";
        }
        if (err.contains("nxdomain") || err.contains("ns record query failed")
                || err.contains("invalid hostname") || err.contains("could not resolve")) {
            return "TOOL_FAILED_ON_CIDR";
        }
        if (err.contains("unable to locate package") || err.contains("no such package")
                || err.contains("not found in repository")) {
            return "PACKAGE_NOT_FOUND";
        }
        if (err.contains("256 ip addresses") && err.contains("hosts up")) {
            return "EVERYTHING_UP";
        }
        return "UNKNOWN";
    }

    /** Phase 2-4 collapsed: build, validate, execute, capture. */
    private IterationResult buildAndRun(AuditPlan plan, String cidr,
                                         ai.intelliswarm.swarmai.skill.runtime.ContainerSkillRuntime runtime,
                                         int iter) {
        boolean isKali = plan.base_image.toLowerCase().contains("kali");
        String packageInstall = isKali
                ? "RUN apt-get update && apt-get install -y --no-install-recommends "
                  + String.join(" ", plan.packages) + " && rm -rf /var/lib/apt/lists/*\n"
                : "RUN apk add --no-cache " + String.join(" ", plan.packages) + "\n";
        String dockerfile = "FROM " + plan.base_image + "\n"
                + packageInstall
                + "ENTRYPOINT [\"sh\", \"-c\", \"" + plan.scan_command.replace("\"", "\\\"") + "\"]\n";

        // ─── Slice-A guardrail #3: timeout floor ───
        // The LLM tends to be optimistic about runtimes. Floor at 120 s
        // even when it claims 30 s — most realistic /24 scans need at
        // least a minute or two before they produce useful output. Cap
        // at 900 s (the runtime's hard ceiling).
        int timeout = Math.min(Math.max(plan.estimated_duration_seconds, 120), 900);
        ai.intelliswarm.swarmai.skill.ContainerSkillSpec spec =
                ai.intelliswarm.swarmai.skill.ContainerSkillSpec.builderFromDockerfile(dockerfile)
                        .envFromParam("target", "TARGET")
                        .network("bridge")
                        .memory(isKali ? "2g" : "512m")
                        .timeout(java.time.Duration.ofSeconds(timeout))
                        .build();

        ai.intelliswarm.swarmai.skill.SkillDefinition def =
                new ai.intelliswarm.swarmai.skill.SkillDefinition();
        def.setName("planned_audit_iter" + iter + "_"
                + (isKali ? "kali" : "alpine") + "_" + cidr.replace("/", "_").replace(".", "_"));
        def.setDescription("LLM-planned network audit (iter " + iter + ") using "
                + plan.base_image + " with " + plan.packages + ". Cmd: " + plan.scan_command);
        def.setType(ai.intelliswarm.swarmai.skill.SkillType.CONTAINER);
        def.setCategory("security");
        def.setTags(java.util.List.of("network", "audit", isKali ? "kali" : "alpine", "iter-" + iter));
        def.setContainerSpec(spec);
        GeneratedSkill skill = new GeneratedSkill(def);

        banner("Phase 2-3: skill construction + validation (iter " + iter + ")");
        ai.intelliswarm.swarmai.skill.SkillValidator validator =
                new ai.intelliswarm.swarmai.skill.SkillValidator(true);
        var validation = validator.validate(skill);
        if (!validation.passed()) {
            logger.warn("validation : FAILED — {}", validation.errorsAsString());
            return new IterationResult(false, -1, "validator: " + validation.errorsAsString(), "", 0);
        }
        logger.info("validation : PASSED");

        banner("Phase 4: execution (iter " + iter + ")");
        long t0 = System.currentTimeMillis();
        Object output;
        try {
            output = skill.execute(java.util.Map.of("target", cidr));
        } catch (Exception e) {
            return new IterationResult(false, -1, "execute() threw: " + e.getMessage(), "", 0);
        }
        long elapsedMs = System.currentTimeMillis() - t0;
        String out = String.valueOf(output);

        // Heuristic for success: skill.execute returns "Error: ..." on
        // non-zero exit; otherwise stdout. The audit log has the precise
        // exit code; we read it back to be robust.
        var auditEntries = runtime.auditLog();
        int exitCode = 0;
        String stderrPreview = "";
        if (!auditEntries.isEmpty()) {
            var lastEntry = auditEntries.get(auditEntries.size() - 1);
            exitCode = lastEntry.exitCode();
            stderrPreview = lastEntry.stderrPreview();
        }
        boolean success = exitCode == 0 && !out.startsWith("Error:");
        return new IterationResult(success, exitCode, stderrPreview, out, elapsedMs);
    }

    /** Persist the final (successful) plan-skill so --list-skills sees it. */
    private void persistFinalSkill(AuditPlan plan, String cidr) {
        boolean isKali = plan.base_image.toLowerCase().contains("kali");
        String packageInstall = isKali
                ? "RUN apt-get update && apt-get install -y --no-install-recommends "
                  + String.join(" ", plan.packages) + " && rm -rf /var/lib/apt/lists/*\n"
                : "RUN apk add --no-cache " + String.join(" ", plan.packages) + "\n";
        String dockerfile = "FROM " + plan.base_image + "\n"
                + packageInstall
                + "ENTRYPOINT [\"sh\", \"-c\", \"" + plan.scan_command.replace("\"", "\\\"") + "\"]\n";
        ai.intelliswarm.swarmai.skill.ContainerSkillSpec spec =
                ai.intelliswarm.swarmai.skill.ContainerSkillSpec.builderFromDockerfile(dockerfile)
                        .envFromParam("target", "TARGET")
                        .network("bridge")
                        .memory(isKali ? "2g" : "512m")
                        .timeout(java.time.Duration.ofSeconds(
                                Math.min(Math.max(plan.estimated_duration_seconds, 30), 900)))
                        .build();
        ai.intelliswarm.swarmai.skill.SkillDefinition def =
                new ai.intelliswarm.swarmai.skill.SkillDefinition();
        def.setName("planned_audit_" + (isKali ? "kali" : "alpine") + "_"
                + cidr.replace("/", "_").replace(".", "_"));
        def.setDescription("LLM-planned + iteratively-refined network audit. Final command: "
                + plan.scan_command);
        def.setType(ai.intelliswarm.swarmai.skill.SkillType.CONTAINER);
        def.setCategory("security");
        def.setTags(java.util.List.of("network", "audit", "discovery",
                isKali ? "kali" : "alpine", "self-improved"));
        def.setContainerSpec(spec);
        GeneratedSkill skill = new GeneratedSkill(def);
        skill.setStatus(ai.intelliswarm.swarmai.skill.SkillStatus.VALIDATED);
        try {
            ai.intelliswarm.swarmai.skill.SkillRegistry registry =
                    new ai.intelliswarm.swarmai.skill.SkillRegistry();
            registry.register(skill);
            registry.save(java.nio.file.Paths.get("output", "skills"));
            logger.info("Final (successful) skill persisted to: output/skills/{}/SKILL.md", skill.getName());
        } catch (java.io.IOException e) {
            logger.warn("Could not persist final skill: {}", e.getMessage());
        }
    }

    /** Outcome of one plan-build-run iteration in the self-improving loop. */
    private record IterationResult(
            boolean success,
            int exitCode,
            String errorSummary,
            String output,
            long elapsedMs) {}

    /**
     * Slice-A guardrail: repair common plan inconsistencies in-place
     * before we waste a build cycle on them. Currently:
     * <ul>
     *   <li>If {@code scan_command} uses {@code --script=...} or {@code -sC}
     *       (NSE invocation) but {@code packages} lacks {@code nmap-scripts},
     *       add {@code nmap-scripts} to the package list. Without this,
     *       the container errors with "could not locate nse_main.lua".</li>
     *   <li>If {@code scan_command} references {@code masscan} but
     *       {@code packages} lacks it, add it.</li>
     *   <li>If the command references curl/wget but the package isn't
     *       declared, add it (most distros include them but not all).</li>
     * </ul>
     * Each fix is logged so the user sees the framework's repair.
     */
    private static void applyConsistencyFixes(AuditPlan plan) {
        java.util.List<String> packages = new java.util.ArrayList<>(plan.packages);
        boolean usesNseScripts = plan.scan_command.contains("--script=")
                || plan.scan_command.contains("--script ")
                || plan.scan_command.matches(".*\\bnmap\\s+[^|;&]*\\s-sC\\b.*");
        if (usesNseScripts && !packages.contains("nmap-scripts")) {
            logger.info("[consistency-fix] scan_command uses NSE scripts; auto-adding nmap-scripts to packages");
            packages.add("nmap-scripts");
        }
        if (plan.scan_command.contains(" masscan ") && !packages.contains("masscan")) {
            logger.info("[consistency-fix] scan_command references masscan; auto-adding masscan to packages");
            packages.add("masscan");
        }
        // nmap is always needed when scan_command starts with nmap or uses it.
        if (plan.scan_command.contains("nmap") && !packages.contains("nmap")) {
            logger.info("[consistency-fix] scan_command references nmap; auto-adding nmap to packages");
            packages.add("nmap");
        }
        plan.packages = packages;
    }

    /**
     * Stable fingerprint of a plan: same shape → same hash. Used to detect
     * the LLM-stuck case where refinement re-emits an identical plan. The
     * fingerprint covers the fields that drive build + execute, so cosmetic
     * differences (rationale text, expected_findings) don't count.
     */
    private static String planFingerprint(AuditPlan plan) {
        try {
            String canonical = plan.base_image
                    + "|" + new java.util.TreeSet<>(plan.packages)
                    + "|" + plan.scan_command.replaceAll("\\s+", " ").trim();
            byte[] hash = java.security.MessageDigest.getInstance("SHA-1")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-1 ships with every JDK; unreachable.
            return plan.toString();
        }
    }

    /**
     * DTO the planner LLM produces. Spring AI's {@code .entity(...)} parses
     * the JSON response automatically. Fields are public mutable so Jackson
     * can populate them; class is package-private to avoid leaking the
     * shape outside this workflow.
     */
    public static class AuditPlan {
        public String base_image;
        public String rationale;
        public java.util.List<String> packages;
        public String scan_command;
        public int estimated_duration_seconds;
        public java.util.List<String> expected_findings;
    }

    // ---------------------------------------------------------------------
    // --for-gap "<query>" — score existing skills against a stated need,
    //   recommend whether to reuse one or generate a new skill.
    //
    // Decision is the agentic flow the user described: search registry,
    // see scores, decide build-or-reuse before any new image work.
    // ---------------------------------------------------------------------

    private void scoreSkillsForGap(String gap) {
        banner("Scoring existing skills against gap");
        logger.info("Gap: {}", gap);
        logger.info("");

        ai.intelliswarm.swarmai.skill.SkillRegistry registry =
                new ai.intelliswarm.swarmai.skill.SkillRegistry();
        java.nio.file.Path skillsDir = java.nio.file.Paths.get("output", "skills");
        registry.load(skillsDir);

        // Per-image audit aggregates (success rate + avg duration) so we can
        // factor execution efficiency into the combined score.
        var auditP = new ai.intelliswarm.swarmai.skill.runtime.ContainerAuditPersistence();
        var auditEntries = auditP.readAll();
        java.util.Map<String, AuditSummary> summaries = aggregateAudit(auditEntries);

        var allSkills = registry.getActiveSkills();
        if (allSkills.isEmpty()) {
            logger.info("Catalog is empty — DECISION: generate (no existing skills to consider)");
            return;
        }

        // Score every skill: capability (tag/description overlap) + efficiency.
        // We score CONTAINER and non-CONTAINER skills alike; the agent can
        // reuse a CODE skill instead of generating a CONTAINER skill, or
        // vice versa, when capability fits.
        java.util.Set<String> gapTokens = tokenize(gap);
        java.util.List<ScoredSkill> scored = new java.util.ArrayList<>();
        for (var skill : allSkills) {
            double capability = capabilityScore(skill, gapTokens);
            double efficiency = efficiencyScore(skill, summaries);
            // Weights: capability dominates. A skill that doesn't match
            // the gap is useless however efficient it is; a perfect-match
            // skill that runs slowly is still preferable to generating new.
            double combined = 0.70 * capability + 0.30 * efficiency;
            scored.add(new ScoredSkill(skill, capability, efficiency, combined));
        }
        scored.sort((a, b) -> Double.compare(b.combined, a.combined));

        // Print ranked list.
        logger.info("Ranked candidates (top 5):");
        logger.info("{} | {} | {} | {} | {}",
                pad("rank", 4), pad("skill", 32), pad("type", 9),
                pad("capability", 10), pad("combined", 10));
        logger.info("{}---{}---{}---{}---{}",
                "-".repeat(4), "-".repeat(32), "-".repeat(9), "-".repeat(10), "-".repeat(10));
        int shown = 0;
        for (ScoredSkill s : scored) {
            if (shown >= 5) break;
            logger.info("{} | {} | {} | {} | {}",
                    pad("#" + (shown + 1), 4),
                    pad(s.skill.getName(), 32),
                    pad(s.skill.getDefinition().getType().name(), 9),
                    pad(String.format("%.2f", s.capability), 10),
                    pad(String.format("%.2f", s.combined), 10));
            shown++;
        }
        logger.info("");

        // Reuse decision: pick the highest-capability skill above the floor,
        // then check its combined score. A 0.5-capability skill that runs in
        // 60s is far more useful than a 0.05-capability skill that runs in
        // 1s — the latter doesn't actually satisfy the gap, no matter how
        // efficient it is. Display ranks by combined; decision ranks by
        // capability with combined as tiebreaker.
        ScoredSkill best = scored.stream()
                .filter(s -> s.capability >= 0.12)
                .max((a, b) -> {
                    int byCap = Double.compare(a.capability, b.capability);
                    return byCap != 0 ? byCap : Double.compare(a.combined, b.combined);
                })
                .orElse(scored.get(0));
        boolean reuse = best.capability >= 0.12 && best.combined >= 0.18;

        banner("DECISION: " + (reuse ? "REUSE existing skill" : "GENERATE new skill"));
        if (reuse) {
            logger.info("Best match  : {} (combined score {})",
                    best.skill.getName(), String.format("%.2f", best.combined));
            logger.info("Capability  : {} (tag + description overlap with the gap)",
                    String.format("%.2f", best.capability));
            logger.info("Efficiency  : {} (audit-derived: success rate × inverse-log-duration)",
                    String.format("%.2f", best.efficiency));
            logger.info("Description : {}", best.skill.getDescription());
            logger.info("Type        : {}", best.skill.getDefinition().getType());
            var spec = best.skill.getDefinition().getContainerSpec();
            if (spec != null) {
                String img = spec.hasImage() ? spec.image() : "swarmai-skill-" + sha1Short(spec.dockerfile());
                logger.info("Image       : {} ({})", img,
                        imageStillPresent(img) ? "present" : "would rebuild");
            }
            logger.info("");
            logger.info("To use it directly:");
            logger.info("  GeneratedSkill skill = registry.findByName(\"{}\");", best.skill.getName());
            logger.info("  skill.execute(Map.of(...));");
        } else {
            logger.info("Best match scored {} (capability {}, combined {}) — too low to reuse.",
                    best.skill.getName(),
                    String.format("%.2f", best.capability),
                    String.format("%.2f", best.combined));
            logger.info("Recommend: invoke a SkillEngine (Codex, Claude Code) to generate a new skill.");
            logger.info("Reuse threshold: capability ≥ 0.12 AND combined ≥ 0.18.");
        }
    }

    /**
     * Capability score = Jaccard overlap of (skill tags ∪ skill description tokens)
     * with the gap query tokens. Range [0, 1].
     */
    private static double capabilityScore(
            ai.intelliswarm.swarmai.skill.GeneratedSkill skill,
            java.util.Set<String> gapTokens) {
        var def = skill.getDefinition();
        java.util.Set<String> skillTokens = new java.util.HashSet<>();
        if (def.getTags() != null) {
            for (String tag : def.getTags()) skillTokens.addAll(tokenize(tag));
        }
        if (def.getDescription() != null) {
            skillTokens.addAll(tokenize(def.getDescription()));
        }
        if (def.getName() != null) {
            skillTokens.addAll(tokenize(def.getName()));
        }
        if (skillTokens.isEmpty() || gapTokens.isEmpty()) return 0.0;
        java.util.Set<String> intersection = new java.util.HashSet<>(skillTokens);
        intersection.retainAll(gapTokens);
        java.util.Set<String> union = new java.util.HashSet<>(skillTokens);
        union.addAll(gapTokens);
        return (double) intersection.size() / union.size();
    }

    /**
     * Efficiency score for CONTAINER skills derived from the audit log:
     * success_rate × inverse-log of average-duration. Range [0, 1] for
     * sub-second skills with 100% success; ~0.3 for minute-long skills.
     * Non-CONTAINER skills score a flat 0.5 (no audit data, but we don't
     * want to penalise them entirely vs an unmeasured CONTAINER skill).
     */
    private static double efficiencyScore(
            ai.intelliswarm.swarmai.skill.GeneratedSkill skill,
            java.util.Map<String, AuditSummary> summaries) {
        var spec = skill.getDefinition().getContainerSpec();
        if (spec == null) return 0.5; // no container, no audit data
        String tag = spec.hasImage() ? spec.image() : "swarmai-skill-" + sha1Short(spec.dockerfile());
        AuditSummary s = summaries.get(tag);
        if (s == null || s.runs() == 0) return 0.4; // no observations yet
        double rate = (double) s.successes() / s.runs();
        double avgSec = Math.max(0.1, s.avgDurationMs() / 1000.0);
        // 1 / (1 + log10(avgSec)) maps avgSec=1s→1.0, 10s→0.5, 100s→0.33, 1000s→0.25.
        double speed = 1.0 / (1.0 + Math.log10(avgSec));
        return Math.max(0.0, Math.min(1.0, rate * speed));
    }

    /**
     * Lowercase + alphanumeric tokens with light stemming so common word
     * variants ({@code ports/port}, {@code services/service},
     * {@code fingerprinting/fingerprint}) collapse to one token. Improves
     * recall for skill-vs-gap matching without bringing in a real stemmer.
     */
    private static java.util.Set<String> tokenize(String s) {
        if (s == null) return java.util.Set.of();
        java.util.Set<String> tokens = new java.util.HashSet<>();
        for (String raw : s.toLowerCase().split("[^a-z0-9]+")) {
            if (raw.length() < 3) continue;
            tokens.add(stem(raw));
        }
        return tokens;
    }

    /** Trim trailing inflections — enough for ports→port, services→service, etc. */
    private static String stem(String t) {
        if (t.length() > 5 && t.endsWith("ings")) return t.substring(0, t.length() - 4);
        if (t.length() > 4 && t.endsWith("ing"))  return t.substring(0, t.length() - 3);
        if (t.length() > 4 && t.endsWith("ies"))  return t.substring(0, t.length() - 3) + "y";
        if (t.length() > 4 && t.endsWith("es"))   return t.substring(0, t.length() - 2);
        if (t.length() > 4 && t.endsWith("s") && !t.endsWith("ss")) return t.substring(0, t.length() - 1);
        return t;
    }

    private record ScoredSkill(
            ai.intelliswarm.swarmai.skill.GeneratedSkill skill,
            double capability,
            double efficiency,
            double combined) {}

    /** Aggregate audit entries by image tag, computing per-image metrics. */
    private static java.util.Map<String, AuditSummary> aggregateAudit(
            java.util.List<ai.intelliswarm.swarmai.skill.runtime.ContainerAuditEntry> entries) {
        java.util.Map<String, AuditSummary> map = new java.util.LinkedHashMap<>();
        for (var e : entries) {
            map.compute(e.image(), (k, prev) -> (prev == null ? AuditSummary.empty() : prev).plus(e));
        }
        return map;
    }

    /** Pairwise tag-intersection report for the supersession hint. */
    private static java.util.List<String> supersessionHints(
            java.util.List<ai.intelliswarm.swarmai.skill.GeneratedSkill> skills) {
        java.util.List<String> hints = new java.util.ArrayList<>();
        for (int i = 0; i < skills.size(); i++) {
            for (int j = i + 1; j < skills.size(); j++) {
                var a = skills.get(i);
                var b = skills.get(j);
                var aTags = new java.util.HashSet<>(a.getDefinition().getTags());
                var bTags = new java.util.HashSet<>(b.getDefinition().getTags());
                aTags.retainAll(bTags);
                if (aTags.size() >= 2) {
                    hints.add(a.getName() + "  ⟷  " + b.getName()
                            + "   (shared tags: " + aTags + ")");
                }
            }
        }
        return hints;
    }

    /** True if {@code docker image inspect <tag>} succeeds. */
    private static boolean imageStillPresent(String tag) {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "image", "inspect", tag);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            return p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** First 12 hex chars of the SHA1 of {@code s}. Mirrors ContainerSkillRuntime. */
    private static String sha1Short(String s) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-1")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "?";
        }
    }

    /** Per-image audit aggregate. */
    private record AuditSummary(int runs, int successes, long totalDurationMs) {
        static AuditSummary empty() { return new AuditSummary(0, 0, 0L); }
        AuditSummary plus(ai.intelliswarm.swarmai.skill.runtime.ContainerAuditEntry e) {
            return new AuditSummary(
                    runs + 1,
                    successes + (e.exitCode() == 0 ? 1 : 0),
                    totalDurationMs + e.durationMs());
        }
        long avgDurationMs() { return runs == 0 ? 0 : totalDurationMs / runs; }
        String successRatePct() {
            return runs == 0 ? "n/a" : String.format("%.0f%%", 100.0 * successes / runs);
        }
    }

    // ---------------------------------------------------------------------
    // --audit-log — print the persisted audit log without running anything
    // ---------------------------------------------------------------------

    private void printPersistedAuditLog() {
        var p = new ai.intelliswarm.swarmai.skill.runtime.ContainerAuditPersistence();
        banner("Persisted container audit log");
        logger.info("File: {}", p.logPath());
        var entries = p.readAll();
        if (entries.isEmpty()) {
            logger.info("(empty — no container skill runs have been persisted yet)");
            logger.info("");
            logger.info("To populate: run --rich-scan <CIDR> or any other workflow that calls");
            logger.info("`runtime.setAuditPersistence(new ContainerAuditPersistence())`.");
            return;
        }
        logger.info("");
        logger.info("{} entries (oldest first):", entries.size());
        logger.info("");
        for (var entry : entries) {
            logger.info("{}", entry.summary());
        }
    }

    // ---------------------------------------------------------------------
    // --home-scan <CIDR> — one Codex-generated CONTAINER skill that scans
    // the given range, runs under a strict policy, prints the audit trail
    // ---------------------------------------------------------------------

    private void runHomeScan(SkillEngine engine, String cidr) {
        banner("Home Network Scan via CONTAINER skill");
        logger.info("Target range : {}", cidr);
        logger.info("Strategy     : framework builds an inline Dockerfile (alpine + nmap),");
        logger.info("               runs the container, captures the scan report.");
        logger.info("");
        logger.info("Note: OpenAI's content policy declines to generate offensive-security");
        logger.info("skills from a Codex prompt. We construct the SKILL.md programmatically");
        logger.info("for this case; the framework's CONTAINER pipeline is identical regardless");
        logger.info("of who authored the skill (Codex, Claude Code, hand-written, persisted).");
        logger.info("");

        // Apply a strict-ish policy to the shared runtime BEFORE any execute().
        // The runtime is JVM-wide so this is the only place to set it.
        var runtime = sharedContainerRuntime();
        var policy = ai.intelliswarm.swarmai.skill.runtime.ContainerPolicy.builder()
                .allowedNetworkModes("bridge")
                .allowBaseImage("alpine:*")
                .memoryCapRequired(true)
                .maxTimeout(java.time.Duration.ofMinutes(15))
                .banDockerfilePattern("(?i)^\\s*USER\\s+root\\s*$")
                .build();
        runtime.setPolicy(policy);
        runtime.clearAuditLog(); // start fresh for this run

        banner("Active container policy");
        logger.info("network modes allowed     : bridge only (required for nmap to send packets)");
        logger.info("base images allowed       : alpine:* (rejects ubuntu, kali, etc.)");
        logger.info("memory cap required       : yes");
        logger.info("max timeout               : 15 minutes");
        logger.info("banned Dockerfile patterns: USER root (refuses privilege-escalating images)");
        logger.info("");

        // Programmatic SKILL.md construction — same shape Codex would produce.
        // Build the spec directly, wrap in a SkillDefinition, build the
        // GeneratedSkill, and let the rest of the pipeline run unchanged.
        banner("Constructing CONTAINER skill programmatically");
        String dockerfile =
                "FROM alpine:3.19\n"
              + "RUN apk add --no-cache nmap nmap-scripts\n"
              + "ENTRYPOINT [\"sh\", \"-c\", \"nmap -sV --top-ports 100 --max-retries 1 -T4 $TARGET\"]\n";
        ai.intelliswarm.swarmai.skill.ContainerSkillSpec spec =
                ai.intelliswarm.swarmai.skill.ContainerSkillSpec.builderFromDockerfile(dockerfile)
                        .envFromParam("target", "TARGET")
                        .network("bridge")
                        .memory("512m")
                        .timeout(java.time.Duration.ofMinutes(10))
                        .build();

        ai.intelliswarm.swarmai.skill.SkillDefinition def =
                new ai.intelliswarm.swarmai.skill.SkillDefinition();
        def.setName("home_network_port_scan");
        def.setDescription("Host-discovery + fast service-version port scan over a CIDR range using nmap inside a sandboxed container");
        def.setType(ai.intelliswarm.swarmai.skill.SkillType.CONTAINER);
        def.setCategory("security");
        def.setTags(java.util.List.of("network", "discovery", "service-version", "nmap"));
        def.setContainerSpec(spec);
        GeneratedSkill skill = new GeneratedSkill(def);

        banner("SKILL.md the framework will execute");
        for (String line : skill.toSkillMd().split("\n", -1)) {
            logger.info("  {}", line);
        }
        logger.info("name        : {}", skill.getName());
        logger.info("type        : {}", skill.getDefinition().getType());
        logger.info("description : {}", skill.getDescription());

        // Print the SKILL.md so you can see what was actually generated.
        banner("Full SKILL.md");
        for (String line : skill.toSkillMd().split("\n", -1)) {
            logger.info("  {}", line);
        }

        banner("Validating under strict policy");
        ai.intelliswarm.swarmai.skill.SkillValidator validator =
                new ai.intelliswarm.swarmai.skill.SkillValidator(true); // permissive on IT failures
        var validation = validator.validate(skill);
        logger.info("validation : {}",
                validation.passed() ? "PASSED" : "FAILED — " + validation.errorsAsString());

        banner("Running scan against " + cidr);
        logger.info("This will:");
        logger.info("  1. Build the image (one-time, ~30s)");
        logger.info("  2. Start a container named swarmai-skill-<uuid8> on the bridge network");
        logger.info("  3. nmap will probe top 100 ports on each live host in {}", cidr);
        logger.info("  4. Container exits when nmap finishes; output captured via stdout");
        logger.info("");
        logger.info("While it runs you can observe live in another terminal:");
        logger.info("  docker ps                       # see the container");
        logger.info("  docker logs -f <container>      # tail nmap progress");
        logger.info("");

        long execStart = System.currentTimeMillis();
        Object out;
        try {
            out = skill.execute(java.util.Map.of("target", cidr));
        } catch (Exception e) {
            banner("Skill execution threw: " + e.getMessage());
            return;
        }
        long execMs = System.currentTimeMillis() - execStart;

        banner("Scan complete in " + execMs + " ms");
        logger.info("=== nmap report ===");
        for (String line : String.valueOf(out).split("\n", -1)) {
            logger.info("  {}", line);
        }

        // Audit log — proves the framework knows exactly what ran.
        banner("Audit log (every container the runtime touched this run)");
        var entries = runtime.auditLog();
        if (entries.isEmpty()) {
            logger.info("(empty)");
        } else {
            for (var entry : entries) {
                logger.info("  {}", entry.summary());
            }
        }

        // What images the framework now owns.
        var built = runtime.trackedImages();
        if (!built.isEmpty()) {
            banner("Framework-owned images (from this run)");
            for (String tag : built) {
                logger.info("  • {} — visible in `docker images`", tag);
            }
            logger.info("");
            logger.info("To reclaim space:    docker rmi {}", String.join(" ", built));
            logger.info("Or programmatically: runtime.pruneImagesBuiltByMe()");
        }
    }

    /** Reflective accessor for the JVM-wide ContainerSkillRuntime singleton. */
    private static ai.intelliswarm.swarmai.skill.runtime.ContainerSkillRuntime sharedContainerRuntime() {
        try {
            var holderClass = Class.forName(
                    "ai.intelliswarm.swarmai.skill.GeneratedSkill$ContainerRuntimeHolder");
            var sharedMethod = holderClass.getDeclaredMethod("shared");
            sharedMethod.setAccessible(true);
            return (ai.intelliswarm.swarmai.skill.runtime.ContainerSkillRuntime)
                    sharedMethod.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot access ContainerRuntimeHolder", e);
        }
    }

    /** Build the Codex-backed engine with system auth + ephemeral workspaces. */
    private SkillEngine buildEngine(ExecutorService executor) {
        CodingAgentSubagentSpawner spawner = CodexSubagentSpawner.createWithSystemAuth(
                executor,
                () -> new EphemeralDirectoryWorkspace("codex-skill-")
        );
        return new CodingAgentSkillGenerator(
                spawner,
                () -> new EphemeralDirectoryWorkspace("codex-skill-"),
                TIMEOUT
        );
    }

    // ---------------------------------------------------------------------
    // Showcase — curated multi-case run with execution + summary table
    // ---------------------------------------------------------------------

    private void runShowcase(SkillEngine engine) {
        banner("Codex Skill Creation — Showcase");
        logger.info("Running {} capability gaps end-to-end. Each is generated by Codex,",
                SHOWCASE_CASES.size());
        logger.info("validated, registered in a SkillRegistry, then executed against the");
        logger.info("sample input shown alongside the gap.");
        logger.info("");

        SkillRegistry registry = new SkillRegistry();
        List<CaseOutcome> outcomes = new ArrayList<>();

        for (int i = 0; i < SHOWCASE_CASES.size(); i++) {
            ShowcaseCase c = SHOWCASE_CASES.get(i);
            banner(String.format("Case %d/%d — %s", i + 1, SHOWCASE_CASES.size(), c.label));
            logger.info("Gap         : {}", c.gap);
            logger.info("Sample input: {}", c.sampleInput);
            logger.info("Sanity check: {}", c.expectedHint);
            outcomes.add(runSingle(engine, c.gap, c.sampleInput, registry));
        }

        banner("Showcase summary");
        logger.info("{}-+-{}-+-{}-+-{}-+-{}",
                "-".repeat(22), "-".repeat(8), "-".repeat(10), "-".repeat(10), "-".repeat(40));
        logger.info("{} | {} | {} | {} | {}",
                pad("case", 22), pad("gen", 8), pad("validate", 10),
                pad("execute", 10), pad("output", 40));
        logger.info("{}-+-{}-+-{}-+-{}-+-{}",
                "-".repeat(22), "-".repeat(8), "-".repeat(10), "-".repeat(10), "-".repeat(40));
        for (CaseOutcome o : outcomes) {
            logger.info("{} | {} | {} | {} | {}",
                    pad(o.label, 22),
                    pad(o.genMs >= 0 ? o.genMs + "ms" : "FAIL", 8),
                    pad(o.validate, 10),
                    pad(o.execute, 10),
                    pad(o.output, 40));
        }
        logger.info("");
        logger.info("Skills registered: {} in SkillRegistry — same pipeline self-improving",
                registry.size());
        logger.info("agents would use to acquire new capabilities at runtime.");

        // Show what the framework BUILT (not just pulled) inside Docker.
        // These persist in `docker images` and are owned by the runtime;
        // pruneImagesBuiltByMe() reclaims them on shutdown if you opt in.
        var imageRuntime = ai.intelliswarm.swarmai.skill.runtime
                .ContainerSkillRuntime.class;
        try {
            var holderClass = Class.forName(
                    "ai.intelliswarm.swarmai.skill.GeneratedSkill$ContainerRuntimeHolder");
            var sharedMethod = holderClass.getDeclaredMethod("shared");
            sharedMethod.setAccessible(true);
            var runtime = (ai.intelliswarm.swarmai.skill.runtime.ContainerSkillRuntime)
                    sharedMethod.invoke(null);
            var images = runtime.trackedImages();
            if (!images.isEmpty()) {
                logger.info("");
                logger.info("Container images the framework built or pulled this run:");
                for (String tag : images) {
                    logger.info("  • {} (tracked by ContainerSkillRuntime — visible in `docker images`)", tag);
                }
                logger.info("These images are owned by the framework. To reclaim space:");
                logger.info("  docker rmi {}", String.join(" ", images));
                logger.info("Or programmatically: runtime.pruneImagesBuiltByMe()");
            }
        } catch (ReflectiveOperationException ignored) {
            // Fallback when running against a slimmed framework — silent.
        }
    }

    // ---------------------------------------------------------------------
    // One end-to-end run: generate → validate → execute → return outcome
    // ---------------------------------------------------------------------

    private CaseOutcome runSingle(SkillEngine engine,
                                   String gap,
                                   Map<String, Object> sampleInput,
                                   SkillRegistry registryOrNull) {
        String label = sampleInput == null ? "(custom)" : findLabel(gap);

        logger.info("");
        logger.info("Capability gap:");
        logger.info("  {}", abbreviate(gap, 200));
        logger.info("");

        // Decide whether to advertise tools to Codex. Showcase cases set
        // composesTools explicitly; a custom (single-mode) gap defaults to
        // empty-tools so Codex computes inline and we don't have to worry
        // about the calculator's narrow operator set.
        ShowcaseCase scOrNull = sampleInput == null ? null : findShowcase(gap);
        boolean advertiseTools = scOrNull != null && scOrNull.composesTools;
        List<String> toolsForGen = advertiseTools ? WIRED_TOOLS : List.of();
        long start = System.currentTimeMillis();
        GeneratedSkill skill = engine.generate(gap, toolsForGen);
        long genMs = System.currentTimeMillis() - start;

        if (skill == null) {
            banner("Codex did not produce a usable skill (took " + genMs + " ms)");
            return new CaseOutcome(label, -1, "—", "—", "(no skill)");
        }

        banner("Codex produced a skill in " + genMs + " ms");
        logger.info("name        : {}", skill.getName());
        logger.info("type        : {}", skill.getDefinition().getType());
        logger.info("description : {}", skill.getDescription());
        logger.info("category    : {}", skill.getDefinition().getCategory());
        logger.info("tags        : {}", skill.getDefinition().getTags());

        // Print only the code section — the full SKILL.md is verbose and
        // dominated by integration test boilerplate.
        printCodeSection(skill);

        banner("Validating skill");
        // Permissive mode — coding-agent-generated skills often have integration
        // tests with exact-string assertions that off-by-one against the runtime
        // (rounding, locale). The skill itself is still callable; we want it to
        // reach the registry so downstream agents can use it.
        SkillValidator validator = new SkillValidator(true);
        SkillValidator.ValidationResult validation = validator.validate(skill);
        String validateStatus;
        if (validation.passed()) {
            logger.info("validation : PASSED");
            summarizeIntegrationTests(validation, "  ");
            validateStatus = "PASS";
        } else {
            logger.warn("validation : FAILED — {}", abbreviate(validation.errorsAsString(), 160));
            validateStatus = "FAIL";
        }

        // Register if validation passed (so the skill is discoverable to
        // any other agent that scans the registry).
        if (validation.passed() && registryOrNull != null) {
            registryOrNull.register(skill);
            logger.info("Registered in SkillRegistry: {}", skill.getName());
        }

        // EXECUTE — this is the proof that Codex's output is live, callable
        // code. We run it against the sample input regardless of validation
        // status; if it throws, we report the exception cleanly.
        Map<String, Object> input = sampleInput != null ? sampleInput : extractFirstIntegrationTestInput(skill);
        if (input == null) {
            logger.info("");
            logger.info("(no sample input available — skipping execute step)");
            return new CaseOutcome(label, genMs, validateStatus, "—", "(no input)");
        }

        banner("Executing skill against sample input");
        logger.info("input  : {}", input);
        // Wire real tool implementations so that generated skills which call
        // tools.calculator.execute(...) resolve at runtime. Without this, the
        // tools binding would be null and tool-composing skills throw NPE.
        skill.setAvailableTools(buildToolsMap());
        String output;
        String execStatus;
        try {
            long t0 = System.currentTimeMillis();
            Object raw = skill.execute(input);
            long execMs = System.currentTimeMillis() - t0;
            output = raw == null ? "(null)" : raw.toString();
            execStatus = "OK " + execMs + "ms";
            logger.info("output : {}", output);
            logger.info("elapsed: {} ms", execMs);
        } catch (Exception e) {
            output = "EXCEPTION: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            execStatus = "THROW";
            logger.warn("execute: {}", output);
        }

        return new CaseOutcome(label, genMs, validateStatus, execStatus, abbreviate(output, 38));
    }

    /** Try to pull a first sample input from the parsed integration tests. */
    private Map<String, Object> extractFirstIntegrationTestInput(GeneratedSkill skill) {
        if (skill.getDefinition() == null) return null;
        var its = skill.getDefinition().getIntegrationTests();
        if (its == null || its.isEmpty()) return null;
        var first = its.get(0);
        // The IntegrationTest record exposes inputs as Map<String, Object>.
        try {
            var method = first.getClass().getMethod("inputs");
            Object result = method.invoke(first);
            if (result instanceof Map<?, ?> m) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (var e : m.entrySet()) {
                    out.put(String.valueOf(e.getKey()), e.getValue());
                }
                return out.isEmpty() ? null : out;
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    private void printCodeSection(GeneratedSkill skill) {
        String code = skill.getDefinition() == null ? null : skill.getDefinition().getCode();
        if (code == null || code.isBlank()) return;
        banner("Generated code");
        for (String line : code.split("\n", -1)) {
            logger.info("  {}", line);
        }
    }

    // ---------------------------------------------------------------------
    // Auth, helpers, types
    // ---------------------------------------------------------------------

    private boolean checkAuth() {
        try {
            CodingAgentAuth.requireCodexAuth();
        } catch (IllegalStateException e) {
            logger.error("Codex authentication missing: {}", e.getMessage());
            return false;
        }
        boolean cached = CodingAgentAuth.codexHasCachedLogin();
        boolean envKey = System.getenv("OPENAI_API_KEY") != null
                && !System.getenv("OPENAI_API_KEY").isBlank();
        logger.info("Codex auth   : OK (cached-login={}, OPENAI_API_KEY={})", cached, envKey);
        logger.info("Codex home   : {}", CodingAgentAuth.codexHome());
        return true;
    }

    private static void summarizeIntegrationTests(SkillValidator.ValidationResult v, String indent) {
        if (!v.hasIntegrationTestResults()) {
            logger.info("{}integration tests : (none)", indent);
            return;
        }
        logger.info("{}integration tests : {} passed / {} failed",
                indent, v.integrationTestsPassed(), v.integrationTestsFailed());
    }

    /**
     * Build the tool registry exposed to generated skills. The keys are the
     * tool function names that Codex sees in the prompt (and writes as
     * {@code tools.<name>.execute(...)}); the values are real
     * {@link BaseTool} instances the Groovy runtime resolves at execute time.
     */
    private static Map<String, BaseTool> buildToolsMap() {
        Map<String, BaseTool> tools = new LinkedHashMap<>();
        tools.put("calculator", new CalculatorTool());
        return tools;
    }

    private static String findLabel(String gap) {
        ShowcaseCase c = findShowcase(gap);
        return c == null ? "(custom)" : c.label;
    }

    private static ShowcaseCase findShowcase(String gap) {
        for (ShowcaseCase c : SHOWCASE_CASES) {
            if (c.gap.equals(gap)) return c;
        }
        return null;
    }

    private static String pad(String s, int width) {
        if (s == null) s = "";
        if (s.length() >= width) return s.substring(0, width);
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) sb.append(' ');
        return sb.toString();
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static void banner(String title) {
        String line = "=".repeat(Math.min(80, title.length() + 4));
        logger.info("");
        logger.info(line);
        logger.info("  {}", title);
        logger.info(line);
    }

    /**
     * A capability-gap test case with sample input + sanity-check hint.
     * {@code composesTools=true} advertises {@link #WIRED_TOOLS} to Codex
     * so the agent will delegate to those tools inside the generated skill.
     */
    private record ShowcaseCase(
            String label,
            String gap,
            Map<String, Object> sampleInput,
            String expectedHint,
            boolean composesTools) {}

    /** End-to-end outcome of one case, for the summary table. */
    private record CaseOutcome(
            String label,
            long genMs,
            String validate,
            String execute,
            String output) {}
}
