package ai.intelliswarm.swarmai.examples.windows;

import ai.intelliswarm.swarmai.SwarmAIExamplesApplication;
import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.tool.os.FileSystemTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * "Tidy my desktop" — a single-agent workflow that uses the cross-platform
 * {@code os_filesystem} tool to inspect a folder (defaults to {@code ~/Desktop}),
 * propose a category structure, and move loose files into folders.
 *
 * <p><b>Cross-platform:</b> works on Windows, macOS, and Linux — the tool is
 * platform-agnostic. The directory name {@code desktop-tidy-windows} is preserved
 * for backward compatibility from when this example was Windows-only; despite the
 * name, the underlying tool now supports every OS.
 *
 * <p>Every mutation goes through the supervised approval gate: by default the
 * {@link ai.intelliswarm.swarmai.tool.safety.ConsoleApprovalGateHandler} prints
 * each proposed {@code mkdir} / {@code move} to stderr and waits for {@code y/N}
 * on stdin before executing. The agent invocations themselves use {@code apply=true}
 * so the user is the one approving each step at the prompt.
 *
 * <p>Run: {@code ./desktop-tidy-windows/run.sh} (defaults to {@code ~/Desktop})
 * <br>Or:  {@code ./desktop-tidy-windows/run.sh "/path/to/folder"}
 *
 * <p>This example is gated behind {@code swarmai.tools.os.enabled=true};
 * the {@code run.sh} sets that flag automatically.
 */
@Component
@ConditionalOnProperty(prefix = "swarmai.tools.os", name = "enabled", havingValue = "true")
public class DesktopTidyExample {

    private static final Logger logger = LoggerFactory.getLogger(DesktopTidyExample.class);

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final FileSystemTool fsTool;

    public DesktopTidyExample(ChatClient.Builder chatClientBuilder,
                              ApplicationEventPublisher eventPublisher,
                              FileSystemTool fsTool) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
        this.fsTool = fsTool;
    }

    public void run(String... args) {
        Path folder = resolveFolder(args);

        String smoke = fsTool.smokeTest();
        if (smoke != null) {
            logger.error("FileSystemTool unhealthy: {}", smoke);
            return;
        }

        // Sanity-print what the tool can see, so the user knows we're aimed at the right place.
        Object listing = fsTool.execute(Map.of("operation", "list", "path", folder.toString()));
        logger.info("");
        logger.info("=== Current contents of {} ===", folder);
        logger.info("{}", listing);

        ChatClient chatClient = chatClientBuilder.build();

        Agent organiser = Agent.builder()
            .role("Desktop Organiser")
            .goal("Tidy the contents of " + folder + " by grouping items into a small set of "
                    + "category folders (Apps, Documents, Images, Videos, Archives, Misc).")
            .backstory("You use the os_filesystem tool to do real work. You ALWAYS:\n"
                    + " 1. Start with operation='list' (no path) and operation='list' path='" + folder + "' "
                    + "to discover what's there and what's allowed.\n"
                    + " 2. The list output uses this exact column layout, separated by two-space gaps:\n"
                    + "      - <TYPE>  <YYYY-MM-DD HH:MM>  <SIZE>b  <FILENAME>\n"
                    + "    Example:  - FILE  2025-04-25 18:58  209011b  2023 Messinis Theodoros _ Estimation UBP.pdf\n"
                    + "    Here the FILENAME is everything AFTER the 'NNNNb  ' size token. The size and\n"
                    + "    timestamp are metadata only — NEVER include them in any path you build.\n"
                    + "    The full source path = '" + folder + "/' + FILENAME (use forward or backslashes consistently).\n"
                    + " 3. Decide a small set of category folders that fit the actual contents. "
                    + "Six is plenty; do NOT invent dozens.\n"
                    + " 4. For each missing category folder, call operation='mkdir' "
                    + "path='" + folder + "/<Category>' apply=true. The user will be prompted to approve each mkdir.\n"
                    + " 5. For each loose file, call operation='move' path='<src>' to='<dest>' apply=true,\n"
                    + "    where <src> = '" + folder + "/' + FILENAME and <dest> = '" + folder + "/<Category>/' + FILENAME.\n"
                    + "    Skip directories — the v1 tool does not support moving them.\n"
                    + " 6. NEVER guess paths. Only act on items the list step actually returned.\n"
                    + " 7. If a move returns 'path does not exist', re-read the listing — you almost certainly\n"
                    + "    glued metadata into the filename. Do not retry the same wrong path.\n"
                    + " 8. HARD LIMIT: issue AT MOST 50 tool calls per single turn. OpenAI rejects messages\n"
                    + "    with more than 128 tool_calls. After ~50 moves in one turn, stop, return a brief\n"
                    + "    progress note, and let the next turn pick up where you left off.\n"
                    + " 9. Keep going across turns until you have moved every loose file you can categorise,\n"
                    + "    or the folder is empty of loose files. End with a markdown summary of folders\n"
                    + "    created and files moved/skipped.")
            .chatClient(chatClient)
            .tools(List.of(fsTool))
            .maxTurns(40)
            .verbose(true)
            .build();

        Task tidy = Task.builder()
            .description("Inspect " + folder + " and tidy it. Propose a small set of category folders "
                    + "(Apps, Documents, Images, Videos, Archives, Misc — pick the ones that match what's "
                    + "actually there), create the folders that don't exist yet, then move every loose file "
                    + "into the right folder until the folder is clean. Each call to the tool that mutates "
                    + "the filesystem will be approved interactively by the human at the console — wait for "
                    + "each result before deciding the next move. End with a short markdown summary of what "
                    + "was created and moved.")
            .expectedOutput("A markdown summary listing: (a) which category folders were created, "
                    + "(b) which files were moved (with old → new paths), and (c) anything skipped and why.")
            .agent(organiser)
            .build();

        Swarm swarm = Swarm.builder()
            .agent(organiser)
            .task(tidy)
            .process(ProcessType.SEQUENTIAL)
            .verbose(true)
            .eventPublisher(eventPublisher)
            .build();

        SwarmOutput result = swarm.kickoff(Map.of("folder", folder.toString()));

        logger.info("");
        logger.info("=== Desktop tidy result ===");
        logger.info("{}", result.getFinalOutput());
    }

    private static Path resolveFolder(String[] args) {
        if (args.length > 0 && !args[0].isBlank()) {
            return Paths.get(args[0]);
        }
        // Default: the user's Desktop. On Windows this resolves to C:\Users\<user>\Desktop.
        return Paths.get(System.getProperty("user.home"), "Desktop");
    }

    public static void main(String[] args) {
        SpringApplication.run(SwarmAIExamplesApplication.class,
            args.length > 0 ? prepend("desktop-tidy", args) : new String[]{"desktop-tidy"});
    }

    private static String[] prepend(String first, String[] rest) {
        String[] out = new String[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }
}
