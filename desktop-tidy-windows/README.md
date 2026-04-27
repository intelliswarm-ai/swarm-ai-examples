# Desktop Tidy

A single-agent workflow that tidies a folder (default: your Desktop) by:

1. listing what's there,
2. proposing a small set of category folders (Apps, Documents, Images, Videos, Archives, Misc),
3. creating the missing folders,
4. moving loose files into the right one.

Every mutation goes through the **supervised approval gate** — by default each
proposed `mkdir` and `move` prints to stderr and waits for a `y` / `N` decision
on stdin. You stay in control; the agent only proposes.

> The directory name `desktop-tidy-windows/` is preserved from when this example
> was Windows-only. It now uses the cross-platform `os_filesystem` tool and
> works the same on **Windows, macOS, and Linux**.

## Requirements

- **Windows, macOS, or Linux.** The `os_filesystem` tool detects the host at
  runtime and uses `java.nio.file` everywhere, so no per-OS install steps.
- **swarmai 1.0.13+.** The cross-platform `swarmai.tools.os` category was
  introduced in 1.0.13. If the examples pom is still on an earlier version,
  install the framework locally first:
  ```bash
  cd /path/to/swarm-ai
  mvn install -DskipTests
  ```
  Then bump `<swarmai.version>` in `swarm-ai-examples/pom.xml` accordingly.
- **A local LLM via Ollama** (default `mistral:latest`), same as every other example.

## Run it

From the examples root:

```bash
./desktop-tidy-windows/run.sh                                # tidy ~/Desktop on any OS
./desktop-tidy-windows/run.sh "$HOME/Desktop"                # macOS / Linux explicit
./desktop-tidy-windows/run.sh "C:/Users/me/Desktop"          # Windows explicit
./desktop-tidy-windows/run.sh "$HOME/Downloads"              # any allowlisted folder
```

The folder you pass must be inside the configured allowlist
(`swarmai.tools.os.filesystem.allowed-roots`, defaults to `~/Desktop`,
`~/Downloads`, `~/Documents` on every OS via `${user.home}` expansion).
