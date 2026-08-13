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
├── bin/
│   ├── ai-worklog                      <- Groovy-default runtime dispatcher
│   ├── ai-worklog-groovy               <- Direct Groovy launcher
│   └── ai-worklog-python               <- Direct Python launcher
├── catalog/
│   └── examples.json                 <- Fictional service and delivery examples
├── config/
│   └── workspace-config.example.json <- Java/Groovy and workspace example
├── schemas/
│   ├── catalog-entry.schema.json
│   ├── release-manifest.schema.json
│   ├── ticket-state.schema.json
│   └── workspace-config.schema.json
├── groovy/
│   ├── src/main/groovy/              <- Groovy CLI and command implementation
│   ├── src/test/groovy/              <- Groovy unit tests
│   └── build.gradle
├── python/
│   ├── pyproject.toml                  <- Python package and editable install
│   └── src/ai_worklog_framework/
│       ├── adapters/                   <- Read-only external service adapters
│       ├── catalog/                    <- Catalog and ticket preparation
│       ├── delivery/                   <- Delivery lifecycle reporting
│       ├── diagnostics/                <- Reusable diagnostic packs
│       ├── reports/                    <- Daily and close-out reports
│       ├── state/                      <- Structured ticket state
│       └── toolchain/                  <- Python, Java, and Groovy routing
├── scripts/
│   ├── bootstrap.sh                  <- Safe workspace interface setup
│   └── run-groovy-tool.sh            <- Per-tool Java/Groovy launcher
├── shared/                           <- Cross-runtime rules and defaults
├── tests/
│   ├── parity/                       <- Python/Groovy contract tests
│   └── unit/                         <- Python unit tests
├── .gitignore
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
existing service integrations and stores runtime state under
`<workspace>/.ai-worklog/`.

The Groovy and Python implementations expose the same command tree and consume
the same JSON contracts under `shared/`. Groovy is the operational default;
Python remains a supported fallback and parity reference.

## Requirements

- macOS or Linux
- Groovy 3, 4, or 5
- Java 17 for Groovy 3/4, or Java 17–25 for Groovy 5
- Python 3.10 or newer for the fallback runtime and parity tests
- Git
- Optional tools used by individual adapters:
  - GitHub CLI
  - AWS CLI
  - `kubectl`
  - Argo CD CLI
  - Additional Java or Groovy versions used by workspace tools

## Installation

Clone the repository and put its dispatcher on `PATH`:

```bash
git clone https://github.com/tomasz-wojda/ai-worklog-framework.git
cd ai-worklog-framework
export PATH="$PWD/bin:$PATH"
ai-worklog --version
```

Groovy is the default runtime. The launcher selects Java 17 on macOS when it is
available, which keeps Groovy 3 and 4 compatible. Install the optional Python
fallback and test environment with:

```bash
python3 -m venv .venv
source .venv/bin/activate
python3 -m pip install -e python/
```

Select a runtime explicitly when needed:

```bash
ai-worklog --runtime groovy --version
ai-worklog --runtime python --version
AI_WORKLOG_RUNTIME=python ai-worklog --version
```

Persist the default runtime for future commands:

```bash
ai-worklog config runtime groovy
ai-worklog config runtime python
ai-worklog config runtime
```

Runtime selection uses `--runtime`, then `AI_WORKLOG_RUNTIME`, then the
persisted runtime in `~/.ai-worklog/config.json`, and finally Groovy.

`ai-worklog-groovy` and `ai-worklog-python` are also available for direct
runtime selection. The Python package installs the fallback command as
`ai-worklog-python`; it does not replace the Groovy-default dispatcher.

## Workspace Setup

Workspace initialization creates the runtime directories, seeds configuration,
and links existing service directories under `integrations/`. It never
reads credential contents or overwrites existing targets.

Preview all operations:

```bash
ai-worklog workspace init /absolute/path/to/workspace
```

Create links:

```bash
ai-worklog workspace init /absolute/path/to/workspace --apply
```

Remove links created by the framework:

```bash
ai-worklog workspace revert /absolute/path/to/workspace --apply
```

The legacy `scripts/bootstrap.sh` interface remains available as a compatibility
wrapper. The following service integrations are supported:

```
jira newrelic aws eks jenkins github argocd artifactory ssh snow datadog
```

## Configuration

Workspace initialization creates `.ai-worklog/config.json` when it is absent.

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

### Named Workspaces

Register frequently used workspaces once:

```bash
ai-worklog workspace add work /Users/example/work --default
ai-worklog workspace add test /Users/example/work-test
ai-worklog workspace add personal /Users/example/personal
```

