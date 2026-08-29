# Units 1–2 Implementation Plan — Launcher Precompilation, Discovery and Error Reporting

> **For agentic workers:** implement task-by-task, in order. Steps use checkbox
> (`- [ ]`) syntax for tracking. Do not proceed to the next task until the
> current task's verification steps pass.
>
> **Note on format:** this plan deliberately contains no code bodies. The
> operator's protocol forbids example code in PLAN output, so each
> implementation step specifies the exact file, symbol, line range, and required
> behaviour instead. Shell invocations are included only as verification
> instructions.

**Goal:** Make the Groovy launcher fast enough to iterate on, then fix workspace
discovery so that neither the home directory nor a repository containing a
`prompt.log` can impersonate a workspace, and make every resolution failure name
the path it resolved and how.

**Architecture:** Unit 1 adds a compiled fast path to `bin/ai-worklog-groovy`
with an automatic fallback to today's interpreted invocation, so behaviour is
unchanged when no build exists. Unit 2 restructures marker discovery from a flat
five-marker list into ordered primary and legacy tiers, excludes the global
config directory, and threads the existing resolution `source` value out to the
user in error messages.

**Tech Stack:** Groovy 6 with Gradle, Python 3.10+, pytest, `GroovyTestCase`,
shared JSON contracts under `shared/`.

**Spec:** `docs/specs/2026-08-29-workspace-setup-consolidation-design.md`
(decisions D8 and D9; Unit 1 implements the cost note in spec section 5)

**Program plan:** `docs/plans/2026-08-29-workspace-consolidation-program.md`

## Global Constraints

- Dual-runtime parity is mandatory. Every behavioural change lands in both
  runtimes with identical human output and agreeing exit codes.
- Baseline that must stay green, as measured on 2026-08-29: 271 Python unit and
  contract tests, 177 parity tests, 118 Groovy tests. Permitted pre-existing red
  is 9 parity failures plus `test_public_content`:
  - 8 failures in `tests/parity/test_setup_parity.py`, where Python emits source
    `"../jira"` for skipped integration symlinks while Groovy emits null:
    `test_setup_auto_detection_from_marker`,
    `test_setup_init_apply_json_and_state`, `test_setup_init_conflict_blocked`,
    `test_setup_init_dry_run_json`, `test_setup_init_merges_existing_ides`,
    `test_setup_repair_after_missing_symlink`,
    `test_setup_repair_dry_run_and_idempotent_apply`,
    `test_setup_v1_global_config_migration_on_apply`.
  - 1 failure in `tests/parity/test_cli_parity.py`,
    `test_runtime_versions_are_explicit`, where the version regex permits a
    pre-release suffix for java but not for groovy and so rejects the local
    `groovy 6.0.0-beta-2`. Verified pre-existing against the interpreted path.
  - `test_public_content` failing on 11 ticket-prefix hits in
    `docs/command-surface-audit.md`.
- `shared/public-content-policy.json` forbids 6 token hashes and allows only the
  ticket prefixes APP, FLOW, LICENSE, OPS, OTHER, PROJ, TEST, UTF.
- `GROOVY_HOME` must be set; `groovy/build.gradle` throws a `GradleException`
  otherwise.
- Exit code convention preserved: unknown command exits 1 under Groovy, 2 under
  Python.
- Do not commit or push unless the operator instructs it in-session. Where a
  step says "commit", prepare the commit message and request approval.

---

## Task 1: Compiled fast path for the Groovy launcher

**Files:**
- Modify: `bin/ai-worklog-groovy:12-16` (the `export` and `exec` block)
- Modify: `groovy/build.gradle` (only if a dedicated build task is added; the
  `groovy` and `application` plugins and `mainClass` at lines 20–22 already
  provide what is needed)
- No `.gitignore` change: `/groovy/build/` and `.gradle/` are ignored at
  `.gitignore:50-51`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: no API surface. `bin/ai-worklog-groovy` remains a drop-in launcher
  accepting the same arguments and emitting the same output. Later tasks consume
  only its speed.

