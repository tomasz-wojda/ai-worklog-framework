@echo off
:: Purpose: Main Windows Batch dispatcher for ai-worklog CLI commands.
:: Role: Parses runtime flags, inspects configuration, and routes execution to Groovy or Python runtimes.

setlocal enabledelayedexpansion

:: Resolve root directory of the framework
set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "ROOT=%%~fI"

set "RUNTIME="
set "ARGS="

:: Parse runtime argument from passed options
set "NEXT_IS_RUNTIME=0"
for %%A in (%*) do (
    if "!NEXT_IS_RUNTIME!"=="1" (
        set "RUNTIME=%%~A"
        set "NEXT_IS_RUNTIME=0"
    )
    if "%%~A"=="--runtime" (
        set "NEXT_IS_RUNTIME=1"
    )
    if "%%~A"=="--runtime=groovy" set "RUNTIME=groovy"
    if "%%~A"=="--runtime=python" set "RUNTIME=python"
)
:after_parse

:: Check environment variable fallback
if not defined RUNTIME (
    if defined AI_WORKLOG_RUNTIME (
        set "RUNTIME=%AI_WORKLOG_RUNTIME%"
    )
)

:: Default runtime is groovy
if not defined RUNTIME (
    set "RUNTIME=groovy"
)

:: Dispatch to selected runtime
if /i "%RUNTIME%"=="groovy" goto do_groovy
if /i "%RUNTIME%"=="python" goto do_python

echo Unsupported runtime: %RUNTIME% (expected groovy or python) >&2
exit /b 1

:do_groovy
call "%SCRIPT_DIR%ai-worklog-groovy.cmd" %*
exit /b %ERRORLEVEL%

:do_python
set "PYTHON_EXEC=python"
where py >nul 2>&1
if %ERRORLEVEL% equ 0 set "PYTHON_EXEC=py"

set "PYTHONPATH=%ROOT%\python\src;%PYTHONPATH%"
%PYTHON_EXEC% -m ai_worklog_framework.cli %*
exit /b %ERRORLEVEL%
