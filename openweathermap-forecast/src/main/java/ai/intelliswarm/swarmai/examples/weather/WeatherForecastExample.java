package ai.intelliswarm.swarmai.examples.weather;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.tool.data.OpenWeatherMapTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * OpenWeatherMapTool showcase — a travel-planner agent answers "should I pack an umbrella?"
 * by pulling current conditions + 5-day forecast for a city.
 *
 * <p>Run: {@code ./run.sh weather "Zurich"}
 */
@Component
public class WeatherForecastExample {

    private static final Logger logger = LoggerFactory.getLogger(WeatherForecastExample.class);

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final OpenWeatherMapTool weatherTool;

    public WeatherForecastExample(ChatClient.Builder chatClientBuilder,
                                  ApplicationEventPublisher eventPublisher,
                                  OpenWeatherMapTool weatherTool) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
        this.weatherTool = weatherTool;
    }

    public void run(String... args) {
        String city = args.length > 0 ? String.join(" ", args) : "Zurich,CH";

        String smoke = weatherTool.smokeTest();
        if (smoke != null) {
            logger.error("OpenWeatherMapTool unhealthy: {}", smoke);
            logger.error("Set OPENWEATHER_API_KEY (free key: https://openweathermap.org/api).");
            return;
        }

        ChatClient chatClient = chatClientBuilder.build();

        Agent plannerAgent = Agent.builder()
            .role("Travel Planner")
            .goal("Give concrete packing advice for a 5-day trip to " + city)
            .backstory("You are a practical travel planner. For every weather claim you invoke the " +
                       "weather tool with operation='current' and operation='forecast'. You translate " +
                       "temps/rain into specific packing advice (umbrella? layers? swimsuit?).")
            .chatClient(chatClient)
            .tools(List.of(weatherTool))
            .maxTurns(5)
            .verbose(true)
            .build();

        Task task = Task.builder()
            .description("A traveller is heading to " + city + " for 5 days starting tomorrow. Using " +
                         "the weather tool: (1) summarise the current conditions, (2) pull the 5-day " +
                         "forecast, (3) give specific, concrete packing advice based on the actual " +
                         "numbers. Cite the tool's output where you justify each piece of advice.")
            .expectedOutput("Current conditions + 5-day forecast + specific packing advice grounded " +
                            "in the forecast numbers.")
            .agent(plannerAgent)
            .build();

        Swarm swarm = Swarm.builder()
            .agent(plannerAgent)
            .task(task)
            .process(ProcessType.SEQUENTIAL)
            .verbose(true)
            .eventPublisher(eventPublisher)
            .build();

        SwarmOutput result = swarm.kickoff(Map.of("city", city));

        logger.info("");
        logger.info("=== OpenWeatherMapTool showcase result ===");
        logger.info("{}", result.getFinalOutput());
    }

    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
            args.length > 0 ? prepend("weather", args) : new String[]{"weather"});
    }

    private static String[] prepend(String first, String[] rest) {
        String[] out = new String[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }
}
