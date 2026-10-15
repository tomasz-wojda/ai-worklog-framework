# Automox Operator

```bash
ai-worklog service automox profiles
ai-worklog service automox auth-test
ai-worklog service automox orgs
ai-worklog service automox groups
ai-worklog service automox group 601134
ai-worklog service automox devices --query hostname
ai-worklog service automox device hostname
ai-worklog service automox device-packages hostname --state pending
ai-worklog service automox device-packages hostname --limit 500 --page 2
ai-worklog service automox activity --since 2026-09-01 --event system.patch.failed
ai-worklog service automox patch-summary --since 2026-09-01 --csv
ai-worklog service automox policies
ai-worklog service automox policy 987483
ai-worklog service automox policy-stats --policy 987483
ai-worklog service automox device-queue hostname --wait 60
ai-worklog service automox policy-run 987483 --device hostname
ai-worklog service automox policy-run 987483 --all --confirm-all 987483 --apply
ai-worklog service automox worklet-create NAME evaluation.sh remediation.sh --apply
ai-worklog service automox policy-delete 987483 --confirm-name NAME --apply
ai-worklog service automox device-move hostname 601134 --apply
ai-worklog service automox policy-add-group 987483 601134 --apply
ai-worklog service automox group-create NAME --notes TEXT
ai-worklog service automox group-create NAME --policy 987483 --apply
ai-worklog service automox policy-remove-group 987483 601134 --apply
ai-worklog service automox policy-set-groups 987483 601134 601135 --apply
ai-worklog service automox activity --since 2026-10-14 --event all --limit 5000
```

Automox uses profile-scoped keys in
`integrations/automox/automox.properties`: `org`, `api_token`,
`enrollment_key`, `api_base_url`, `domain`, and `ssh_user`. Environment
overrides are `AUTOMOX_PROFILE`, `AUTOMOX_API_TOKEN`,
`AUTOMOX_ENROLLMENT_KEY`, `AUTOMOX_ORG`, and `AUTOMOX_API_BASE_URL`. Legacy
`token` and `server-id` files are read as non-executable fallbacks.

`device-packages` and `patch-summary` support RFC 4180 CSV output. Every
external mutation is a dry run unless `--apply` is supplied. Policy-wide runs
also require `--confirm-all` to match the policy id, and deletion requires
`--confirm-name` to match the current policy name. SSH agent operations, batch
enrollment, and setup wrappers are outside this operator.

Policy schedules are decoded from the Automox bitmasks, where bit 1 is
reserved. Days use 2 for Monday through 64 for Saturday and 128 for Sunday,
weeks of the month use 2 through 32 for weeks one to five, and months use 2
for January through 4096 for December. The report keeps the raw
`schedule_days`, `schedule_weeks_of_month`, and `schedule_months` values next
to the decoded lists, and names any undefined bits in the report message.
Earlier versions decoded Sunday from bit 1, reported every week one week
early, and dropped week five; re-run any saved `policy` reports before relying
on their schedules.

`device` reports `next_patch_time` and each assigned policy's `id`, `name`,
and `next_remediation`. `next_remediation_reliable` is false while the operator
rules list `next_remediation` as unreliable, which is the shipped default;
confirm the schedule with `policy` before acting on it.

`policy-remove-group` and `policy-set-groups` send one full-object PUT built
from a fresh GET, including `id` and `organization_id`, then re-read the
policy and fail if anything other than `server_groups` and `server_count`
changed. Leaving a policy with no server groups requires `--allow-empty`.
`group-create` refuses an existing name regardless of case and defaults the
parent to the organization default group.

`activity` rows share `event_id` within one event, one row per package, so
deduplicate rows on `event_id`, `hostname`, and `package_name`. `--event all`
returns every event type.

`device-packages` returns at most `--limit` packages per call. Use `--page`,
starting at 1, to read later pages; `totals.page` and `totals.pages` show the
position, and a page past the last one is rejected.
