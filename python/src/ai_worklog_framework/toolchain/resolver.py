"""
resolver.py — Detects installed runtimes and resolves per-tool environments.

Maps workspace tools (Jira CLI, New Relic CLI, Jenkins syntax check) to the
Java and Groovy versions they require. Groovy 3 is incompatible with Java 25;
Java 17 is the safe default for existing Groovy scripts in the workspace.

Inputs:
  - Optional workspace toolchain configuration from config.json/local.json.

Outputs:
  - DetectedRuntime inventory and ToolEnvironment for each named tool.
"""

import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from ai_worklog_framework.result import Result, ResultSet, Status
from ai_worklog_framework.shared import load_shared


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


@dataclass
class ToolSpec:
    name: str
    java_major: int
    groovy_major: Optional[int] = None
    description: str = ""


@dataclass
class ToolEnvironment:
    tool: str
    java_home: Optional[Path]
    groovy_executable: Optional[Path]
    ready: bool
    message: str


_TOOLCHAIN_RULES = load_shared("toolchain-tools.json", {})
DEFAULT_TOOL_SPECS: Dict[str, ToolSpec] = {
    name: ToolSpec(
        name=name,
        java_major=int(spec["java"]),
        groovy_major=int(spec["groovy"]) if spec.get("groovy") is not None else None,
        description=spec.get("description", ""),
    )
    for name, spec in _TOOLCHAIN_RULES.get("tools", {}).items()
}
GROOVY_JAVA_COMPAT: Dict[int, Tuple[int, int]] = {
    int(major): (int(bounds["min_java"]), int(bounds["max_java"]))
    for major, bounds in _TOOLCHAIN_RULES.get("compatibility", {}).items()
}


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
    if code == 0 and version:
        return PythonRuntime(executable=exe, version_string=version)
    return None


def _java_version_at_home(java_home: Path) -> Optional[JavaRuntime]:
    java_bin = java_home / "bin" / "java"
    if not java_bin.is_file():
        return None
    env = os.environ.copy()
    env["JAVA_HOME"] = str(java_home)
    code, out, err = _run([str(java_bin), "-version"], env=env)
    text = out + err
    match = JAVA_MAJOR_RE.search(text)
    if not match:
        return None
    major = int(match.group(1))
    return JavaRuntime(major=major, home=java_home, version_string=text.splitlines()[0] if text else "")


def detect_java_runtimes(config: Dict[str, Any]) -> List[JavaRuntime]:
    found: Dict[int, JavaRuntime] = {}
    configured = config.get("java", {})

    for key, path_str in configured.items():
        try:
            major = int(str(key).replace("java", ""))
        except ValueError:
            continue
        home = Path(os.path.expanduser(str(path_str)))
        runtime = _java_version_at_home(home)
        if runtime:
            found[major] = runtime

    if shutil.which("/usr/libexec/java_home"):
        code, out, err = _run(["/usr/libexec/java_home", "-V"])
        combined = out + err
        for line in combined.splitlines():
            match = re.search(r"(\d+)(?:\.\d+)*.*\((.+)\)", line)
            if not match:
                continue
            major = int(match.group(1))
            if major in found:
                continue
            code2, home_out, _ = _run(["/usr/libexec/java_home", "-v", str(major)])
            if code2 == 0 and home_out.strip():
                runtime = _java_version_at_home(Path(home_out.strip()))
                if runtime:
                    found[major] = runtime

    if not found and os.environ.get("JAVA_HOME"):
        runtime = _java_version_at_home(Path(os.environ["JAVA_HOME"]))
        if runtime:
            found[runtime.major] = runtime

    if not found and shutil.which("java"):
        code, out, err = _run(["java", "-version"])
        text = out + err
        match = JAVA_MAJOR_RE.search(text)
        if match:
            major = int(match.group(1))
            java_home = os.environ.get("JAVA_HOME", "")
            home = Path(java_home) if java_home else Path("/")
            found[major] = JavaRuntime(major=major, home=home, version_string=text.splitlines()[0])

    return sorted(found.values(), key=lambda r: r.major)


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


