"""Collect the actual locked wheels' license texts without reading outside this project's venv."""
from importlib import metadata
from pathlib import Path
import json
import re
import sys

root = Path(__file__).resolve().parents[1]
environment = Path(sys.prefix).resolve()
if environment != root / "agent-service" / ".venv":
    raise SystemExit("Run with agent-service/.venv Python")
out = root / "docs/verification/g3-dependencies"
out.mkdir(parents=True, exist_ok=True)
pins = re.findall(r"^([A-Za-z0-9_.-]+)(?:\[[^]]+\])?==([^ \\\n]+)", (root / "agent-service/requirements.lock").read_text(), re.M)
inventory, notices = [], []
for name, version in pins:
    dist = metadata.distribution(name)
    if dist.version != version:
        raise SystemExit(f"Installed version differs from lock: {name}")
    declared = dist.metadata.get("License-Expression") or dist.metadata.get("License") or "UNKNOWN"
    texts = []
    for file in dist.files or []:
        if not re.match(r"(?i)^(licen[cs]e|copying|notice|authors)", Path(file).name):
            continue
        path = Path(dist.locate_file(file)).resolve()
        if not path.is_relative_to(environment) or not path.is_file():
            continue
        texts.append({"file": str(file), "text": path.read_text(encoding="utf-8", errors="replace")})
    inventory.append({"name": name, "version": version, "license": declared, "licenseFiles": [t["file"] for t in texts]})
    notices.append(f"\n{'=' * 72}\n{name}=={version}\nDeclared license: {declared}\n")
    for entry in texts:
        notices.append(f"\n--- {entry['file']} ---\n{entry['text']}\n")
missing = [p["name"] for p in inventory if p["license"] == "UNKNOWN" or not p["licenseFiles"]]
(out / "licenses.json").write_text(json.dumps({"components": inventory, "missingEvidence": missing}, indent=2, ensure_ascii=False), encoding="utf-8")
(root / "agent-service/THIRD_PARTY_NOTICES.txt").write_text("AgentOps Python runtime dependencies - actual installed wheel notices\n" + "".join(notices), encoding="utf-8")
print(f"Collected {len(inventory)} locked distributions; missing evidence: {missing}")
if missing: raise SystemExit(1)
