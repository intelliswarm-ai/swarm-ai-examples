package ai.intelliswarm.swarmai.examples.ocr;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.tool.vision.OcrTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * OcrTool showcase — renders a known text string into a synthetic PNG (so no external
 * assets are needed), then runs OCR against it and prints the extracted text.
 *
 * <p>Run: {@code ./run.sh ocr [text-to-render]}
 */
@Component
public class OcrExtractionExample {

    private static final Logger logger = LoggerFactory.getLogger(OcrExtractionExample.class);

    @SuppressWarnings("unused")
    private final ChatClient.Builder chatClientBuilder;
    @SuppressWarnings("unused")
    private final ApplicationEventPublisher eventPublisher;
    private final OcrTool ocrTool;

    public OcrExtractionExample(ChatClient.Builder chatClientBuilder,
                                ApplicationEventPublisher eventPublisher,
                                OcrTool ocrTool) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
        this.ocrTool = ocrTool;
    }

    public void run(String... args) throws IOException {
        String text = args.length > 0 ? String.join(" ", args)
            : "Invoice #1042 — SwarmAI Corp — Total: $980.00";

        String smoke = ocrTool.smokeTest();
        if (smoke != null) {
            logger.error("OcrTool unhealthy: {}", smoke);
            logger.error("Install Tesseract on this host. See README.md for distro-specific commands.");
            return;
        }

        // 1. Render the string into a PNG we can hand to the OCR tool.
        Path image = Files.createTempFile("ocr-example-", ".png");
        renderPng(image, text);
        logger.info("Rendered synthetic image: {}", image);

        // 2. Run OCR via path.
        logger.info("\n--- OCR via file path ---");
        Object byPath = ocrTool.execute(Map.of(
            "path", image.toString(),
            "language", "eng"));
        logger.info("{}", byPath);

        // 3. Run OCR via base64 to exercise the inline-bytes path too.
        byte[] bytes = Files.readAllBytes(image);
        String b64 = java.util.Base64.getEncoder().encodeToString(bytes);
        logger.info("\n--- OCR via inline base64 ---");
        Object byB64 = ocrTool.execute(Map.of(
            "base64", b64,
            "language", "eng"));
        logger.info("{}", byB64);

        logger.info("\nExpected the extracted text to contain: \"{}\"", text);

        Files.deleteIfExists(image);
    }

    private static void renderPng(Path where, String text) throws IOException {
        BufferedImage img = new BufferedImage(800, 220, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 36));
        g.drawString(text, 30, 120);
        g.dispose();
        ImageIO.write(img, "png", where.toFile());
    }

    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
            args.length > 0 ? prepend("ocr", args) : new String[]{"ocr"});
    }

    private static String[] prepend(String first, String[] rest) {
        String[] out = new String[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }
}
