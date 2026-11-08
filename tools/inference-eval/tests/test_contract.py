import copy
import json
from pathlib import Path
import re
import sys
import tempfile
import unittest

TOOL = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOL))
from contract import (ENUMS, FIELDS, INT_MAX, Invalid, LONG_MAX, MAX_BYTES,
                      NULLABLE, NUMBERS, read_json, validate_export, validate_metadata)
from evaluate import AI, ROOT


def fixture():
    return json.loads((TOOL / "fixtures/baseline.json").read_text())


class ContractTests(unittest.TestCase):
    def test_current_kotlin_export_fields_and_enums(self):
        source = (ROOT / (AI + "telemetry/JournalJson.kt")).read_text()
        export_writer = source.split("private fun writeAttempts(", 1)[1]
        fields = set(re.findall(r'writer.name\("(\w+)"\)', export_writer))
        self.assertEqual(fields, FIELDS)
        sources = "\n".join((ROOT / (AI + path)).read_text() for path in (
            "telemetry/InferenceTelemetry.kt", "Backend.kt", "GenerationEvent.kt"))
        names = {"task": "AiTaskKind", "backend": "Backend", "outcome": "AttemptOutcome",
                 "trigger": "AttemptTrigger", "contractStatus": "ContractStatus",
                 "errorType": "GenerationErrorType", "stopReason": "StopReason"}
        for field, name in names.items():
            body = re.search(r"enum class " + name + r"\s*\{([^}]+)\}", sources)[1]
            self.assertEqual(set(re.findall(r"\b[A-Z][A-Z_]+\b", body)), set(ENUMS[field]))

    def test_fixture_and_kotlin_bounds(self):
        rows = fixture()
        for field, maximum in NUMBERS.items():
            rows[0][field] = maximum
        self.assertEqual(validate_export(rows), rows)
        self.assertEqual(INT_MAX, 2147483647)
        self.assertEqual(LONG_MAX, 9223372036854775807)

    def test_unknown_missing_and_forbidden_fields(self):
        for field in ("Prompt", "prompt", "Response", "response", "owner", "creatorId", "extra"):
            rows = fixture()
            rows[1][field] = "withheld"
            with self.subTest(field=field), self.assertRaises(Invalid):
                validate_export(rows)
        for field in FIELDS:
            rows = fixture()
            del rows[0][field]
            with self.subTest(missing=field), self.assertRaises(Invalid):
                validate_export(rows)

    def test_numbers_reject_bool_float_string_negative_and_overflow(self):
        for field, maximum in NUMBERS.items():
            for value in (True, False, 1.0, "1", -1, maximum + 1, float("nan"), float("inf")):
                rows = fixture()
                rows[0][field] = value
                with self.subTest(field=field, kind=type(value)), self.assertRaises(Invalid):
                    validate_export(rows)

    def test_nullable_and_required_fields(self):
        for field in FIELDS:
            rows = fixture()
            rows[0][field] = None
            if field in NULLABLE:
                validate_export(rows)
            else:
                with self.subTest(field=field), self.assertRaises(Invalid):
                    validate_export(rows)

    def test_enum_and_hash_validation(self):
        for field in ENUMS:
            for value in ("bad", "completed", True, [], {}):
                rows = fixture()
                rows[0][field] = value
                with self.subTest(field=field), self.assertRaises(Invalid):
                    validate_export(rows)
        for field in ("requestHash", "modelFingerprint"):
            for value in ("a" * 63, "a" * 65, "g" * 64, "a" * 63 + "\n", 10):
                rows = fixture()
                rows[0][field] = value
                with self.subTest(field=field), self.assertRaises(Invalid):
                    validate_export(rows)
        rows = fixture()
        rows[0]["modelFingerprint"] = "F" * 64
        validate_export(rows)

    def test_duplicate_attempts_and_inconsistent_request_tasks(self):
        rows = fixture()
        with self.assertRaises(Invalid):
            validate_export(rows + [copy.deepcopy(rows[0])])
        rows[1]["task"] = "MEMORY_EXTRACTION"
        with self.assertRaises(Invalid):
            validate_export(rows)

    def test_top_level_and_bad_rows(self):
        for data in ({"attempts": fixture()}, None, {}, [None], [False], fixture() + [{}]):
            with self.subTest(kind=type(data)), self.assertRaises(Invalid):
                validate_export(data)
        self.assertEqual(validate_export([]), [])

    def test_json_rejects_duplicate_keys_nonintegers_and_nonfinite(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "input.json"
            for raw in (b'{"task":1,"task":2}', b'[NaN]', b'[Infinity]', b'[-Infinity]',
                        b'[1.0]', b'[1e9999]', b'[] trailing', b'\xff', b'[' * 2000):
                path.write_bytes(raw)
                with self.subTest(raw_length=len(raw)), self.assertRaises(Invalid):
                    read_json(path)
            path.write_bytes(b' ' * (MAX_BYTES + 1))
            with self.assertRaises(Invalid):
                read_json(path)

    def test_metadata_is_strict_and_bounded(self):
        metadata = json.loads((TOOL / "fixtures/baseline.metadata.json").read_text())
        validate_metadata(metadata)
        for field, bad in (("label", "unsafe\nlabel"), ("label", "a" * 81),
                           ("label", True), ("workload_sha256", "bad"),
                           ("source_revision", "main"), ("source_revision", "f" * 41),
                           ("owner", "extra")):
            data = dict(metadata, **{field: bad})
            with self.subTest(field=field), self.assertRaises(Invalid):
                validate_metadata(data)
        for field in metadata:
            data = dict(metadata)
            del data[field]
            with self.assertRaises(Invalid):
                validate_metadata(data)


if __name__ == "__main__":
    unittest.main()
