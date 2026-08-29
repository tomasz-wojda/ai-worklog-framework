# Workspace Setup Consolidation — Program Plan

> **For agentic workers:** this is the program-level view of five sequential
> units. Each unit gets its own detailed task plan, generated against the tree
> as it exists when that unit starts. Do not implement from this document; use
> the detailed plan for the current unit.

**Goal:** Replace the three overlapping workspace-creation commands with one
converging `workspace apply` verb, make the filesystem the source of truth for
workspace identity, and guarantee that legacy workspaces are adopted additively
and never modified in place.

**Architecture:** Five sequential units, each independently shippable and
verifiable. Discovery and error reporting land first because they fix live bugs
without renaming anything. The shape change follows under the existing command
names. The breaking rename lands only after the behaviour beneath it is proven.
Preflight state semantics land last because they depend on the shape change.

**Tech Stack:** Groovy 6 (default runtime, `groovy/src/main/groovy`), Python 3.10+
(fallback runtime, `python/src/ai_worklog_framework`), shared JSON contracts in
`shared/`, Gradle for the Groovy build, pytest for Python and parity suites,
`GroovyTestCase` for Groovy tests.

**Spec:** `docs/specs/2026-08-29-workspace-setup-consolidation-design.md`

## Global Constraints

Every task in every unit is bound by these. Values are copied from the spec.

- **Dual-runtime parity is mandatory.** Every behavioural change lands in both
  `groovy/src/main/groovy` and `python/src/ai_worklog_framework`. Human-readable
  output must match between runtimes and exit codes must agree.
- **Test baseline that must stay green,** as measured on 2026-08-29: 271 Python
  unit and contract tests, 177 parity tests, 118 Groovy tests. The 9 known
  pre-existing parity failures (8 in `test_setup_parity.py` over integration
  symlink source, 1 in `test_cli_parity.py` over the groovy pre-release version
  suffix) and the pre-existing `test_public_content` failure (11 ticket-prefix
  hits in `docs/command-surface-audit.md`) are the only permitted red. The full
  enumeration lives in the step 1-2 plan's Global Constraints.
- **Non-destruction guarantees G1–G8** (spec section 3) apply to all units.
  Chiefly: every `apply` action is create-if-absent; no command removes a
  directory containing files; no command removes `.ai-worklog/`, `worklog/`,
  `repos/`, `tmp/`, or the `integrations/` hub.
- **No confirmation prompts.** `--dry-run` is the only preview mechanism.
- **Exit code convention is preserved:** unknown command exits 1 under Groovy
  and 2 under Python (argparse). Do not attempt to unify.
- **Public content policy:** `shared/public-content-policy.json` forbids 6 token
  hashes and allows only the ticket prefixes APP, FLOW, LICENSE, OPS, OTHER,
  PROJ, TEST, UTF. Applies to every `.md`, `.py`, `.groovy`, `.json`, `.sh`.
- **Groovy build requires `GROOVY_HOME`.** `groovy/build.gradle` resolves jars
  from `$GROOVY_HOME/lib` and throws a `GradleException` when absent.
- **Never commit or push** unless explicitly instructed in the session.

---

## Unit 1 — Groovy launcher precompilation

**Why first:** every unit below depends on running the parity suite repeatedly.
`bin/ai-worklog-groovy` currently recompiles 52 source files on every
invocation, measured at 2.69s wall per call against 0.099s for the Python
launcher. The parity suite spawns the CLI roughly 200 times, which accounts for
essentially all of its ~543s runtime. Running from precompiled classes was
measured at 0.32s per call, an 8.4x improvement projecting the suite to 90–110s.

**Files:** `bin/ai-worklog-groovy` (lines 12–16), `groovy/build.gradle`.
`/groovy/build/` and `.gradle/` are already git-ignored at `.gitignore:50-51`,
so no ignore changes are needed.

**Task inventory:** add a fast path that runs compiled classes when they are
present and newer than sources, falling back to the current interpreted
invocation otherwise; document the build command; measure the suite before and
after.

**Interfaces produced:** a `bin/ai-worklog-groovy` that is behaviourally
identical but faster. No API change. No other unit depends on its internals.

**Exit criteria:** all 118 Groovy tests and 156 parity tests behave exactly as
before; measured parity suite runtime at or under 150s; launcher still works
with no build present.

**Detailed plan:** `docs/plans/2026-08-29-step-1-2-speedup-and-discovery.md`

---

## Unit 2 — Discovery and error reporting (spec D8, D9)

**Why second:** fixes four live bugs with no rename and no breaking change.