**Current behaviour to preserve:** the launcher resolves `ROOT` from its own
location, honours `AI_WORKLOG_GROOVY` as the Groovy executable override,
exits 2 with the message `Groovy is required for the default runtime. Use
--runtime python as fallback.` when that executable is absent, exports
`AI_WORKLOG_FRAMEWORK_ROOT`, and execs Groovy with `-cp
$ROOT/groovy/src/main/groovy` against `Main.groovy`.

- [ ] **Step 1: Record the baseline timing**

Run: `time ./bin/ai-worklog-groovy --version` three times and record the mean
wall time. Expected: approximately 2.7s per invocation.

Run: `time (source .venv/bin/activate && python3 -m pytest tests/parity -q)` and
record total wall time. Expected: approximately 9 minutes.

Write both numbers into the task's commit message later; they are the evidence
that this task worked.

- [ ] **Step 2: Confirm a Gradle compile produces the expected output tree**

Run: `gradle -p groovy classes`
Expected: `BUILD SUCCESSFUL`, and `groovy/build/classes/groovy/main/ai/worklog/framework/Main.class` exists.

If the build fails on `GROOVY_HOME`, stop and report; the classpath contract in
`groovy/build.gradle:10-13` is a prerequisite, not part of this task.

- [ ] **Step 3: Add the fast path to the launcher, with fallback**

> **Amended 2026-08-29 after execution.** This step originally compared source
> mtimes against `Main.class` and stated that no stamp file was required. That
> proved unworkable: Gradle decides `classes` up-to-dateness by content hash, so
> after an mtime-only change such as a `git checkout`, a branch switch, or a
> `touch`, Gradle reports `UP-TO-DATE` and never rewrites `Main.class`. The
> launcher therefore saw a permanently stale build that `gradle classes` could
> not clear, pinning it to the slow path until an unrelated content change
> occurred. The marker must advance whenever a build is *requested*, not only
> when compilation *occurs*. The design below is what shipped.

First modify `groovy/build.gradle`, appending after the existing
`tasks.withType(Test).configureEach` block:

1. Register a task `stampClasses`, configured as never up to date so it executes
   on every invocation regardless of input hashes.
2. Its single action ensures a stamp file exists and sets its modification time
   to the current clock time. The stamp lives at `ai-worklog-stamp` directly
   inside the Groovy build directory, addressed through `layout.buildDirectory`
   rather than the deprecated `buildDir` property, since this is Gradle 9.7.1.
3. Wire `classes` to be finalized by `stampClasses`, so the stamp refreshes on
   `gradle -p groovy classes` and on every task depending on `classes`,
   including `test` and `build`.

Then modify `bin/ai-worklog-groovy`, replacing the unconditional `exec` at lines
13–16 with a decision. Required behaviour, in order:

1. Compute the compiled output directory as `$ROOT/groovy/build/classes/groovy/main`
   and the stamp path as `$ROOT/groovy/build/ai-worklog-stamp`.
2. Treat the compiled tree as usable only when the compiled `Main.class` exists
   as proof the class tree is real, **and** the stamp exists, **and** no file
   under `$ROOT/groovy/src/main/groovy` matching `*.groovy` is newer than the
   stamp. Use `find` with `-newer` against the stamp so the check is a single
   filesystem walk.
3. When usable, exec `java` with a classpath of the compiled directory followed
   by every jar in `$GROOVY_HOME/lib`, and the main class
   `ai.worklog.framework.Main`. Resolve the Java executable from `JAVA_HOME` when
   set, otherwise from `PATH`.
4. When not usable, or when `java` cannot be resolved, or when `GROOVY_HOME` is
   unset, fall through to the existing interpreted `exec` unchanged.
5. Preserve `AI_WORKLOG_FRAMEWORK_ROOT` export and the `AI_WORKLOG_GROOVY` and
   Groovy-missing checks in all paths. The Groovy-missing check must remain
   reachable only on the interpreted path, since the compiled path does not need
   the `groovy` executable.

Do not add an environment variable to force either path. The staleness check is
the only selector, so a stale build can never silently serve old code, and
because the stamp always advances, `gradle classes` always clears a stale state.

- [ ] **Step 4: Verify the compiled path produces identical output**

Run: `./bin/ai-worklog-groovy --version` and compare to the value recorded in
Step 1. Expected: byte-identical.