Commands run outside a workspace use the saved default:

```bash
ai-worklog jenkins controllers
ai-worklog preflight
```

Select a registered workspace for one command:

```bash
ai-worklog -w test jenkins controllers
ai-worklog --workspace-name personal preflight
```

Use an unregistered path for one command:

```bash
ai-worklog --workspace /some/path preflight
```

Inspect and change registrations:

```bash
ai-worklog workspace list
ai-worklog workspace show test
ai-worklog workspace current
ai-worklog workspace default test
ai-worklog workspace remove personal
ai-worklog config show
```

Removing a registration never deletes its directory. Global preferences are
stored in `~/.ai-worklog/config.json`; workspace-specific configuration remains
under `<workspace>/.ai-worklog/`. The global directory and file use private
permissions and must not contain service credentials.

Workspace selection uses direct `--workspace`, then `-w` or
`--workspace-name`, then `AI_WORKLOG_WORKSPACE`, then
`AI_WORKLOG_WORKSPACE_NAME`, then current-directory discovery, and finally the
saved default. Missing registered paths and malformed global configuration are
reported instead of silently selecting another workspace.

## Python, Java, and Groovy Toolchains

The framework does not force one global `JAVA_HOME`. It resolves a runtime for
each tool.

| Tool | Java | Groovy |
|------|------|--------|
| `jira-cli` | 17 | 3+ |
| `newrelic-cli` | 17 | 3+ |
| `jenkins-syntax-check` | 17 | Not required |
| `gradle-java25` | 25 | Not required |
| `framework-groovy` | 17 | 3+ |

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

The bundled catalog contains fictional examples only. Store organization-specific
entries in the workspace-local `.ai-worklog/catalog/` overlay. Workspace
initialization protects the complete `.ai-worklog/` directory with a local
ignore file so configuration, state, catalog overlays, and evidence are not
committed accidentally.

Secret references may contain names or paths only. Actual values are rejected.

### Ticket Preparation

```bash
ai-worklog ticket prepare PROJ-1234
```

The preparation report discovers active and archived worklogs, catalog matches,
local repositories, relevant pull requests, known delivery paths, readiness,
and preparation gaps.

### Structured Ticket State

```bash
ai-worklog state init PROJ-1234 --service example-eks-platform
ai-worklog state init PROJ-1234 --service example-eks-platform --apply
ai-worklog state set PROJ-1234 --path implementation.state --value in_progress
ai-worklog state set PROJ-1234 --path implementation.state --value in_progress --apply
ai-worklog state blocker add PROJ-1234 --description "Waiting for access" --apply
ai-worklog state show PROJ-1234
```

State writes are dry-runs unless `--apply` is present. Values may be strings or
JSON literals, and every update is validated before an atomic file replacement.

### Read-only Reconciliation

```bash
ai-worklog reconcile status PROJ-1234
ai-worklog reconcile status PROJ-1234 --system jenkins --json
```

Reconciliation compares structured ticket state with Jira, Git, GitHub,
Jenkins, Argo CD, and Tempo without modifying local or external state.

### Jenkins Operator

```bash
ai-worklog jenkins controllers
ai-worklog jenkins health primary
ai-worklog jenkins job primary folder/job --builds 5 --parameters
ai-worklog jenkins plugins primary --require workflow-job
ai-worklog jenkins credentials primary --domain _
ai-worklog jenkins seed primary seed-job
ai-worklog jenkins syntax-check Jenkinsfile
```

Jenkins operations are read-only. Credential output is limited to identifiers
and descriptive metadata, build parameters omit values, and syntax validation
delegates to the configured `ai-vault` validator.

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
ai-worklog diag run k8s-workload --namespace example --app example-worker
```

Registered packs cover Kubernetes workloads, OOM investigations, Argo CD sync,
New Relic telemetry, Jenkins builds, host parity, and Automox policy evidence.
Pack execution is read-only. Each run validates prerequisites and parameters,
redacts captured output, and writes an evidence bundle under
`.ai-worklog/evidence/`. Generic parameters use `--param key=value`.

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
python3 -m pip install -e python/
python3 -m pip install pytest
```

Run the test suite:

```bash
python3 -m pytest -c python/pyproject.toml tests/ -q
JAVA_HOME=$(/usr/libexec/java_home -v 17) gradle -p groovy test
```

The parity suite invokes both launchers and compares stable output, JSON
payloads, report semantics, and exit codes.

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

Apache License 2.0. See [LICENSE](LICENSE).