**Files (9):**
- `shared/workspace-markers.json`
- `python/src/ai_worklog_framework/global_config.py` — `global_home()` at line 46,
  `resolve_workspace_selection()` at line 490 with the cwd branch at 520–526 and
  the default fallback at 527–529, `_discover_workspace_from_cwd()` at 556–571
  with an inline marker default at line 559
- `python/src/ai_worklog_framework/setup/checks.py` —
  `find_workspace_registration()` at 173–179
- `python/src/ai_worklog_framework/workspace/commands.py` — `_workspace_context()`
  at 72–97, error raises at 310–311, 397–398, 471–472
- `groovy/.../core/GlobalConfig.groovy` — global home at line 27,
  `resolveWorkspaceSelection()` at 453, `discoverWorkspaceFromCwd()` at 570–586
  with an inline marker default at line 573
- `groovy/.../setup/SetupChecks.groovy` — `findWorkspaceRegistration()` at 182
- `groovy/.../commands/WorkspaceCommands.groovy` — error throws at 303, 404, 469
- `tests/unit/test_global_config.py`, `tests/parity/test_cli_parity.py`

**Task inventory:** exclude the global config directory from marker matching;
reduce markers to `.ai-worklog` as authoritative with `worklog` and
`integrations` as legacy hints reported under a distinct `cwd_legacy` source,
dropping `prompt.log` and `jira`; include the resolved path and resolution
source in every resolution failure message.

**Interfaces produced:** `resolve_workspace_selection` /
`resolveWorkspaceSelection` gains `cwd_legacy` as a possible `source` value.
Units 3–5 rely on that vocabulary when composing messages.

**Exit criteria:** running from a directory whose only marker is `prompt.log`
no longer resolves that directory; resolution from a non-workspace directory
under `$HOME` reaches `default_workspace`; every resolution failure names a path
and a source; parity holds.

**Detailed plan:** `docs/plans/2026-08-29-step-1-2-speedup-and-discovery.md`

---

## Unit 3 — Workspace shape and non-destruction guarantees (spec D5, D6, section 3)

**Why third:** the largest unit, and it must land under the existing command
names so that the rename in Unit 4 is a pure surface change.

**Files (33 touched by the relevant symbols).** Principal ones:
- `shared/workspace-init.json` — add `repos` and `tmp` to `directories`
- `python/src/ai_worklog_framework/workspace/planner.py` — `plan_init()` at 68,
  service loop at 94–133; `_managed_target()` at 33, `_is_managed_link()` at 37,
  `_foreign_integration_reason()` at 60 become unused for new workspaces
- `python/src/ai_worklog_framework/setup/planner.py`,
  `setup/manifest.py`, `paths.py` (`service_dir()` at 80)
- `groovy/.../workspace/WorkspacePlanner.groovy`,
  `groovy/.../setup/SetupManifest.groovy`, `groovy/.../core/FrameworkPaths.groovy`
- `schemas/setup-report.schema.json` and the `setup.json` manifest schema

**Task inventory:** add `repos/` and `tmp/` to the created shape; create one
directory per declared service under `integrations/`; stop creating
`integrations/<service> -> ../<service>` symlinks while continuing to treat an
existing symlink as satisfied; extend the `.ai-worklog/setup.json` manifest to
record directories created by the framework so teardown has a provenance record;
add the G1–G8 test suite including the legacy-adoption checksum fixture.

**Interfaces produced:** a created-directories record. Unit 4 asserts against it;
Unit 5 depends on every service directory existing.

**Exit criteria:** G8 proven by checksum over a legacy fixture before and after;
an existing symlinked integration is untouched; an unknown integration directory
such as `integrations/confluence` is untouched; parity holds.

**Blocked on:** Unit 2 (error vocabulary), Unit 1 (iteration speed).

**Amendments made during execution.**

1. *The provenance record lives in its own file, not `setup.json`.* The task
   inventory said to extend `.ai-worklog/setup.json`. That file is the skills
   manifest: `validate_manifest` whitelists top-level keys and drops unknown
   ones, `save_manifest` validates on write, and the schema requires
   `workspace_name`, `ai_vault_root`, `ides`, and `skills`. `workspace init`
   holds none of those and never writes the file, so recording directories there
   would have coupled workspace creation to the vault machinery. The record is
   now `.ai-worklog/created.json`, written by `apply_plan` when it is given a
   workspace, holding `version` and a sorted `directories` list of
   workspace-relative paths. Approved before coding.

2. *`revert` no longer removes the shape directories.* Spec G6 forbids removing
   `.ai-worklog/`, `worklog/`, `repos/`, `tmp/`, or the `integrations/` hub, and
   D10 limits removal to created-and-still-empty integration directories. The
   previous `plan_revert` removed the hub, every empty shape directory, and the
   two managed files under `.ai-worklog/`. It now emits one `rmdir` per declared
   service and skips with a reason unless the directory is in the record, is a
   real directory, and is empty. `_append_directory_cleanup` and the file and
   directory teardown loops are gone.

