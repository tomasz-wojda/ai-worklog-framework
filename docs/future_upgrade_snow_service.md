# Future upgrade: `service snow` (ServiceNow, read-only)

Status: deferred. Nothing in this document is implemented. It records the
design for a read-only ServiceNow operator so the work can be picked up
without repeating the research.

## Evidence (session 67fcbd8e, 25 Aug - 15 Oct 2026)

- Authentication uses a browser session: a `Cookie` header plus the `g_ck`
  value sent as `X-UserToken`, both kept in `integrations/snow/helper`
  together with the instance host.
- The Table API (`/api/now/table/sc_task`) returns HTTP 401 with that
  session. Only the `JSONv2` processor works:
  `https://<instance>/<table>.do?JSONv2&displayvalue=all&sysparm_query=...&sysparm_fields=...&sysparm_limit=...`
- All calls made were reads. No ServiceNow writes went through the API.
- Tables used: `sc_task`, `sc_task_list`, `sc_req_item`, `sys_attachment`,
  `sys_attachment_list`, `sys_user`, `sys_user_group`, `sys_user_grmember`,
  `sys_journal_field`. Attachments are downloaded from
  `sys_attachment.do?sys_id=<id>`.
- Vulnerability reports attached to SCTASKs are XLSX files with a single
  sheet (`Sheet1`) and a fixed layout of 79 columns. Twelve samples under
  `tmp/snow/vuln_analysis/` share the header that starts
  `Environment Type, Business Service, Asset Type, Asset Name, Targets, T,
  Sys Id, Status, Name, Location Path, Detection Method, Remediation, ...`.
  Date columns hold Excel serial numbers.
  `NAB_Stage_High_25_08_26.xlsx` has no header row; data starts in row 1.

## Prerequisite

Before any code is written, run a probe of `integrations/snow/helper` that
prints only each line's key name and value length, never a value. The
credential parser below assumes keys `instance`, `cookie`, and `token` or
`g_ck`, in `key=value` or `key: value` form. If the probe shows a
different shape, revise the parser contract first.

## Layout

Following the other operators:

- `shared/snow-operator-rules.json`
- `groovy/.../adapters/SnowCredentials.groovy`
- `groovy/.../adapters/SnowAdapter.groovy`
- `groovy/.../snow/SnowOperatorReport.groovy` (extends `OperatorReport`,
  with a `KEPT_ITEM_KEYS` allowlist)
- `groovy/.../snow/XlsxReader.groovy`
- `groovy/.../snow/VulnerabilityReport.groovy`
- `groovy/.../commands/SnowCommands.groovy`
- Registration in `ServiceCommands`, `shared/preflight-checks.json`, and
  `shared/workspace-init.json`
- `shared/command-contract.json` section `service snow`
- `schemas/snow-operator-report.schema.json`
- `docs/operators/snow.md`
- Tests: `SnowOperatorTest`, `XlsxReaderTest`, `VulnerabilityReportTest`,
  plus updated counts in `CommandContractTest` and
  `ServiceCatalogCommandTest`

## Credentials and transport

- Resolution order: environment `SNOW_INSTANCE`, `SNOW_COOKIE`,
  `SNOW_USER_TOKEN`, then `integrations/snow/helper`. Only the adapter
  parses the file. Reports expose `instance`, `has_cookie`, and
  `has_token`, never the values.
- Requests send `Cookie` and `X-UserToken` headers and use `ReadOnlyHttp`
  with its `truncated` flag; a truncated body is an error.
- HTTP 401 or 403, or an HTML login page in place of JSON, is BLOCKED:
  `ServiceNow session expired; refresh integrations/snow/helper`.
- JSON without `records` is ERROR: `Malformed ServiceNow response`.

## Rules file

- `instance` default `wbd.service-now.com`.
- Field lists per table:
  - `sc_task`: `number, sys_id, state, priority, assigned_to,
    assignment_group, due_date, request_item, short_description,
    description, opened_at, closed_at`
  - `sc_req_item`: `number, sys_id, short_description, opened_by,
    requested_for, description`
  - `sys_attachment`: `sys_id, file_name, size_bytes, content_type,
    sys_created_on`
  - `sys_user`: `sys_id, name, user_name, email`
  - `sys_user_group`: `sys_id, name, manager`
  - `sys_user_grmember`: `user, group`
  - `sys_journal_field`: `element, value, sys_created_on, sys_created_by`
