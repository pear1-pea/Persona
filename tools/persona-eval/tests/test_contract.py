from copy import deepcopy
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from oracle import evaluate, select
from schema import load_suite, validate_case


class ContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cases = {c["id"]: c for c in load_suite()}

    def fixture(self, case_id):
        return deepcopy(self.cases[case_id])

    def test_all_fixed_deterministic_expectations(self):
        for case in self.cases.values():
            if case["track"] == "deterministic":
                with self.subTest(case=case["id"]):
                    self.assertEqual(evaluate(case), case["expected"])

    def test_owner_swap_does_not_leak_same_memory_id(self):
        case = self.fixture("dev-01")
        case["context"]["owner"] = "u-02"
        self.assertEqual(evaluate(case)["injected"], ["foreign:v1"])

    def test_explicit_account_share_applies_to_another_persona_of_same_owner(self):
        case = self.fixture("dev-05")
        case["context"]["persona"] = "p-unshared"
        self.assertEqual(evaluate(case)["candidates"], ["tea:v1"])

    def test_scope_change_keeps_the_same_owner_memory_identity(self):
        case = self.fixture("dev-01")
        other = deepcopy(case["memories"][0])
        other.update(ref="account:v9", persona=None, version=9)
        case["memories"].append(other)
        self.assertEqual(evaluate(case)["injected"], ["account:v9"])

    def test_deleted_new_revision_does_not_fall_back_to_old(self):
        case = self.fixture("dev-06")
        deleted = deepcopy(case["memories"][0])
        deleted.update(ref="tea:v2", version=2, deleted=True)
        case["memories"].append(deleted)
        self.assertEqual(evaluate(case)["candidates"], [])

    def test_latest_revision_is_order_independent(self):
        case = self.fixture("dev-24")
        forward = select(case["memories"], case["context"])
        backward = select(list(reversed(case["memories"])), case["context"])
        self.assertEqual(forward, ["tea:v2"])
        self.assertEqual(forward, backward)

    def test_revocation_changes_injection_not_recorded_candidates(self):
        self.assertEqual(evaluate(self.fixture("dev-09")), {
            "candidates": ["tea:v1"], "injected": [], "rejected_writes": []})

    def test_delete_blocks_both_replay_and_revision(self):
        for action in ("replay", "revise"):
            case = self.fixture("dev-13")
            case["events"][1]["action"] = action
            self.assertEqual(evaluate(case)["rejected_writes"], ["replay:v2"])
            self.assertEqual(evaluate(case)["injected"], [])

    def test_cancel_is_not_undone_by_fallback(self):
        case = self.fixture("heldout-06")
        case["events"].append({"action": "executor", "value": "cloud"})
        self.assertEqual(evaluate(case)["injected"], [])

    def test_expiry_uses_fixed_clock_and_keeps_input_immutable(self):
        case = self.fixture("heldout-08")
        case["context"]["as_of"] = "2026-10-29T07:59:59Z"
        before = deepcopy(case["memories"])
        self.assertEqual(evaluate(case)["injected"], ["tea:v1"])
        self.assertEqual(case["memories"], before)

    def test_schema_rejects_string_consent(self):
        case = self.fixture("dev-06")
        case["memories"][0]["cloud_allowed"] = "false"
        with self.assertRaisesRegex(ValueError, "boolean"):
            validate_case(case, "dev")

    def test_schema_rejects_ambiguous_revisions(self):
        case = self.fixture("dev-06")
        duplicate = deepcopy(case["memories"][0])
        duplicate["ref"] = "different-ref-same-version"
        case["memories"].append(duplicate)
        with self.assertRaisesRegex(ValueError, "ambiguous"):
            validate_case(case, "dev")

    def test_write_cannot_change_owner(self):
        case = self.fixture("dev-11")
        case["events"][0]["replacement"]["owner"] = "u-02"
        with self.assertRaisesRegex(ValueError, "ownership"):
            validate_case(case, "dev")

    def test_schema_rejects_unknown_evidence_ref(self):
        case = self.fixture("dev-06")
        case["expected"]["injected"] = ["unseen:v1"]
        with self.assertRaisesRegex(ValueError, "evidence"):
            validate_case(case, "dev")

    def test_subjective_track_never_has_an_automatic_verdict(self):
        case = self.fixture("dev-19")
        case["expected"] = {"pass": True}
        with self.assertRaisesRegex(ValueError, "automatic"):
            validate_case(case, "dev")


if __name__ == "__main__":
    unittest.main()