Run: `./bin/ai-worklog-groovy -w work workspace check` and
`./bin/ai-worklog-python -w work workspace check`, and diff them. Expected: no
differences, matching the project's byte-identical-human-output convention.

- [ ] **Step 5: Verify the fallback path still works**

Run: `mv groovy/build /tmp/awbuild-parked && ./bin/ai-worklog-groovy --version`
Expected: same output, slower, via the interpreted path.

Run: `mv /tmp/awbuild-parked groovy/build` to restore.

- [ ] **Step 6: Verify staleness detection**

Run: `touch groovy/src/main/groovy/ai/worklog/framework/Main.groovy` then
`time ./bin/ai-worklog-groovy --version`.
Expected: the interpreted path is taken (timing returns to roughly 2.7s),
proving a stale build is not used.

Run: `gradle -p groovy classes` then `time ./bin/ai-worklog-groovy --version`.
Expected: fast path restored. This is the decisive case for the amended design in
Step 3, and it must hold even though `:classes` reports `UP-TO-DATE` and no
recompilation happens, because `:stampClasses` still runs and advances the stamp.

- [ ] **Step 7: Run both test suites**

Run: `gradle -p groovy test --rerun-tasks`
Expected: `BUILD SUCCESSFUL`. `--rerun-tasks` is required because a cached `test`
task reports `UP-TO-DATE` and emits no counts. Gradle does not print totals, so
confirm 118 tests with 0 failures and 0 errors by aggregating the
`tests=`, `failures=`, and `errors=` attributes across
`groovy/build/test-results/test/TEST-*.xml`.

Run: `source .venv/bin/activate && python3 -m pytest tests/parity -q` and record
the wall time.
Expected: same pass/fail set as Step 1, with wall time at or under 150s.

- [ ] **Step 8: Prepare the commit**

Stage `bin/ai-worklog-groovy` and `groovy/build.gradle` if modified. Propose the
message `perf(runtime): run the Groovy CLI from compiled classes when available`
with a body citing the before and after timings from Steps 1 and 7. Request
operator approval before committing.

---

## Task 2: Exclude the global config directory from marker discovery

**Files:**
- Modify: `python/src/ai_worklog_framework/global_config.py:556-571`
  (`_discover_workspace_from_cwd`)
- Modify: `groovy/src/main/groovy/ai/worklog/framework/core/GlobalConfig.groovy:570-586`
  (`discoverWorkspaceFromCwd`)
- Test: `tests/unit/test_global_config.py`
- Test: `groovy/src/test/groovy/ai/worklog/framework/GlobalConfigTest.groovy`

**Interfaces:**
- Consumes: `global_home()` at `global_config.py:42-46`, which honours the
  `AI_WORKLOG_HOME` override and otherwise returns `Path.home() / ".ai-worklog"`
  unresolved, so callers comparing against it must resolve it first; and the
  Groovy equivalent `configHome()` at `GlobalConfig.groovy:21-28`, which honours
  `AI_WORKLOG_HOME` and the `ai.worklog.test.home` system property and otherwise
  returns `new File(System.getProperty('user.home'), '.ai-worklog').canonicalFile`.
- Produces: no signature change. `_discover_workspace_from_cwd` continues to
  return an optional path in this task; Task 3 changes its return shape.

**Defect being fixed:** `~/.ai-worklog` is the global config directory but also
matches the `.ai-worklog` marker, so `$HOME` always resolves as a workspace. A
side effect is that the `default_workspace` fallback at
`global_config.py:527-529` is unreachable from any non-workspace directory under
the home directory.

> **Amended 2026-08-29 after execution.** Steps 1, 3, 5 and 7 originally
> compared the *candidate directory* against `global_home()`. That fix cannot
> work. With no override, `global_home()` is `$HOME/.ai-worklog`, while the
> directory that wrongly resolves is `$HOME`, which matches because that config
> directory is its *child*. Candidate and global home are therefore never equal
> in production, verified live: resolving from `$HOME` returned `/Users/example`
> with source `cwd_marker` while `global_home()` was `/Users/example/.ai-worklog`.
> The rule must instead ignore a marker hit when the *marker's own* path is the
> global config directory. The original Step 1 test could not detect this,
> because pointing `AI_WORKLOG_HOME` at a plain temporary directory and creating
> `.ai-worklog` inside it inverts the production relationship and makes the
> candidate equal the global home. The design below is what shipped.

