package ai.intelliswarm.swarmai.examples.basics;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.agent.streaming.AgentEvent;
import ai.intelliswarm.swarmai.examples.metrics.WorkflowMetricsCollector;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import ai.intelliswarm.swarmai.judge.LLMJudge;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Three SEQUENTIAL agents (WHAT / WHY / SO-WHAT) that share context via the
 * inputs map, now with token-level streaming. Each agent's reply renders live
 * with a distinct color, and {@link Swarm#runStreaming} threads the previous
 * task's {@link TaskOutput} as context to the next agent automatically.
 *
 * <p>This example is a textbook Phase-1 streaming fit: SEQUENTIAL process,
 * three single-turn agents, zero tools.
 */
@Component
public class ContextVariablesExample {

    private static final Logger logger = LoggerFactory.getLogger(ContextVariablesExample.class);
    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(5);

    private static final String C_RESET = "[0m";
    private static final String C_WHAT  = "[36m"; // cyan
    private static final String C_WHY   = "[33m"; // yellow
    private static final String C_SO    = "[32m"; // green

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

        String audience = "senior software engineers";
        String tone = "professional yet approachable";
        String wordCount = "500";

        WorkflowMetricsCollector metrics = new WorkflowMetricsCollector("context-variables");
        metrics.start();

        Agent whatReporter = Agent.builder()
                .role("Factual Reporter (WHAT)")
                .goal("Describe WHAT '" + topic + "' IS: its definition, components, and " +
                      "concrete mechanics. Stay strictly descriptive -- do not explain causes " +
                      "or implications. Your scope is 'the facts on the ground'.")
                .backstory("You are a technical encyclopedist. You answer 'what is it?' with " +
                           "precise, neutral descriptions. You never speculate on motivation " +
                           "or consequences -- those are handled by other specialists.")
                .chatClient(chatClient)
                .verbose(false)
                .build();

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
                .verbose(false)
                .build();

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
                .verbose(false)
                .build();

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
                        "WHY-layer task. Building on the factual brief from the WHAT agent "
                      + "(passed as context), explain WHY '%s' emerged and WHY organizations "
                      + "adopt it. For audience '%s', tone '%s' (~%s words):\n\n"
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
                        "SO-WHAT-layer task. Building on the WHAT brief and WHY analysis "
                      + "(both passed as context), produce a decision brief on '%s' for "
                      + "audience '%s' in a '%s' tone (~%s words):\n\n"
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
                .verbose(false)
                .eventPublisher(eventPublisher)
                .budgetTracker(metrics.getBudgetTracker())
                .budgetPolicy(metrics.getBudgetPolicy())
                .build();

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("topic", topic);
        inputs.put("audience", audience);
        inputs.put("tone", tone);
        inputs.put("wordCount", wordCount);

        // Demux merged stream by agentId so per-agent rendering stays distinct
        // (SEQUENTIAL doesn't actually interleave, but this is the pattern that
        // would survive a switch to PARALLEL too).
        Map<String, AgentRender> renders = new LinkedHashMap<>();
        renders.put(whatReporter.getId(),  new AgentRender("WHAT",     C_WHAT));
        renders.put(whyAnalyst.getId(),    new AgentRender("WHY",      C_WHY));
        renders.put(soWhatAdvisor.getId(), new AgentRender("SO WHAT",  C_SO));

        logger.info("\n=== Streaming WHAT -> WHY -> SO WHAT ===\n");

        swarm.runStreaming(inputs)
                .doOnNext(evt -> renderEvent(renders, evt))
                .blockLast(STREAM_TIMEOUT);

        // The final brief is the SO-WHAT agent's output.
        AgentRender finalRender = renders.get(soWhatAdvisor.getId());
        String finalOutput = finalRender.taskOutput != null
                ? finalRender.taskOutput.getRawOutput()
                : finalRender.accum.toString();

        int taskCount = (int) renders.values().stream().filter(r -> r.taskOutput != null).count();

        logger.info("\n=== Result ===");
        logger.info("{}", finalOutput);
        logger.info("\n=== Pipeline Stats ===");
        logger.info("Tasks completed: {}", taskCount);

        if (judge != null && judge.isAvailable()) {
            judge.evaluate("context-variables",
                "Three agents with distinct WHAT/WHY/SO-WHAT responsibilities sharing context via inputs map (streaming)",
                finalOutput,
                finalOutput != null && !finalOutput.isBlank(), System.currentTimeMillis() - startMs,
                3, 3, "SEQUENTIAL", "shared-context-between-agents");
        }

        metrics.stop();
        metrics.report();
    }

    private static void renderEvent(Map<String, AgentRender> renders, AgentEvent evt) {
        AgentRender r = renders.get(evt.agentId());
        if (r == null) return;
        if (evt instanceof AgentEvent.AgentStarted) {
            System.out.printf("%n%s>>> %s <<<%s%n%s", r.color, r.label, C_RESET, r.color);
            System.out.flush();
        } else if (evt instanceof AgentEvent.TextDelta d) {
            System.out.print(d.text());
            System.out.flush();
            r.accum.append(d.text());
        } else if (evt instanceof AgentEvent.AgentFinished f) {
            System.out.print(C_RESET);
            System.out.println();
            System.out.flush();
            if (f.taskOutput() != null) r.taskOutput = f.taskOutput();
        } else if (evt instanceof AgentEvent.AgentError e) {
            System.out.print(C_RESET);
            System.out.println();
            logger.error("[{}] error: {} - {}", r.label, e.exceptionType(), e.message());
        }
    }

    private static final class AgentRender {
        final String label;
        final String color;
        final StringBuilder accum = new StringBuilder();
        TaskOutput taskOutput;
        AgentRender(String label, String color) { this.label = label; this.color = color; }
    }

    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
                args.length > 0 ? args : new String[]{"context-variables"});
    }
}
