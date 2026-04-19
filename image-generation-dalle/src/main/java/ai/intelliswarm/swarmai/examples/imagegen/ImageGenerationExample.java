package ai.intelliswarm.swarmai.examples.imagegen;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.tool.vision.ImageGenerationTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * ImageGenerationTool showcase — generate a blog cover image via OpenAI DALL-E 3, save it to
 * a timestamped PNG. No LLM agent wrapper — the tool output is a URL/file, not prose, so going
 * straight to the tool removes a failure mode.
 *
 * <p>Run: {@code ./run.sh image-gen "A minimalist illustration of a swarm of bees collaborating"}
 */
@Component
public class ImageGenerationExample {

    private static final Logger logger = LoggerFactory.getLogger(ImageGenerationExample.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    @SuppressWarnings("unused")
    private final ChatClient.Builder chatClientBuilder;
    @SuppressWarnings("unused")
    private final ApplicationEventPublisher eventPublisher;
    private final ImageGenerationTool imageTool;

    public ImageGenerationExample(ChatClient.Builder chatClientBuilder,
                                  ApplicationEventPublisher eventPublisher,
                                  ImageGenerationTool imageTool) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
        this.imageTool = imageTool;
    }

    public void run(String... args) {
        String prompt = args.length > 0 ? String.join(" ", args)
            : "A minimalist flat-color illustration of a stylised swarm of bees collaborating, " +
              "indigo and amber palette, clean lines, no text.";

        String smoke = imageTool.smokeTest();
        if (smoke != null) {
            logger.error("ImageGenerationTool unhealthy: {}", smoke);
            logger.error("Set OPENAI_API_KEY env var.");
            return;
        }

        String outputPath = "output/image-gen/swarmai-" + LocalDateTime.now().format(TS) + ".png";

        logger.info("Prompt: {}", prompt);
        logger.info("Will save to: {}", outputPath);

        Object result = imageTool.execute(Map.of(
            "prompt", prompt,
            "model", "dall-e-3",
            "size", "1024x1024",
            "quality", "standard",
            "response_format", "url",   // URL mode so we can see the signed URL in logs
            "save_to", outputPath));

        logger.info("");
        logger.info("=== ImageGenerationTool result ===");
        logger.info("{}", result);
    }

    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
            args.length > 0 ? prepend("image-gen", args) : new String[]{"image-gen"});
    }

    private static String[] prepend(String first, String[] rest) {
        String[] out = new String[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }
}
