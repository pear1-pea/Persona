import copy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from model_package import validate_manifest


class SharedValidatorContractTest(unittest.TestCase):
    def test_shared_android_contract(self):
        fixture = Path(__file__).resolve().parents[3] / "app/src/test/resources/model-package-contract.json"
        contract = json.loads(fixture.read_text(encoding="utf-8"))
        for case in contract["cases"]:
            with self.subTest(case=case["name"]), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                payloads = dict(contract["payloads"], **case.get("payloads", {}))
                if "config" in case:
                    payloads["config.json"] = json.dumps(case["config"])
                for path, contents in payloads.items():
                    (root / path).write_bytes(contents.encode("utf-8"))
                manifest = copy.deepcopy(contract["manifest"])
                manifest["files"] = []
                for path in payloads:
                    if path in case.get("omitEntries", []):
                        continue
                    content = (root / path).read_bytes()
                    entry = {"path": path, "role": "file", "size": len(content),
                             "sha256": hashlib.sha256(content).hexdigest()}
                    entry.update(case.get("fileOverrides", {}).get(path, {}))
                    manifest["files"].append(entry)
                manifest.update(case.get("manifest", {}))
                (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
                (root / "READY").write_bytes(b"ready\n")
                for path in case.get("removeFiles", []):
                    (root / path).unlink()
                errors, _ = validate_manifest(root, manifest, require_ready=True)
                self.assertEqual(case["accept"], not errors, errors)
