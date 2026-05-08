# edit-discipline — Read-before-Edit + unique-match + stale-read (swarmai 1.0.19+)

Showcases `EditDisciplineGuard` end-to-end with a real LLM. Two tools are
bound to one guard. The discipline catches the failure modes that make blind
edits unsafe: editing a file the LLM hasn't seen, replacing an ambiguous
substring, or operating on stale content.

## What this proves

| Invariant | Catches |
|---|---|
| Read-before-Edit | LLM "knows" what's in the file from the user message but never looked. Edit refused with actionable hint. |
| Unique-match | `oldString` matches more than one location. Edit refused; LLM must pick a longer anchor or pass `replaceAll=true`. |
| Stale-read | File changed (e.g. by another agent) since the LLM read it. Edit refused; LLM must re-read. |

The exception messages tell the LLM exactly how to recover, which is the load-bearing part — without that, the agent gets stuck.

## Architecture

```
read_file(path)
   └── guard.recordRead(path, content)        ← captures content for stale-read check

edit_file(path, oldString, newString, replaceAll)
   └── guard.applyEdit(path, currentContent, ...)
        ├── check 1: path was read?           → ReadBeforeEditException
        ├── check 2: content unchanged?       → StaleReadException
        ├── check 3: oldString unique?        → AmbiguousMatchException / NoMatchException
        └── apply edit; auto-update snapshot for next round
```

## Run

```bash
# requires OPENAI_API_KEY in .env (parent dir)
./edit-discipline/run.sh
```

The example creates a temp YAML config and asks the LLM to bump `version: 1.0.18` → `1.0.19`. The user message describes the file's contents inline — tempting the LLM to skip the read.

## Output shape

```
======================================================================
  EditDisciplineGuard — read-before-edit invariant
======================================================================

  Sample file: /tmp/edit-discipline-XXXX.yml

user> I have a config at /tmp/... with version 1.0.18 and environment staging.
      Please bump the version to 1.0.19...

agent> The version has been successfully updated from 1.0.18 to 1.0.19...

======================================================================
  Final file contents on disk
======================================================================
# SwarmAI Demo Config
version: 1.0.19
environment: staging
debug: false

# Database
db.host: localhost
db.port: 5432

======================================================================
  Quality check
======================================================================
  read_file calls:   1
  edit_file calls:   1  (1 succeeded)
  edits refused:     0 no-read, 0 no-match, 0 ambiguous

  Checks:
    [PASS] file was read at least once
    [PASS] at least one edit succeeded
    [PASS] version bumped 1.0.18 -> 1.0.19 in the on-disk file
    [PASS] no other lines were changed

  QUALITY CHECK PASSED
  -> The agent followed the read-before-edit discipline on the first try.
```

A well-behaved gpt-4o-mini will read first; a less careful one might try the edit, get refused, and recover. Either path passes the quality check — what matters is the on-disk state.

## License

Apache License 2.0 — see [`LICENSE`](../LICENSE).
