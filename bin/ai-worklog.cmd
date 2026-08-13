@echo off
:: Purpose: Main Windows Batch dispatcher for ai-worklog CLI commands.
:: Role: Parses runtime flags, inspects configuration, and routes execution to Groovy or Python runtimes.

setlocal enabledelayedexpansion

:: Resolve root directory of the framework
set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "ROOT=%%~fI"

set "RUNTIME="
set "ARGS="

:: Parse runtime argument if provided as first parameter
:parse_loop
if "%~1"=="" goto after_parse
if "%~1"=="--runtime" (
    set "RUNTIME=%~2"
    shift
    shift
    goto parse_loop
)
if "%~1"=="--runtime=groovy" (
    set "RUNTIME=groovy"
    shift
    goto parse_loop
)
if "%~1"=="--runtime=python" (
    set "RUNTIME=python"
    shift
    goto parse_loop
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
if /i "%RUNTIME%"=="groovy" (
    call "%SCRIPT_DIR%ai-worklog-groovy.cmd" %*
    exit /b %ERRORLEVEL%
)

if /i "%RUNTIME%"=="python" (
    :: Locate Python interpreter on Windows
    set "PYTHON_EXEC=python"
    where py >nul 2>&1
    if !ERRORLEVEL! equ 0 set "PYTHON_EXEC=py"
    
    set "PYTHONPATH=%ROOT%\python\src;%PYTHONPATH%"
    !PYTHON_EXEC! -m ai_worklog_framework.cli %*
    exit /b %ERRORLEVEL%
)

echo Unsupported runtime: %RUNTIME% (expected groovy or python) >&2
exit /b 1
