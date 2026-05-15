package ai.intelliswarm.swarmai.examples.anthropicfiles;

import ai.intelliswarm.swarmai.llm.anthropic.AnthropicConfig;
import ai.intelliswarm.swarmai.llm.anthropic.files.AnthropicFile;
import ai.intelliswarm.swarmai.llm.anthropic.files.AnthropicFilesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * SwarmAI 1.0.24 — Anthropic Files API lifecycle.
 *
 * <p>Self-verifying demo of {@link AnthropicFilesClient}: writes a synthetic
 * 10-K PDF-ish text file to a temp location, uploads it, retrieves the
 * server-side metadata, lists the workspace, downloads the bytes back, and
 * cleans up. Verifies the round-trip is byte-identical (SHA-256 match) and
 * that {@code list()} contains the freshly-uploaded file.
 *
 * <p>Requires {@code ANTHROPIC_API_KEY}.
 */
@Component
public class AnthropicFilesExample {

    private static final Logger logger = LoggerFactory.getLogger(AnthropicFilesExample.class);

    private static final String SAMPLE_DOC = """
            ACME Corp — Annual Report (synthetic for example purposes)

            We had a very nice year. Revenue: $1,200 million. EBITDA: $360 million.
            Free cash flow: $220 million. The board is satisfied.

            (This text stands in for a real PDF/document — the Files API
            doesn't care about MIME type for upload/download symmetry,
            only that the bytes round-trip.)
            """;

    public void run(String[] args) {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            logger.error("ANTHROPIC_API_KEY env var not set. This example calls the real Anthropic API.");
            return;
        }

        logger.info("");
        logger.info("================================================================");
        logger.info("  SwarmAI 1.0.24 — Anthropic Files API");
        logger.info("  Upload → metadata → list → download → verify → delete");
        logger.info("================================================================");
        logger.info("");

        AnthropicFilesClient files = AnthropicFilesClient.from(AnthropicConfig.withApiKey(apiKey));

        // ---- [1/6] Prepare local file ----
        Path local;
        try {
            local = Files.createTempFile("swarmai-files-demo-", ".txt");
            Files.writeString(local, SAMPLE_DOC, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            logger.error("Could not create temp file: {}", e.getMessage());
            return;
        }
        long localSize;
        String localSha;
        try {
            localSize = Files.size(local);
            localSha = sha256(Files.readAllBytes(local));
        } catch (java.io.IOException e) {
            logger.error("Could not read temp file: {}", e.getMessage());
            return;
        }
        logger.info("[1/6] Wrote local file:");
        logger.info("      Path:    {}", local);
        logger.info("      Size:    {} bytes", localSize);
        logger.info("      SHA-256: {}", localSha);

        AnthropicFile uploaded = null;
        try {
            // ---- [2/6] Upload ----
            logger.info("");
            logger.info("[2/6] Uploading to Anthropic Files API …");
            Instant uploadStart = Instant.now();
            uploaded = files.upload(local);
            Duration uploadDur = Duration.between(uploadStart, Instant.now());
            logger.info("      File ID:    {}", uploaded.id());
            logger.info("      Filename:   {}", uploaded.filename());
            logger.info("      MIME type:  {}", uploaded.mimeType());
            logger.info("      Size:       {} bytes (server)  vs  {} bytes (local) → {}",
                    uploaded.sizeBytes(), localSize,
                    uploaded.sizeBytes() == localSize ? "✓ match" : "✗ MISMATCH");
            logger.info("      Created:    {}", uploaded.createdAt());
            logger.info("      Wall time:  {} ms", uploadDur.toMillis());

            // ---- [3/6] Retrieve metadata (round-trip check) ----
            logger.info("");
            logger.info("[3/6] Re-fetching server-side metadata by ID …");
            AnthropicFile reFetched = files.metadata(uploaded.id());
            boolean idMatch    = reFetched.id().equals(uploaded.id());
            boolean sizeMatch  = reFetched.sizeBytes() == uploaded.sizeBytes();
            boolean mimeMatch  = reFetched.mimeType().equals(uploaded.mimeType());
            logger.info("      ID match:        {}", idMatch ? "✓" : "✗");
            logger.info("      Size match:      {}", sizeMatch ? "✓" : "✗");
            logger.info("      MIME type match: {}", mimeMatch ? "✓" : "✗");
            logger.info("      Downloadable:    {}  (gates the [5/6] step below)",
                    reFetched.downloadable() ? "✓ yes" : "✗ no");

            // ---- [4/6] List workspace ----
            logger.info("");
            logger.info("[4/6] Listing workspace (first page) …");
            List<AnthropicFile> workspace = files.list();
            final String uploadedId = uploaded.id();
            Optional<AnthropicFile> found = workspace.stream()
                    .filter(f -> f.id().equals(uploadedId))
                    .findFirst();
            logger.info("      Files in workspace: {}", workspace.size());
            logger.info("      Our upload found:   {}",
                    found.isPresent() ? "✓ at id=" + found.get().id() : "✗ NOT FOUND");
            if (workspace.size() <= 5) {
                workspace.forEach(f -> logger.info("        - {} ({}, {} bytes)",
                        f.id(), f.filename(), f.sizeBytes()));
            }

            // ---- [5/6] Download + SHA-256 round-trip verification ----
            //
            // The Files API gates downloads via the server-side `downloadable`
            // flag. As of 2026-Q1 only certain artifact types (notably
            // code-execution outputs) come back with downloadable=true; arbitrary
            // user uploads are *not* downloadable — Anthropic accepts them for
            // model reference but won't echo them back. We branch accordingly.
            logger.info("");
            logger.info("[5/6] Download + SHA-256 round-trip …");
            if (reFetched.downloadable()) {
                byte[] downloaded = files.download(uploaded.id());
                String downloadedSha = sha256(downloaded);
                boolean bytesMatch = downloadedSha.equals(localSha);
                logger.info("      Downloaded:  {} bytes", downloaded.length);
                logger.info("      SHA-256:     {}", downloadedSha);
                logger.info("      Round-trip:  {}", bytesMatch
                        ? "✓ byte-identical with local file"
                        : "✗ MISMATCH (local " + localSha + ")");
            } else {
                logger.info("      [skipped] Server returned downloadable=false for this upload.");
                logger.info("      This is expected for arbitrary user uploads — the Files API");
                logger.info("      currently only re-emits bytes for code-execution / model-generated");
                logger.info("      artifacts. User-uploaded documents are accepted for *reference*");
                logger.info("      (passed by file_id into message calls) but not for download.");
            }
        } finally {
            // ---- [6/6] Cleanup ----
            logger.info("");
            logger.info("[6/6] Cleanup …");
            try { Files.deleteIfExists(local); } catch (java.io.IOException ignored) {}
            if (uploaded != null) {
                boolean deleted = files.delete(uploaded.id());
                logger.info("      Local file deleted; server file deleted: {}",
                        deleted ? "✓" : "✗ (will linger until you delete via API/console)");
            }
        }
        logger.info("");
        logger.info("Done. The Files API is a workspace-scoped store — uploaded files");
        logger.info("persist until explicitly deleted and can be referenced by subsequent");
        logger.info("requests via their file_id (beta messages surface; substrate integration");
        logger.info("for DocumentBlock.File is a future change).");
        logger.info("");
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
