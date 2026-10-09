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
├── docs/
│   ├── operators/                    <- One guide per service operator
│   └── upgrade-plan                  <- Roadmap and known limitations
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
- Groovy with a compatible system JVM
- Git
- Python 3.9 or newer for the AI Vault rules installer and hooks; Python 3.10
  or newer for the Python fallback runtime and parity tests
- Gradle, optional, to precompile the CLI for faster startup
- Optional tools used by individual adapters:
  - GitHub CLI
  - AWS CLI
  - `kubectl`
  - Argo CD CLI
  - Additional Java or Groovy versions used by workspace tools

## Installation

### Quick start

The recommended layout keeps both repositories inside the workspace, where
`ai-worklog` finds the AI vault without extra configuration:

```
~/workspace/
└── repos/
    ├── ai-vault/
    └── ai-worklog-framework/
```

```bash
# 1. create the workspace folder
mkdir ~/workspace

# 2. create the repos folder inside it
mkdir ~/workspace/repos

# 3. clone AI Vault and the framework into repos/
git clone https://github.com/tomasz-wojda/ai-vault.git ~/workspace/repos/ai-vault
git clone https://github.com/tomasz-wojda/ai-worklog-framework.git ~/workspace/repos/ai-worklog-framework

# 4. put the ai-worklog command on PATH with a symlink
mkdir -p ~/.local/bin
ln -s ~/workspace/repos/ai-worklog-framework/bin/ai-worklog ~/.local/bin/ai-worklog
ai-worklog --version
#    if the command is not found, add ~/.local/bin to PATH once:
#    echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.zshrc && source ~/.zshrc

# 5. optional: precompile for faster startup (about a quarter second instead of several)
gradle -p ~/workspace/repos/ai-worklog-framework/groovy classes

# 6. set up the workspace from inside it
cd ~/workspace
ai-worklog workspace apply --dry-run
ai-worklog workspace apply

# 7. confirm, then see which integrations still need credentials
ai-worklog workspace check
ai-worklog preflight
```

Step 4 can use any directory already on `PATH`; the launcher follows the
symlink back to the repository. Step 5 needs `GROOVY_HOME` or a Groovy
installation at `/opt/groovy/current`, and must be repeated after pulling
framework changes; without it the launcher runs the sources directly. Step 6
materializes AI Vault skills for the detected IDEs, installs the AI Vault
workspace rules (`.rules`, `AGENTS.md`, `CLAUDE.md`, and `.cursor/rules/`),
creates `integrations/`, and registers the workspace with the vault it found in
`repos/ai-vault`. A new workspace reports preflight issues until credentials are
placed under `integrations/<service>/`.

Cursor users also install the machine-wide Cursor hooks once, as described in
AI Vault's README (`scripts/install-cursor-harness.py --scope user`).

To update later, pull both repositories, repeat step 5, and rerun
`ai-worklog workspace apply`; it only adds what is missing.

### Runtimes

Groovy is the default runtime. The launcher uses the active system JVM selected
by the Groovy installation. Install the optional Python fallback and test
environment with:

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

```bash
# 1. once per machine: point the framework at your AI vault
ai-worklog config set-ai-vault-root /path/to/ai-vault

# 2. from inside the directory you want to become a workspace
cd /path/to/workspace
ai-worklog workspace apply

# 3. confirm it came up clean
ai-worklog workspace check

# 4. see which integrations still need credentials
ai-worklog preflight
```

Step 1 is needed only when the vault is not at `<workspace>/repos/ai-vault`, the
location `apply` checks by default; without either, `apply` stops with
`AI vault not found`. Pass `--ai-vault PATH` to `apply` instead to supply it per
invocation. Step 4 lists every integration as not configured until credentials
are placed under `integrations/<service>/`, which the framework never does for
you.

`ai-worklog workspace apply` is the single entry point. It creates the runtime
directories, seeds configuration, creates the service directories under
`integrations/`, materializes AI Vault skills into the detected IDE profiles,
installs the AI Vault workspace rules, and registers the workspace in the global
configuration. It accepts either a
registered workspace short name (`work`) or a directory path, and with no
argument it targets the current directory exactly. It never reads credential
contents and never overwrites existing targets.

