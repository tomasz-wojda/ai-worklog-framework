"""
resolver.py — Detects active Python, Java, and Groovy runtimes.

Detects the active Python, system Java, and configured Groovy runtimes.

Inputs:
  - Optional workspace Groovy configuration from config.json/local.json.

Outputs:
  - Runtime inventory results.
"""

import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from ai_worklog_framework.result import Result, ResultSet, Status


JAVA_MAJOR_RE = re.compile(r'version "(\d+)')
GROOVY_VERSION_RE = re.compile(r"Groovy Version:\s*(\d+(?:\.\d+)*)", re.IGNORECASE)
GROOVY_VERSION_ALT_RE = re.compile(r"(\d+\.\d+\.\d+)")


@dataclass
class JavaRuntime:
    major: int
    home: Path
    version_string: str = ""


@dataclass
class GroovyRuntime:
    major: int
    executable: Path
    version_string: str = ""


@dataclass
class PythonRuntime:
    executable: Path
    version_string: str


def _run(cmd: List[str], env: Optional[Dict[str, str]] = None, timeout: int = 10) -> Tuple[int, str, str]:
    try:
        proc = subprocess.run(
            cmd, capture_output=True, text=True, timeout=timeout, env=env
        )
        return proc.returncode, proc.stdout, proc.stderr
    except (subprocess.TimeoutExpired, FileNotFoundError) as exc:
        return 127, "", str(exc)


def detect_python() -> Optional[PythonRuntime]:
    exe = Path(sys.executable)
    code, out, err = _run([str(exe), "--version"])
    version = (out or err).strip()
    if code == 0 and version and "nie znaleziono" not in version.lower() and "not found" not in version.lower():
        return PythonRuntime(executable=exe, version_string=version)
    return None


def _java_version_at_home(java_home: Path) -> Optional[JavaRuntime]:
    if not java_home.is_dir():
        return None
    java_exe = java_home / "bin" / "java.exe"
    java_bin = java_home / "bin" / "java"
    target = java_exe if java_exe.is_file() else java_bin
    if not target.is_file():
        return None
    env = os.environ.copy()
    env["JAVA_HOME"] = str(java_home)
    code, out, err = _run([str(target), "-version"], env=env)
    if code != 0:
        return None
    text = out + err
    match = JAVA_MAJOR_RE.search(text)
    if not match:
        return None
    major = int(match.group(1))
    return JavaRuntime(major=major, home=java_home, version_string=text.splitlines()[0] if text else "")


def detect_java_runtimes() -> List[JavaRuntime]:
    if os.environ.get("JAVA_HOME"):
        runtime = _java_version_at_home(Path(os.environ["JAVA_HOME"]))
        if runtime:
            return [runtime]

    java_home_tool = Path("/usr/libexec/java_home")
    if java_home_tool.is_file():
        code, out, _ = _run([str(java_home_tool)])
        if code == 0 and out.strip():
            runtime = _java_version_at_home(Path(out.strip()))
            if runtime:
                return [runtime]

    java_executable = shutil.which("java")
    if java_executable:
        code, out, err = _run(["java", "-version"])
        text = out + err
        match = JAVA_MAJOR_RE.search(text)
        if match:
            major = int(match.group(1))
            home = Path(java_executable).resolve().parent.parent
            return [JavaRuntime(major=major, home=home, version_string=text.splitlines()[0])]

    return []


def detect_groovy_runtimes(config: Dict[str, Any]) -> List[GroovyRuntime]:
    found: List[GroovyRuntime] = []
    configured = config.get("groovy", {})
    candidates: List[Path] = []

    for key, path_str in configured.items():
        if key == "default":
            candidates.append(Path(os.path.expanduser(str(path_str))))
        else:
            try:
                int(key)
                candidates.append(Path(os.path.expanduser(str(path_str))))
            except ValueError:
                candidates.append(Path(os.path.expanduser(str(path_str))))

    default_groovy = shutil.which("groovy")
    if default_groovy:
        candidates.append(Path(default_groovy))

    seen: set = set()
    for candidate in candidates:
        if not candidate.is_file():
            continue
        path_key = str(candidate.resolve())
        if path_key in seen:
            continue
        seen.add(path_key)
        env = os.environ.copy()
        code, out, err = _run([str(candidate), "--version"], env=env)
        text = out + err
        version_match = GROOVY_VERSION_RE.search(text) or GROOVY_VERSION_ALT_RE.search(text)
        if code != 0 or not version_match:
            continue
        version_string = version_match.group(1)
        major = int(version_string.split(".")[0])
        found.append(GroovyRuntime(major=major, executable=candidate, version_string=version_string))

    return sorted(found, key=lambda r: r.major)


def check_toolchain(toolchain_config: Dict[str, Any]) -> ResultSet:
    results = ResultSet()

    python_rt = detect_python()
    if python_rt:
        results.add(Result(
            status=Status.READY,
            source="python3",
            message=python_rt.version_string,
            detail={"executable": str(python_rt.executable)},
        ))
    else:
        results.add(Result(status=Status.BLOCKED, source="python3", message="Not detected"))

    java_runtimes = detect_java_runtimes()
    if java_runtimes:
        for rt in java_runtimes:
            results.add(Result(
                status=Status.READY,
                source=f"java:{rt.major}",
                message=rt.version_string or str(rt.home),
                detail={"home": str(rt.home)},
            ))
    else:
        results.add(Result(status=Status.DEGRADED, source="java", message="No Java runtimes detected"))

    groovy_runtimes = detect_groovy_runtimes(toolchain_config)
    if groovy_runtimes:
        for rt in groovy_runtimes:
            results.add(Result(
                status=Status.READY,
                source=f"groovy:{rt.major}",
                message=f"{rt.version_string} @ {rt.executable}",
            ))
    else:
        results.add(Result(status=Status.DEGRADED, source="groovy", message="No Groovy runtimes detected"))

    return results
