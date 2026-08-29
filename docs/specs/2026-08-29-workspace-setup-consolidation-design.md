# Workspace Setup Consolidation — Design

Date: 2026-08-29
Status: Design approved in discussion; not yet planned or implemented

## 1. Problem

Three commands can bring a workspace into existence or fix it, and they disagree
about what a workspace is.

`workspace init` is an installer: it creates the directory structure, links
integrations, materializes IDE skills, and registers the workspace. `workspace
add` is a bookmark: it writes a registry entry and creates nothing. `workspace
repair` re-materializes drifted artifacts, but refuses to act unless the
workspace is already registered and accepts no path argument.

The two creation paths came from separate feature lines that landed a day apart
in separate namespaces — `workspace add` in `64651f2` (2026-08-10) and `setup
init` in `ec00917` (2026-08-11). They were never in the same command until the
`setup`-into-`workspace` merge, which made a pre-existing redundancy visible.

Observed consequences:

1. A workspace registered by `add` has no `.ai-worklog/`, yet every other command
   treats registry presence as proof it is real. `workspace check` reports
   `[READY] workspace: Registered and available` beside
   `[BLOCKED] structure: Missing: .ai-worklog/`.
2. Workspace discovery matches five markers, two of which are wrong.
   `~/.ai-worklog` is the global config directory but matches the primary marker,
   so `$HOME` always looks like a workspace. `prompt.log` matches inside four
   repositories under `repos/`, so each impersonates a workspace; a command run
   from a repository resolves that repository as the workspace.
3. Because cwd discovery succeeds on `$HOME`, the `default_workspace` fallback in
   `resolve_workspace_selection` is unreachable from anywhere under the home
   directory that is not a workspace.
4. Errors report neither the resolved path nor how it was resolved, so
   `Workspace is not registered` is emitted for a directory the user never named.
5. No single command returns a workspace to a clean state. `revert` removes links
   and skill artifacts but neither directories nor the registry entry; `remove`
   touches only the registry.
6. Setup produces a smaller shape than preflight demands. `apply` creates the
   `integrations/` hub but no service directories, and preflight hard-blocks with
   `Directory not found` when a service directory is absent. `repos/` is never
   created although `GitAdapter` resolves its root to `workspace_root / "repos"`.

## 2. Decisions

### D1 — The filesystem is the source of truth

A directory is a workspace because it contains `.ai-worklog/`. The global
registry in `~/.ai-worklog/config.json` is a convenience table of name-to-path
shortcuts and is not evidence that a workspace exists. A registry entry pointing
at a directory without `.ai-worklog/` is invalid, not authoritative.

Rationale: makes a workspace self-describing and portable, gives teardown a
single subject, and removes the state that produced consequence 1.

### D2 — One converging verb, `apply`

`init`, `add`, and `repair` are replaced by `workspace apply`, which drives a
directory toward the declared shape: create what is missing, register if
unregistered, re-materialize drifted artifacts, leave everything else untouched.

"Drifted artifacts" means AI Vault skill materialization into IDE profiles — the
only thing the framework materializes and therefore the only thing that can
drift. Integration symlinks are no longer created (D6), so they are not a drift
subject.

Greenfield creation, legacy adoption, drift repair, and registration stop being
four features and become one operation over four starting states. `init` already
handles all four; only its name and the surrounding commands obscured it.

The verb is `apply` rather than `sync` or `init` because it names the semantics
— a ruleset is applied to a directory — and because a word meaning "create new"
cannot honestly cover adoption of a directory holding existing work.

### D3 — `--dry-run` replaces `--apply`

`workspace apply` writes. `workspace apply --dry-run` previews and changes
nothing. The `--apply` flag is removed from all workspace commands.

The plan/apply split already exists internally (`plan_setup_init` builds a plan,
`apply_init_or_repair_plan` executes it); only the CLI surface hid it behind one
verb and a flag. `scripts/bootstrap.sh` already uses the vocabulary
`[--dry-run|--link|--revert]`.

No confirmation prompts are introduced. Safety is provided by `--dry-run`, by the
non-destruction guarantees in section 7, and by D8.

### D4 — Command surface

```
before: init check show repair revert ides add list default current remove   (+ --apply)
after:  apply check show revert ides list default current                    (+ --dry-run)
```

`init` becomes `apply`. `add` is removed: registration is a consequence of
converging. `repair` is removed: re-materializing drift is what converging does.
`remove` is removed: `revert` unregisters, because `apply` registers.

`check` is retained as the scriptable validation gate with meaningful exit codes,
distinct from `--dry-run`, which previews changes for a human.

### D5 — Converged workspace shape

Directories created by `apply`:

```
.ai-worklog/            marker and framework state
.ai-worklog/state/
.ai-worklog/catalog/
.ai-worklog/evidence/
worklog/
worklog/done/
integrations/
integrations/<service>/ one per service in shared/workspace-init.json (12)
repos/
tmp/
```

