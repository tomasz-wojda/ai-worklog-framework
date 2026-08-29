# Command Surface Audit

Verification status of every `ai-worklog` command, subcommand, flag, and option.

| Field | Value |
| --- | --- |
| Audit date | 2026-08-28 |
| Revised | 2026-08-29 (corrected the `jenkins syntax-check` status) |
| Repository commit | `e881e43` (`refactor(toolchain): remove Java 17 prerequisite`) |
| Framework version | 0.7.0 |
| Groovy runtime | Groovy 6.0.0-beta-2 on OpenJDK 26.0.2.1 (Homebrew) |
| Python runtime | CPython 3.15.0rc1 |
| Target workspace | registered name `work` -> `/Users/twojda/workspace` |
| Method | Live execution of every command group on both runtimes, plus source review of `python/src/ai_worklog_framework/cli.py` and `groovy/src/main/groovy/ai/worklog/framework/Main.groovy` |

## Summary

All 14 command groups are implemented in both runtimes and dispatch correctly.
No command is a stub. The gaps are not missing commands; they are missing
configuration, inconsistent flag handling, unimplemented approved plans, and an
absent live-verification layer.

Highest-impact findings, in order:

1. Twelve of the fourteen `jenkins` subcommands are complete but blocked, because
   the workspace controller definition carries no endpoint or credentials.
2. `--json` is honored by only five command groups, and the Groovy runtime
   silently ignores unrecognized flags instead of rejecting them.
3. Two approved plans in the repository root are unimplemented, including the
   entire Automox command group.
4. There is no CI, and neither test suite can run in the current environment.
5. Delivery, close-out, and ticket preparation read local state only; live
   system observation exists solely in `reconcile status`.

## Status legend

| Mark | Meaning |
| --- | --- |
| `OK` | Executed during the audit and produced correct output |
| `WIRED` | Code path reached and returns exit 3 (blocked) because local configuration or credentials are absent |
| `UNVERIFIED` | Implemented in both runtimes but not executed, because the operation mutates or removes state |
| `GAP` | Not present in the codebase |

Exit codes are stable across both runtimes and are sourced from
`shared/exit-codes.json`:

| Code | Meaning |
| --- | --- |
| 0 | Success |
| 1 | User error |
| 2 | System or adapter error |
| 3 | Blocked |

## Command tree

