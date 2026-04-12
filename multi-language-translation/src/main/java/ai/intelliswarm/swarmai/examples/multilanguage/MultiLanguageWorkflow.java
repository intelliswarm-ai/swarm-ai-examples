package ai.intelliswarm.swarmai.examples.multilanguage;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.examples.metrics.WorkflowMetricsCollector;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
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

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Multi-Language Agent Workflow
 *
 * Demonstrates parallel agents that research and write in different languages,
 * then a synthesizer combines their outputs into a cross-cultural analysis.
 *
 * Flow:
 * 1. PARALLEL: Three regional analysts each research the same topic, writing in
 *    their assigned language (English, Spanish, French) with a cultural lens
 * 2. SYNTHESIS: A Cross-Cultural Synthesizer agent takes all three outputs and
 *    produces a unified global analysis comparing perspectives across cultures
 *
 * This demonstrates:
 * - ProcessType.PARALLEL with dependsOn for fan-out / fan-in
 * - Using agent backstory to control output language (no language() on Agent)
 * - Cultural perspective diversity from the same underlying model
 * - Cross-cultural synthesis from multilingual inputs
 *
 * Usage: java -jar swarmai-framework.jar multi-language "artificial intelligence regulation"
 */
@Component
public class MultiLanguageWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(MultiLanguageWorkflow.class);

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
        logger.info("MULTI-LANGUAGE AGENT WORKFLOW");
        logger.info("=".repeat(80));
        logger.info("Topic:   {}", topic);
        logger.info("Process: PARALLEL (3 regional analysts) -> SYNTHESIS (cross-cultural)");
        logger.info("Languages: English, Spanish, French");
        logger.info("=".repeat(80));

        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("multi-language");
        metrics.start();

        ChatClient chatClient = chatClientBuilder.build();

        // =====================================================================
        // AGENTS
        // =====================================================================

        Agent englishAnalyst = Agent.builder()
                .role("Regional Analyst - English")
                .goal("Research the topic from an English-speaking, Anglo-American cultural " +
                      "perspective. Write your entire analysis in English. Focus on how the " +
                      "topic is discussed in the US, UK, and broader Anglophone world.")
                .backstory("You are a senior policy analyst based in Washington, D.C. with deep " +
                           "expertise in Anglo-American regulatory frameworks and public discourse. " +
                           "You always write in English. Your analysis reflects Western liberal " +
                           "democratic values, common-law traditions, and the role of private-sector " +
                           "innovation. You cite perspectives from US and UK institutions, think tanks, " +
                           "and media outlets.")
                .chatClient(chatClient)
                .maxTurns(1)
                .temperature(0.3)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .verbose(true)
                .build();

        Agent spanishAnalyst = Agent.builder()
                .role("Analista Regional - Espanol")
                .goal("Investigar el tema desde una perspectiva cultural hispanohablante. " +
                      "Escribe todo tu analisis en espanol. Enfocate en como el tema se " +
                      "discute en Espana, Mexico y America Latina.")
                .backstory("Eres un analista senior de politicas con sede en Madrid, con amplia " +
                           "experiencia en marcos regulatorios de la Union Europea y America Latina. " +
                           "Siempre escribes en espanol. Tu analisis refleja la perspectiva del mundo " +
                           "hispanohablante, incluyendo las tradiciones de derecho civil, el papel del " +
                           "estado en la regulacion, y las prioridades sociales de la region. Citas " +
                           "perspectivas de instituciones espanolas, latinoamericanas y de la UE.")
                .chatClient(chatClient)
                .maxTurns(1)
                .temperature(0.3)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .verbose(true)
                .build();

        Agent frenchAnalyst = Agent.builder()
                .role("Analyste Regional - Francais")
                .goal("Rechercher le sujet du point de vue culturel francophone. " +
                      "Redigez toute votre analyse en francais. Concentrez-vous sur la " +
                      "facon dont le sujet est discute en France, au Canada francophone " +
                      "et en Afrique francophone.")
                .backstory("Vous etes un analyste senior de politiques publiques base a Paris, " +
                           "avec une expertise approfondie dans les cadres reglementaires europeens " +
                           "et francophones. Vous ecrivez toujours en francais. Votre analyse reflete " +
                           "la tradition francaise de souverainete numerique, le role fort de l'Etat, " +
                           "et les valeurs de protection des droits fondamentaux. Vous citez des " +
                           "perspectives d'institutions francaises, europeennes et francophones.")
                .chatClient(chatClient)
                .maxTurns(1)
                .temperature(0.3)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .verbose(true)
                .build();

        Agent synthesizer = Agent.builder()
                .role("Cross-Cultural Synthesizer")
                .goal("Analyze all three regional reports (English, Spanish, French) and " +
                      "produce a unified cross-cultural analysis in English. Compare how " +
                      "different cultures view the topic, identify themes unique to each " +
                      "perspective, and provide a global synthesis.")
                .backstory("You are a senior director at a global think tank specializing in " +
                           "comparative policy analysis. You read English, Spanish, and French " +
                           "fluently. Your strength is identifying how cultural context shapes " +
                           "policy discourse. You write in English for a global audience. " +
                           "You never dismiss any regional perspective but instead highlight " +
                           "how each culture's values and institutions shape their approach. " +
                           "You are known for finding both common ground and meaningful " +
                           "divergences across cultures.")
                .chatClient(chatClient)
                .maxTurns(1)
                .temperature(0.4)
                .permissionMode(PermissionLevel.READ_ONLY)
                .toolHook(metrics.metricsHook())
                .verbose(true)
                .build();

        // =====================================================================
        // TASKS -- Parallel regional research + synthesis
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
                    "in English, Spanish, and French respectively.\n\n" +
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
                .dependsOn(englishTask)
                .dependsOn(spanishTask)
                .dependsOn(frenchTask)
                .outputFormat(OutputFormat.MARKDOWN)
                .outputFile("output/multi_language_synthesis.md")
                .maxExecutionTime(180000)
                .build();

        // =====================================================================
        // SWARM -- PARALLEL process
        // Layer 0 (parallel): englishTask + spanishTask + frenchTask
        // Layer 1 (sequential): synthesisTask (depends on all 3)
        // =====================================================================

        Swarm swarm = Swarm.builder()
                .id("multi-language-workflow")
                .agent(englishAnalyst)
                .agent(spanishAnalyst)
                .agent(frenchAnalyst)
                .agent(synthesizer)
                .task(englishTask)
                .task(spanishTask)
                .task(frenchTask)
                .task(synthesisTask)
                .process(ProcessType.PARALLEL)
                .verbose(true)
                .language("en")
                .eventPublisher(eventPublisher)
                .budgetTracker(metrics.getBudgetTracker())
                .budgetPolicy(metrics.getBudgetPolicy())
                .config("topic", topic)
                .build();

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("topic", topic);

        long startTime = System.currentTimeMillis();
        SwarmOutput result = swarm.kickoff(inputs);
        long duration = (System.currentTimeMillis() - startTime) / 1000;

        // =====================================================================
        // RESULTS
        // =====================================================================

        logger.info("\n" + "=".repeat(80));
        logger.info("MULTI-LANGUAGE WORKFLOW COMPLETE");
        logger.info("=".repeat(80));
        logger.info("Topic: {}", topic);
        logger.info("Duration: {} seconds", duration);
        logger.info("Tasks completed: {}", result.getTaskOutputs().size());

        for (TaskOutput taskOutput : result.getTaskOutputs()) {
            String raw = taskOutput.getRawOutput();
            int words = raw != null ? raw.split("\\s+").length : 0;
            logger.info("  Task output: {} words", words);
        }

        logger.info("\n{}", result.getTokenUsageSummary("gpt-4o-mini"));

        String synthesis = result.getTaskOutputs().stream()
                .map(TaskOutput::getRawOutput)
                .filter(Objects::nonNull)
                .reduce((a, b) -> b)
                .orElse("(no synthesis generated)");
        logger.info("\nCross-Cultural Synthesis:\n{}", synthesis);
        logger.info("=".repeat(80));

        if (judge != null && judge.isAvailable()) {
            judge.evaluate("multi-language", "Parallel multilingual analysis with cross-cultural synthesis", result.getFinalOutput(),
                result.isSuccessful(), System.currentTimeMillis() - startTime,
                4, 4, "PARALLEL", "multi-language-translation");
        }

        metrics.stop();
        metrics.report();
    }

    /** Run this example directly: right-click this class and Run in your IDE. */
    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
                args.length > 0 ? args : new String[]{"multi-language"});
    }
}
