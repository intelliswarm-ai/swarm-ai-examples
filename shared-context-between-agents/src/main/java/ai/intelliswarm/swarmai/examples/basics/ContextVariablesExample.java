package ai.intelliswarm.swarmai.examples.basics;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.examples.metrics.WorkflowMetricsCollector;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import ai.intelliswarm.swarmai.judge.LLMJudge;

import java.util.HashMap;
import java.util.Map;

/**
 * Shows how to pass context/state between agents via the inputs map while
 * keeping agent responsibilities genuinely distinct.
 *
 * THREE agents in a SEQUENTIAL pipeline with separated concerns:
 *   - Factual Reporter (WHAT)     -- describes the subject
 *   - Causal Analyst (WHY)        -- explains drivers and motivations
 *   - Strategic Advisor (SO WHAT) -- prescribes implications and next steps
 *
 * The inputs map carries shared context (topic, audience, tone, wordCount)
 * that each task description interpolates using String.format.
 */
@Component
public class ContextVariablesExample {

    private static final Logger logger = LoggerFactory.getLogger(ContextVariablesExample.class);
    @Autowired private LLMJudge judge;

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;

    public ContextVariablesExample(ChatClient.Builder chatClientBuilder,
                                   ApplicationEventPublisher eventPublisher) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
    }

    public void run(String... args) throws Exception {
        long startMs = System.currentTimeMillis();
        ChatClient chatClient = chatClientBuilder.build();
        String topic = args.length > 0 ? String.join(" ", args) : "microservices architecture";

        // Context variables -- shared state for the entire pipeline
        String audience = "senior software engineers";
        String tone = "professional yet approachable";
        String wordCount = "500";

        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("context-variables");
        metrics.start();

        // Agent 1: WHAT -- Factual Reporter (describes the mechanics)
        Agent whatReporter = Agent.builder()
                .role("Factual Reporter (WHAT)")
                .goal("Describe WHAT '" + topic + "' IS: its definition, components, and " +
                      "concrete mechanics. Stay strictly descriptive -- do not explain causes " +
                      "or implications. Your scope is 'the facts on the ground'.")
                .backstory("You are a technical encyclopedist. You answer 'what is it?' with " +
                           "precise, neutral descriptions. You never speculate on motivation " +
                           "or consequences -- those are handled by other specialists.")
                .chatClient(chatClient)
                .verbose(true)
                .build();

        // Agent 2: WHY -- Causal Analyst (explains motivations and forces)
        Agent whyAnalyst = Agent.builder()
                .role("Causal Analyst (WHY)")
                .goal("Explain WHY '" + topic + "' exists and is adopted: driving forces, " +
                      "historical pressures, and the problems it solves. Your scope is " +
                      "causes and motivations -- never restate mechanics or prescribe actions.")
                .backstory("You are a domain historian and systems thinker. You connect " +
                           "technical phenomena to the economic, organizational, and " +
                           "engineering forces that created them. You strictly avoid " +
                           "re-describing the subject or giving recommendations.")
                .chatClient(chatClient)
                .verbose(true)
                .build();

        // Agent 3: SO WHAT -- Strategic Advisor (distills implications and actions)
        Agent soWhatAdvisor = Agent.builder()
                .role("Strategic Advisor (SO WHAT)")
                .goal("Distill SO WHAT the audience should do about '" + topic + "': " +
                      "implications, trade-offs, and concrete next steps. Your scope is " +
                      "prescriptive guidance -- do not redescribe the subject or rehash causes.")
                .backstory("You are an engineering leader who writes decision briefs. You " +
                           "convert factual context and causal analysis into actionable " +
                           "recommendations. You rely on the WHAT and WHY agents for " +
                           "grounding and never duplicate their content.")
                .chatClient(chatClient)
                .verbose(true)
                .build();

        // Distinct task descriptions -- separation of concerns is explicit
        Task whatTask = Task.builder()
                .description(String.format(
                        "WHAT-layer task. For audience '%s' in a '%s' tone (~%s words):\n\n"
                      + "Describe WHAT '%s' is. Cover: definition, core components, "
                      + "how the pieces fit together, and concrete examples. "
                      + "STRICT SCOPE: facts only. Do NOT explain why it matters or "
                      + "what to do about it -- downstream agents cover those.",
                        audience, tone, wordCount, topic))
                .expectedOutput("A purely descriptive brief covering definition, components, and examples")
                .agent(whatReporter)
                .build();

        Task whyTask = Task.builder()
                .description(String.format(
                        "WHY-layer task. Building on the factual brief from the WHAT agent, "
                      + "explain WHY '%s' emerged and WHY organizations adopt it. For "
                      + "audience '%s', tone '%s' (~%s words):\n\n"
                      + "Cover: historical drivers, problems solved, economic and "
                      + "engineering forces, and competing alternatives that were "
                      + "rejected. STRICT SCOPE: causes and motivations only. Do NOT "
                      + "redescribe what it is (the WHAT agent handled that) and do NOT "
                      + "give recommendations (the SO-WHAT agent handles those).",
                        topic, audience, tone, wordCount))
                .expectedOutput("A causal analysis focused exclusively on drivers and motivations")
                .agent(whyAnalyst)
                .dependsOn(whatTask)
                .build();

        Task soWhatTask = Task.builder()
                .description(String.format(
                        "SO-WHAT-layer task. Building on the WHAT brief and WHY analysis, "
                      + "produce a decision brief on '%s' for audience '%s' in a '%s' "
                      + "tone (~%s words):\n\n"
                      + "Cover: (a) implications for the audience, (b) trade-offs and "
                      + "failure modes, (c) 3-5 concrete next steps, and (d) signals to "
                      + "watch. STRICT SCOPE: prescriptive guidance only. Do NOT redefine "
                      + "the topic or re-explain its origins -- reference the upstream "
                      + "agents' work instead.",
                        topic, audience, tone, wordCount))
                .expectedOutput("A prescriptive decision brief with implications, trade-offs, and next steps")
                .agent(soWhatAdvisor)
                .dependsOn(whyTask)
                .build();

        Swarm swarm = Swarm.builder()
                .agent(whatReporter)
                .agent(whyAnalyst)
                .agent(soWhatAdvisor)
                .task(whatTask)
                .task(whyTask)
                .task(soWhatTask)
                .process(ProcessType.SEQUENTIAL)
                .verbose(true)
                .eventPublisher(eventPublisher)
                .budgetTracker(metrics.getBudgetTracker())
                .budgetPolicy(metrics.getBudgetPolicy())
                .build();

        // The inputs map passes all context variables into the swarm
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("topic", topic);
        inputs.put("audience", audience);
        inputs.put("tone", tone);
        inputs.put("wordCount", wordCount);

        SwarmOutput result = swarm.kickoff(inputs);

        logger.info("\n=== Result ===");
        logger.info("{}", result.getFinalOutput());
        logger.info("\n=== Pipeline Stats ===");
        logger.info("Tasks completed: {}", result.getTaskOutputs().size());
        logger.info("Success rate: {}%", (int) (result.getSuccessRate() * 100));

        if (judge != null && judge.isAvailable()) {
            judge.evaluate("context-variables",
                "Three agents with distinct WHAT/WHY/SO-WHAT responsibilities sharing context via inputs map",
                result.getFinalOutput(),
                result.isSuccessful(), System.currentTimeMillis() - startMs,
                3, 3, "SEQUENTIAL", "shared-context-between-agents");
        }

        metrics.stop();
        metrics.report();
    }

    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
                args.length > 0 ? args : new String[]{"context-variables"});
    }
}
