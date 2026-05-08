package ai.intelliswarm.swarmai.examples.slashcommands;

import ai.intelliswarm.swarmai.skill.slash.HelpSkill;
import ai.intelliswarm.swarmai.skill.slash.RouterResult;
import ai.intelliswarm.swarmai.skill.slash.SlashCommandRouter;
import ai.intelliswarm.swarmai.skill.slash.SlashSkill;
import ai.intelliswarm.swarmai.skill.slash.SlashSkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Showcases the slash-command harness primitive (1.0.19+). The harness builds a
 * {@link SlashCommandRouter} fronted by a {@link SlashSkillRegistry}, registers
 * three skills ({@code /help}, {@code /summarise}, {@code /translate}), and
 * processes a fixed list of user inputs. Slash commands route to skill
 * implementations (some LLM-backed); plain text passes through to the LLM
 * directly.
 *
 * <h2>What this proves</h2>
 * <ol>
 *   <li>The router cleanly distinguishes slash commands from plain LLM input</li>
 *   <li>Skills can be pure-Java ({@code /help}) or LLM-delegating ({@code /summarise})</li>
 *   <li>Unknown slash commands surface a clear "not found" without going to the LLM</li>
 *   <li>Skills are composable — {@code /help} reads the registry it lives in</li>
 * </ol>
 *
 * <h2>Quality check</h2>
 * Verifies that every routed input ended up in the expected category, that
 * every LLM-backed skill produced non-trivial output, and that {@code /help}'s
 * output lists all registered skills.
 */
@Component
public class SlashCommandsExample {

    private static final Logger logger = LoggerFactory.getLogger(SlashCommandsExample.class);

    private final ChatClient.Builder chatClientBuilder;

    public SlashCommandsExample(ChatClient.Builder chatClientBuilder) {
        this.chatClientBuilder = chatClientBuilder;
    }

    public void run(String[] args) {
        ChatClient chatClient = chatClientBuilder.build();

        // ---- 1. Build registry + router ----
        SlashSkillRegistry registry = new SlashSkillRegistry();
        AtomicInteger llmCalls = new AtomicInteger(0);

        // /help — pure-Java, lists the registry
        registry.register(HelpSkill.of(registry));

        // /summarise — delegates to the LLM with a focused system prompt
        registry.register(new SlashSkill("summarise",
                "summarise a passage of text in 1-2 sentences",
                argsText -> {
                    if (argsText == null || argsText.isBlank()) {
                        return "Usage: /summarise <text>";
                    }
                    llmCalls.incrementAndGet();
                    return chatClient.prompt()
                            .system("You are a terse editor. Summarise the user's passage "
                                    + "in 1-2 short sentences. Plain prose, no markdown.")
                            .user(argsText)
                            .call().content();
                }));

        // /translate — same pattern; first word is the target language
        registry.register(new SlashSkill("translate",
                "translate text — first word is target language, rest is the text",
                argsText -> {
                    if (argsText == null || argsText.isBlank()) {
                        return "Usage: /translate <language> <text>";
                    }
                    String[] parts = argsText.trim().split("\\s+", 2);
                    if (parts.length < 2) {
                        return "Usage: /translate <language> <text>";
                    }
                    String lang = parts[0];
                    String text = parts[1];
                    llmCalls.incrementAndGet();
                    return chatClient.prompt()
                            .system("Translate the user's text to " + lang
                                    + ". Output only the translation — no preamble, no quotes.")
                            .user(text)
                            .call().content();
                }));

        SlashCommandRouter router = new SlashCommandRouter(registry);

        // ---- 2. Fixed input transcript so the demo is reproducible ----
        List<String> userInputs = (args != null && args.length > 0)
                ? List.of(args)
                : List.of(
                        "/help",
                        "/summarise The mitochondrion is a double-membrane-bound organelle "
                                + "found in most eukaryotic cells. It generates the cell's "
                                + "supply of ATP through oxidative phosphorylation, and is "
                                + "often called 'the powerhouse of the cell'.",
                        "/translate French Hello, world!",
                        "/nonexistent something",
                        "Tell me a haiku about coffee."
                );

        printHeader(registry);

        // ---- 3. Route each input, collect results ----
        AtomicInteger executed = new AtomicInteger(0);
        AtomicInteger notFound = new AtomicInteger(0);
        AtomicInteger passthrough = new AtomicInteger(0);
        String helpOutput = null;
        String summariseOutput = null;
        String translateOutput = null;

        for (int i = 0; i < userInputs.size(); i++) {
            String input = userInputs.get(i);
            line("");
            line(String.format("--- input #%d ---", i + 1));
            line("user> " + truncate(oneLine(input), 200));

            RouterResult result;
            try {
                result = router.route(input);
            } catch (RuntimeException e) {
                logger.error("router threw on input '{}': {}", input, e.getMessage(), e);
                continue;
            }

            String output = switch (result) {
                case RouterResult.SkillExecuted e -> {
                    executed.incrementAndGet();
                    if ("help".equals(e.skillName())) helpOutput = e.output();
                    if ("summarise".equals(e.skillName())) summariseOutput = e.output();
                    if ("translate".equals(e.skillName())) translateOutput = e.output();
                    yield "[skill: /" + e.skillName() + "]\n" + e.output();
                }
                case RouterResult.SkillNotFound n -> {
                    notFound.incrementAndGet();
                    yield "[unknown command: /" + n.name() + "]";
                }
                case RouterResult.PassThrough p -> {
                    passthrough.incrementAndGet();
                    llmCalls.incrementAndGet();
                    String reply;
                    try {
                        reply = chatClient.prompt().user(p.input()).call().content();
                    } catch (RuntimeException ex) {
                        reply = "(LLM call failed: " + ex.getMessage() + ")";
                    }
                    yield "[passthrough → LLM]\n" + reply;
                }
            };

            line(output);
        }

        // ---- 4. Quality check ----
        runQualityCheck(executed.get(), notFound.get(), passthrough.get(),
                llmCalls.get(), registry, helpOutput, summariseOutput, translateOutput);
    }