- Closed task states: `[3, 4, 7]`.
- Limits: `tasks_default` 100, `tasks_max` 1000, `attachment_max_bytes`
  104857600, `xlsx_entry_max_bytes` 52428800.
- Download root: `tmp/snow`.
- The 79 canonical report columns, in order.

## Commands

| Command | Behaviour |
|---|---|
| `auth-test` | `sys_user.do?JSONv2&sysparm_query=user_name=javascript:gs.getUserName()&sysparm_limit=1`; READY with the user name, or BLOCKED. |
| `task <SCTASK>` | Positional `^SCTASK[0-9]+$`. Task fields, the parent RITM from `request_item`, attachments on the task and the RITM, and the journal entry count. `--journal` adds work notes and comments, newest first. |
| `tasks --group <sys_id\|name>` | `--active`, `--query <text>` (`short_descriptionLIKE<text>`), `--assignee <sys_id\|user_name>`, `--closed-since <YYYY-MM-DD>` (`stateIN3,4,7^closed_at>=<date>`), `--limit`. A group name resolves through `sys_user_group`. Ordered by number; truncation reported at the limit. |
| `attachments <SCTASK>` | Lists task and RITM attachments. `--download [<dir>]` (default `tmp/snow/<SCTASK>/`) requires `--apply`, refuses to overwrite without `--force`, enforces `attachment_max_bytes`, and reports path, size, and SHA-256. |
| `report <SCTASK\|file.xlsx>` | Parses a downloaded report, or the task's downloaded `.xlsx` attachments (otherwise errors with `run attachments --download first`). `--by asset\|package\|location\|cve` (default `asset`), `--status open\|all` (default `open`), `--limit`, `--csv`. |
| `closed-stats --group <id\|name> --since <date>` | Members from `sys_user_grmember`, then closed task counts per member, sorted descending. |
| `user <sys_id\|user_name>` | One user. |
| `group-members <sys_id\|name>` | Group details and members. |

## XLSX parsing

- `XlsxReader` uses `java.util.zip` and the JDK XML parser; no new
  dependency.
- Reads `xl/workbook.xml`, `xl/sharedStrings.xml`, and the first worksheet.
  Handles shared strings (`t="s"`), inline strings (`t="inlineStr"`), and
  numeric cells; converts Excel serial dates for the date columns
  (`Due date`, `Date created`, `First found`, `Last found`, `Last fixed`,
  `Resolved At`, `Last updated`, `Last synced`, `Date closed`,
  `Last Patching Date`).
- Rejects any zip entry over `xlsx_entry_max_bytes` uncompressed.

## Report aggregation

- The header row is detected when the first cell is `Environment Type`.
  Without it, the canonical column order from the rules is applied.
- Totals: rows, distinct assets, distinct CVEs (`Name`), counts by
  `Status`, by `Compliance status` (SLA), by `Detection Method`, by
  `Risk rating`, and the internet-facing count (`Accessible From Internet`
  = 1).
- Groups by `Asset Name`, package (from `Summary` before `:`),
  `Location Path`, or CVE, each with a row count, worst `CVSS Score`, and
  earliest `Due date`.

## Tests

- Credential parsing, with values never present in any output.
- 401 and HTML login bodies map to BLOCKED.
- Query strings for each `tasks` option.
- RITM and attachment composition for `task`.
- Download gate, overwrite refusal, and size cap.
- XLSX fixtures built in the test with `ZipOutputStream`: with and without
  a header, shared and inline strings, serial dates.
- Totals and each grouping.
- Report schema validation and the 8 contract actions.

## Live checks (read-only)

- `auth-test`
- `task SCTASK1747278 --journal`
- `tasks --group <team group sys_id> --active --query ulnerab`
- `closed-stats --group <team group sys_id> --since 2026-10-08`
- `report <downloaded vulnerability report>.xlsx`
- `report` on the header-less `NAB_Stage_High_25_08_26.xlsx`
