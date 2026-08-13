"""
cli.py — Command routing and stable exit codes for the ai-worklog entry point.

Provides a top-level dispatcher that routes subcommands to their respective modules.
Exit codes: 0 = success, 1 = user error, 2 = system/adapter error, 3 = blocked.
"""

import sys
import argparse
import platform
from typing import List, Optional, Tuple

from ai_worklog_framework.shared import load_shared

_EXIT_CODES = load_shared(
    "exit-codes.json",
    {"success": 0, "user_error": 1, "system_error": 2, "blocked": 3},
)
EXIT_SUCCESS = _EXIT_CODES["success"]
EXIT_USER_ERROR = _EXIT_CODES["user_error"]
EXIT_SYSTEM_ERROR = _EXIT_CODES["system_error"]
EXIT_BLOCKED = _EXIT_CODES["blocked"]

GLOBAL_OPTION_COMMANDS = frozenset(
    {
        "setup",
        "workspace",
        "config",
        "catalog",
        "ticket",
        "state",
        "preflight",
        "reconcile",
        "jenkins",
        "day",
        "delivery",
        "closeout",
        "diag",
        "toolchain",
    }
)


def _command_index(argv: List[str]) -> Optional[int]:
    for index, token in enumerate(argv):
        if token in GLOBAL_OPTION_COMMANDS:
            return index
    return None


