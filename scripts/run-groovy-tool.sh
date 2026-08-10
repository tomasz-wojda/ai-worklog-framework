#!/usr/bin/env bash
set -euo pipefail

TOOL="${1:-}"
shift || true

if [ -z "${TOOL}" ]; then
    echo "Usage: run-groovy-tool.sh <tool-name> [args...]" >&2
    echo "Tools: jira-cli, newrelic-cli, jenkins-syntax-check" >&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FRAMEWORK_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

if [ -z "${AI_WORKLOG_WORKSPACE:-}" ]; then
    AI_WORKLOG_WORKSPACE="$(cd "${FRAMEWORK_ROOT}/../.." && pwd)"
fi

ENV_OUTPUT="$("${FRAMEWORK_ROOT}/bin/ai-worklog" toolchain env "${TOOL}" --workspace "${AI_WORKLOG_WORKSPACE}" 2>/dev/null || true)"
if echo "${ENV_OUTPUT}" | grep -q "^# BLOCKED"; then
    echo "${ENV_OUTPUT}" >&2
    exit 1
fi

eval "$(echo "${ENV_OUTPUT}" | grep '^export ')"

case "${TOOL}" in
    jira-cli)
        GROOVY_SCRIPT="${AI_WORKLOG_WORKSPACE}/jira/jira-ticket-info.groovy"
        ;;
    newrelic-cli)
        GROOVY_SCRIPT="${AI_WORKLOG_WORKSPACE}/newrelic/newrelic-info.groovy"
        ;;
    jenkins-syntax-check)
        AI_VAULT_ROOT="${AI_VAULT_ROOT:-${AI_WORKLOG_WORKSPACE}/repos/ai-vault}"
        SYNTAX_CHECK_SCRIPT="${AI_VAULT_ROOT}/skills/jenkins-pipeline-architect/scripts/syntax_check.sh"
        if [ ! -f "${SYNTAX_CHECK_SCRIPT}" ]; then
            echo "Jenkins syntax-check script not found: ${SYNTAX_CHECK_SCRIPT}" >&2
            exit 1
        fi
        exec "${SYNTAX_CHECK_SCRIPT}" "$@"
        ;;
    *)
        echo "Unknown tool mapping: ${TOOL}" >&2
        exit 1
        ;;
esac

if [ ! -f "${GROOVY_SCRIPT}" ]; then
    echo "Groovy script not found: ${GROOVY_SCRIPT}" >&2
    exit 1
fi

exec groovy "${GROOVY_SCRIPT}" "$@"
