# Jira Operator

```bash
ai-worklog service jira ticket PROJ-1234
ai-worklog service jira summary --limit 50
ai-worklog service jira rejected
ai-worklog service jira reporter "Display Name"
ai-worklog service jira tempo 2026-09-09
ai-worklog service jira verify 2026-09-09
ai-worklog service jira whoami
ai-worklog service jira log-time PROJ-1234 2026-09-09 3600 "Work summary"
ai-worklog service jira log-time PROJ-1234 2026-09-09 3600 "Work summary" --apply
```

Jira and Tempo use `integrations/jira/jira.properties`. Ticket reads include
description, people, linked issues, assignment history, and paginated comments
and worklogs. Summary, rejected, and reporter searches are bounded and support
JSON output. Board-specific status IDs belong in
`integrations/jira/jira-operator.json`.

Tempo logging is a dry-run unless `--apply` is supplied. Verification compares
Tempo entries with primary files directly under `worklog/`; archived files and
`_jira.log` or `_raw.log` companions are excluded.

```bash
ai-worklog service jira assets-schemas
ai-worklog service jira assets-types 3
ai-worklog service jira assets-attributes 12
ai-worklog service jira assets-object CI-1234
ai-worklog service jira assets-search 'Name = "A.A.PROD.App"' --limit 20
ai-worklog service jira get-ci CI-1234
ai-worklog service jira get-cis
ai-worklog service jira get-cis --env PROD --json
```

Jira Assets (Insight) reads are GET-only and use the same credentials. Object
output maps attribute names to their display values. `get-ci` shows one
application CI, labelled `A.A.<ENV>.<App>`, with the attributes named in
`ci.fields`; `get-cis` lists application CI keys, ids, and names sorted by name.
Searches use IQL at `/rest/insight/1.0/iql/objects`; an instance that only
serves AQL sets `api_paths.assets_search` to `/aql/objects` and
`api_params.assets_search_query` to `qlQuery` in
`integrations/jira/jira-operator.json`, which can also override `ci.fields`,
`ci.search_iql`, and `ci.env_search_iql`.