- [ ] **Step 1: Write the failing Python test**

Add `test_global_home_is_never_a_workspace` to `tests/unit/test_global_config.py`
in a new `TestWorkspaceDiscovery` class. It must reproduce the production layout,
where the config directory *is* the marker rather than containing it: add a
fixture pointing `AI_WORKLOG_HOME`, declared at `global_config.py:21`, at
`<tmp>/fakehome/.ai-worklog`; register a workspace elsewhere as
`default_workspace`; change the working directory to a marker-free child of
`<tmp>/fakehome`; then assert `resolve_workspace_selection()` returns the
registered default's path with `source` equal to `default_workspace` and `name`
equal to the registered name.

This layout is what discriminates the two candidate fixes: the superseded
candidate-equality rule passes under the old layout and fails under this one.

- [ ] **Step 2: Run it to confirm it fails**

Run: `source .venv/bin/activate && python3 -m pytest tests/unit/test_global_config.py::test_global_home_is_never_a_workspace -q`
Expected: FAIL, because the returned path is the temporary home and the source is
`cwd_marker`.

- [ ] **Step 3: Implement the Python exclusion**

In `_discover_workspace_from_cwd`, resolve `global_home()` once before the walk.
When a marker matches, ignore that hit if the marker's own resolved path equals
that value, and carry on testing the remaining markers and then the parents, so a
real workspace above the home directory is still discoverable. Keep the
comparison generic across every marker rather than special-casing `.ai-worklog`,
because `AI_WORKLOG_HOME` may name a directory of any name; a global home at
`/x/worklog` would otherwise leave `/x` matching on the `worklog` marker.

- [ ] **Step 4: Confirm the Python test passes**

Run the command from Step 2. Expected: PASS.

Run: `source .venv/bin/activate && python3 -m pytest tests/unit tests/contract -q`
Expected: 272 passed (271 baseline plus the new test), with only the
pre-existing `test_public_content` failure.

- [ ] **Step 5: Write the failing Groovy test**

Add `testGlobalHomeIsNeverAWorkspace` to `GlobalConfigTest.groovy`, mirroring
Step 1's nested layout. Override the home with the `ai.worklog.test.home` system
property that `configHome()` already honours, since a JVM test cannot set its own
environment variables. Because `discoverWorkspaceFromCwd` reads the working
directory from the `user.dir` system property, set that property to the
marker-free child and restore its original value in a `finally` block; leaking it
would corrupt other tests sharing the JVM.

- [ ] **Step 6: Run it to confirm it fails**

Run: `gradle -p groovy test --tests '*GlobalConfigTest*'`
Expected: FAIL on the new test only.

- [ ] **Step 7: Implement the Groovy exclusion**

Apply the same rule in `discoverWorkspaceFromCwd`: resolve the global home from
`configHome()` before the walk, then ignore any marker hit whose own canonical
path equals it and continue testing the remaining markers and parents.

- [ ] **Step 8: Confirm both suites pass**

Run: `gradle -p groovy test --rerun-tasks`
Expected: `BUILD SUCCESSFUL`, 119 tests, 0 failures, aggregated from the result
XMLs as in Task 1 Step 7.

Run: `source .venv/bin/activate && python3 -m pytest tests/parity -q`
Expected: a failure list byte-identical to the 9 recorded in Global Constraints,
with no new failures and none incidentally fixed.

- [ ] **Step 9: Verify the live defect is gone**

Run: `cd ~ && /path/to/repo/bin/ai-worklog workspace check --json` and inspect
the `workspace` object.
Expected: the resolved path is the registered default workspace, not
`/Users/<user>`, and `registered` is true.

- [ ] **Step 10: Prepare the commit**

Propose `fix(workspace): stop the global config directory matching as a workspace marker`
with a body noting the restored `default_workspace` fallback. Request approval.

---

## Task 3: Tier the marker list and report the legacy source

**Files:**
- Modify: `shared/workspace-markers.json`
- Modify: `python/src/ai_worklog_framework/global_config.py:556-571`
  (`_discover_workspace_from_cwd`, including the inline default at line 559) and
  `:520-526` (the cwd branch of `resolve_workspace_selection`)
