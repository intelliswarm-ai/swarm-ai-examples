# Distributed Pentest Swarm

Distributed penetration testing workflow with parallel per-target agents, self-improving exploitation skills, and reviewer-driven coverage enforcement.

**DISCLAIMER: This example is for educational and CTF (Capture The Flag) use only. It must only be run against systems you own or have explicit written authorization to test. Unauthorized penetration testing is illegal.**

## Architecture

```
                        +---------------------+
                        |  DISCOVERY PHASE    |
                        |  [Pentest Agent]    |
                        |  nmap -sP subnet    |
                        |  Discovers live     |
                        |  hosts on network   |
                        +----------+----------+
                                   |
                     SwarmCoordinator parses
                     discovered IP addresses,
                     fans out per target
                                   |
              +--------------------+--------------------+
              |                    |                    |
              v                    v                    v
   +------------------+ +------------------+ +------------------+
   | TARGET 10.0.0.1  | | TARGET 10.0.0.2  | | TARGET 10.0.0.3  | ...up to 5
   | [Pentest Agent]  | | [Pentest Agent]  | | [Pentest Agent]  |
   | DANGEROUS perms  | | DANGEROUS perms  | | DANGEROUS perms  |
   |                  | |                  | |                  |
   | Phase 1: ENUM    | | Phase 1: ENUM    | | Phase 1: ENUM    |
   |  nmap -sV ports  | |  nmap -sV ports  | |  nmap -sV ports  |
   |                  | |                  | |                  |
   | Phase 2: EXPLOIT | | Phase 2: EXPLOIT | | Phase 2: EXPLOIT |
   |  hydra (creds)   | |  nikto (web)     | |  smbclient (smb) |
   |  nikto (web)     | |  curl (defaults) | |  hydra (ssh)     |
   |  curl (defaults) | |  enum4linux      | |  dig (dns xfer)  |
   |                  | |                  | |                  |
   | Phase 3: REPORT  | | Phase 3: REPORT  | | Phase 3: REPORT  |
   |  Document all    | |  Document all    | |  Document all    |
   |  findings + POC  | |  findings + POC  | |  findings + POC  |
   +--------+---------+ +--------+---------+ +--------+---------+
            |                    |                    |
            +------+-------------+-------------+------+
                   |             |             |
                   v             v             v
         +--------------------------------------------+
         | SHARED SKILL REGISTRY                      |
         | e.g., SMB enumeration skill generated for  |
         | target #1 reused on targets #2 and #3      |
         +--------------------------------------------+
                               |
              +----------------+----------------+
              |                                 |
              v                                 v
   +---------------------+       +---------------------------+
   | REVIEWER             |       | SYNTHESIS                 |
   | [Pentest Lead]       | ----> | [Senior Report Writer]    |
   | Ensures exploitation |       | Combines all per-target   |
   | coverage on every    |       | findings into one report  |
   | discovered service   |       | with remediation guidance |
   | Issues NEXT_COMMANDS |       +---------------------------+
   +---------------------+                    |
                                              v
                               output/pentest_report.md
```

## Parallel Agent Coordination

The SwarmCoordinator manages the full penetration testing lifecycle:

1. **Network Discovery** -- A pentest agent sweeps the target subnet with `nmap -sP` to find live hosts
2. **Fan-Out** -- The coordinator clones one agent per discovered IP address (up to `maxParallelAgents: 5`)
3. **Per-Target Enumeration** -- Each cloned agent runs `nmap -sV` on the top 100 ports to identify running services
4. **Per-Target Exploitation** -- Based on discovered services, agents run targeted attacks: `hydra` for credential brute-forcing, `nikto` for web vulnerabilities, `smbclient`/`enum4linux` for SMB shares, `curl` for default credentials, `dig` for DNS zone transfers
5. **Skill Sharing** -- A CODE skill generated for one target (e.g., an SMB enumeration technique) is immediately available to agents testing other hosts
6. **Reviewer Enforcement** -- A Pentest Lead reviews output and issues `NEXT_COMMANDS` to ensure every open service has been actively tested, not just scanned
7. **Report Synthesis** -- A Senior Report Writer combines all per-target findings into a cohesive penetration test report

