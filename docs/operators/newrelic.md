# New Relic Operator

```bash
ai-worklog service newrelic profiles
ai-worklog service newrelic auth-test
ai-worklog service newrelic applications --query example
ai-worklog service newrelic application 123456
ai-worklog service newrelic violations
ai-worklog service newrelic issues --state activated
ai-worklog service newrelic nrql "SELECT count(*) FROM Transaction SINCE 1 hour ago"
ai-worklog service newrelic dashboard-export DASHBOARD-GUID --format terraform --output tmp/nr/dashboard --apply
ai-worklog service newrelic alert-condition-create POLICY_ID definition.json --confirm-policy POLICY_ID
```

New Relic uses profile-scoped keys in
`integrations/newrelic/newrelic.properties`: `PROFILE.api_key`,
`PROFILE.account_id`, optional `PROFILE.rest_url` and
`PROFILE.graphql_url`, plus global `newrelic.url`. Environment overrides
are `NEW_RELIC_PROFILE`/`NEWRELIC_PROFILE`, `NEW_RELIC_API_KEY`/`NEWRELIC_API_KEY`,
`NEW_RELIC_ACCOUNT_ID`/`NEWRELIC_ACCOUNT_ID`, `NEW_RELIC_REST_URL`, and
`NEW_RELIC_GRAPHQL_URL`. Existing `PROFILE.newrelic.*` and legacy unprefixed
`newrelic.api_key` / `newrelic.account_id` keys remain
compatible. Preflight checks for `newrelic.properties` without reading values;
connectivity uses `auth-test`.

The operator exposes 20 read actions (`profiles` through
`alert-condition`) and 7 apply-gated create/update actions for static NRQL
conditions and dashboard resources. All remote mutations and workspace export
writes are dry runs unless `--apply` is supplied; applied actions require the
matching confirmation flags. `dashboard-export` writes JSON to stdout or
generates Terraform under a workspace-local path with `--format terraform`,
`--output`, `--apply`, and optional `--force`. Delete operations, host-side
`newrelic-infra` / nri-flex / license / restart actions, and the legacy
`newrelic-cli` launcher are intentionally excluded. The former
`./scripts/run-groovy-tool.sh newrelic-cli ...` path fails with migration
guidance; use `ai-worklog service newrelic ...` instead.