- Modify: `groovy/.../core/GlobalConfig.groovy:570-586`
  (`discoverWorkspaceFromCwd`, including the inline default at line 573) and the
  caller block immediately above it that builds the returned map
- Test: `tests/unit/test_global_config.py`
- Test: `groovy/src/test/groovy/ai/worklog/framework/GlobalConfigTest.groovy`

**Interfaces:**
- Consumes: the exclusion from Task 2.
- Produces: `_discover_workspace_from_cwd` / `discoverWorkspaceFromCwd` now
  return both a path and a source discriminator, where the source is
  `cwd_marker` for a `.ai-worklog` match and `cwd_legacy` for a `worklog` or
  `integrations` match. `resolve_workspace_selection` /
  `resolveWorkspaceSelection` propagate that value into their existing `source`
  field instead of the hardcoded `'cwd_marker'`. Task 4 and Units 3–5 consume
  `cwd_legacy` as a valid source value.

**Defect being fixed:** the flat marker list treats incidental files as workspace
evidence. `prompt.log` currently matches inside four repositories under `repos/`,
so each resolves as its own workspace; a command run from a repository resolves
that repository rather than the real workspace. Separately, deleting `worklog/`
or `integrations/` changes which directory the CLI believes it is in, which is
tolerated for legacy folders but must be reported distinctly.

> **Amended 2026-08-29 after execution.** Three corrections.
>
> First, the contract has a **third consumer this task did not list**:
> `python/src/ai_worklog_framework/paths.py` subscripts `_PATH_RULES["markers"]`
> at import time, and it is imported by at least ten production modules, so
> removing the `markers` key raises `KeyError` before any command can run. That
> file also held `find_workspace_root`, a second independent marker walk with no
> global-home exclusion, so it had already diverged from the Task 2 fix. Nothing
> in production called it; its only callers were three tests, one of which
> asserted that a bare `prompt.log` marks a workspace root. It and its two module
> constants and those three tests were deleted, leaving one discovery
> implementation per runtime.
>
> Second, the parity fixture `work_workspace` at
> `tests/parity/test_global_config_parity.py:174-180` built its workspace from a
> **`jira` directory as its only marker**. Removing `jira` would have made
> `test_workspace_current_cwd_marker` fall through to `default_workspace` while
> still agreeing across runtimes, so it would have passed while testing nothing.
> The fixture now uses `.ai-worklog`, both cwd tests assert the literal `source`
> rather than parity alone, and a `legacy_workspace` fixture plus
> `test_workspace_current_cwd_legacy` cover the new tier.
>
> Third, Step 2 was executed before Step 1. The Step 3 expectations below only
> hold while the contract is unchanged, so the tests were written and observed
> red first, then the contract and implementation landed together.

- [ ] **Step 1: Restructure the shared contract**

Replace the flat `markers` array in `shared/workspace-markers.json` with
`primary_markers`, containing only `.ai-worklog`, and `legacy_markers`,
containing `worklog` and `integrations`. Retain `max_parent_depth` at 20. Remove
`prompt.log` and `jira` entirely.

Both runtimes carry an inline default that must mirror the file exactly:
`global_config.py:559` and `GlobalConfig.groovy:573`. Update both. Note the two
inline defaults did not previously mirror the file, since both omitted
`integrations`.

Delete `_PATH_RULES`, `WORKSPACE_MARKERS`, `MAX_PARENT_DEPTH` and
`find_workspace_root` from `python/src/ai_worklog_framework/paths.py`, and the
`TestFindWorkspaceRoot` class and its import from `tests/unit/test_paths.py`.
Retain the `mock_workspace` fixture, which the rest of that file depends on.

- [ ] **Step 2: Write the failing Python tests**

Add to `tests/unit/test_global_config.py`:

- `test_prompt_log_is_not_a_marker` — a directory containing only a `prompt.log`
  file does not resolve; resolution falls through to `default_workspace`.
- `test_jira_directory_is_not_a_marker` — same for a `jira` directory.
- `test_ai_worklog_resolves_with_source_cwd_marker` — a directory containing
  `.ai-worklog` resolves to itself with source `cwd_marker`.