## Prerequisites

- Java 21+
- Running Ollama instance (or OpenAI-compatible API)
- Model configured via `OLLAMA_MODEL` (default: `mistral:latest`)
- Security tools installed in the execution environment: `nmap`, `hydra`, `nikto`, `smbclient`, `enum4linux`, `dig`, `curl`
- **Explicit authorization** to test the target network

## Run

```bash
./run.sh pentest-swarm "Scan 192.168.1.0/24 and test all devices for vulnerabilities"
```

## How It Works

The Distributed Pentest Swarm uses `ProcessType.SWARM` to coordinate parallel penetration testing across multiple network targets. Execution begins with a network discovery phase where a pentest agent runs `nmap -sP` against the specified subnet to identify live hosts. The SwarmCoordinator then fans out one cloned agent per discovered IP address, each running with `PermissionLevel.DANGEROUS` to allow shell command execution. Each per-target agent follows a three-phase protocol: enumeration (service version detection via `nmap -sV`), exploitation (protocol-specific attacks using `hydra`, `nikto`, `smbclient`, and `curl`), and documentation (evidence collection with proof-of-concept details). Agents share CODE skills through a shared `SkillRegistry` -- an exploitation technique discovered on one host is instantly available for testing against others. The Pentest Lead reviewer enforces exploitation coverage by issuing `NEXT_COMMANDS` that contain specific attack commands targeting any open service that has only been scanned but not actively tested. A subnet extraction utility parses CIDR notation or bare IPs from the user's query. Quality criteria require that all hosts are enumerated with service versions, exploitation is attempted on every open service, and findings are tagged with `[CONFIRMED]` evidence markers. The final report combines all per-target results into a professional penetration test deliverable.

## Key Code

```java
// Pentest agent with DANGEROUS permissions for shell command execution
Agent pentestAgent = Agent.builder()
    .role("Penetration Testing Specialist")
    .goal("Perform comprehensive security assessment of the assigned target. " +
          "Execute ALL phases: discovery, enumeration, AND exploitation. " +
          "Use nmap for scanning, hydra for credential testing, nikto for web " +
          "vulnerabilities, smbclient/enum4linux for SMB, and curl for web probing.")
    .chatClient(chatClient)
    .tools(healthyTools)
    .verbose(true)
    .temperature(0.2)
    .maxTurns(3)
    .compactionConfig(CompactionConfig.of(3, 4000))
    .permissionMode(PermissionLevel.DANGEROUS)
    .toolHook(metrics.metricsHook())
    .build();
```

## Output

- `output/pentest_report.md` -- professional penetration test report with executive summary, per-host findings, exploitation results, and remediation recommendations
- `output/discovery.txt` -- initial network discovery results
- `output/nmap_<target>.txt` -- per-target port scan results
- Console summary: duration, tasks completed, skills generated, token usage

## Customization

- Change the target subnet by passing a different CIDR range as the argument
- Adjust `maxParallelAgents` to control concurrent target testing (default: 5)
- Raise `maxIterations` for more reviewer-driven exploitation passes
- Add custom tools for protocol-specific testing (e.g., `sqlmap` for SQL injection)
- Modify `qualityCriteria` to require specific vulnerability categories or compliance standards
- Reduce `maxTurns` on the pentest agent for faster but less thorough scans

## YAML DSL

This workflow can also be defined declaratively in YAML. See [`workflows/pentest.yaml`](src/main/resources/workflows/pentest.yaml):

```bash
# Load and run via YAML instead of Java
Swarm swarm = swarmLoader.load("workflows/pentest.yaml",
    Map.of("targetNetwork", "192.168.1.0/24"));
SwarmOutput output = swarm.kickoff(Map.of());
```

The YAML definition includes SWARM process with DANGEROUS permission mode, tool hooks, and distributed scanning.
