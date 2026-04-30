package ai.intelliswarm.swarmai.examples.multilanguage;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.agent.streaming.AgentEvent;
import ai.intelliswarm.swarmai.examples.metrics.WorkflowMetricsCollector;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.intelliswarm.swarmai.judge.LLMJudge;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Multi-Language Agent Workflow (token-streaming variant).
 *
 * <p>Three regional analysts each translate / re-frame the topic in their own
 * language, then a Cross-Cultural Synthesizer merges the three perspectives,
 * and finally an Outlier Investigator appends specific edge cases. <b>Every
 * agent's output streams live to {@code System.out}</b> via
 * {@link Agent#executeTaskStreaming(Task, java.util.List)} — the demo value is
 * watching translations materialize one token at a time, language by language.
 *
 * <p>Why we orchestrate manually rather than using {@code Swarm.runStreaming}:
 * the original (non-streaming) workflow declared {@code dependsOn(...)} chains
 * (synthesis depends on the three languages, outlier depends on synthesis).
 * Phase-1 {@code Swarm.runStreaming(PARALLEL)} fans tasks out without honoring
 * {@code dependsOn}, so we'd lose the fan-in. The manual orchestration below
 * (run-stream-collect-feed-forward) is the pattern users will copy for any
 * dependent streaming workflow.
 *
 * <p>Console output uses simple ANSI color prefixes per role so the streamed
 * tokens of each agent stay visually distinct as they arrive.
 *
 * Usage: java -jar swarmai-framework.jar multi-language "artificial intelligence regulation"
 */
@Component
public class MultiLanguageWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(MultiLanguageWorkflow.class);
    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(5);

    // ANSI color codes per role — keep terminal output legible when multiple
    // agents stream their long-form prose. No-op on terminals that don't render.
    private static final String C_RESET    = "[0m";
    private static final String C_EN       = "[34m"; // blue
    private static final String C_ES       = "[32m"; // green
    private static final String C_FR       = "[35m"; // magenta
    private static final String C_SYNTH    = "[33m"; // yellow
    private static final String C_OUTLIER  = "[36m"; // cyan

    @org.springframework.beans.factory.annotation.Value("${swarmai.workflow.model:o3-mini}")
    private String workflowModel;

    @Autowired private LLMJudge judge;

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;

    public MultiLanguageWorkflow(ChatClient.Builder chatClientBuilder,
                                 ApplicationEventPublisher eventPublisher) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
    }

    public void run(String... args) {
        String topic = args.length > 0 ? String.join(" ", args)
                : "artificial intelligence regulation";

        logger.info("\n" + "=".repeat(80));
        logger.info("MULTI-LANGUAGE AGENT WORKFLOW (token streaming)");
        logger.info("=".repeat(80));
        logger.info("Topic:     {}", topic);
        logger.info("Pattern:   Stream EN -> ES -> FR -> Synthesizer -> Outlier");
        logger.info("Streaming: Agent.executeTaskStreaming with manual fan-in");
        logger.info("=".repeat(80));

        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("multi-language");
        metrics.start();

        ChatClient chatClient = chatClientBuilder.build();

        // =====================================================================
        // AGENTS
        // =====================================================================

        Agent englishAnalyst = buildAgent(chatClient, metrics,
                "Regional Analyst - English",
                "Research the topic from an English-speaking, Anglo-American cultural " +
                "perspective. Write your entire analysis in English. Focus on how the " +
                "topic is discussed in the US, UK, and broader Anglophone world.",
                "You are a senior policy analyst based in Washington, D.C. with deep " +
                "expertise in Anglo-American regulatory frameworks and public discourse. " +
                "You always write in English. Your analysis reflects Western liberal " +
                "democratic values, common-law traditions, and the role of private-sector " +
                "innovation. You cite perspectives from US and UK institutions, think tanks, " +
                "and media outlets.",
                0.3);

        Agent spanishAnalyst = buildAgent(chatClient, metrics,
                "Analista Regional - Espanol",
                "Investigar el tema desde una perspectiva cultural hispanohablante. " +
                "Escribe todo tu analisis en espanol. Enfocate en como el tema se " +
                "discute en Espana, Mexico y America Latina.",
                "Eres un analista senior de politicas con sede en Madrid, con amplia " +
                "experiencia en marcos regulatorios de la Union Europea y America Latina. " +
                "Siempre escribes en espanol. Tu analisis refleja la perspectiva del mundo " +
                "hispanohablante, incluyendo las tradiciones de derecho civil, el papel del " +
                "estado en la regulacion, y las prioridades sociales de la region. Citas " +
                "perspectivas de instituciones espanolas, latinoamericanas y de la UE.",
                0.3);

        Agent frenchAnalyst = buildAgent(chatClient, metrics,
                "Analyste Regional - Francais",
                "Rechercher le sujet du point de vue culturel francophone. " +
                "Redigez toute votre analyse en francais. Concentrez-vous sur la " +
                "facon dont le sujet est discute en France, au Canada francophone " +
                "et en Afrique francophone.",
                "Vous etes un analyste senior de politiques publiques base a Paris, " +
                "avec une expertise approfondie dans les cadres reglementaires europeens " +
                "et francophones. Vous ecrivez toujours en francais. Votre analyse reflete " +
                "la tradition francaise de souverainete numerique, le role fort de l'Etat, " +
                "et les valeurs de protection des droits fondamentaux. Vous citez des " +
                "perspectives d'institutions francaises, europeennes et francophones.",
                0.3);

        Agent synthesizer = buildAgent(chatClient, metrics,
                "Cross-Cultural Synthesizer",
                "Analyze all three regional reports (English, Spanish, French) and " +
                "produce a unified cross-cultural analysis in English. Compare how " +
                "different cultures view the topic, identify themes unique to each " +
                "perspective, and provide a global synthesis.",
                "You are a senior director at a global think tank specializing in " +
                "comparative policy analysis. You read English, Spanish, and French " +
                "fluently. Your strength is identifying how cultural context shapes " +
                "policy discourse. You write in English for a global audience. " +
                "You never dismiss any regional perspective but instead highlight " +
                "how each culture's values and institutions shape their approach. " +
                "You are known for finding both common ground and meaningful " +
                "divergences across cultures.",
                0.4);

        Agent outlierInvestigator = buildAgent(chatClient, metrics,
                "Outlier & Edge Case Investigator",
                "After the cross-cultural synthesis, drill into specific named examples, " +
                "country-level outliers, and cultural edge cases on the topic. " +
                "Produce a section titled 'Outliers and Specific Examples' that gets " +
                "appended to the final synthesis report.",
                "You are an investigative comparative-politics researcher who refuses " +
                "to accept broad generalizations. You hunt for specific regulatory " +
                "actions, named landmark cases, unusual countries within each cultural " +
                "bloc (e.g., Quebec inside the francophone world, Argentina inside the " +
                "hispanic world, Ireland inside the anglosphere), and cases where the " +
                "headline synthesis is misleading. You cite concrete names, dates, and " +
                "statistics, and never restate the main synthesis.",
                0.3);

        // =====================================================================
        // TASKS
        // =====================================================================

        Task englishTask = Task.builder()
                .description("Analyze the topic \"" + topic + "\" from an Anglo-American perspective.\n\n" +
                    "Write your ENTIRE analysis in English. Include:\n" +
                    "1. How this topic is framed in US/UK public discourse\n" +
                    "2. Key institutions and stakeholders shaping the debate\n" +
                    "3. Dominant policy positions and regulatory approaches\n" +
                    "4. Cultural values that influence the Anglo-American perspective\n" +
                    "5. Three key themes unique to the English-speaking world's view")
                .expectedOutput("A 300-500 word analysis in English covering the Anglo-American perspective")
                .agent(englishAnalyst)
                .outputFormat(OutputFormat.MARKDOWN)
                .maxExecutionTime(120000)
                .build();

        Task spanishTask = Task.builder()
                .description("Analiza el tema \"" + topic + "\" desde la perspectiva hispanohablante.\n\n" +
                    "Escribe TODO tu analisis en espanol. Incluye:\n" +
                    "1. Como se enmarca este tema en el discurso publico de Espana y America Latina\n" +
                    "2. Instituciones y actores clave que moldean el debate\n" +
                    "3. Posiciones politicas dominantes y enfoques regulatorios\n" +
                    "4. Valores culturales que influyen en la perspectiva hispanohablante\n" +
                    "5. Tres temas clave unicos de la vision del mundo hispanohablante")
                .expectedOutput("Un analisis de 300-500 palabras en espanol cubriendo la perspectiva hispanohablante")
                .agent(spanishAnalyst)
                .outputFormat(OutputFormat.MARKDOWN)
                .maxExecutionTime(120000)
                .build();

        Task frenchTask = Task.builder()
                .description("Analysez le sujet \"" + topic + "\" du point de vue francophone.\n\n" +
                    "Redigez TOUTE votre analyse en francais. Incluez:\n" +
                    "1. Comment ce sujet est cadre dans le discours public en France et dans la francophonie\n" +
                    "2. Institutions et acteurs cles qui faconnent le debat\n" +
                    "3. Positions politiques dominantes et approches reglementaires\n" +
                    "4. Valeurs culturelles qui influencent la perspective francophone\n" +
                    "5. Trois themes cles propres a la vision francophone du sujet")
                .expectedOutput("Une analyse de 300-500 mots en francais couvrant la perspective francophone")
                .agent(frenchAnalyst)
                .outputFormat(OutputFormat.MARKDOWN)
                .maxExecutionTime(120000)
                .build();

        Task synthesisTask = Task.builder()
                .description("You have received three regional analyses on \"" + topic + "\" written " +
                    "in English, Spanish, and French respectively (passed as context).\n\n" +
                    "Produce a cross-cultural synthesis report IN ENGLISH with these sections:\n\n" +
                    "1. **Executive Summary** - 2-3 sentences on how the topic is viewed globally\n" +
                    "2. **Comparative Analysis** - How each culture/region frames the topic differently, " +
                    "with specific examples from each regional report\n" +
                    "3. **Themes Unique to Each Perspective**:\n" +
                    "   - English-speaking world: 2-3 distinctive themes\n" +
                    "   - Spanish-speaking world: 2-3 distinctive themes\n" +
                    "   - French-speaking world: 2-3 distinctive themes\n" +
                    "4. **Common Ground** - Themes or concerns shared across all three perspectives\n" +
                    "5. **Unified Global Analysis** - A balanced synthesis that accounts for all " +
                    "cultural viewpoints, with recommendations for cross-cultural dialogue\n\n" +
                    "Reference specific points from each regional report. Do NOT dismiss any perspective.")
                .expectedOutput("A comprehensive cross-cultural synthesis report in English with all five sections")
                .agent(synthesizer)
                .outputFormat(OutputFormat.MARKDOWN)
                .outputFile("output/multi_language_synthesis.md")
                .maxExecutionTime(180000)
                .build();

        Task outlierTask = Task.builder()
                .description("The cross-cultural synthesizer has produced a unified global analysis on \"" + topic + "\" " +
                    "(passed as context together with the original three regional reports).\n\n" +
                    "Your job is to drill into specific examples and edge cases that the synthesis may " +
                    "have glossed over. Produce ONLY a new markdown section titled EXACTLY:\n\n" +
                    "## Outliers and Specific Examples\n\n" +
                    "Under this heading include:\n" +
                    "1. **Named Regulatory / Policy Examples** - 3-5 specific cases, laws, or programs by name\n" +
                    "2. **Country-Level Outliers** - Countries inside each cultural bloc that break the bloc's pattern " +
                    "(e.g., Quebec in francophonie, Argentina in hispanophonie, Ireland in the anglosphere)\n" +
                    "3. **Edge Cases** - Scenarios where multiple cultural perspectives conflict sharply\n" +
                    "4. **Counter-Narratives** - Cases where the synthesis' main thesis does NOT hold\n" +
                    "5. **Specific Statistics** - Concrete numbers (adoption rates, enforcement actions, polling) " +
                    "from specific sources when available\n\n" +
                    "Do NOT restate the synthesis. Do NOT list broad themes again. " +
                    "Output ONLY the new section with concrete, named examples.")
                .expectedOutput("A markdown section titled 'Outliers and Specific Examples' with named cases and statistics")
                .agent(outlierInvestigator)
                .outputFormat(OutputFormat.MARKDOWN)
                .outputFile("output/multi_language_outliers.md")
                .maxExecutionTime(180000)
                .build();

        // =====================================================================
        // EXECUTE — manual orchestration with streaming
        // =====================================================================

        long startTime = System.currentTimeMillis();
        logger.info("\nStarting analysis (each agent's tokens stream live below)...\n");

        // Stage 1: language analysts run in series so their console output stays
        // legible. Each one's deltas print live; we collect the TaskOutputs.
        TaskOutput enOut = streamAgent(englishAnalyst, englishTask, "EN", C_EN, List.of());
        TaskOutput esOut = streamAgent(spanishAnalyst, spanishTask, "ES", C_ES, List.of());
        TaskOutput frOut = streamAgent(frenchAnalyst,  frenchTask,  "FR", C_FR, List.of());

        List<TaskOutput> regional = new ArrayList<>(List.of(enOut, esOut, frOut));

        // Stage 2: synthesis sees all three regional analyses as context.
        TaskOutput synthOut = streamAgent(synthesizer, synthesisTask, "SYNTHESIS", C_SYNTH, regional);

        // Stage 3: outlier investigator sees regional + synthesis as context.
        List<TaskOutput> outlierContext = new ArrayList<>(regional);
        outlierContext.add(synthOut);
        TaskOutput outlierOut = streamAgent(outlierInvestigator, outlierTask, "OUTLIERS", C_OUTLIER, outlierContext);

        long duration = (System.currentTimeMillis() - startTime) / 1000;

        // =====================================================================
        // RESULTS
        // =====================================================================

        logger.info("\n" + "=".repeat(80));
        logger.info("MULTI-LANGUAGE WORKFLOW COMPLETE");
        logger.info("=".repeat(80));
        logger.info("Topic:           {}", topic);
        logger.info("Duration:        {} seconds", duration);
        logger.info("Tasks streamed:  5");

        String synthesis = synthOut != null ? synthOut.getRawOutput() : "";
        String outliers  = outlierOut != null ? outlierOut.getRawOutput() : "";
        String combinedFinalOutput = (synthesis == null ? "" : synthesis)
                + (outliers == null || outliers.isBlank() ? "" : "\n\n" + outliers);
        if (combinedFinalOutput.isBlank()) {
            combinedFinalOutput = "(no synthesis generated)";
        }

        logger.info("\nCross-Cultural Synthesis + Outliers:\n{}", combinedFinalOutput);
        logger.info("=".repeat(80));

        if (judge != null && judge.isAvailable()) {
            judge.evaluate("multi-language", "Parallel multilingual analysis with synthesis and outlier investigation",
                combinedFinalOutput, true, System.currentTimeMillis() - startTime,
                5, 5, "PARALLEL", "multi-language-translation");
        }

        metrics.stop();
        metrics.report();
    }

    // =========================================================================
    // STREAMING EXECUTION
    // =========================================================================

    /**
     * Run {@code task} on {@code agent} via {@link Agent#executeTaskStreaming},
     * print each {@link AgentEvent.TextDelta} live with a colored {@code label}
     * banner, and return the terminal {@link TaskOutput}. The caller can pass
     * the returned {@code TaskOutput} as context to a downstream streamed call.
     */
    private TaskOutput streamAgent(Agent agent, Task task, String label, String color,
                                   List<TaskOutput> context) {
        System.out.printf("%n%s>>> %s <<<%s%n%s",
                color, label, C_RESET, color);
        System.out.flush();

        StringBuilder accum = new StringBuilder();
        TaskOutput[] holder = new TaskOutput[1];

        agent.executeTaskStreaming(task, context)
                .doOnNext(evt -> {
                    if (evt instanceof AgentEvent.TextDelta d) {
                        System.out.print(d.text());
                        System.out.flush();
                        accum.append(d.text());
                    } else if (evt instanceof AgentEvent.AgentFinished f) {
                        holder[0] = f.taskOutput();
                    } else if (evt instanceof AgentEvent.AgentError e) {
                        System.out.print(C_RESET);
                        System.out.println();
                        logger.error("  [{}] Agent error: {} - {}",
                                label, e.exceptionType(), e.message());
                    }
                })
                .blockLast(STREAM_TIMEOUT);

        System.out.print(C_RESET);
        System.out.println();
        System.out.flush();

        // If for some reason AgentFinished never landed (shouldn't happen, but
        // defensive), build a TaskOutput from the accumulated text so context
        // chaining still works downstream.
        if (holder[0] != null) {
            return holder[0];
        }
        return TaskOutput.builder()
                .taskId(task.getId())
                .agentId(agent.getId())
                .rawOutput(accum.toString())
                .description(task.getDescription())
                .build();
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private Agent buildAgent(ChatClient chatClient, WorkflowMetricsCollector metrics,
                             String role, String goal, String backstory, double temp) {
        return Agent.builder()
                .role(role)
                .goal(goal)
                .backstory(backstory)
                .chatClient(chatClient)
                .maxTurns(1)
                .temperature(temp)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .verbose(false)
                .build();
    }

    /** Run this example directly: right-click this class and Run in your IDE. */
    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
                args.length > 0 ? args : new String[]{"multi-language"});
    }
}
