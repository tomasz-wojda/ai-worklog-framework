# Purpose: PowerShell entrypoint for executing Groovy tools in ai-worklog-framework.
# Role: Resolves framework root directory and executes Groovy Main class with arguments in PowerShell.

[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments=$true)]
    [string[]]$ScriptArgs
)

# Resolve root directory of the framework
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root = (Get-Item "$ScriptDir\..").FullName

# Set environment variable for framework root
$env:AI_WORKLOG_FRAMEWORK_ROOT = $Root

# Determine Groovy executable
$GroovyExec = if ($env:AI_WORKLOG_GROOVY) { $env:AI_WORKLOG_GROOVY } else { "groovy" }

# Check if Groovy command exists
$CommandCheck = Get-Command $GroovyExec -ErrorAction SilentlyContinue
if (-not $CommandCheck) {
    [Console]::Error.WriteLine("Groovy is required for the default runtime. Use --runtime python as fallback.")
    exit 2
}

# Execute Groovy Main script with passed arguments
& $GroovyExec -cp "$Root\groovy\src\main\groovy" "$Root\groovy\src\main\groovy\ai\worklog\framework\Main.groovy" @ScriptArgs
exit $LASTEXITCODE
