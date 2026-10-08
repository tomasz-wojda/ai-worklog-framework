# Jenkins Operator

```bash
ai-worklog service jenkins controllers
ai-worklog service jenkins health primary
ai-worklog service jenkins job primary folder/job --builds 5 --parameters
ai-worklog service jenkins plugins primary --require workflow-job
ai-worklog service jenkins credentials primary --domain _
ai-worklog service jenkins seed primary seed-job
ai-worklog service jenkins syntax-check Jenkinsfile
ai-worklog service jenkins artifacts primary folder/job last-successful
ai-worklog service jenkins artifacts primary folder/job last-completed
ai-worklog service jenkins artifacts primary folder/job 42
ai-worklog service jenkins download-artifact primary folder/job last-successful dist/app.jar
ai-worklog service jenkins download-artifact primary folder/job 42 dist/app.jar --apply
ai-worklog service jenkins download-artifact primary folder/job 42 dist/app.jar --apply --force
ai-worklog service jenkins job-export primary folder/job
ai-worklog service jenkins job-export primary folder/job --apply
ai-worklog service jenkins job-export primary folder/job --apply --force --cwd
ai-worklog service jenkins run-script primary list-jobs.groovy
ai-worklog service jenkins run-script primary list-jobs.groovy --apply
cat list-jobs.groovy | ai-worklog service jenkins run-script primary - --apply
ai-worklog service jenkins run-script primary --script 'println Jenkins.instance.numExecutors' --apply
```

Jenkins operations do not mutate the controller, except `run-script --apply`,
which runs Groovy with full controller privileges in the Script Console.
Credential output is limited
to identifiers and descriptive metadata, build parameters omit values, and
syntax validation delegates to the configured `ai-vault` validator. An artifact
build selector is `last-successful`, `last-completed`, or a positive build
number.

`artifacts` lists metadata. `download-artifact` selects one exact,
case-sensitive artifact relative path and plans a local download. Add `--apply`
to write it. Existing files are refused unless `--force` is also supplied.
Nested job and artifact paths are preserved under
`tmp/services/jenkins/<controller>/<job>/<resolved-build-number>/`. Downloads
stream through a temporary file, default to a five-minute timeout, and are
limited to 1 GiB. Run `ai-worklog service jenkins download-artifact --help` for complete
usage.

`job-export` checks that the job exists and plans an export of its `config.xml`.
Add `--apply` to write it to
`tmp/services/jenkins/<controller>/<job>/config.xml`, or add `--cwd` to write
`<folder>_<job>_config.xml` to the current directory instead. Existing files
are refused unless `--force` is also supplied. The export is raw and keeps
Jenkins-encrypted values, so do not commit it. Reading `config.xml` requires
the Job/ExtendedRead or Configure permission.

`run-script` takes its Groovy from exactly one source: a UTF-8 file path, `-`
for standard input, or `--script '<code>'`. Scripts run only on controllers
whose `jenkins.properties` entry includes `<id>.run_scripts=true`; add that key
when adding a controller that may run scripts. A missing key, or any other
value, means false, and `controllers` shows the effective `run_scripts` value.
Without `--apply` the command reports the source, size and SHA-256 of the
script without contacting Jenkins. With `--apply` it posts the script to
`/scriptText` and captures up to 1 MiB of output; longer output is truncated
and the report is degraded. Jenkins returns HTTP 200 even when the script
throws, so a failing script reports success with the stack trace as output.

The former `ai-worklog jenkins ...` path was removed in version 0.10.0.
Existing scripts must use `ai-worklog service jenkins ...`.