```
ai-worklog                                                          OK  v0.7.0, both runtimes
├── --version                                                       OK
├── -h | --help                                                     OK  top level only
├── --runtime groovy|python                                         OK  must precede the command
├── --workspace PATH                                                OK
├── -w NAME | --workspace-name NAME                                 OK
└── env: AI_WORKLOG_RUNTIME, AI_WORKLOG_WORKSPACE,                  OK
         AI_WORKLOG_WORKSPACE_NAME, AI_WORKLOG_HOME,
         AI_WORKLOG_FRAMEWORK_ROOT

config                                                              OK
├── show                    --json                                  OK
├── runtime [groovy|python] --json                                  OK  read and persist
└── set-ai-vault-root PATH  --json                                  UNVERIFIED

setup                                                               OK
├── init [name] [path]      --ide auto|cursor|claude|antigravity*    OK  29 actions planned
│                           --runtime  --ai-vault  --default
│                           --adopt  --json  --apply
├── check                   --json                                  OK  exit 3 outside a workspace
├── show                    --json                                  OK
├── repair                  --ide*  --json  --apply                 OK  dry-run clean, 29 actions
└── revert                  --ide cursor|claude|antigravity*        OK  dry-run, 17 actions
                            --json  --apply

workspace                                                           OK
├── init [name|path]        --apply                                 OK  idempotent, skips existing
├── revert [name|path]      --apply                                 OK  only unlinks managed links
├── add NAME PATH           --default  --json                       UNVERIFIED
├── list                    --json                                  OK
├── show NAME               --json                                  OK
├── default [NAME]          --json                                  OK
├── current                 --json                                  OK  reports resolution source
└── remove NAME             --json                                  UNVERIFIED

catalog                                                             OK  3 fictional entries
├── validate                                                        OK  PASS (3 entries)
├── show SERVICE                                                    OK  always JSON; lists valid ids on miss
└── search QUERY                                                    OK

ticket                                                              OK
└── prepare KEY                                                     OK  local worklogs, repos, PRs via gh

state                                                               OK
├── list                                                            OK
├── show KEY                                                        OK
├── init KEY                --summary  --service*                   OK  dry-run without --apply
│                           --governance-mode research|innovate|
│                             plan|execute
│                           --apply
├── set KEY                 --path  --value  --apply                OK  validated before atomic write
├── blocker
│   ├── add KEY             --description  --owner  --apply         OK
│   └── resolve KEY         --index N  --apply                      UNVERIFIED
└── decision
    ├── add KEY             --id  --description  --owner  --apply   OK
    └── resolve KEY         --id  --resolution  --apply             UNVERIFIED

preflight                   --service S...  --ticket KEY            OK  ticket scoping narrows checks

day                                                                 OK
├── start                                                           OK
└── end                                                             OK

delivery                                                            OK
└── status KEY                                                      OK  reads local state only

closeout                                                            OK
└── report KEY                                                      OK  reads local state only

reconcile                                                           OK
└── status KEY              --system NAME*  --json                  OK  6 systems

diag                                                                OK
├── list                                                            OK  7 packs registered
└── run PACK                --namespace  --app  --service           OK
                            --param k=v*  --output  --json
    ├── k8s-workload                                                OK  executed, status degraded
    ├── k8s-oom                                                     WIRED
    ├── argocd-sync                                                 WIRED
    ├── jenkins-build                                               WIRED
    ├── nr-telemetry                                                WIRED
    ├── host-parity                                                 WIRED
    └── automox-policy                                              WIRED

toolchain                                                           OK
├── check                                                           OK  python3, java:26, groovy
└── list                                                            OK

jenkins                                                             OK dispatch, 2 of 14 verified OK
├── controllers                        --json                       OK  1 controller, id=jenkins
├── health [ctrl]                      --json                       WIRED
├── whoami [ctrl]                      --json                       WIRED
├── nodes [ctrl]                       --json                       WIRED
├── queue [ctrl]           --limit N   --json                       WIRED
├── jobs [ctrl]            --folder --query --limit  --json         WIRED
├── job [ctrl] [job]       --builds N --parameters  --json          WIRED
├── views [ctrl]           --view NAME --json                       WIRED
├── plugins [ctrl]         --require ID*  --json                    WIRED
├── credentials [ctrl]     --domain D    --json                     WIRED
├── credential-domains [ctrl]           --json                      WIRED
├── artifacts [ctrl] [job] [selector]   --json                      WIRED
├── seed [ctrl] [job]                   --json                      WIRED
└── syntax-check FILE...                --json                      OK  local ai-vault validator, no controller needed

automox                                                             GAP  approved plan, not implemented
```

An asterisk after a flag means the flag is repeatable.

## Command reference

| Command | Purpose |
| --- | --- |
| `config` | Read and persist global preferences in `~/.ai-worklog/config.json`: default runtime, AI vault root, workspace registry |
| `setup` | Plan, apply, inspect, repair, and revert workspace and per-IDE skill materialization |
| `workspace` | Register, resolve, and inspect named workspaces; create and remove managed integration links |
| `catalog` | Validate, display, and search service catalog entries, including workspace-local overlays |
| `ticket` | Generate a preparation report from local worklogs, cloned repositories, catalog matches, and open pull requests |
| `state` | Create and mutate structured per-ticket state, including blockers and decisions, with validation and atomic writes |
| `preflight` | Report local environment readiness: workspace structure, binaries, credentials presence, Git and AWS identity, Kubernetes context |
| `day` | Day Start and Day End routines over active ticket state and worklogs |
| `delivery` | Render the delivery lifecycle position for a ticket from recorded state |
| `closeout` | Produce a close-out and handover report from recorded state |
| `reconcile` | Compare structured ticket state against Jira, Git, GitHub, Jenkins, ArgoCD, and Tempo, read-only |
| `diag` | List and execute declarative read-only diagnostic packs, writing redacted evidence bundles |
| `toolchain` | Report detected Python, Java, and Groovy runtimes |
| `jenkins` | Read-only Jenkins operator surface across controllers, jobs, builds, nodes, queue, views, plugins, credentials, and pipeline syntax |

