"""
cli.py — Command routing and stable exit codes for the ai-worklog entry point.

Provides a top-level dispatcher that routes subcommands to their respective modules.
Exit codes: 0 = success, 1 = user error, 2 = system/adapter error, 3 = blocked.
"""

import sys
import argparse
from typing import List, Optional


EXIT_SUCCESS = 0
EXIT_USER_ERROR = 1
EXIT_SYSTEM_ERROR = 2
EXIT_BLOCKED = 3


def build_parser() -> argparse.ArgumentParser:
    """
    Constructs the argument parser tree for all ai-worklog subcommands.

    Returns:
        argparse.ArgumentParser with registered subcommands.
    """
    parent_parser = argparse.ArgumentParser(add_help=False)
    parent_parser.add_argument(
        "--workspace", type=str, default=argparse.SUPPRESS,
        help="Override workspace root (default: auto-detect)"
    )

    parser = argparse.ArgumentParser(
        prog="ai-worklog",
        description="DevOps daily workflow automation framework",
        parents=[parent_parser],
    )
    parser.add_argument(
        "--version", action="store_true", help="Print version and exit"
    )

    subparsers = parser.add_subparsers(dest="command")

    # catalog
    catalog_parser = subparsers.add_parser("catalog", help="Service catalog operations", parents=[parent_parser])
    catalog_sub = catalog_parser.add_subparsers(dest="catalog_action")
    catalog_sub.add_parser("validate", help="Validate catalog schemas and entries")
    catalog_show = catalog_sub.add_parser("show", help="Show catalog entry")
    catalog_show.add_argument("service", help="Service identifier")
    catalog_search = catalog_sub.add_parser("search", help="Search catalog")
    catalog_search.add_argument("query", help="Search term")

    # ticket
    ticket_parser = subparsers.add_parser("ticket", help="Ticket operations", parents=[parent_parser])
    ticket_sub = ticket_parser.add_subparsers(dest="ticket_action")
    ticket_prepare = ticket_sub.add_parser("prepare", help="Generate preparation report")
    ticket_prepare.add_argument("key", help="JIRA ticket key")

    # preflight
    preflight_parser = subparsers.add_parser("preflight", help="Environment preflight checks", parents=[parent_parser])
    preflight_parser.add_argument(
        "--service", type=str, nargs="*", help="Limit to specific services"
    )
    preflight_parser.add_argument(
        "--ticket", type=str, help="Scope preflight to ticket requirements"
    )

    # day
    day_parser = subparsers.add_parser("day", help="Daily routines", parents=[parent_parser])
    day_sub = day_parser.add_subparsers(dest="day_action")
    day_sub.add_parser("start", help="Day start reconciliation report")
    day_sub.add_parser("end", help="Day end summary and continuation capsule")

    # delivery
    delivery_parser = subparsers.add_parser("delivery", help="Delivery state tracking", parents=[parent_parser])
    delivery_sub = delivery_parser.add_subparsers(dest="delivery_action")
    delivery_status = delivery_sub.add_parser("status", help="Show delivery lifecycle")
    delivery_status.add_argument("key", help="JIRA ticket key")

    # closeout
    closeout_parser = subparsers.add_parser("closeout", help="Close-out and handover", parents=[parent_parser])
    closeout_sub = closeout_parser.add_subparsers(dest="closeout_action")
    closeout_report = closeout_sub.add_parser("report", help="Generate close-out report")
    closeout_report.add_argument("key", help="JIRA ticket key")

    # diagnostics
    diag_parser = subparsers.add_parser("diag", help="Diagnostic packs", parents=[parent_parser])
    diag_sub = diag_parser.add_subparsers(dest="diag_action")
    diag_sub.add_parser("list", help="List available diagnostic packs")
    diag_run = diag_sub.add_parser("run", help="Run a diagnostic pack")
    diag_run.add_argument("pack", help="Pack identifier")
    diag_run.add_argument("--namespace", type=str, help="Kubernetes namespace")
    diag_run.add_argument("--app", type=str, help="Application or service name")

    # toolchain
    toolchain_parser = subparsers.add_parser(
        "toolchain", help="Python/Java/Groovy version detection and routing", parents=[parent_parser]
    )
    toolchain_sub = toolchain_parser.add_subparsers(dest="toolchain_action")
    toolchain_sub.add_parser("check", help="Detect runtimes and validate tool compatibility", parents=[parent_parser])
    toolchain_sub.add_parser("list", help="List tools and resolved environments", parents=[parent_parser])
    toolchain_env = toolchain_sub.add_parser("env", help="Print shell exports for a tool", parents=[parent_parser])
    toolchain_env.add_argument("tool", help="Tool name (e.g. jira-cli, newrelic-cli)")

    return parser


def dispatch(args: argparse.Namespace) -> int:
    """
    Routes a parsed argument namespace to the appropriate handler.

    Args:
        args: Parsed CLI arguments.

    Returns:
        Exit code integer.
    """
    from ai_worklog_framework import __version__

    if args.version:
        print(f"ai-worklog {__version__}")
        return EXIT_SUCCESS

    if not args.command:
        build_parser().print_help()
        return EXIT_USER_ERROR

    if args.command == "catalog":
        from ai_worklog_framework.catalog import commands as catalog_cmds
        return catalog_cmds.run(args)

    if args.command == "ticket":
        from ai_worklog_framework.catalog import ticket as ticket_cmds
        return ticket_cmds.run(args)

    if args.command == "preflight":
        from ai_worklog_framework.adapters import preflight as preflight_mod
        return preflight_mod.run(args)

    if args.command == "day":
        from ai_worklog_framework.reports import daily
        return daily.run(args)

    if args.command == "delivery":
        from ai_worklog_framework.delivery import commands as delivery_cmds
        return delivery_cmds.run(args)

    if args.command == "closeout":
        from ai_worklog_framework.reports import closeout
        return closeout.run(args)

    if args.command == "diag":
        from ai_worklog_framework.diagnostics import commands as diag_cmds
        return diag_cmds.run(args)

    if args.command == "toolchain":
        from ai_worklog_framework.toolchain import commands as toolchain_cmds
        return toolchain_cmds.run(args)

    build_parser().print_help()
    return EXIT_USER_ERROR


def main(argv: Optional[List[str]] = None) -> None:
    """
    Entry point for the ai-worklog CLI.

    Args:
        argv: Optional argument list (defaults to sys.argv[1:]).
    """
    parser = build_parser()
    args = parser.parse_args(argv)
    code = dispatch(args)
    sys.exit(code)


if __name__ == "__main__":
    main()
