package com.example.swarmai;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.tool.common.CalculatorTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Map;

/**
 * The minimum viable SwarmAI application.
 *
 * One {@link Agent}, one {@link Task}, one {@link Swarm}. The agent solves a math word
 * problem by calling {@link CalculatorTool} — no network, no API keys, just to prove
 * the Agent → Tool → LLM loop works end-to-end.
 *
 * <p>Run: {@code mvn spring-boot:run}  (with Ollama on localhost:11434, default profile).
 *
 * <p>To use any other tool, change the {@code CalculatorTool} parameter below to something
 * from {@code ai.intelliswarm.swarmai.tool.*} (WikipediaTool, ArxivTool, JiraTool,
 * KafkaProducerTool, S3Tool, ImageGenerationTool, PineconeVectorTool, OpenApiToolkit,
 * SpringDataRepositoryTool …). The Task / Agent / Swarm wiring stays identical.
 *
 * <p>Check https://central.sonatype.com/namespace/ai.intelliswarm for the latest version
 * and bump {@code swarmai.version} in {@code pom.xml} as new releases land.
 */
@SpringBootApplication
public class QuickstartApplication {

    public static void main(String[] args) {
        SpringApplication.run(QuickstartApplication.class, args);
    }

    @Bean
    CommandLineRunner runDemo(ChatClient.Builder chatClientBuilder,
                              CalculatorTool calculatorTool) {
        return args -> {
            Agent analyst = Agent.builder()
                .role("Quantitative Analyst")
                .goal("Answer word problems with precise arithmetic.")
                .backstory("You always use the calculator tool for any arithmetic — " +
                           "never guess at numbers. Show the expression you evaluated.")
                .chatClient(chatClientBuilder.build())
                .tools(List.of(calculatorTool))
                .maxTurns(3)
                .build();

            Task problem = Task.builder()
                .description("A store sells coffee at $4.75 per bag. A customer buys 13 " +
                             "bags and uses an 18% loyalty discount. What is the final " +
                             "price they pay? Use the calculator tool; show the calculation.")
                .expectedOutput("The final price in dollars, with the expression you used.")
                .agent(analyst)
                .build();

            SwarmOutput result = Swarm.builder()
                .agent(analyst)
                .task(problem)
                .process(ProcessType.SEQUENTIAL)
                .build()
                .kickoff(Map.of());

            System.out.println("\n=== SwarmAI quickstart result ===\n");
            System.out.println(result.getFinalOutput());
            System.out.println("\nSuccessful: " + result.isSuccessful());
        };
    }
}