Files seeded by `apply`, skipped when present:
`.ai-worklog/config.json` from `config/workspace-config.example.json`, and
`.ai-worklog/.gitignore` from `config/workspace.gitignore`.

Also performed: AI Vault skill materialization into registered IDE profiles, and
the global registry entry.

`repos/` is added because `GitAdapter` computes its allowed root as
`workspace_root / repositories_root` and reports only per-repository errors, so
an empty `repos/` is harmless and closes a gap where reconciliation expects a
directory setup never made. `tmp/` is added because it is already in use.

### D6 — Integrations are plain directories

An integration lives at `integrations/<service>` as a real directory. The
symlink indirection is removed: `apply` no longer creates
`integrations/<service> -> ../<service>`, and the supporting machinery
(`_managed_target`, `_is_managed_link`, foreign-symlink conflict detection, and
the matching removal logic in revert) is no longer needed for new workspaces.

Rationale: of the six known integrations in the reference workspace, five
(`aws`, `jenkins`, `jira`, `snow`, `ssh`) are already plain directories under
`integrations/`, and only `automox` uses the symlink form.
`WorkspacePaths.service_dir` already prefers `integrations/<service>` and falls
back to the workspace root, so both layouts resolve.

Existing symlinks are preserved. `apply` treats an existing
`integrations/<service>` — directory or symlink — as satisfied and plans nothing
against it. Directories not in the service list, such as
`integrations/confluence`, are left untouched.

### D7 — Preflight reports four states, derived from the filesystem

Because every service directory now exists, `Directory not found` no longer
distinguishes an unused integration from a broken one. Emptiness carries that
meaning instead:

- `ready` — configured and passing its checks
- `degraded` — configured, but a credential is stale or a session is absent
- `not configured` — directory exists and is empty; informational, not a failure
- `error` — configured but failing

No declared service list is introduced; intent is derived from the filesystem,
consistent with D1. `shared/preflight-checks.json` continues to map each service
to its required binaries.

### D8 — Discovery: ordered markers, home excluded, `apply` never walks up

Marker resolution walks up to `max_parent_depth` parents. At each level:

1. `.ai-worklog/` is authoritative; source is reported as `cwd_marker`.
2. `worklog/` or `integrations/` match as a legacy hint; source is reported as
   `cwd_legacy`, and the command states that the directory is not initialized.
3. No marker match is honored when the candidate directory is `global_home()`.

`prompt.log` and `jira` are removed from the marker list. A log file and a
service directory name are not evidence of a workspace, and `prompt.log` in
particular currently causes four repositories under `repos/` to impersonate
workspaces.

`worklog/` and `integrations/` are retained so that legacy workspaces predating
`.ai-worklog/` continue to be found.

Bare `workspace apply` targets the current directory exactly and does not walk
up, so it cannot silently converge a parent. Read-only commands keep the upward
walk.

Excluding `global_home()` restores the `default_workspace` fallback, which is
currently unreachable from any non-workspace directory under `$HOME`.

### D9 — Errors name the path and the source

`resolve_workspace_selection` already tags every result with a source
(`explicit_path`, `workspace_name`, `env_path`, `env_name`, `cwd_marker`,
`default_workspace`; plus `cwd_legacy` from D8). Every resolution failure
includes both:

```
Workspace is not registered: /Users/example (source: cwd_marker)
```

`apply` and `revert` print the resolved target and source before acting. This
replaces a confirmation prompt.

### D10 — Teardown: `revert` only, no deep purge

`workspace revert` removes exactly:

- IDE skill artifacts recorded in `.ai-worklog/setup.json` and provably managed
- integration directories that `apply` created and that are still empty
- the global registry entry

Because D6 removes the symlink form, there is no longer a structural test for
"the framework created this". `.ai-worklog/setup.json` therefore gains a record
of directories created by `apply`, and `revert` removes only directories in that
record, and only while they remain empty. A directory the user created is never
in the record and is never removed, even if empty.

`revert` never removes `.ai-worklog/`, `worklog/`, `repos/`, `tmp/`, any
non-empty directory, or any integration content. There is no `--purge` flag and
no deep-teardown command. Fully un-workspacing a directory is a deliberate
manual `rm -rf .ai-worklog`.

Consequence: after `revert` the directory remains a workspace (the marker
survives) but is unmaterialized and unregistered. Under D1 that is a legal
state, and `apply` restores it exactly, so `apply`/`revert` is a true inverse
pair with no drift.

Rationale: the framework becomes structurally incapable of destroying ticket
state or evidence, because no code path removes `.ai-worklog/`.

Directories such as `worklog/`, `repos/`, and `tmp/` are created by `apply` but
never removed by `revert`. The asymmetry is deliberate: these are where user data
accumulates, and an empty one is harmless.

## 3. Non-destruction guarantees