3. *A symlinked integration is satisfied, not a conflict.* D6 retires
   foreign-symlink conflict detection and G3 makes any existing
   `integrations/<service>` satisfied. `test_workspace_init_blocks_canonical_conflicts_with_parity`
   asserted exit 3 for a symlink pointing at `../other`; it is renamed
   `test_workspace_init_preserves_existing_integration_symlink_with_parity` and
   asserts exit 0 with the link untouched.

4. *Two test-isolation defects fixed.* The renamed parity test did not set
   `AI_WORKLOG_HOME`, so once init stopped blocking it registered a workspace in
   the developer's real `~/.ai-worklog/config.json`; it now uses an isolated home
   and a minimal vault. `test_setup_init_conflict_blocked` ran both runtimes
   against one workspace without resetting between them, so the first runtime's
   writes changed the second one's plan; it now resets between runs.

5. *Dead code removed.* `_managed_target`, `_is_managed_link`, `_path_present`,
   `_append_directory_cleanup` and their Groovy counterparts, plus the `Paths`
   and `os` imports they needed, are deleted. The `symlink` and `unlink` action
   kinds stay in `apply_plan` and the report schema because skill materialization
   still emits them.

**Result:** Python unit and contract 292 passed with only the pre-existing
`test_public_content` failure; Groovy 137 tests, 0 failures; parity 186 passed,
3 failed, down from 9. All three remaining parity failures were confirmed
pre-existing by running them against a clean `HEAD` worktree:
`test_runtime_versions_are_explicit` (groovy pre-release version string),
`test_setup_init_apply_json_and_state` (`antigravity_is_symlink`), and
`test_setup_init_conflict_blocked` (both runtimes already returned 0, not 3).
Removing the symlink actions fixed the other six.

---

## Unit 4 — Verb consolidation and migration (spec D2, D3, D4, section 4)

**Why fourth:** breaking change, safe only once Unit 3's behaviour is proven.

**Files (16).** Principal ones: `python/.../cli.py`,
`python/.../workspace/commands.py`, `groovy/.../Main.groovy`,
`groovy/.../commands/WorkspaceCommands.groovy`, `bin/ai-worklog`,
`scripts/bootstrap.sh` (call sites at lines 15, 18, 21), `README.md`,
`docs/command-surface-audit.md`, `tests/unit/test_setup.py`,
`tests/parity/test_setup_parity.py`.

**Task inventory:** rename `init` to `apply`; remove `add`, `repair`, and
`remove`; replace the `--apply` flag with `--dry-run` across `apply` and
`revert`; make bare `apply` target the current directory exactly with no upward
walk; register the removed names as tombstones that emit a redirecting error
without functioning; make `revert` unregister; update `bootstrap.sh`, the README
Workspace Setup and Named Workspaces sections, the README promise at line 260,
the stale 11-service list in the README, and the audit document.

**Interfaces produced:** the final eight-subcommand surface — `apply`, `check`,
`show`, `revert`, `ides`, `list`, `default`, `current`.

**Exit criteria:** removed names exit non-zero with a redirecting message;
`apply`/`revert` is a true inverse pair leaving pre-existing content unchanged;
`bootstrap.sh` works; parity holds on all renamed surfaces.

**Blocked on:** Unit 3.

---

## Unit 5 — Preflight state semantics (spec D7)

**Why last:** depends on Unit 3 having made every service directory exist.

**Files (4):** `python/src/ai_worklog_framework/adapters/preflight.py` —
`_check_service_directory()` at 126–132, `_check_service_properties()` at 135;
`groovy/.../commands/PreflightCommands.groovy` — lines 98 and 124;
plus their tests.

**Task inventory:** replace the present/absent directory check with four states
derived from the filesystem — `ready`, `degraded`, `not configured` for an empty
directory, and `error` — so that an unused integration is informational rather
than blocking.

**Exit criteria:** an empty service directory yields `not configured` and does
not block; a populated but failing one yields `degraded` or `error`; parity
holds.

**Blocked on:** Unit 3.

---

## Cross-unit verification

Run after every unit, not only at the end:

- `source .venv/bin/activate && python3 -m pytest tests/unit -q`
- `source .venv/bin/activate && python3 -m pytest tests/parity -q`
- `gradle -p groovy test`
- `./bin/ai-worklog --version` and `./bin/ai-worklog-python --version`

Compare human output between runtimes for every command a unit touched. The
project's convention is byte-identical human output across runtimes.