Workspace rules come from the vault's `scripts/install-cursor-harness.py
--workspace-rules`, run with `python3` (override with `AI_WORKLOG_PYTHON`): the
workspace `.rules` and `AGENTS.md` link to the vault `.rules`, the vault's
always-apply Cursor rules are linked into `.cursor/rules/`, and a managed
`CLAUDE.md` imports them for Claude Code. `--dry-run` reports pending rule files
without writing them, and `workspace check` reports a `rules` layer. An existing
unrelated `AGENTS.md`, `.rules`, or `CLAUDE.md` is never replaced; `apply` then
reports `degraded` with the reason. Cursor hooks stay a separate, machine-wide
installer step documented in AI Vault. The Python runtime does not install rules.

`apply` converges: running it on a fresh directory creates the workspace, running
it on an existing one fills in whatever is missing, and running it twice in a row
changes nothing the second time.

Apply to a named or explicit target:

```bash
ai-worklog workspace apply work
# or using an absolute directory path:
ai-worklog workspace apply /absolute/path/to/workspace
```

Preview without changing anything:

```bash
ai-worklog workspace apply work --dry-run
```

Remove what the framework created and unregister the workspace:

```bash
ai-worklog workspace revert work
```

`revert` removes only framework-created artifacts that are still empty; service
directories holding your files, and the `worklog/`, `repos/`, and `tmp/` trees,
are always left alone. Scope it to one IDE with `--ide`, which leaves the
workspace registered.

The legacy `scripts/bootstrap.sh` interface remains available as a compatibility
wrapper. The following service integrations are supported:

```
jira newrelic aws eks jenkins github argocd artifactory automox ssh snow datadog
```

### Inspecting a Workspace

```bash
ai-worklog workspace check
ai-worklog workspace show
ai-worklog workspace apply
```

`check` validates every layer and exits non-zero when a layer is blocked. `show`
reports the current state without validating. `apply` re-materializes managed
artifacts that have drifted.

### IDE Profiles

```bash
ai-worklog workspace ides
ai-worklog workspace ides cursor claude
ai-worklog workspace ides auto
```

With no arguments the registered profiles are displayed. Supplying one or more of
`cursor`, `claude`, or `antigravity` replaces the registered set, and `auto`
re-detects. This updates the registry only; run `ai-worklog workspace apply`
afterwards to materialize the change.

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
ai-worklog workspace apply work /Users/example/work --default
ai-worklog workspace apply test /Users/example/work-test
ai-worklog workspace apply personal /Users/example/personal
```

Commands run outside a workspace use the saved default:

```bash
ai-worklog service jenkins controllers
ai-worklog preflight
```

Select a registered workspace for one command:

```bash
ai-worklog -w test service jenkins controllers
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
ai-worklog workspace revert personal
ai-worklog config show
```

Reverting a registration never deletes user content. Global preferences are
stored in `~/.ai-worklog/config.json`; workspace-specific configuration remains
under `<workspace>/.ai-worklog/`. The global directory and file use private
permissions and must not contain service credentials.

The two command groups divide by scope. `config` owns machine-wide settings that
have a single value: the runtime and the AI Vault root. `workspace` owns
everything belonging to a specific workspace, including its lifecycle, IDE
profiles, and registry entry. `config show` marks whether the configured runtime
is actually present:

```
  runtime: groovy [available]
```

Workspace selection uses direct `--workspace`, then `-w` or
`--workspace-name`, then `AI_WORKLOG_WORKSPACE`, then
`AI_WORKLOG_WORKSPACE_NAME`, then current-directory discovery, and finally the
saved default. Missing registered paths and malformed global configuration are
reported instead of silently selecting another workspace.

## Python, Java, and Groovy Toolchains

The framework, workspace Groovy tools, and Gradle use the active system JVM.
Runtime inventory is available as an explicit diagnostic and is not part of
workspace preflight or setup readiness.

`gradle -p groovy classes` (also run by `gradle -p groovy test`) builds
`groovy/build/libs/ai-worklog.jar`, a launcher classpath limited to the Groovy
modules the CLI uses, and a JDK AOT cache trained on common commands. The
launcher uses them while they are newer than every source file and all
classpath entries exist; otherwise it falls back to running the sources with
`groovy`. A cache from another JDK or build is ignored silently. Rebuild after
changing the JDK or Groovy installation.

```bash
ai-worklog toolchain check
ai-worklog toolchain list
```

Run a workspace Groovy tool:

```bash
ai-worklog service jira summary
ai-worklog service newrelic violations
```

## Commands

### CLI Help and Introspection

Help is available at the root, command, and action levels:

```bash
ai-worklog --help
ai-worklog service --help
ai-worklog service jenkins --help
ai-worklog service jenkins artifacts --help
ai-worklog help service jenkins artifacts
ai-worklog help service jenkins artifacts --json
```

Explicit help is written to standard output and exits successfully. Invalid
usage and error messages are written to standard error, while human and JSON
operation reports remain on standard output.

The Jenkins command uses strict positional order and rejects unknown,
repeated, or surplus arguments. Options may appear between positionals. Use
`--` to stop option parsing when a positional value begins with a hyphen.
`help ... --json` emits the versioned command contract used for parsing and is
the machine-readable surface intended for future Groovy/Python parity checks.

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

### Catalog

```bash
ai-worklog catalog list
ai-worklog catalog list --json
ai-worklog catalog validate
ai-worklog catalog show example-eks-platform
ai-worklog catalog search example
```

The catalog contains logical systems and their delivery relationships. Catalog
entries can model repositories, owners, Jenkins jobs, Argo CD
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

### Service Operators

```bash
ai-worklog service list
ai-worklog service list --json
```

Service operators connect the CLI to external systems. They are separate from
catalog systems, which describe logical applications, platforms, and delivery
relationships.

| Service | Commands | Guide |
| --- | --- | --- |
| Jira, Tempo, and Jira Assets | `ai-worklog service jira` | [docs/operators/jira.md](docs/operators/jira.md) |
| Jenkins | `ai-worklog service jenkins` | [docs/operators/jenkins.md](docs/operators/jenkins.md) |
| Automox | `ai-worklog service automox` | [docs/operators/automox.md](docs/operators/automox.md) |
| New Relic | `ai-worklog service newrelic` | [docs/operators/newrelic.md](docs/operators/newrelic.md) |
| Artifactory | `ai-worklog service artifactory` | [docs/operators/artifactory.md](docs/operators/artifactory.md) |

Run `ai-worklog help --json service <name> <action>` for the exact positionals,
options, and validation of any action.

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
gradle -p groovy test
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
