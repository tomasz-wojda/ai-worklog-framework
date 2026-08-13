@echo off
:: Purpose: Windows Batch entrypoint for executing Groovy tools in ai-worklog-framework.
:: Role: Resolves framework root directory and executes Groovy Main class with arguments.

setlocal enabledelayedexpansion

:: Resolve root directory of the framework
set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "ROOT=%%~fI"

:: Set environment variable for framework root
set "AI_WORKLOG_FRAMEWORK_ROOT=%ROOT%"

:: Determine Groovy executable
if defined AI_WORKLOG_GROOVY (
    set "GROOVY_EXEC=%AI_WORKLOG_GROOVY%"
) else (
    set "GROOVY_EXEC=groovy"
)

:: Check if Groovy command exists
where %GROOVY_EXEC% >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo Groovy is required for the default runtime. Use --runtime python as fallback. >&2
    exit /b 2
)

:: Execute Groovy Main script with passed arguments
if not defined JAVA_OPTS set "JAVA_OPTS=-Dfile.encoding=UTF-8"
%GROOVY_EXEC% -cp "%ROOT%\groovy\src\main\groovy" "%ROOT%\groovy\src\main\groovy\ai\worklog\framework\Main.groovy" %*
exit /b %ERRORLEVEL%
