# slash-commands — user-invokable skills mid-conversation (swarmai 1.0.19+)

Showcases the slash-command harness primitive: the user types `/<name> [args]` and
the harness routes the input to a registered skill instead of sending it
straight to the LLM. Familiar slash-command UX: `/help`, `/review`,
`/clear`, etc.

## What this proves

| | |
|---|---|
| Slash commands are first-class | The `SlashCommandRouter` distinguishes `/x` from plain text deterministically before any LLM call |
| Skills can be pure-Java or LLM-backed | `/help` is local; `/summarise` and `/translate` delegate to the LLM with focused system prompts |
| Unknown slashes are recoverable | Returns a `SkillNotFound` result the harness can surface to the user without burning a turn |
| Skills compose | `/help` reads the registry it lives in, so it's always honest about what's available |

## Architecture

```
user input  ─▶  SlashCommandRouter ─▶  parses /<name> [args]
                       │
                       ├─ matched ─▶  SlashSkill.execute(args) ─▶  output (Java or LLM)
                       │
                       ├─ unknown ─▶  SkillNotFound  ─▶  harness shows "Unknown command: /xyz"
                       │
                       └─ plain  ──▶  PassThrough   ─▶  chatClient.prompt().user(...).call()
```

| Type | Role |
|---|---|
| `SlashSkill` | Record: name + description + `Function<String, String>` action |
| `SlashSkillRegistry` | Insertion-ordered registry, name lookup, `/help` source |
| `SlashCommand` | Parsed `(name, args, raw)` triple, with `parse(input)` |
| `SlashCommandRouter` | Front door: returns sealed `RouterResult` (executed / notFound / passThrough) |
| `HelpSkill` | Built-in `/help` that lists every other registered skill |

## Run

```bash
# requires OPENAI_API_KEY in .env (parent dir)
./slash-commands/run.sh                  # default 5-input transcript
./slash-commands/run.sh "/summarise <your text>"  # single-input mode
```

The default transcript exercises every routing branch:

| # | Input | Expected route |
|---|---|---|
| 1 | `/help` | skill executed (pure Java) |
| 2 | `/summarise <passage>` | skill executed (LLM-backed) |
| 3 | `/translate French Hello, world!` | skill executed (LLM-backed) |
| 4 | `/nonexistent something` | skill not found |
| 5 | `Tell me a haiku about coffee.` | passthrough → LLM |

## Output shape

```
======================================================================
  Slash commands — user-invokable skills
======================================================================

  Registry:
    /help         — list available commands
    /summarise    — summarise a passage of text in 1-2 sentences
    /translate    — translate text — first word is target language, rest is the text

--- input #1 ---
user> /help
[skill: /help]
Available commands:
  /help       — list available commands
  /summarise  — summarise a passage of text in 1-2 sentences
  /translate  — translate text — first word is target language, rest is the text

--- input #2 ---
user> /summarise The mitochondrion is a double-membrane-bound organelle...
[skill: /summarise]
Mitochondria are double-membrane organelles in most eukaryotic cells that
generate ATP via oxidative phosphorylation, often called "the powerhouse
of the cell".

...

======================================================================
  Quality check
======================================================================
  inputs routed:    5 (executed=3, notFound=1, passthrough=1)

  Checks:
    [PASS] each routing branch exercised
    [PASS] /help output lists every registered skill
    [PASS] /summarise produced non-trivial LLM output
    [PASS] /translate output differs from input

  QUALITY CHECK PASSED
```

## Composing skills with other primitives

Skills can capture any harness state in their action closure. Common patterns:

- **LLM-backed skill**: capture a `ChatClient`, call `chatClient.prompt()...`
- **Stateful skill**: capture a counter, registry, or session map; mutate per call
- **Tool-bridge skill**: invoke a `BaseTool` directly and format the result
- **TaskList skill**: capture a `TaskList` and let `/tasks` dump the current state
- **Reminder-driven skill**: capture a `SystemReminderChannel` and let
  `/notify <msg>` queue a reminder for the next turn

The pattern is identical: `Function<String, String>` is the entire skill body.

## License

Apache License 2.0 — see [`LICENSE`](../LICENSE).
