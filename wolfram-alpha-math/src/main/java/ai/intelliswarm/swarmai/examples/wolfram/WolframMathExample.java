package ai.intelliswarm.swarmai.examples.wolfram;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.tool.research.WolframAlphaTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * WolframAlphaTool showcase — precise math / physics answers grounded in a computational engine.
 * Requires WOLFRAM_APPID (free at https://developer.wolframalpha.com/).
 *
 * <p>Run: {@code ./run.sh wolfram "integrate x^2 dx from 0 to 5"}
 */
@Component
public class WolframMathExample {

    private static final Logger logger = LoggerFactory.getLogger(WolframMathExample.class);

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final WolframAlphaTool wolframAlphaTool;

    public WolframMathExample(ChatClient.Builder chatClientBuilder,
                              ApplicationEventPublisher eventPublisher,
                              WolframAlphaTool wolframAlphaTool) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
        this.wolframAlphaTool = wolframAlphaTool;
    }

    public void run(String... args) {
        String question = args.length > 0 ? String.join(" ", args)
            : "What is the integral of x^3 from 0 to 5, and what is the mass of Jupiter in kilograms?";

        String smoke = wolframAlphaTool.smokeTest();
        if (smoke != null) {
            logger.error("WolframAlphaTool unhealthy: {}", smoke);
            logger.error("Set WOLFRAM_APPID env var (free key at https://developer.wolframalpha.com/).");
            return;
        }

        ChatClient chatClient = chatClientBuilder.build();

        Agent mathAgent = Agent.builder()
            .role("Quantitative Analyst")
            .goal("Answer math/physics questions using the wolfram_alpha tool — never invent numbers")
            .backstory("You are a rigorous analyst. For every quantitative question you invoke the " +
                       "wolfram_alpha tool. For symbolic math problems you use mode='full' to see the " +
                       "step-by-step pods; for unit conversions and single facts you use mode='short'.")
            .chatClient(chatClient)
            .tools(List.of(wolframAlphaTool))
            .maxTurns(5)
            .verbose(true)
            .build();

        Task task = Task.builder()
            .description("Answer this multi-part question using the wolfram_alpha tool for every " +
                         "quantitative claim: " + question + ". Quote the exact tool output for each " +
                         "number you report.")
            .expectedOutput("A structured answer with each numeric value attributed to a wolfram_alpha " +
                            "tool invocation.")
            .agent(mathAgent)
            .build();

        Swarm swarm = Swarm.builder()
            .agent(mathAgent)
            .task(task)
            .process(ProcessType.SEQUENTIAL)
            .verbose(true)
            .eventPublisher(eventPublisher)
            .build();

        SwarmOutput result = swarm.kickoff(Map.of("question", question));

        logger.info("");
        logger.info("=== WolframAlphaTool showcase result ===");
        logger.info("{}", result.getFinalOutput());
    }

    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
            args.length > 0 ? prepend("wolfram", args) : new String[]{"wolfram"});
    }

    private static String[] prepend(String first, String[] rest) {
        String[] out = new String[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }
}
