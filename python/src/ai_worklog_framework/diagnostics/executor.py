import json
import shutil
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, Optional, Tuple
from urllib.parse import urlparse

from ai_worklog_framework.diagnostics.evidence import EvidenceBundle, EvidenceStep
from ai_worklog_framework.paths import WorkspacePaths
from ai_worklog_framework.redaction import redact_dict, redact_string


def _expand(command: list, parameters: Dict[str, str]) -> list:
    return [part.format_map(parameters) for part in command]


def _validate_parameters(parameters: Dict[str, str]) -> None:
    for key, value in parameters.items():
        if any(character in value for character in ("\x00", "\n", "\r")):
            raise ValueError(f"Invalid control character in parameter: {key}")
        if key == "url":
            if urlparse(value).scheme not in ("http", "https"):
                raise ValueError("URL parameter must use http or https")
        elif value.startswith("-"):
            raise ValueError(f"Parameter must not begin with '-': {key}")


def _execute_step(step: dict, parameters: Dict[str, str]) -> EvidenceStep:
    command = _expand(step["command"], parameters)
    started = time.monotonic()
    try:
        result = subprocess.run(
            command,
            capture_output=True,
            text=True,
            timeout=int(step.get("timeout_seconds", 30)),
        )
        exit_code = result.returncode
        stdout = redact_string(result.stdout)
        stderr = redact_string(result.stderr)
    except subprocess.TimeoutExpired as exc:
        exit_code = 124
        stdout = redact_string(exc.stdout or "")
        stderr = "Timed out"
    except OSError as exc:
        exit_code = 127
        stdout = ""
        stderr = redact_string(str(exc))
    duration = int((time.monotonic() - started) * 1000)
    return EvidenceStep(
        id=step["id"],
        command=[redact_string(part) for part in command],
        exit_code=exit_code,
        duration_ms=duration,
        stdout=stdout,
        stderr=stderr,
    )


def run_pack(
    pack_id: str,
    pack: dict,
    parameters: Dict[str, str],
    paths: WorkspacePaths,
    output: Optional[Path] = None,
) -> Tuple[EvidenceBundle, Path]:
    _validate_parameters(parameters)
    timestamp = datetime.now(timezone.utc)
    missing = [
        name for name in pack.get("required_parameters", [])
        if not parameters.get(name)
    ]
    missing_tools = [
        tool for tool in pack.get("prerequisites", [])
        if not shutil.which(tool)
    ]
    if not pack.get("read_only", False):
        status = "blocked"
        steps = []
    elif missing or missing_tools:
        status = "blocked"
        steps = []
    else:
        steps = [_execute_step(step, parameters) for step in pack.get("steps", [])]
        status = "success" if all(step.exit_code == 0 for step in steps) else "degraded"

    if missing:
        steps.append(EvidenceStep(
            id="parameters",
            command=[],
            exit_code=1,
            duration_ms=0,
            stdout="",
            stderr=f"Missing parameters: {', '.join(missing)}",
        ))
    if missing_tools:
        steps.append(EvidenceStep(
            id="prerequisites",
            command=[],
            exit_code=127,
            duration_ms=0,
            stdout="",
            stderr=f"Missing prerequisites: {', '.join(missing_tools)}",
        ))
    if not pack.get("read_only", False):
        steps.append(EvidenceStep(
            id="safety",
            command=[],
            exit_code=1,
            duration_ms=0,
            stdout="",
            stderr="Write-capable diagnostic packs are refused",
        ))
    safe_parameters = redact_dict(parameters)
    bundle = EvidenceBundle(
        pack=pack_id,
        timestamp=timestamp.isoformat(),
        parameters=safe_parameters,
        status=status,
        steps=steps,
    )
    target = output or (
        paths.root
        / ".ai-worklog"
        / "evidence"
        / f"{pack_id}-{timestamp.strftime('%Y%m%dT%H%M%SZ')}.json"
    )
    target = target.expanduser().resolve()
    allowed = (paths.root / ".ai-worklog" / "evidence").resolve()
    if output is None or target == allowed or allowed in target.parents:
        target.parent.mkdir(parents=True, exist_ok=True)
    elif not target.parent.is_dir():
        raise ValueError(f"Output directory not found: {target.parent}")
    temporary = target.with_suffix(target.suffix + ".tmp")
    temporary.write_text(
        json.dumps(bundle.to_dict(), indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    temporary.replace(target)

    return bundle, target
