# AI Worklog Framework - DevOps Operator Layer

`ai-worklog-framework` is the executable operator layer for the daily DevOps
workflow. It complements `ai-vault`, which owns agent protocols and behavioral
rules.

The framework provides read-only workspace preparation, environment preflight,
service discovery, ticket state, delivery tracking, diagnostics, and close-out
reports. Credentials, worklogs, cloned repositories, and generated state remain
outside this repository.

```
ai-worklog-framework/
├── catalog/
│   └── services.json                 <- Versioned service and delivery metadata
├── config/
│   └── workspace-config.example.json <- Java/Groovy and workspace example
├── schemas/
│   ├── catalog-entry.schema.json
│   ├── release-manifest.schema.json
│   ├── ticket-state.schema.json
│   └── workspace-config.schema.json
├── scripts/
│   ├── bootstrap.sh                  <- Safe workspace interface setup
│   └── run-groovy-tool.sh            <- Per-tool Java/Groovy launcher
├── src/ai_worklog_framework/
│   ├── adapters/                     <- Read-only external service adapters
│   ├── catalog/                      <- Catalog and ticket preparation
│   ├── delivery/                     <- Delivery lifecycle reporting
│   ├── diagnostics/                  <- Reusable diagnostic packs
│   ├── reports/                      <- Daily and close-out reports
│   ├── state/                        <- Structured ticket state
│   ├── toolchain/                    <- Python, Java, and Groovy routing
│   ├── cli.py
│   ├── config.py
│   ├── paths.py
│   ├── redaction.py
│   └── result.py
├── tests/
├── .gitignore
├── pyproject.toml
└── README.md
```

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│ ai-vault                                                 │
│ Protocols, modes, routines, worklog content rules        │
├──────────────────────────────────────────────────────────┤
│ ai-worklog-framework                                     │
│ CLI, catalog, preflight, state, diagnostics, reports     │
├──────────────────────────────────────────────────────────┤
│ Runtime workspace                                        │
│ Credentials, worklogs, repos, sessions, generated state  │
└──────────────────────────────────────────────────────────┘
```

The runtime workspace is not versioned by this repository. The framework reads
existing service interfaces and stores runtime state under
`<workspace>/.ai-worklog/`.

## Requirements

- macOS or Linux
- Python 3.10 or newer
- Git
- Optional tools used by individual adapters:
  - GitHub CLI
  - AWS CLI
  - `kubectl`
  - Argo CD CLI
  - Java 17 and/or Java 25
  - Groovy 3, 4, or 5

## Installation

Create a virtual environment and install the package:

```bash
git clone https://github.com/tomasz-wojda/ai-worklog-framework.git
cd ai-worklog-framework
python3 -m venv .venv
source .venv/bin/activate
python3 -m pip install -e .
```

Verify the CLI:

```bash
ai-worklog --version
```

To use the command without manually activating the virtual environment, add
`<repository>/.venv/bin` to `PATH`.

## Workspace Setup

The bootstrap script creates service links under
`worklog/interface/`. It never reads credential contents and refuses to
overwrite existing targets.

Preview all operations:

```bash
./scripts/bootstrap.sh /absolute/path/to/workspace --dry-run
```

Create links:

```bash
./scripts/bootstrap.sh /absolute/path/to/workspace --link
```

Remove links created by the framework:

```bash
./scripts/bootstrap.sh /absolute/path/to/workspace --revert
```

The following service interfaces are supported:

```
jira newrelic aws eks jenkins github argocd artifactory ssh snow datadog
```

## Configuration

Create a workspace-local configuration:

```bash
mkdir -p /absolute/path/to/workspace/.ai-worklog
cp config/workspace-config.example.json \
  /absolute/path/to/workspace/.ai-worklog/config.json