## Findings

### 1. The Jenkins operator is complete but unconfigured

`jenkins controllers` returns a single controller with no endpoint or
credentials:

```
- {'id': 'jenkins', 'url': '', 'has_user': False, 'has_token': False}
```

Every remaining `jenkins` subcommand except `syntax-check` reaches its adapter
and correctly returns exit 3 with status `blocked`. `syntax-check` is the one
subcommand independent of controller configuration, because it validates
pipeline files locally rather than calling a controller. The implementation is
not the gap; the workspace integration at `integrations/jenkins/` supplies no
URL, user, or token. This is the largest command group in the tree, so
populating that integration unblocks more surface area than any other single
change.

`syntax-check` carries its own caveat. It succeeds only because `JAVA_HOME` is
exported in the calling environment, `/opt/java/openjdk-26` on the audited
machine. With `JAVA_HOME` unset, the delegated script fails with `JDK 26 or
lower is required`, because its fallback resolution relies on
`/usr/libexec/java_home`, which is non-functional on this machine, and on
Linux-only `/usr/lib/jvm` globs. This is a property of the `ai-vault` script
`skills/jenkins-pipeline-architect/scripts/syntax_check.sh`, not of framework
code, but it means the subcommand can fail in any context that does not inherit
`JAVA_HOME`.

A secondary observation: `jenkins controllers` and `jenkins syntax-check` both
render the raw Groovy map rather than formatted fields, unlike every other
human-readable output in the codebase.

### 2. Flag handling is inconsistent, and Groovy accepts unknown flags

`--json` is implemented for `config`, `setup`, `workspace`, `reconcile status`,
and `diag run`. It is not implemented for `state`, `day`, `toolchain`,
`diag list`, `preflight`, `ticket`, `delivery`, or `closeout`.

The more serious issue is that the Groovy runtime discards unrecognized flags
instead of rejecting them. Both of the following ran to completion, exited
normally, and produced the ordinary human-readable report:

```bash
ai-worklog -w work preflight --bogus-flag
ai-worklog -w work preflight --service jira --output /tmp/pf.json
```

No file was written to `/tmp/pf.json`, and no diagnostic was emitted. The Python
runtime uses `argparse` and would reject both. This is a genuine cross-runtime
behavioral divergence that the parity suite does not currently catch, and it
means a mistyped flag silently produces a result the operator did not ask for.

Related: the Groovy runtime supports `-h` and `--help` only at the top level.
`ai-worklog catalog --help` prints the `catalog` usage line and exits 1, whereas
Python's `argparse` renders per-subcommand help and exits 0.

### 3. Two approved plans are unimplemented

Both `automox-module-plan` and `runtime-deferral-plan` in the repository root
declare `Status: approved, not yet implemented` in their own headers.

For Automox, the supporting contracts are already in place: `automox` is
registered in `shared/workspace-init.json` and `shared/preflight-checks.json`,
the workspace has an `integrations/automox` link, and the `automox-policy`
diagnostic pack is registered and listed by `diag list`. What does not exist is
the command group. `ai-worklog automox` falls through to the usage banner and
exits 1, identically to any unrecognized command.

The runtime deferral plan would gate the Python runtime behind an explicit
configuration lever, un-ship the four Windows launchers, add a parity-suite
skip, and freeze the supported surface for 1.0.0. None of it is present.

### 4. No CI, and no runnable test suite in this environment

There is no `.github/` directory, so no pipeline enforces any baseline. The test
code itself is substantial: 415 Python test functions across `tests/unit/` and
`tests/parity/`, and 118 Groovy test methods under
`groovy/src/test/groovy/`. None of it can execute here:

| Prerequisite | State |
| --- | --- |
| `pytest` | Not installed |
| `gradle` | Not on `PATH` |
| `groovy/gradlew` | Absent, so there is no wrapper fallback |
| `.venv` | Absent |
| `ai_worklog_framework` package | Not importable without `PYTHONPATH=python/src` |