- `test_worklog_resolves_as_legacy_with_source_cwd_legacy` — a directory
  containing only `worklog` resolves to itself with source `cwd_legacy`.
- `test_integrations_resolves_as_legacy` — same for `integrations`.
- `test_primary_marker_wins_over_legacy_in_same_directory` — a directory
  containing both `.ai-worklog` and `worklog` resolves with source `cwd_marker`.
- `test_nearest_directory_wins_over_distant_primary` — a child with `worklog` and
  a parent with `.ai-worklog` resolves the child with source `cwd_legacy`,
  documenting that proximity beats tier across levels.

- [ ] **Step 3: Run them to confirm they fail**

Run: `source .venv/bin/activate && python3 -m pytest tests/unit/test_global_config.py::TestWorkspaceDiscovery -q`
Expected: 5 of the 7 fail. The two negative tests fail because `prompt.log` and
`jira` still match, and the three `cwd_legacy` tests fail because no source
discriminator exists yet. `test_ai_worklog_resolves_with_source_cwd_marker` and
`test_primary_marker_wins_over_legacy_in_same_directory` already pass, because
`cwd_marker` is the hardcoded value; they remain as regression guards.

- [ ] **Step 4: Implement the Python tiering**

In `_discover_workspace_from_cwd`, at each level and after the Task 2 exclusion:
test the primary tier first and return the directory with a `cwd_marker`
discriminator; otherwise test the legacy tier and return it with a `cwd_legacy`
discriminator; otherwise ascend. Return both values to the caller.

In `resolve_workspace_selection` at lines 520–526, use the returned
discriminator as the `source` value rather than the literal `'cwd_marker'`.
Leave the `name` field `None` as today.

- [ ] **Step 5: Confirm the Python tests pass**

Run the command from Step 3. Expected: all PASS.

Run: `source .venv/bin/activate && python3 -m pytest tests/unit tests/contract -q`
Expected: 276 passed, only the pre-existing `test_public_content` failure. That
total is 272 before this task, plus 7 new tests, minus the 3 deleted with
`find_workspace_root`.

- [ ] **Step 6: Write the failing Groovy tests**

Add the same seven cases to `GlobalConfigTest.groovy` as
`testPromptLogIsNotAMarker`, `testJiraDirectoryIsNotAMarker`,
`testAiWorklogResolvesWithSourceCwdMarker`,
`testWorklogResolvesAsLegacyWithSourceCwdLegacy`,
`testIntegrationsResolvesAsLegacy`,
`testPrimaryMarkerWinsOverLegacyInSameDirectory`, and
`testNearestDirectoryWinsOverDistantPrimary`.

- [ ] **Step 7: Run them to confirm they fail, then implement**

Run: `gradle -p groovy test --tests '*GlobalConfigTest*'`
Expected: the seven new tests fail.

Apply the same tiering in `discoverWorkspaceFromCwd`, returning a map carrying
the directory and the discriminator, and propagate it in the caller block that
currently sets `source` for the cwd case.

- [ ] **Step 8: Confirm both suites and parity**

Run: `gradle -p groovy test --rerun-tasks`
Expected: `BUILD SUCCESSFUL`, 126 tests, 0 failures.

Run: `source .venv/bin/activate && python3 -m pytest tests/parity -q`
Expected: 178 passed, being the 177 baseline plus
`test_workspace_current_cwd_legacy`, and a failure list byte-identical to the 9
recorded in Global Constraints.

- [ ] **Step 9: Verify the live defect is gone**

Run, from the framework repository's own directory:
`./bin/ai-worklog-python workspace check --json`
Expected: the resolved workspace is no longer the framework repository itself.
Before this task it resolved there solely because a `prompt.log` sits in that
directory.

- [ ] **Step 10: Prepare the commit**

Propose `fix(workspace): tier workspace markers and drop incidental ones` with a
body naming the four repositories that previously impersonated workspaces.
Request approval.

---

## Task 4: Report the resolved path and source in resolution failures

**Files:**
- Modify: `python/src/ai_worklog_framework/workspace/commands.py` —
  `_workspace_context` at 72–97, and all five call sites at 256, 280, 306, 392,
  467; the three failure messages at 310–311, 397–398, 471–472
