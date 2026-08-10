"""
commands.py — Toolchain CLI: check, list, and env export for named tools.

Inputs:
  - Parsed CLI arguments for toolchain subcommand.

Outputs:
  - Human-readable report or shell-exportable environment variables.
"""

from ai_worklog_framework.cli import EXIT_SUCCESS, EXIT_USER_ERROR
from ai_worklog_framework.config import load_config
from ai_worklog_framework.paths import resolve_workspace
from ai_worklog_framework.toolchain.resolver import (
    DEFAULT_TOOL_SPECS,
    build_toolchain_env,
    check_toolchain,
    detect_groovy_runtimes,
    detect_java_runtimes,
    resolve_tool_environment,
)


def run(args) -> int:
    if not args.toolchain_action:
        print("Usage: ai-worklog toolchain {check|list|env}")
        return EXIT_USER_ERROR

    workspace = resolve_workspace(
        getattr(args, "workspace", None),
        getattr(args, "workspace_name", None),
    )
    config = load_config(workspace)
    toolchain_cfg = config.toolchain

    if args.toolchain_action == "check":
        return _check(toolchain_cfg)
    if args.toolchain_action == "list":
        return _list(toolchain_cfg)
    if args.toolchain_action == "env":
        return _env(toolchain_cfg, args.tool)
    return EXIT_USER_ERROR


def _check(toolchain_cfg) -> int:
    results = check_toolchain(toolchain_cfg)
    print(results.summary())
    print()
    overall = results.overall_status.value.upper()
    print(f"Toolchain: {overall}")
    return EXIT_SUCCESS if results.ok else EXIT_USER_ERROR


def _list(toolchain_cfg) -> int:
    java_rts = detect_java_runtimes(toolchain_cfg)
    groovy_rts = detect_groovy_runtimes(toolchain_cfg)

    print("Configured tools:")
    for name, spec in DEFAULT_TOOL_SPECS.items():
        req = f"Java {spec.java_major}"
        if spec.groovy_major:
            req += f", Groovy {spec.groovy_major}+"
        print(f"  {name}: {req}")
        print(f"    {spec.description}")

    print()
    print("Resolved environments:")
    for name in DEFAULT_TOOL_SPECS:
        env = resolve_tool_environment(name, toolchain_cfg, java_rts, groovy_rts)
        icon = "OK" if env.ready else "BLOCKED"
        print(f"  [{icon}] {name}: {env.message}")
    return EXIT_SUCCESS


def _env(toolchain_cfg, tool_name: str) -> int:
    if tool_name not in DEFAULT_TOOL_SPECS:
        print(f"Unknown tool: {tool_name}")
        print(f"Available: {', '.join(sorted(DEFAULT_TOOL_SPECS.keys()))}")
        return EXIT_USER_ERROR

    java_rts = detect_java_runtimes(toolchain_cfg)
    groovy_rts = detect_groovy_runtimes(toolchain_cfg)
    tool_env = resolve_tool_environment(tool_name, toolchain_cfg, java_rts, groovy_rts)

    if not tool_env.ready:
        print(f"# BLOCKED: {tool_env.message}")
        return EXIT_USER_ERROR

    env = build_toolchain_env(tool_env)
    print(f"# Environment for {tool_name}")
    print(f"export JAVA_HOME={env['JAVA_HOME']}")
    print(f"export PATH={env['PATH']}")
    if tool_env.groovy_executable:
        print(f"# Groovy: {tool_env.groovy_executable}")
    return EXIT_SUCCESS
