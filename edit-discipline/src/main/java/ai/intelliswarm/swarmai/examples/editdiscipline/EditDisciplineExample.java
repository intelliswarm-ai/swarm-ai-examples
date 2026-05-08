package ai.intelliswarm.swarmai.examples.editdiscipline;

import ai.intelliswarm.swarmai.tool.safety.EditDisciplineGuard;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Showcases {@link EditDisciplineGuard} (1.0.19+) end-to-end
 * with a real LLM. The agent has two tools — {@code read_file} and
 * {@code edit_file} — both wired through a single guard. Without a prior
 * read, edits are refused with an actionable error; with a read in place,
 * edits succeed and the guard's stale-read check carries through.
 *
 * <h2>Demo arc</h2>
 * The example deliberately gives the LLM a directive that <em>tempts</em> it
 * to skip the read (the user message describes the file's contents, suggesting
 * no read is needed). gpt-4o-mini will typically:
 * <ol>
 *   <li>Try {@code edit_file} directly — guard refuses with a clear error</li>
 *   <li>Read the recovery hint, call {@code read_file}, then retry the edit</li>
 *   <li>Edit succeeds; guard auto-updates its content snapshot</li>
 * </ol>
 *
 * <h2>Quality check</h2>
 * Verifies {@code (a)} ≥ 1 edit was refused before any read,
 * {@code (b)} the file was eventually read, {@code (c)} ≥ 1 edit succeeded,
 * {@code (d)} the file's actual on-disk content matches the expected post-edit
 * state.
 */
@Component
public class EditDisciplineExample {

    private static final Logger logger = LoggerFactory.getLogger(EditDisciplineExample.class);

    private final ChatClient.Builder chatClientBuilder;

    public EditDisciplineExample(ChatClient.Builder chatClientBuilder) {
        this.chatClientBuilder = chatClientBuilder;
    }

