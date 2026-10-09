# Automox Jenkins Update

Jenkins controllers installed from the `jenkins.noarch` RPM receive core
upgrades through their Automox patch policy. This runbook confirms what each
controller runs, when Automox upgrades it, how to finish plugin updates, and
how to clear vulnerable jars that agents cached from an older core.

## 1. Inventory

```bash
ai-worklog service jenkins controllers
ai-worklog service jenkins nodes <controller>
```

`controllers` lists every configured controller and its effective
`run_scripts` and `admin_actions` values. `nodes` maps agent hosts to the
controller that serves them, which decides when an agent's cached jars stop
being refreshed.

## 2. Installed RPM against running core

```bash
ai-worklog service automox device-packages <host> --query jenkins --state all
ai-worklog service jenkins health <controller>
```

`device-packages` returns the installed `jenkins.noarch` version and any
pending one. Pass `--query` to filter the list; without it, results arrive in
alphabetical pages of `--limit` packages, and `--page` reads the later pages.
`health` reports the running core as `core_version`, taken from the
controller's `X-Jenkins` header. An installed RPM newer than the running core
means Jenkins has not restarted. A second controller on the same host under
another port is a separate installation that the RPM does not upgrade.

## 3. Upgrade schedule

```bash
ai-worklog service automox device <host>
ai-worklog service automox group <server_group_id>
ai-worklog service automox policies
ai-worklog service automox policy <policy_id>
ai-worklog service automox activity --device <host> --since <YYYY-MM-DD>
```

`device` gives the server group, `next_patch_time`, and each assigned policy
with its `next_remediation`. `policies` lists the policies attached to that
group, and `activity` shows each run's installed package list. A run that
patched the host without `jenkins` in its list did not upgrade the core.

`policy` decodes the schedule into `days`, `weeks_of_month`, `months`, and
`time`, and keeps the raw `schedule_days`, `schedule_weeks_of_month`, and
`schedule_months` values. Undefined bits appear in the report message.
`next_remediation_reliable` is false by default, so check the decoded
schedule before trusting `next_remediation`. `next_remediation` is UTC.

To upgrade one controller before its window:

```bash
ai-worklog service automox policy-run <policy_id> --device <host>
ai-worklog service automox policy-run <policy_id> --device <host> --apply
```

Policies with `auto_reboot` restart the host, which restarts Jenkins.

## 4. Plugin updates

```bash
ai-worklog service jenkins plugins <controller>
ai-worklog service jenkins plugins install <controller> bouncycastle-api
ai-worklog service jenkins plugins install <controller> bouncycastle-api --apply
ai-worklog service jenkins safe-restart <controller>
ai-worklog service jenkins safe-restart <controller> --apply
```

Both commands require `<id>.admin_actions=true` in `jenkins.properties`.
`plugins install` shows whether each plugin will be installed, upgraded, or
left as current, and `--apply` queues the download without waiting for it.
Install after the core upgrade so one restart activates both. A controller
whose update list predates the plugin release reports the plugin as current
until its update center refreshes.

`safe-restart` restarts only when no build is running, including Pipeline
builds. While builds run, the dry run is degraded and `--apply` refuses with
exit code 3. When the controller is idle, `--apply` puts it in quiet-down,
checks again, and then schedules the restart. If a build starts between the
checks, the command refuses and leaves the controller in quiet-down; wait for
the build to finish and run `safe-restart --apply` again, or cancel the
quiet-down with **Cancel Shutdown**.

## 5. Agent jar cache

Agents keep classes they received from the controller under
`<agent home>/remoting/jarCache/<first two hex digits>/<rest>.jar`. While the
controller still ships a vulnerable jar, builds download it again on demand,
not only on reconnect, so deleting it lasts only until the next build. Once
the controller runs the fixed core, remove the stale file from each agent
listed by `nodes` and confirm that no other copy of the library remains in the
cache.

## 6. Verification

```bash
ai-worklog service jenkins health <controller>
ai-worklog service jenkins plugins <controller>
ai-worklog service automox device-packages <host> --query kernel --state all
```

Confirm the core version, the plugin version, the bundled library versions
under `plugins/<plugin>/WEB-INF/optional-lib`, and the libraries in
`/var/cache/jenkins/war/WEB-INF/lib`. A kernel installed by the same policy
takes effect only after an operating system reboot; a Jenkins restart is not
enough.
