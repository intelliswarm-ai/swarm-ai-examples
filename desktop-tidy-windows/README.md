# Desktop Tidy (Windows)

A single-agent workflow that tidies a folder (default: your Desktop) by:

1. listing what's there,
2. proposing a small set of category folders (Apps, Documents, Images, Videos, Archives, Misc),
3. creating the missing folders,
4. moving loose files into the right one.

Every mutation goes through the **supervised approval gate** — by default each
proposed `mkdir` and `move` prints to stderr and waits for a `y` / `N` decision
on stdin. You stay in control; the agent only proposes.

## Requirements

- **Windows.** The `windows_filesystem` tool is OS-gated (declares `os: windows`
  in its `ToolRequirements`). It will refuse to execute on Linux or macOS.
- **swarmai 1.0.11+.** The Windows tool category was added in 1.0.11. If the
  examples pom is still on 1.0.10, install the framework locally first:
  ```bash
  cd /path/to/swarm-ai
  mvn install -DskipTests
  ```
  Then bump `<swarmai.version>` in `swarm-ai-examples/pom.xml` to `1.0.11-SNAPSHOT`
  (or the released 1.0.11 once published).
- **A local LLM via Ollama** (default `mistral:latest`), same as every other example.

## Run it

From the examples root:

```bash
./desktop-tidy-windows/run.sh                   # tidy ~/Desktop
./desktop-tidy-windows/run.sh "C:/Users/me/Desktop"
./desktop-tidy-windows/run.sh "C:/Users/me/Downloads"   # any allowlisted folder
```

The folder you pass must be inside the configured allowlist
(`swarmai.tools.windows.filesystem.allowed-roots`, defaults to `~/Desktop`,
`~/Downloads`, `~/Documents`).

## What you'll see

```
=== Current contents of C:\Users\me\Desktop ===
Listing of C:\Users\me\Desktop
- DIR   2026-04-20 14:00  Apps
- FILE  2026-04-21 09:12  120000b  budget.xlsx
- FILE  2026-04-22 16:33  2400000b vacation.mp4
- FILE  2026-04-23 11:08  18000b   meeting-notes.docx
…

=== SwarmAI Approval Request ===
Request:  9b8f4f0c-…
Gate:     tool:windows_filesystem
Tool:     windows_filesystem
Risk:     LOW
Summary:  Create directory C:\Users\me\Desktop\Documents
Tenant:   default
Timeout:  PT5M
Ops (1):
  - mkdir C:\Users\me\Desktop\Documents
Approve? [y/N]:  y
[swarmai] APPROVED.

=== SwarmAI Approval Request ===
Summary:  Move C:\…\Desktop\budget.xlsx → C:\…\Desktop\Documents\budget.xlsx
Risk:     MEDIUM
Approve? [y/N]:  y
[swarmai] APPROVED.

…
```

Reject any prompt by hitting `n` (or anything other than `y`/`yes`/`approve`)
and the agent moves on without touching that file.

## Configuration

The `run.sh` shim sets:

```
--swarmai.tools.windows.enabled=true
```

Other useful overrides (pass on the command line):

| Property | What it does | Default |
|---|---|---|
| `swarmai.tools.windows.filesystem.allowed-roots[0]` | First allowed root | `${user.home}/Desktop` |
| `swarmai.tools.windows.filesystem.dry-run-default` | If true, mutations require explicit `apply=true` | `true` |
| `swarmai.tools.windows.approval-timeout` | How long the prompt waits before timing out | `5m` |

## How it stays safe

- **Allowlist:** the `windows_filesystem` tool refuses any path outside its
  configured roots. An agent that hallucinates `C:\Windows\System32\…` gets
  `Error: path is outside allowlist` and never touches the OS.
- **Approval gate:** every `mkdir` / `move` is presented to you before it runs.
  The default fallback handler (`ConsoleApprovalGateHandler`) reads `y/N` from
  stdin and **fails closed** on EOF, unrecognised input, or timeout.
- **No directory move/delete in v1:** the tool refuses to move or delete a
  directory recursively. If the agent proposes that, it gets an error string
  back and adapts.

## Related

- The tool itself: `swarm-ai/swarmai-tools/src/main/java/ai/intelliswarm/swarmai/tool/windows/WindowsFileSystemTool.java`
- The approval flow: `…/tool/safety/SupervisedMutationGuard.java` and
  `…/tool/safety/ConsoleApprovalGateHandler.java`
- Other Windows-PC tools you can wire up similarly: `WindowsProcessTool`,
  `WindowsWindowTool`, `WindowsShortcutTool`, `WindowsScreenshotTool`.