The most recent recorded baseline in `upgrade-plan` is from Windows on
2026-08-13 and still carries 9 unit and 15 parity failures.

### 5. External state is read from local records, not observed live

Per `upgrade-plan`, Phase 8 (Delivery Orchestration and Live Verification) is
`NOT STARTED` and Phase 10 (Consolidation and Migration) is `NOT STARTED`.

Consequently `delivery status`, `closeout report`, and `ticket prepare` render
recorded state and local filesystem discovery. They do not query Jira, Jenkins,
or ArgoCD to determine where a change actually is. `reconcile status` is the only
command performing live cross-system comparison.

### 6. Workspace resolution matches the framework repository itself

Run from inside the repository without `-w`, resolution selects the repository
directory:

```
Workspace: /Users/twojda/workspace/repos/ai-worklog-framework
Source: cwd_marker
```

That directory contains no `.ai-worklog/`. This is why bare `setup check`
returns exit 3 while `-w work setup check` succeeds. Whether the marker set in
`shared/workspace-markers.json` is intended to match the framework repository
itself is worth confirming.

## Verification commands

The sweep used the following invocations. All are read-only; every `state` and
`setup` mutation was run without `--apply` and therefore reported a plan only.

```bash
ai-worklog --version
ai-worklog --runtime python --version

ai-worklog config show
ai-worklog config runtime

ai-worklog -w work workspace current
ai-worklog -w work workspace list
ai-worklog -w work workspace show work
ai-worklog -w work workspace default
ai-worklog -w work workspace init work
ai-worklog -w work workspace revert work

ai-worklog -w work setup check
ai-worklog -w work setup show
ai-worklog -w work setup init work
ai-worklog -w work setup repair
ai-worklog -w work setup revert

ai-worklog -w work catalog validate
ai-worklog -w work catalog search example
ai-worklog -w work catalog show example-eks-platform
ai-worklog -w work catalog show nope-missing

ai-worklog -w work ticket prepare KD-7289

ai-worklog -w work state list
ai-worklog -w work state show KD-7289
ai-worklog -w work state init ZZZ-1 --summary test --service example-eks-platform
ai-worklog -w work state set KD-7289 --path implementation.state --value in_progress
ai-worklog -w work state blocker add KD-7289 --description "probe"
ai-worklog -w work state decision add KD-7289 --id d1 --description "probe"

ai-worklog -w work preflight
ai-worklog -w work preflight --service jira
ai-worklog -w work preflight --ticket KD-7289

ai-worklog -w work day start
ai-worklog -w work day end

ai-worklog -w work delivery status KD-7289
ai-worklog -w work closeout report KD-7289

ai-worklog -w work reconcile status KD-7289
ai-worklog -w work reconcile status KD-7289 --system jenkins --json

ai-worklog -w work diag list
ai-worklog -w work diag run k8s-workload --namespace example --app example-worker
ai-worklog -w work diag run argocd-sync
ai-worklog -w work diag run automox-policy
ai-worklog -w work diag run host-parity
ai-worklog -w work diag run jenkins-build
ai-worklog -w work diag run k8s-oom
ai-worklog -w work diag run nr-telemetry

ai-worklog -w work toolchain check
ai-worklog -w work toolchain list

ai-worklog -w work jenkins controllers
ai-worklog -w work jenkins health jenkins
ai-worklog -w work jenkins whoami jenkins
ai-worklog -w work jenkins jobs jenkins
ai-worklog -w work jenkins syntax-check /tmp/Jenkinsfile.probe
```

Each command was additionally executed with `--runtime python` for the groups
listed in the tree; outputs matched on the human-readable path.

## Maintenance

Refresh this document when the command tree, flag set, or roadmap status
changes. It records observed behavior at a specific commit in a specific
environment, so `WIRED` and `UNVERIFIED` marks in particular are properties of
the audit environment rather than of the code. Related documents:

- `upgrade-plan` — roadmap phase status and outstanding work
- `automox-module-plan` — approved Automox command group specification
- `runtime-deferral-plan` — approved Python gating and Windows unship specification
- `README.md` — user-facing command documentation