```

Configuration is loaded in this order:

1. Framework defaults
2. `<workspace>/.ai-worklog/config.json`
3. `<workspace>/.ai-worklog/local.json`

Later files override earlier files. Use `local.json` for machine-specific paths.
Do not store credentials in either file.

The workspace can be selected with:

```bash
ai-worklog --workspace /absolute/path/to/workspace preflight
```

or:

```bash
export AI_WORKLOG_WORKSPACE=/absolute/path/to/workspace
```

## Python, Java, and Groovy Toolchains

The framework does not force one global `JAVA_HOME`. It resolves a runtime for
each tool.

| Tool | Java | Groovy |
|------|------|--------|
| `jira-cli` | 17 | 3+ |
| `newrelic-cli` | 17 | 3+ |
| `jenkins-syntax-check` | 17 | Not required |
| `gradle-java25` | 25 | Not required |

Compatibility policy:

| Groovy | Supported Java range |
|--------|----------------------|
| 3 | 8–17 |
| 4 | 8–21 |
| 5 | 17–25 |

Detect installed runtimes and validate mappings:

```bash
ai-worklog toolchain check
ai-worklog toolchain list
```

Print shell exports for a specific tool:

```bash
ai-worklog toolchain env jira-cli
```

Run a mapped Groovy tool:

```bash
./scripts/run-groovy-tool.sh jira-cli summary
./scripts/run-groovy-tool.sh newrelic-cli violations
```

## Commands

### Environment Preflight

```bash
ai-worklog preflight
ai-worklog preflight --service jira jenkins
ai-worklog preflight --ticket PROJ-1234
```

Preflight reports workspace structure, required binaries, authentication
presence, Git identity, AWS identity, Kubernetes context, ServiceNow cookie age,
and toolchain compatibility. It does not refresh credentials or modify
configuration.

### Service Catalog

```bash
ai-worklog catalog validate
ai-worklog catalog show example-eks-platform
ai-worklog catalog search example
```

Catalog entries can model repositories, owners, Jenkins jobs, Argo CD
applications, environments, build artifacts, secret references, monitoring
entities, and delivery paths.

Secret references may contain names or paths only. Actual values are rejected.

### Ticket Preparation

```bash
ai-worklog ticket prepare PROJ-1234
```

The preparation report discovers active and archived worklogs, catalog matches,
local repositories, relevant pull requests, known delivery paths, readiness,
and preparation gaps.

### Daily Routines

```bash
ai-worklog day start
ai-worklog day end
```

Day Start summarizes active ticket state and worklogs. Day End reports
uncommitted work, blockers, next actions, and a continuation capsule.

### Delivery and Close-out

```bash
ai-worklog delivery status PROJ-1234
ai-worklog closeout report PROJ-1234
```

Delivery reporting distinguishes investigation, local implementation, pull
requests, builds, GitOps, synchronization, and live verification. Close-out
reports include delivery evidence, unresolved items, Tempo status, and worklogs
eligible for archival.

### Diagnostic Packs

```bash
ai-worklog diag list
ai-worklog diag run k8s-workload --namespace example --app example
```

Registered packs cover Kubernetes workloads, OOM investigations, Argo CD sync,
New Relic telemetry, Jenkins builds, host parity, and Automox policy evidence.
Pack execution is read-only; packs that are not yet implemented report their
stub status explicitly.

## Runtime State

Structured ticket state is stored outside the repository:

```
<workspace>/.ai-worklog/state/<TICKET-KEY>.json
```

State dimensions are independent:

- Governance mode
- Investigation
- Local implementation
- Pull requests
- Builds
- GitOps
- Synchronization
- Live verification
- Tempo and administrative close-out
- Decisions, blockers, and next action

No external system is silently treated as the single source of truth.
Contradictions should be reported for review.

## Safety Boundaries

- External operations are read-only by default.
- The framework never commits or pushes.
- The framework never refreshes credentials.
- The framework never mutates kubeconfig.
- Runtime secrets and session files are excluded by `.gitignore`.
- Generated reports pass through redaction helpers.
- External writes remain governed by the `ai-vault` PLAN/EXECUTE and Write Gate
  protocols.

## Validation

Install test dependencies in the virtual environment:

```bash
source .venv/bin/activate
python3 -m pip install pytest
```

Run the test suite:

```bash
python3 -m pytest tests/ -q
```

Validate the live workspace without changing it:

```bash
./scripts/bootstrap.sh /absolute/path/to/workspace --dry-run
ai-worklog --workspace /absolute/path/to/workspace preflight
ai-worklog --workspace /absolute/path/to/workspace catalog validate
```

## Relationship to AI Vault

`ai-vault` owns:

- RESEARCH, INNOVATE, PLAN, and EXECUTE governance
- Daily DevOps routines
- Worklog structure and content rules
- Jenkins scripted-pipeline guidance

`ai-worklog-framework` owns:

- Executable CLI behavior
- Workspace discovery and setup
- Service and delivery metadata
- Toolchain resolution
- Read-only service adapters
- Ticket state and reports

Changes to commands or state schemas may require matching updates to the
`ai-vault` skills and cross-skill integration contracts.

## License

MIT. See [LICENSE](LICENSE).
