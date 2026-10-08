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
