# Purpose: Main PowerShell dispatcher for ai-worklog CLI commands.
# Role: Parses runtime flags, inspects configuration, and routes execution to Groovy or Python runtimes in PowerShell.

[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments=$true)]
    [string[]]$ScriptArgs
)

# Resolve root directory of the framework
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root = (Get-Item "$ScriptDir\..").FullName

$Runtime = $env:AI_WORKLOG_RUNTIME
$PassArgs = @()

for ($i = 0; $i -lt $ScriptArgs.Count; $i++) {
    $arg = $ScriptArgs[$i]
    if ($arg -eq "--runtime" -and ($i + 1) -lt $ScriptArgs.Count) {
        $Runtime = $ScriptArgs[$i + 1]
        $i++
    } elseif ($arg.StartsWith("--runtime=")) {
        $Runtime = $arg.Substring(10)
    } else {
        $PassArgs += $arg
    }
}

if (-not $Runtime) {
    $Runtime = "groovy"
}

if ($Runtime -eq "groovy") {
    & "$ScriptDir\ai-worklog-groovy.ps1" @ScriptArgs
    exit $LASTEXITCODE
} elseif ($Runtime -eq "python") {
    $PythonExec = if (Get-Command py -ErrorAction SilentlyContinue) { "py" } else { "python" }
    $env:PYTHONPATH = "$Root\python\src" + [System.IO.Path]::PathSeparator + $env:PYTHONPATH
    & $PythonExec -m ai_worklog_framework.cli @ScriptArgs
    exit $LASTEXITCODE
} else {
    [Console]::Error.WriteLine("Unsupported runtime: $Runtime (expected groovy or python)")
    exit 1
}