    private void runQualityCheck(int executed, int notFound, int passthrough,
                                  int llmCalls, SlashSkillRegistry registry,
                                  String helpOutput, String summariseOutput,
                                  String translateOutput) {
        nl();
        line("======================================================================");
        line("  Quality check");
        line("======================================================================");
        line(String.format("  inputs routed:    %d (executed=%d, notFound=%d, passthrough=%d)",
                executed + notFound + passthrough, executed, notFound, passthrough));
        line(String.format("  LLM calls made:   %d", llmCalls));
        line(String.format("  registry size:    %d", registry.size()));

        boolean atLeastOneEach = executed >= 3 && notFound >= 1 && passthrough >= 1;
        boolean helpListedAllSkills = helpOutput != null
                && helpOutput.contains("/help")
                && helpOutput.contains("/summarise")
                && helpOutput.contains("/translate");
        boolean summariseProducedNonTrivial = summariseOutput != null
                && summariseOutput.length() >= 20
                && !summariseOutput.toLowerCase().startsWith("usage:");
        boolean translateLooksTranslated = translateOutput != null
                && !translateOutput.equalsIgnoreCase("Hello, world!")
                && translateOutput.length() >= 5;

        nl();
        line("  Checks:");
        line("    [" + check(atLeastOneEach)
                + "] each routing branch exercised "
                + "(executed>=3 notFound>=1 passthrough>=1)");
        line("    [" + check(helpListedAllSkills)
                + "] /help output lists every registered skill");
        line("    [" + check(summariseProducedNonTrivial)
                + "] /summarise produced non-trivial LLM output (len>=20, not usage)");
        line("    [" + check(translateLooksTranslated)
                + "] /translate output differs from input (translation actually happened)");

        nl();
        boolean allPass = atLeastOneEach && helpListedAllSkills
                && summariseProducedNonTrivial && translateLooksTranslated;
        if (allPass) {
            line("  QUALITY CHECK PASSED");
            line("  -> Slash commands route correctly, LLM-backed skills work, /help is honest.");
        } else {
            line("  QUALITY CHECK FAILED — inspect counts above.");
        }
    }

    private static String check(boolean ok) { return ok ? "PASS" : "FAIL"; }

    private static void printHeader(SlashSkillRegistry registry) {
        nl();
        line("======================================================================");
        line("  Slash commands — user-invokable skills");
        line("======================================================================");
        line("");
        line("  Registry:");
        for (SlashSkill s : registry.all()) {
            line(String.format("    /%-12s — %s", s.name(), s.description()));
        }
        line("");
        line("  The example feeds a fixed list of inputs through SlashCommandRouter.");
        line("  Slash commands route to registered skills; plain text passes through");
        line("  to the LLM. Unknown slashes surface a clear 'not found' result.");
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
}
