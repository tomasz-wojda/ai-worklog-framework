# Artifactory Operator

```bash
ai-worklog service artifactory profiles
ai-worklog service artifactory status
ai-worklog service artifactory auth-test libs-release-local
ai-worklog service artifactory repositories --type local --query release
ai-worklog service artifactory artifacts libs-release-local com/example --recursive --since 2026-09-01
ai-worklog service artifactory artifact libs-release-local com/example/app/1.0/app-1.0.jar
ai-worklog service artifactory manifest libs-release-local releases/app/manifest.json --max-bytes 4096
```

Every action is read-only and accepts `--json`, `--profile`, and `--timeout`.
`manifest` returns bounded UTF-8 text without writing anything to disk; content
that is not text is refused, and content over `--max-bytes` is truncated.

Credentials resolve in this order:

1. `ARTIFACTORY_URL`, `ARTIFACTORY_TOKEN`, and `ARTIFACTORY_AUTH_SCHEME`.
2. `integrations/artifactory/artifactory.properties`, using `<profile>.url`,
   `<profile>.token`, and `<profile>.auth_scheme` for the selected profile, then
   unprefixed, `artifactory.`, or `default.` keys.
3. Legacy `creds` or `credentials` files in the same directory.

The profile comes from `--profile`, then `ARTIFACTORY_PROFILE`, then
`default`. `auth_scheme` is `auto`, `bearer`, or `api-key`; `auto` selects
`api-key` for tokens starting with `AKCp` and `bearer` otherwise. `profiles`
reports which sources are set without printing URLs or tokens.

Listing limits default to 200 repositories or artifacts (maximum 1000), and
`manifest` reads 64 KiB by default (maximum 1 MiB). Results beyond a limit are
reported as truncated.