def extract_global_options(argv: List[str]) -> Tuple[dict, List[str]]:
    args = list(argv)
    options: dict = {}
    command_index = _command_index(args)

    def take_option(name: str, before_command: bool = False) -> Optional[str]:
        while name in args:
            index = args.index(name)
            if before_command and command_index is not None and index >= command_index:
                break
            if index + 1 >= len(args):
                raise ValueError(f"Missing value for {name}")
            value = args[index + 1]
            if value.startswith("-"):
                raise ValueError(f"Missing value for {name}")
            del args[index : index + 2]
            return value
        return None

    options["workspace"] = take_option("--workspace")
    workspace_name = take_option("--workspace-name")
    if workspace_name is None:
        workspace_name = take_option("-w")
    options["workspace_name"] = workspace_name
    options["runtime"] = take_option("--runtime", before_command=True)
    return options, args


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
    parent_parser.add_argument(
        "-w", "--workspace-name", dest="workspace_name", type=str,
        default=argparse.SUPPRESS,
        help="Select a registered workspace by name",
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

    setup_parser = subparsers.add_parser("setup", help="Workspace and IDE setup operations")
    setup_sub = setup_parser.add_subparsers(dest="setup_action")
    setup_init = setup_sub.add_parser("init", help="Initialize workspace setup")
    setup_init.add_argument("name", help="Workspace registration name")
    setup_init.add_argument("path", nargs="?", help="Workspace root path (optional if name is registered)")
    setup_init.add_argument(
        "--ide",
        action="append",
        choices=["auto", "cursor", "claude", "antigravity"],
        help="IDE profile (repeatable; default auto)",
    )
    setup_init.add_argument("--runtime", choices=["groovy", "python"])
    setup_init.add_argument("--ai-vault", dest="ai_vault", help="AI vault root path")
    setup_init.add_argument("--default", action="store_true", help="Set as default workspace")
    setup_init.add_argument("--json", action="store_true")
    setup_init.add_argument("--apply", action="store_true", help="Apply planned changes")
    setup_check = setup_sub.add_parser(
        "check", help="Validate setup readiness", parents=[parent_parser],
    )
    setup_check.add_argument("--json", action="store_true")
    setup_show = setup_sub.add_parser(
        "show", help="Show setup summary", parents=[parent_parser],
    )
    setup_show.add_argument("--json", action="store_true")
    setup_repair = setup_sub.add_parser(
        "repair", help="Repair setup-managed artifacts", parents=[parent_parser],
    )
    setup_repair.add_argument(
        "--ide",
        action="append",
        choices=["auto", "cursor", "claude", "antigravity"],
        help="Limit repair to IDE profiles",
    )
    setup_repair.add_argument("--json", action="store_true")
    setup_repair.add_argument("--apply", action="store_true", help="Apply planned changes")
    setup_revert = setup_sub.add_parser(
        "revert", help="Revert setup-managed artifacts", parents=[parent_parser],
    )
    setup_revert.add_argument(
        "--ide",
        action="append",
        choices=["cursor", "claude", "antigravity"],
        help="Limit revert to IDE profiles",
    )
    setup_revert.add_argument("--json", action="store_true")
    setup_revert.add_argument("--apply", action="store_true", help="Apply planned changes")

    workspace_parser = subparsers.add_parser("workspace", help="Workspace setup operations")
    workspace_sub = workspace_parser.add_subparsers(dest="workspace_action")
    for action in ("init", "revert"):
        workspace_action = workspace_sub.add_parser(action)
        workspace_action.add_argument("path", help="Workspace registration name or root path")
        workspace_action.add_argument("--apply", action="store_true", help="Apply planned changes")
    workspace_add = workspace_sub.add_parser("add", help="Register a workspace")
    workspace_add.add_argument("name", help="Workspace name")
    workspace_add.add_argument("path", help="Workspace root path")
    workspace_add.add_argument("--default", action="store_true", help="Set as default workspace")
    workspace_add.add_argument("--json", action="store_true")
    workspace_list = workspace_sub.add_parser("list", help="List registered workspaces")
    workspace_list.add_argument("--json", action="store_true")
    workspace_show = workspace_sub.add_parser("show", help="Show a registered workspace")
    workspace_show.add_argument("name", help="Workspace name")
    workspace_show.add_argument("--json", action="store_true")
    workspace_default = workspace_sub.add_parser("default", help="Show or set default workspace")
    workspace_default.add_argument("name", nargs="?", help="Workspace name")
    workspace_default.add_argument("--json", action="store_true")
    workspace_current = workspace_sub.add_parser(
        "current", help="Show resolved workspace", parents=[parent_parser],
    )
    workspace_current.add_argument("--json", action="store_true")
    workspace_remove = workspace_sub.add_parser("remove", help="Remove a workspace registration")
    workspace_remove.add_argument("name", help="Workspace name")
    workspace_remove.add_argument("--json", action="store_true")

    config_parser = subparsers.add_parser("config", help="Global configuration")
    config_sub = config_parser.add_subparsers(dest="config_action")
    config_show = config_sub.add_parser("show", help="Show global configuration")
    config_show.add_argument("--json", action="store_true")
    config_runtime = config_sub.add_parser("runtime", help="Show or set default runtime")
    config_runtime.add_argument("runtime", nargs="?")
    config_runtime.add_argument("--json", action="store_true")
    config_vault = config_sub.add_parser("set-ai-vault-root", help="Set AI vault root path")
    config_vault.add_argument("path", help="AI vault root path")
    config_vault.add_argument("--json", action="store_true")

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

    state_parser = subparsers.add_parser("state", help="Structured ticket state", parents=[parent_parser])
    state_sub = state_parser.add_subparsers(dest="state_action")
    state_sub.add_parser("list", help="List ticket state files")
    state_show = state_sub.add_parser("show", help="Show ticket state")
    state_show.add_argument("key")
    state_init = state_sub.add_parser("init", help="Initialize ticket state")
    state_init.add_argument("key")
    state_init.add_argument("--summary")
    state_init.add_argument("--service", action="append")
    state_init.add_argument(
        "--governance-mode",
        choices=["research", "innovate", "plan", "execute"],
        default="research",
    )
    state_init.add_argument("--apply", action="store_true")
    state_set = state_sub.add_parser("set", help="Set a validated state path")
    state_set.add_argument("key")
    state_set.add_argument("--path", required=True)
    state_set.add_argument("--value", required=True)
    state_set.add_argument("--apply", action="store_true")
    state_blocker = state_sub.add_parser("blocker", help="Manage blockers")
    state_blocker_sub = state_blocker.add_subparsers(dest="state_operation")
    blocker_add = state_blocker_sub.add_parser("add")
    blocker_add.add_argument("key")
    blocker_add.add_argument("--description", required=True)
    blocker_add.add_argument("--owner")
    blocker_add.add_argument("--apply", action="store_true")
    blocker_resolve = state_blocker_sub.add_parser("resolve")
    blocker_resolve.add_argument("key")
    blocker_resolve.add_argument("--index", required=True, type=int)
    blocker_resolve.add_argument("--apply", action="store_true")
    state_decision = state_sub.add_parser("decision", help="Manage decisions")
    state_decision_sub = state_decision.add_subparsers(dest="state_operation")
    decision_add = state_decision_sub.add_parser("add")
    decision_add.add_argument("key")
    decision_add.add_argument("--id", required=True)
    decision_add.add_argument("--description", required=True)
    decision_add.add_argument("--owner")
    decision_add.add_argument("--apply", action="store_true")
    decision_resolve = state_decision_sub.add_parser("resolve")
    decision_resolve.add_argument("key")
    decision_resolve.add_argument("--id", required=True)
    decision_resolve.add_argument("--resolution", required=True)
    decision_resolve.add_argument("--apply", action="store_true")

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

    reconcile_parser = subparsers.add_parser(
        "reconcile", help="Cross-system read-only reconciliation", parents=[parent_parser]
    )
    reconcile_sub = reconcile_parser.add_subparsers(dest="reconcile_action")
    reconcile_status = reconcile_sub.add_parser("status", help="Reconcile ticket state with external systems")
    reconcile_status.add_argument("key", help="JIRA ticket key")
    reconcile_status.add_argument(
        "--system",
        action="append",
        help="Limit reconciliation to specific systems",
    )
    reconcile_status.add_argument("--json", action="store_true", help="Print structured JSON report")

    # diagnostics
    diag_parser = subparsers.add_parser("diag", help="Diagnostic packs", parents=[parent_parser])
    diag_sub = diag_parser.add_subparsers(dest="diag_action")
    diag_sub.add_parser("list", help="List available diagnostic packs")
    diag_run = diag_sub.add_parser("run", help="Run a diagnostic pack")
    diag_run.add_argument("pack", help="Pack identifier")
    diag_run.add_argument("--namespace", type=str, help="Kubernetes namespace")
    diag_run.add_argument("--app", type=str, help="Application or service name")
    diag_run.add_argument("--service", type=str, help="Catalog service identifier")
    diag_run.add_argument("--param", action="append", help="Pack parameter as key=value")
    diag_run.add_argument("--output", type=str, help="Evidence output path")
    diag_run.add_argument("--json", action="store_true", help="Print evidence JSON")

    # toolchain
    toolchain_parser = subparsers.add_parser(
        "toolchain", help="Python/Java/Groovy version detection and routing", parents=[parent_parser]
    )
    toolchain_sub = toolchain_parser.add_subparsers(dest="toolchain_action")
    toolchain_sub.add_parser("check", help="Detect runtimes and validate tool compatibility", parents=[parent_parser])
    toolchain_sub.add_parser("list", help="List tools and resolved environments", parents=[parent_parser])
    toolchain_env = toolchain_sub.add_parser("env", help="Print shell exports for a tool", parents=[parent_parser])
    toolchain_env.add_argument("tool", help="Tool name (e.g. jira-cli, newrelic-cli)")

    jenkins_parser = subparsers.add_parser("jenkins", help="Jenkins operator", parents=[parent_parser])
    jenkins_sub = jenkins_parser.add_subparsers(dest="jenkins_action")
    jenkins_controllers = jenkins_sub.add_parser("controllers", help="List configured controllers")
    jenkins_controllers.add_argument("--json", action="store_true")
    jenkins_health = jenkins_sub.add_parser("health", help="Controller health")
    jenkins_health.add_argument("controller", nargs="?")
    jenkins_health.add_argument("--json", action="store_true")
    jenkins_job = jenkins_sub.add_parser("job", help="Inspect a job")
    jenkins_job.add_argument("controller", nargs="?")
    jenkins_job.add_argument("job", nargs="?")
    jenkins_job.add_argument("--builds", type=int)
    jenkins_job.add_argument("--parameters", action="store_true")
    jenkins_job.add_argument("--json", action="store_true")
    jenkins_plugins = jenkins_sub.add_parser("plugins", help="Installed plugins")
    jenkins_plugins.add_argument("controller", nargs="?")
    jenkins_plugins.add_argument("--require", action="append", default=[])
    jenkins_plugins.add_argument("--json", action="store_true")
    jenkins_credentials = jenkins_sub.add_parser("credentials", help="Credential metadata")
    jenkins_credentials.add_argument("controller", nargs="?")
    jenkins_credentials.add_argument("--domain")
    jenkins_credentials.add_argument("--json", action="store_true")
    jenkins_seed = jenkins_sub.add_parser("seed", help="Seed job status")
    jenkins_seed.add_argument("controller", nargs="?")
    jenkins_seed.add_argument("job", nargs="?")
    jenkins_seed.add_argument("--json", action="store_true")
    jenkins_syntax = jenkins_sub.add_parser("syntax-check", help="Validate pipeline syntax")
    jenkins_syntax.add_argument("file", nargs="*")
    jenkins_syntax.add_argument("--json", action="store_true")
    jenkins_nodes = jenkins_sub.add_parser("nodes", help="List Jenkins nodes")
    jenkins_nodes.add_argument("controller", nargs="?")
    jenkins_nodes.add_argument("--json", action="store_true")
    jenkins_queue = jenkins_sub.add_parser("queue", help="Inspect build queue")
    jenkins_queue.add_argument("controller", nargs="?")
    jenkins_queue.add_argument("--limit", type=int)
    jenkins_queue.add_argument("--json", action="store_true")
    jenkins_jobs = jenkins_sub.add_parser("jobs", help="Browse or search jobs")
    jenkins_jobs.add_argument("controller", nargs="?")
    jenkins_jobs.add_argument("--folder")
    jenkins_jobs.add_argument("--query")
    jenkins_jobs.add_argument("--limit", type=int)
    jenkins_jobs.add_argument("--json", action="store_true")
    jenkins_artifacts = jenkins_sub.add_parser("artifacts", help="Inspect build artifact metadata")
    jenkins_artifacts.add_argument("controller", nargs="?")
    jenkins_artifacts.add_argument("job", nargs="?")
    jenkins_artifacts.add_argument("build_selector", nargs="?")
    jenkins_artifacts.add_argument("--json", action="store_true")
    jenkins_views = jenkins_sub.add_parser("views", help="List or inspect views")
    jenkins_views.add_argument("controller", nargs="?")
    jenkins_views.add_argument("--view")
    jenkins_views.add_argument("--json", action="store_true")
    jenkins_whoami = jenkins_sub.add_parser("whoami", help="Show authenticated identity")
    jenkins_whoami.add_argument("controller", nargs="?")
    jenkins_whoami.add_argument("--json", action="store_true")
    jenkins_credential_domains = jenkins_sub.add_parser(
        "credential-domains", help="List credential domains",
    )
    jenkins_credential_domains.add_argument("controller", nargs="?")
    jenkins_credential_domains.add_argument("--json", action="store_true")

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
        print(f"ai-worklog {__version__} (python {platform.python_version()})")
        return EXIT_SUCCESS

    if not args.command:
        build_parser().print_help()
        return EXIT_USER_ERROR

    if args.command == "setup":
        if not args.setup_action:
            print("Usage: ai-worklog setup {init|check|show|repair|revert} ...")
            return EXIT_USER_ERROR
        from ai_worklog_framework.setup import commands as setup_cmds
        return setup_cmds.run(args)

    if args.command == "catalog":
        from ai_worklog_framework.catalog import commands as catalog_cmds
        return catalog_cmds.run(args)

    if args.command == "workspace":
        if not args.workspace_action:
            print(
                "Usage: ai-worklog workspace "
                "{init|revert|add|list|show|default|current|remove} ..."
            )
            return EXIT_USER_ERROR
        from ai_worklog_framework.workspace import commands as workspace_cmds
        return workspace_cmds.run(args)

    if args.command == "config":
        if not args.config_action:
            print("Usage: ai-worklog config {show|runtime}")
            return EXIT_USER_ERROR
        from ai_worklog_framework import global_config_commands as config_cmds
        return config_cmds.run(args)

    if args.command == "ticket":
        from ai_worklog_framework.catalog import ticket as ticket_cmds
        return ticket_cmds.run(args)

    if args.command == "state":
        if not args.state_action:
            print("Usage: ai-worklog state {list|init|show|set|blocker|decision}")
            return EXIT_USER_ERROR
        from ai_worklog_framework.state import commands as state_cmds
        return state_cmds.run(args)

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

    if args.command == "reconcile":
        from ai_worklog_framework.reconciliation import commands as reconcile_cmds
        return reconcile_cmds.run(args)

    if args.command == "diag":
        from ai_worklog_framework.diagnostics import commands as diag_cmds
        return diag_cmds.run(args)

    if args.command == "toolchain":
        from ai_worklog_framework.toolchain import commands as toolchain_cmds
        return toolchain_cmds.run(args)

    if args.command == "jenkins":
        from ai_worklog_framework.jenkins import commands as jenkins_cmds
        return jenkins_cmds.run(args)

    build_parser().print_help()
    return EXIT_USER_ERROR


def main(argv: Optional[List[str]] = None) -> None:
    """
    Entry point for the ai-worklog CLI.

    Args:
        argv: Optional argument list (defaults to sys.argv[1:]).
    """
    parser = build_parser()
    global_opts, remaining = extract_global_options(argv if argv is not None else sys.argv[1:])
    args = parser.parse_args(remaining)
    for key, value in global_opts.items():
        if value is not None:
            setattr(args, key, value)
    try:
        code = dispatch(args)
    except ValueError as exc:
        print(str(exc))
        code = EXIT_USER_ERROR
    sys.exit(code)


if __name__ == "__main__":
    main()