These are requirements, not consequences. Legacy workspaces must not be
corrupted, deleted, or modified beyond additive creation.

- **G1** Every `apply` action is create-if-absent. `apply` never deletes,
  overwrites, truncates, or moves an existing path.
- **G2** `apply` never writes inside an existing integration directory.
- **G3** `apply` treats an existing `integrations/<service>`, whether directory
  or symlink, as satisfied and plans nothing against it.
- **G4** An artifact the framework cannot prove it created is reported as a
  conflict and skipped, never replaced.
- **G5** No command removes a directory that contains files.
- **G6** No command removes `.ai-worklog/`, `worklog/`, `repos/`, `tmp/`, or the
  `integrations/` hub itself.
- **G7** `--dry-run` output is exhaustive: every action that would be taken is
  listed, including skips and their reasons.
- **G8** Adoption is byte-identical for pre-existing content: after `apply` on a
  legacy workspace, every file that existed before is unchanged.

G8 is the acceptance criterion for legacy safety and must be asserted by test,
not by inspection.

## 4. Migration

Breaking change of the same shape as the `setup`-into-`workspace` merge.

- `workspace init`, `workspace add`, `workspace repair`, `workspace remove`, and
  the `--apply` flag are removed.
- The removed subcommand names are retained as tombstones: recognized only to
  emit a redirecting error (`init` → "renamed to apply"), never to function.
- `scripts/bootstrap.sh` has three call sites to update, at lines 15, 18, and 21.
- `README.md` requires rewrites to the Workspace Setup section, the Named
  Workspaces section, and the promise at line 260 that removing a registration
  never deletes its directory. The supported-services list is also stale: it
  names 11 services where `shared/workspace-init.json` declares 12 (`automox`
  is missing from the docs).
- `docs/command-surface-audit.md` requires a command-surface change note.

## 5. Testing

Parity between the Groovy and Python runtimes is mandatory: human output must
match and exit codes must agree.

New coverage required:

1. **Legacy adoption fixture** — a directory containing `worklog/done/` with a
   file, `integrations/<service>/` as real directories with files, `repos/`,
   `tmp/`, and no `.ai-worklog/`. Assert G8 by checksum over all pre-existing
   files before and after `apply`.
2. **Symlink tolerance** — an existing `integrations/<service> -> ../<service>`
   is left intact and planned against, per G3.
3. **Marker precedence** — `.ai-worklog/` beats a legacy hint in the same
   directory; a legacy hint resolves with source `cwd_legacy`; `prompt.log` and
   `jira` no longer resolve anything.
4. **Global home exclusion** — resolution from a non-workspace directory under
   `$HOME` reaches `default_workspace` rather than resolving `$HOME`.
5. **Inverse property** — `apply`, `revert`, `apply`, `revert` leaves
   pre-existing content unchanged and the registry in its initial state.
6. **Error surface** — every resolution failure includes path and source.
7. **Preflight states** — empty service directory yields `not configured`;
   populated but failing yields `degraded` or `error`.

Existing baselines: 271 Python unit tests and 118 Groovy tests pass; the parity
suite has 8 known pre-existing failures unrelated to this work.

Cost note: the parity suite takes roughly nine minutes because
`bin/ai-worklog-groovy` recompiles 52 source files on every invocation, at about
2.69s per call across roughly 200 invocations. Compiling once and invoking via
`java` was measured at 0.32s per call, an 8.4x improvement projecting the suite
to 90–110s. For a change of this breadth the speedup should land first.

## 6. Implementation sequencing

The decisions are separable and should not land as one change. Recommended order,
each step independently shippable and verifiable:

1. **Test speedup** — precompile the Groovy sources so the parity suite runs in
   90–110s rather than nine minutes. Everything below depends on running that
   suite repeatedly.
2. **Discovery and errors (D8, D9)** — smallest change, highest immediate value,
   and it fixes live bugs independently of any command rename: `$HOME`
   impersonating a workspace, four repositories impersonating workspaces via
   `prompt.log`, the unreachable `default_workspace` fallback, and errors that
   name no path.
3. **Shape and guarantees (D5, D6, section 3)** — service directories, `repos/`,
   `tmp/`, plain integrations, and the G1–G8 test suite. Still under the existing
   command names.
4. **Verb consolidation (D2, D3, D4) and migration (section 4)** — the breaking
   change, once the behavior beneath it is already proven.
5. **Preflight states (D7)** — depends on step 3 having made every service
   directory exist.

## 7. Out of scope

- The `integration <name> <command>` namespace, which would move `jenkins` and
  its 14 subcommands off the top level. This concerns *using* integrations rather
  than setting up a workspace, and warrants its own spec.
- The service catalog. A catalog service is an application that is operated;
  an integration is a tool the framework talks to. They are different axes and
  the catalog should not track integrations.
- Toolchain command redundancy.
- Relocating credentials outside the workspace, which the removed symlink
  indirection previously enabled and which remains possible manually.