def _pick_java(runtimes: List[JavaRuntime], major: int) -> Optional[JavaRuntime]:
    for runtime in runtimes:
        if runtime.major == major:
            return runtime
    return None


def _pick_groovy(runtimes: List[GroovyRuntime], major: Optional[int], java_major: int) -> Optional[GroovyRuntime]:
    if major is None:
        return None
    compatible = [r for r in runtimes if r.major == major]
    if not compatible:
        compatible = [r for r in runtimes if r.major >= major]
    for runtime in compatible:
        bounds = GROOVY_JAVA_COMPAT.get(runtime.major)
        if bounds and bounds[0] <= java_major <= bounds[1]:
            return runtime
    return compatible[0] if compatible else None


def resolve_tool_environment(
    tool_name: str,
    toolchain_config: Dict[str, Any],
    java_runtimes: List[JavaRuntime],
    groovy_runtimes: List[GroovyRuntime],
) -> ToolEnvironment:
    tool_overrides = toolchain_config.get("tools", {})
    spec = DEFAULT_TOOL_SPECS.get(tool_name)
    if not spec:
        return ToolEnvironment(tool=tool_name, java_home=None, groovy_executable=None, ready=False, message="Unknown tool")

    override = tool_overrides.get(tool_name, {})
    java_major = int(override.get("java", spec.java_major))
    groovy_major = override.get("groovy", spec.groovy_major)
    if groovy_major is not None:
        groovy_major = int(groovy_major)

    java_rt = _pick_java(java_runtimes, java_major)
    if not java_rt:
        return ToolEnvironment(
            tool=tool_name,
            java_home=None,
            groovy_executable=None,
            ready=False,
            message=f"Java {java_major} not found",
        )

    groovy_rt = _pick_groovy(groovy_runtimes, groovy_major, java_rt.major)
    if spec.groovy_major is not None and not groovy_rt:
        return ToolEnvironment(
            tool=tool_name,
            java_home=java_rt.home,
            groovy_executable=None,
            ready=False,
            message=f"Groovy {spec.groovy_major} not found for Java {java_rt.major}",
        )

    if groovy_rt:
        bounds = GROOVY_JAVA_COMPAT.get(groovy_rt.major, (8, 25))
        if not (bounds[0] <= java_rt.major <= bounds[1]):
            return ToolEnvironment(
                tool=tool_name,
                java_home=java_rt.home,
                groovy_executable=groovy_rt.executable,
                ready=False,
                message=(
                    f"Incompatible: Groovy {groovy_rt.major} with Java {java_rt.major} "
                    f"(supported Java {bounds[0]}-{bounds[1]})"
                ),
            )

    parts = [f"Java {java_rt.major} @ {java_rt.home}"]
    if groovy_rt:
        parts.append(f"Groovy {groovy_rt.version_string} @ {groovy_rt.executable}")
    return ToolEnvironment(
        tool=tool_name,
        java_home=java_rt.home,
        groovy_executable=groovy_rt.executable if groovy_rt else None,
        ready=True,
        message="; ".join(parts),
    )


def build_toolchain_env(tool_env: ToolEnvironment) -> Dict[str, str]:
    env = os.environ.copy()
    if tool_env.java_home:
        env["JAVA_HOME"] = str(tool_env.java_home)
        java_bin = tool_env.java_home / "bin"
        env["PATH"] = f"{java_bin}{os.pathsep}{env.get('PATH', '')}"
    return env


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

    java_runtimes = detect_java_runtimes(toolchain_config)
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

    for tool_name in DEFAULT_TOOL_SPECS:
        tool_env = resolve_tool_environment(tool_name, toolchain_config, java_runtimes, groovy_runtimes)
        status = Status.READY if tool_env.ready else Status.BLOCKED
        results.add(Result(status=status, source=f"tool:{tool_name}", message=tool_env.message))

    return results