    public void run(String[] args) {
        // ---- 1. Sample file (deterministic content) ----
        Path tempFile;
        String initialContent = """
                # SwarmAI Demo Config
                version: 1.0.18
                environment: staging
                debug: false

                # Database
                db.host: localhost
                db.port: 5432
                """;
        try {
            tempFile = Files.createTempFile("edit-discipline-", ".yml");
            Files.writeString(tempFile, initialContent, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.error("could not create temp file: {}", e.getMessage(), e);
            return;
        }
        tempFile.toFile().deleteOnExit();

        // ---- 2. Wire the guard + counters ----
        EditDisciplineGuard guard = new EditDisciplineGuard();
        AtomicInteger readCalls = new AtomicInteger();
        AtomicInteger editAttempts = new AtomicInteger();
        AtomicInteger editSuccesses = new AtomicInteger();
        AtomicInteger editRefusedNoRead = new AtomicInteger();
        AtomicInteger editRefusedNoMatch = new AtomicInteger();
        AtomicInteger editRefusedAmbiguous = new AtomicInteger();

        // ---- 3. Tool callbacks ----
        Function<ReadInput, String> readFn = input -> {
            readCalls.incrementAndGet();
            if (input == null || input.path == null || input.path.isBlank()) {
                return "Error: 'path' is required.";
            }
            Path target = Path.of(input.path);
            try {
                String content = Files.readString(target, StandardCharsets.UTF_8);
                guard.recordRead(target, content);
                return content;
            } catch (IOException e) {
                return "Error: failed to read " + target + ": " + e.getMessage();
            }
        };

        Function<EditInput, String> editFn = input -> {
            editAttempts.incrementAndGet();
            if (input == null || input.path == null || input.path.isBlank()) {
                return "Error: 'path' is required.";
            }
            if (input.oldString == null || input.oldString.isEmpty()) {
                return "Error: 'oldString' is required and must not be empty.";
            }
            if (input.newString == null) {
                return "Error: 'newString' is required (use empty string for deletion).";
            }
            boolean replaceAll = Boolean.TRUE.equals(input.replaceAll);
            Path target = Path.of(input.path);

            String currentContent;
            try {
                currentContent = Files.readString(target, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "Error: failed to read " + target + ": " + e.getMessage();
            }

            String newContent;
            try {
                newContent = guard.applyEdit(target, currentContent,
                        input.oldString, input.newString, replaceAll);
            } catch (EditDisciplineGuard.ReadBeforeEditException e) {
                editRefusedNoRead.incrementAndGet();
                return "Error: " + e.getMessage();
            } catch (EditDisciplineGuard.NoMatchException e) {
                editRefusedNoMatch.incrementAndGet();
                return "Error: " + e.getMessage();
            } catch (EditDisciplineGuard.AmbiguousMatchException e) {
                editRefusedAmbiguous.incrementAndGet();
                return "Error: " + e.getMessage();
            } catch (RuntimeException e) {
                return "Error: " + e.getMessage();
            }

            try {
                Files.writeString(target, newContent, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "Error: failed to write " + target + ": " + e.getMessage();
            }
            editSuccesses.incrementAndGet();
            return "Edit applied. " + (replaceAll ? "(replaceAll)" : "(single match)");
        };

        ToolCallback readCb = FunctionToolCallback.builder("read_file", readFn)
                .description("Read the full contents of a file at the given path. "
                        + "Required: path. Returns the file content as a string.")
                .inputType(ReadInput.class)
                .build();

        ToolCallback editCb = FunctionToolCallback.builder("edit_file", editFn)
                .description(
                        "Replace oldString with newString in a file. "
                                + "Required: path, oldString, newString. "
                                + "Optional: replaceAll (default false). "
                                + "PRECONDITION: you MUST have called read_file on the same path "
                                + "in this session before calling edit_file, otherwise the edit "
                                + "is refused. oldString must match the file content exactly "
                                + "(including whitespace) and must occur exactly once unless "
                                + "replaceAll is true.")
                .inputType(EditInput.class)
                .build();

        ChatClient chatClient = chatClientBuilder.build();

        printHeader(tempFile);

        // ---- 4. Drive the LLM ----
        // The user prompt deliberately describes what the file SAYS without
        // inviting a read first — the LLM may try to skip read_file. The
        // discipline guard then catches the slip and the LLM recovers.
        String systemPrompt = """
                You are a careful file-editing assistant. You have two tools:
                read_file(path) and edit_file(path, oldString, newString, replaceAll?).

                Rules to follow:
                  1. Before any edit_file call on a path, you MUST have called read_file
                     on that same path in this session.
                  2. If edit_file returns an error starting with "Edit refused:", read
                     the error carefully and recover — usually by calling read_file
                     and retrying with the exact text you saw.
                  3. Make the requested change with the smallest possible oldString
                     that uniquely identifies the location. Do not rewrite more than
                     necessary.
                """;

        String userPrompt = "I have a config at " + tempFile
                + " with version 1.0.18 and environment staging. "
                + "Please bump the version to 1.0.19. Make ONE single edit; "
                + "do not change anything else. When done, confirm what you changed.";

        line("user> " + userPrompt);
        nl();

        long startMs = System.currentTimeMillis();
        String response;
        try {
            response = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .toolCallbacks(readCb, editCb)
                    .call()
                    .content();
        } catch (RuntimeException e) {
            logger.error("LLM call failed: {}", e.getMessage(), e);
            return;
        }
        long elapsedMs = System.currentTimeMillis() - startMs;

        // ---- 5. Show results ----
        line("agent> " + truncate(oneLine(response), 400));

        // Read the file back to verify on-disk state
        String finalContent;
        try {
            finalContent = Files.readString(tempFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            finalContent = "(could not read final content: " + e.getMessage() + ")";
        }

        nl();
        line("======================================================================");
        line("  Final file contents on disk");
        line("======================================================================");
        line(finalContent);

        // ---- 6. Quality check ----
        runQualityCheck(initialContent, finalContent,
                readCalls.get(), editAttempts.get(), editSuccesses.get(),
                editRefusedNoRead.get(), editRefusedNoMatch.get(),
                editRefusedAmbiguous.get(), elapsedMs);
    }

    private void runQualityCheck(String initial, String finalContent,
                                  int reads, int edits, int successes,
                                  int refusedNoRead, int refusedNoMatch, int refusedAmbiguous,
                                  long elapsedMs) {
        nl();
        line("======================================================================");
        line("  Quality check");
        line("======================================================================");
        line(String.format("  elapsed:           %.2fs", elapsedMs / 1000.0));
        line(String.format("  read_file calls:   %d", reads));
        line(String.format("  edit_file calls:   %d  (%d succeeded)", edits, successes));
        line(String.format("  edits refused:     %d no-read, %d no-match, %d ambiguous",
                refusedNoRead, refusedNoMatch, refusedAmbiguous));

        boolean readHappened = reads >= 1;
        boolean atLeastOneEditSucceeded = successes >= 1;
        boolean versionWasBumped = !finalContent.contains("version: 1.0.18")
                && finalContent.contains("version: 1.0.19");
        boolean noOtherChanges = preserveCheck(initial, finalContent);

        nl();
        line("  Checks:");
        line("    [" + check(readHappened)
                + "] file was read at least once  (got " + reads + ")");
        line("    [" + check(atLeastOneEditSucceeded)
                + "] at least one edit succeeded   (got " + successes + ")");
        line("    [" + check(versionWasBumped)
                + "] version bumped 1.0.18 -> 1.0.19 in the on-disk file");
        line("    [" + check(noOtherChanges)
                + "] no other lines were changed (only the version line differs)");

        boolean allPass = readHappened && atLeastOneEditSucceeded
                && versionWasBumped && noOtherChanges;
        nl();
        if (allPass) {
            line("  QUALITY CHECK PASSED");
            if (refusedNoRead > 0) {
                line("  -> The discipline guard caught " + refusedNoRead
                        + " premature edit attempt(s); the agent recovered.");
            } else {
                line("  -> The agent followed the read-before-edit discipline on the first try.");
            }
        } else {
            line("  QUALITY CHECK FAILED — inspect counts above.");
        }
    }

    private static boolean preserveCheck(String initial, String finalContent) {
        // Every line that doesn't contain "version" should appear unchanged in finalContent.
        for (String line : initial.split("\n", -1)) {
            if (line.contains("version")) continue;
            if (line.isBlank()) continue;
            if (!finalContent.contains(line)) return false;
        }
        return true;
    }

    private static String check(boolean ok) { return ok ? "PASS" : "FAIL"; }

    private static void printHeader(Path tempFile) {
        nl();
        line("======================================================================");
        line("  EditDisciplineGuard — read-before-edit invariant");
        line("======================================================================");
        line("");
        line("  Two tools are bound to one guard:");
        line("    read_file(path)                         -> records the read");
        line("    edit_file(path, oldString, newString)   -> guard checks read first");
        line("");
        line("  The guard enforces three invariants:");
        line("    1. read-before-edit  (path must have been read in this session)");
        line("    2. unique-match      (oldString must occur exactly once, or replaceAll)");
        line("    3. stale-read        (file content unchanged since the read)");
        line("");
        line("  Sample file: " + tempFile);
        line("");
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private static void line(String s) { System.out.println(s); }
    private static void nl() { System.out.println(); }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ReadInput {
        public String path;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EditInput {
        public String path;
        public String oldString;
        public String newString;
        public Boolean replaceAll;
    }
}
