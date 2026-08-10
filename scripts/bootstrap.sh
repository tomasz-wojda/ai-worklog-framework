#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKSPACE="${1:-}"
MODE="${2:---dry-run}"

if [[ -z "$WORKSPACE" || "$WORKSPACE" == "-h" || "$WORKSPACE" == "--help" ]]; then
  echo "Usage: bootstrap.sh <workspace> [--dry-run|--link|--revert]"
  exit 1
fi

case "$MODE" in
  --dry-run)
    exec "$ROOT/bin/ai-worklog" workspace init "$WORKSPACE"
    ;;
  --link)
    exec "$ROOT/bin/ai-worklog" workspace init "$WORKSPACE" --apply
    ;;
  --revert)
    exec "$ROOT/bin/ai-worklog" workspace revert "$WORKSPACE" --apply
    ;;
  *)
    echo "Unknown mode: $MODE" >&2
    exit 1
    ;;
esac
