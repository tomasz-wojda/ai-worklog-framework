import hashlib
import json
import re
from pathlib import Path
from urllib.parse import urlparse


ROOT = Path(__file__).resolve().parents[2]
POLICY = json.loads((ROOT / "shared/public-content-policy.json").read_text())
TEXT_SUFFIXES = {
    ".gradle", ".groovy", ".json", ".md", ".properties", ".py", ".sh", ".toml",
    ".txt", ".xml", ".yaml", ".yml"
}
TEXT_NAMES = {".gitignore", "LICENSE"}
EXCLUDED_PARTS = {
    ".git", ".gradle", ".pytest_cache", ".venv", "__pycache__", "build"
}


def public_text_files():
    for path in ROOT.rglob("*"):
        if not path.is_file() or EXCLUDED_PARTS.intersection(path.parts):
            continue
        if path.suffix in TEXT_SUFFIXES or path.name in TEXT_NAMES:
            yield path


def test_public_text_contains_only_allowed_identifiers():
    forbidden_hashes = set(POLICY["forbidden_token_sha256"])
    allowed_owners = set(POLICY["allowed_github_owners"])
    allowed_prefixes = set(POLICY["allowed_ticket_prefixes"])
    failures = []
    for path in public_text_files():
        for line_number, line in enumerate(path.read_text(errors="replace").splitlines(), 1):
            lowered = line.lower()
            tokens = re.findall(r"[a-z0-9][a-z0-9_-]{2,}", lowered)
            candidates = set(tokens)
            for token in tokens:
                candidates.update(part for part in re.split(r"[-_]", token) if part)
            for candidate in candidates:
                digest = hashlib.sha256(candidate.encode()).hexdigest()
                if digest in forbidden_hashes:
                    failures.append(f"{path.relative_to(ROOT)}:{line_number}: forbidden identifier")
            for owner in re.findall(r"github\.com/([^/\s]+)", line):
                if owner not in allowed_owners:
                    failures.append(f"{path.relative_to(ROOT)}:{line_number}: GitHub owner")
            for prefix in re.findall(r"\b([A-Z]{2,10})-\d+\b", line):
                if prefix not in allowed_prefixes:
                    failures.append(f"{path.relative_to(ROOT)}:{line_number}: ticket prefix")
    assert failures == []


def test_bundled_catalog_is_fictional():
    catalog_files = sorted((ROOT / "catalog").glob("*.json"))
    assert [path.name for path in catalog_files] == ["examples.json"]
    entries = json.loads(catalog_files[0].read_text())
    allowed_projects = set(POLICY["allowed_catalog_projects"])
    for entry in entries:
        assert entry["id"].startswith(POLICY["catalog_id_prefix"])
        assert entry["jira"]["project"] in allowed_projects
        for repository in entry.get("repositories", []):
            parsed = urlparse(repository["url"])
            assert parsed.hostname == "github.com"
            assert parsed.path.split("/")[1] == "example-org"
            assert repository["local_dir"].startswith(
                POLICY["catalog_repository_prefix"]
            )
