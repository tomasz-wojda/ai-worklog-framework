#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FRAMEWORK_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

SERVICES="jira newrelic aws eks jenkins github argocd artifactory ssh snow datadog"
MARKER=".managed-by-ai-worklog-framework"

usage() {
    cat <<'EOF'
Usage: bootstrap.sh <workspace> [--dry-run|--link|--revert]

  --dry-run   (default) print every action, change nothing
  --link      create worklog/interface/<svc> as symlinks to ../../<svc>
  --revert    remove symlinks and markers created by this script

Only service directories that exist at the workspace root are linked.
Existing targets are never overwritten. Credential contents are never read.
This script is idempotent; repeated runs produce the same result.
EOF
}

WORKSPACE="${1:-}"
MODE="${2:---dry-run}"

case "${WORKSPACE}" in
    ''|-h|--help) usage; exit 1 ;;
esac

if [ ! -d "${WORKSPACE}" ]; then
    echo "ERROR: workspace not found: ${WORKSPACE}"
    exit 1
fi

WORKSPACE="$(cd "${WORKSPACE}" && pwd)"
IFACE="${WORKSPACE}/worklog/interface"
CONFIG_DIR="${WORKSPACE}/.ai-worklog"

case "${MODE}" in
    --dry-run|--link|--revert) ;;
    *) echo "ERROR: unknown mode: ${MODE}"; usage; exit 1 ;;
esac

DRY=0
[ "${MODE}" = "--dry-run" ] && DRY=1

act() {
    if [ "${DRY}" -eq 1 ]; then
        echo "  would: $*"
    else
        echo "  run:   $*"
        "$@" || { echo "  FAILED: $*"; exit 1; }
    fi
}

echo "workspace: ${WORKSPACE}"
echo "mode:      ${MODE}"
echo

if [ "${MODE}" = "--revert" ]; then
    if [ ! -d "${IFACE}" ]; then
        echo "nothing to revert: ${IFACE} does not exist"
        exit 0
    fi
    for svc in ${SERVICES}; do
        entry="${IFACE}/${svc}"
        [ -e "${entry}" ] || [ -L "${entry}" ] || continue
        if [ -L "${entry}" ]; then
            echo "${svc}: removing symlink"
            act rm "${entry}"
        elif [ -f "${entry}/${MARKER}" ]; then
            echo "${svc}: managed marker found, removing link"
            act rm "${entry}/${MARKER}"
            act rm -rf "${entry}"
        else
            echo "${svc}: not managed by this script, leaving alone"
        fi
    done
    if [ "${DRY}" -eq 0 ] && [ -d "${IFACE}" ]; then
        rmdir "${IFACE}" 2>/dev/null && echo "removed empty ${IFACE}" || true
    fi
    echo
    echo "revert complete"
    exit 0
fi

[ -d "${CONFIG_DIR}" ] || act mkdir -p "${CONFIG_DIR}"
[ -d "${IFACE}" ] || act mkdir -p "${IFACE}"

LINKED=0
SKIPPED=0

for svc in ${SERVICES}; do
    src="${WORKSPACE}/${svc}"
    dst="${IFACE}/${svc}"

    if [ ! -d "${src}" ] && [ ! -L "${src}" ]; then
        echo "${svc}: absent at workspace root, skipping"
        SKIPPED=$((SKIPPED + 1))
        continue
    fi

    if [ -e "${dst}" ] || [ -L "${dst}" ]; then
        echo "${svc}: ${dst} already exists, skipping"
        SKIPPED=$((SKIPPED + 1))
        continue
    fi

    if [ -L "${src}" ]; then
        echo "${svc}: root entry is already a symlink, skipping"
        SKIPPED=$((SKIPPED + 1))
        continue
    fi

    echo "${svc}: link"
    act ln -s "../../${svc}" "${dst}"
    LINKED=$((LINKED + 1))
done

echo
echo "linked=${LINKED} skipped=${SKIPPED}"
if [ "${DRY}" -eq 1 ]; then
    echo "dry run only, nothing changed. re-run with --link to apply."
fi