- Modify: `groovy/.../commands/WorkspaceCommands.groovy` — `workspaceContext` at
  528, and all five call sites at 246, 272, 299, 400, 465; the three failure
  throws at 303, 404, 469
- Test: `tests/unit/test_setup.py`
- Test: `tests/parity/test_cli_parity.py`
- Test: `groovy/src/test/groovy/ai/worklog/framework/SetupTest.groovy`

**Interfaces:**
- Consumes: the `source` values produced by Task 3, including `cwd_legacy`.
- Produces: `_workspace_context` returns a fifth element, the resolution source
  string. `workspaceContext` returns a fifth list element likewise. Every caller
  must be updated; the Python sites at 256 and 280 currently unpack four names
  and the sites at 306, 392, 467 discard the fourth with `_`.

**Defect being fixed:** `Workspace is not registered` is emitted without naming
the directory it resolved or how, which is why a failure about `$HOME` appeared
to contradict a registry listing a different workspace.

> **Amended 2026-08-29 after execution.** Every line reference in this task was
> verified accurate. Four points the task left open were settled during
> execution.
>
> The `positional` branch of `_workspace_context` never calls
> `resolve_workspace_selection`, so there is no selection dict to take a source
> from. It reports the existing vocabulary rather than a new value:
> `workspace_name` when the positional matches a registered name, and
> `explicit_path` otherwise.
>
> The exact strings, which both runtimes must emit byte-identically, are
> `Workspace is not registered: {path} (source: {source})` for the ordinary case,
> matching spec D9, and that same text followed by
> `. Directory is not initialized; run 'workspace init' to initialize it.` for
> `cwd_legacy`. American spelling matches the codebase, which uses `Initialize`
> and `initialization`. The remedy names `init` and must be updated when Unit 4
> renames it to `apply`.
>
> Only three Groovy call sites need changing, not five. Groovy reads the context
> by index rather than destructuring, so appending a fifth element leaves the
> `check` and `show` sites at 246 and 272 untouched. Python destructures, so all
> five of its sites do change.
>
> Both new tests assert an exit code of 1, from `user_error` in
> `shared/exit-codes.json`.

- [ ] **Step 1: Write the failing Python test**

Add `test_unregistered_failure_names_path_and_source` to
`tests/unit/test_setup.py`. Drive a workspace-context-consuming command from a
directory that resolves by marker but is not registered, and assert the emitted
message contains both the resolved absolute path and the parenthesised source,
matching the form `Workspace is not registered: <path> (source: cwd_marker)`.

Add `test_legacy_hint_failure_reports_cwd_legacy` asserting that a directory
resolving via `worklog` reports `(source: cwd_legacy)` and additionally states
that the directory is not initialised.

- [ ] **Step 2: Run to confirm failure**

Run: `source .venv/bin/activate && python3 -m pytest tests/unit/test_setup.py -q -k "names_path_and_source or cwd_legacy"`
Expected: FAIL; the current message contains neither path nor source.

- [ ] **Step 3: Implement in Python**

Extend `_workspace_context` to return the resolution source as a fifth element,
taking it from the resolved selection dict. Update all five call sites. Change
the three failure messages to include the resolved path and the source, and for
`cwd_legacy` additionally state that the directory is not initialised and name
`workspace init` as the remedy. Do not rename any command in this unit; the
rename is Unit 4.

- [ ] **Step 4: Confirm Python tests pass**

Run the command from Step 2. Expected: PASS.

Run: `source .venv/bin/activate && python3 -m pytest tests/unit -q`
Expected: 278 passed, only the pre-existing `test_public_content` failure.

- [ ] **Step 5: Mirror in Groovy**

Add `testUnregisteredFailureNamesPathAndSource` and
`testLegacyHintFailureReportsCwdLegacy` to `SetupTest.groovy`, confirm they fail
via `gradle -p groovy test --tests '*SetupTest*'`, then extend `workspaceContext`
to return the source as a fifth list element, update all five call sites, and
change the three throws at 303, 404, and 469 to match the Python wording exactly.

- [ ] **Step 6: Assert parity explicitly**

