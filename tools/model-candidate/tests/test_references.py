import copy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from references import no_links, read_json, reference_case, verify_snapshot


class TokenizerBoundary:
    def __init__(self, count=21, disagree=False):
        self.count = count
        self.disagree = disagree
        self.calls = []

    def apply_chat_template(self, messages, **options):
        self.calls.append(options)
        if options == {"tokenize": False, "add_generation_prompt": True, "enable_thinking": False}:
            return "synthetic<|im_start|>assistant\n<think>\n\n</think>\n\n"
        if options == {"tokenize": True, "add_generation_prompt": True, "enable_thinking": False}:
            return list(range(self.count + int(self.disagree)))
        raise AssertionError("Unexpected tokenizer invocation")

    def __call__(self, text, **options):
        if options != {"add_special_tokens": False}:
            raise AssertionError("Unexpected tokenizer encoding options")
        return {"input_ids": list(range(self.count))}


class ReferenceTests(unittest.TestCase):
    def case(self):
        return {"id": "smoke-single", "kind": "SMOKE", "source": None,
                "messages": [{"role": "user", "content": "synthetic"}], "legacyPlanningText": "legacy",
                "params": {"temperature": 0.0, "topP": 0.8, "maxTokens": 24}, "expected": []}

    def test_external_tokenizer_hard_switch_and_no_extra_special_tokens(self):
        tokenizer = TokenizerBoundary()
        row, summary = reference_case(tokenizer, self.case())
        self.assertEqual(21, len(row["referenceIds"]))
        self.assertNotIn("legacyPlanningText", row)
        self.assertEqual(4096 - 21 - 24 - 56, summary["remainingTokens"])
        self.assertEqual(2, len(tokenizer.calls))

    def test_disagreeing_encoding_paths_are_rejected(self):
        with self.assertRaises(ValueError):
            reference_case(TokenizerBoundary(disagree=True), self.case())

    def test_over_budget_is_rejected_before_bundle_creation(self):
        with self.assertRaises(ValueError):
            reference_case(TokenizerBoundary(count=4096), self.case())

    def test_unexpected_parameter_types_and_caps_are_rejected(self):
        for key, value in [("maxTokens", True), ("maxTokens", 256), ("temperature", True),
                           ("temperature", 0.7), ("topP", float("nan")), ("topP", 1.0)]:
            case = self.case()
            case["params"][key] = value
            with self.subTest(key=key, value=repr(value)), self.assertRaises(ValueError):
                reference_case(TokenizerBoundary(), case)

    def test_empty_messages_unknown_roles_and_oversized_input_are_rejected(self):
        for messages in [[], [{"role": "tool", "content": "synthetic"}],
                         [{"role": "user", "content": ""}], [{"role": "user", "content": "🙂" * 8192}]]:
            case = self.case()
            case["messages"] = messages
            with self.assertRaises(ValueError):
                reference_case(TokenizerBoundary(), case)

    def test_case_fields_are_closed(self):
        case = self.case()
        case["owner"] = "synthetic"
        with self.assertRaises(ValueError):
            reference_case(TokenizerBoundary(), case)

    def test_duplicate_keys_and_non_json_constants_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "input.json"
            for raw in ['{"revision":1,"revision":2}', '{"x":NaN}', '{"x":Infinity}', '\ufeff{}']:
                path.write_text(raw, encoding="utf-8")
                with self.assertRaises(ValueError):
                    read_json(path)

    def fixture(self, root):
        files = []
        for i in range(10):
            data = ("synthetic-" + str(i)).encode()
            name = str(i) + ".json"
            (root / name).write_bytes(data)
            files.append({"path": name, "size": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                          "hf_blob_id": hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest(),
                          "hf_lfs_sha256": None})
        return {"snapshotFiles": files, "modelRevision": "synthetic-fixture"}

    def test_real_snapshot_files_are_hashed_and_git_blob_checked(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            lock = self.fixture(root)
            self.assertTrue(verify_snapshot(root, lock)["completeVerified"])
            forged = copy.deepcopy(lock)
            forged["snapshotFiles"][0]["hf_blob_id"] = "0" * 40
            with self.assertRaises(ValueError):
                verify_snapshot(root, forged)
            (root / "0.json").write_text("changed", encoding="utf-8")
            with self.assertRaises(ValueError):
                verify_snapshot(root, lock)

    def test_extra_snapshot_file_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            lock = self.fixture(root)
            (root / "extra").write_text("synthetic", encoding="utf-8")
            with self.assertRaises(ValueError):
                verify_snapshot(root, lock)

    def test_linked_source_and_ancestor_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            actual = root / "actual"
            actual.mkdir()
            lock = self.fixture(actual)
            link = root / "alias"
            link.symlink_to(actual, target_is_directory=True)
            with self.assertRaises(ValueError):
                no_links(link / "0.json")
            with self.assertRaises(ValueError):
                verify_snapshot(link, lock)


if __name__ == "__main__":
    unittest.main()
