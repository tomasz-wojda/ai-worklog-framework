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
ai-worklog service jira comment PROJ-1234 "Deployed to STAGE"
ai-worklog service jira comment PROJ-1234 --file comment.txt
ai-worklog service jira comment PROJ-1234 --file comment.txt --apply
ai-worklog service jira comment-edit PROJ-1234 10001 --file comment.txt
ai-worklog service jira comment-edit PROJ-1234 10001 --file comment.txt --apply
```

Jira and Tempo use `integrations/jira/jira.properties`. Ticket reads include
description, people, linked issues, assignment history, and paginated comments
and worklogs. Summary, rejected, and reporter searches are bounded and support
JSON output. Board-specific status IDs belong in
`integrations/jira/jira-operator.json`.

Tempo logging is a dry-run unless `--apply` is supplied. Verification compares
Tempo entries with primary files directly under `worklog/`; archived files and
`_jira.log` or `_raw.log` companions are excluded.

`comment` takes the body as text words or from a UTF-8 `--file`, in Jira wiki
markup, and is a dry-run unless `--apply` is supplied. Every run checks the
issue, resolves each `[~username]` mention through `/rest/api/2/user`, and
refuses inactive or unknown users and a body identical to an existing comment.
Bodies are limited to `limits.issue_comment_max_characters` (32767). After
posting, the body Jira returns must match the one sent; the report then carries
`comment_id` and `comment_url`.

`comment-edit` replaces the body of a comment whose author matches
`/rest/api/2/myself`, and refuses comments by other authors and a body equal to
the current one. It applies the same mention and length checks, verifies that
Jira returns the same comment ID and body, and reports the old and new body
sha256 and character counts.

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

```bash
ai-worklog service jira boards --project PROJ
ai-worklog service jira boards --name "Team Board" --limit 20
ai-worklog service jira board 123
```

Board reads are GET-only calls to the Agile REST API at
`/rest/agile/1.0/board`. `boards` lists boards with type and project. `board`
shows one board's columns with their statuses and, for scrum boards, the active
sprints. Its `status_ids` output has the same shape as `board.status_ids` in
`integrations/jira/jira-operator.json` and can be copied there.