Add `test_workspace_failure_messages_match_between_runtimes` to
`tests/parity/test_cli_parity.py`, invoking the same failing command under both
`bin/ai-worklog-groovy` and `bin/ai-worklog-python` from the same working
directory and asserting byte-identical stdout and equal exit codes.

- [ ] **Step 7: Run everything**

Run: `gradle -p groovy test --rerun-tasks`
Expected: `BUILD SUCCESSFUL`, 128 tests, 0 failures.

Run: `source .venv/bin/activate && python3 -m pytest tests/unit tests/contract -q`
Expected: 278 passed, only the pre-existing `test_public_content` failure.

Run: `source .venv/bin/activate && python3 -m pytest tests/parity -q`
Expected: 180 passed, being 178 plus the two parametrized cases of the new parity
test, and a failure list byte-identical to the 9 in Global Constraints.

- [ ] **Step 8: Prepare the commit**

Propose `fix(workspace): name the resolved path and source in resolution failures`
and request approval.

---

## IMPLEMENTATION CHECKLIST

1. Record baseline timings for `bin/ai-worklog-groovy --version` and the parity suite.
2. Confirm `gradle -p groovy classes` produces `groovy/build/classes/groovy/main/ai/worklog/framework/Main.class`.
3. Add the compiled fast path with staleness check and interpreted fallback to `bin/ai-worklog-groovy:12-16`.
4. Verify compiled output is byte-identical to interpreted output, and identical to the Python runtime.
5. Verify the fallback path works with `groovy/build` absent.
6. Verify staleness detection reverts to the interpreted path after touching a source file.
7. Run `gradle -p groovy test` and the parity suite; confirm the suite is at or under 150s.
8. Prepare the Unit 1 commit with before and after timings; request approval.
9. Write `test_global_home_is_never_a_workspace` in `tests/unit/test_global_config.py` and confirm it fails.
10. Implement the `global_home()` exclusion in `global_config.py:556-571`.
11. Confirm the Python test passes and the unit suite is green.
12. Write `testGlobalHomeIsNeverAWorkspace` in `GlobalConfigTest.groovy` and confirm it fails.
13. Implement the same exclusion in `GlobalConfig.groovy:570-586`.
14. Run both suites; verify from the home directory that the default workspace now resolves.
15. Prepare the Task 2 commit; request approval.
16. Restructure `shared/workspace-markers.json` into primary and legacy tiers, dropping `prompt.log` and `jira`.
17. Mirror the new contract in the inline defaults at `global_config.py:559` and `GlobalConfig.groovy:573`.
18. Write the seven marker and source tests in `tests/unit/test_global_config.py` and confirm they fail.
19. Implement tiered discovery returning a `cwd_marker` or `cwd_legacy` discriminator in `global_config.py`.
20. Propagate the discriminator into the `source` field at `global_config.py:520-526`.
21. Confirm the Python tests pass and the unit suite is green.
22. Write the seven mirrored tests in `GlobalConfigTest.groovy` and confirm they fail.
23. Implement tiered discovery and source propagation in `GlobalConfig.groovy`.
24. Run both suites and parity; verify the framework repository no longer resolves as its own workspace.
25. Prepare the Task 3 commit; request approval.
26. Write `test_unregistered_failure_names_path_and_source` and `test_legacy_hint_failure_reports_cwd_legacy` in `tests/unit/test_setup.py`; confirm they fail.
27. Extend `_workspace_context` at `workspace/commands.py:72` to return the source and update call sites 256, 280, 306, 392, 467.
28. Rewrite the three Python failure messages at 310–311, 397–398, 471–472 to include path and source.
29. Confirm the Python tests pass and the unit suite is green.
30. Write the two mirrored tests in `SetupTest.groovy`; confirm they fail.
31. Extend `workspaceContext` at `WorkspaceCommands.groovy:528` to return the source and update call sites 246, 272, 299, 400, 465.
32. Rewrite the three Groovy throws at 303, 404, 469 to match the Python wording exactly.
33. Add `test_workspace_failure_messages_match_between_runtimes` to `tests/parity/test_cli_parity.py`.
34. Run `gradle -p groovy test` and `pytest tests/unit tests/parity`; confirm only known failures remain.
35. Prepare the Task 4 commit; request approval.
