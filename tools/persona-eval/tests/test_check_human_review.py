import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_human_review import check


def fixture(case_id="dev-19", reviewable=True):
    # Annotation-protocol fixtures are never represented as real human or model evidence.
    row = {"pair_id": case_id + "-r1", "case_id": case_id, "repetition": 1,
           "reviewable": reviewable, "human_reviewer": None, "rationale": None,
           "winner": None, "second_reviewer": None,
           "must_checks": {arm: {"requirement": None} for arm in ("A", "B")},
           "must_not_violations": {arm: {"violation": None} for arm in ("A", "B")},
           "dimension_scores": {arm: {"style": None} for arm in ("A", "B")}}
    template = {"protocol": "human-pairwise-v1", "input_sha256": "0" * 64,
                "split": "dev", "cases": [row]}
    mapping = [{"pair_id": row["pair_id"], "A": "baseline", "B": "candidate"}]
    annotation = copy.deepcopy(template)
    if reviewable:
        scored = annotation["cases"][0]
        scored.update(human_reviewer="unit-reviewer", rationale="Synthetic protocol test", winner="B")
        for arm in ("A", "B"):
            scored["must_checks"][arm]["requirement"] = True
            scored["must_not_violations"][arm]["violation"] = False
            scored["dimension_scores"][arm]["style"] = 2
    return template, annotation, mapping


class HumanAnnotationTest(unittest.TestCase):
    def test_complete_annotations_are_not_automatic_release_approval(self):
        result = check(*fixture())
        self.assertTrue(result["annotations_complete"])
        self.assertEqual(result["reviewed_items"], 1)
        self.assertEqual(result["release_decision"], "requires_owner_assessment")
        self.assertFalse(result["automated_quality_judgment"])

    def test_missing_scores_or_reviewer_are_never_filled(self):
        template, _, mapping = fixture()
        result = check(template, copy.deepcopy(template), mapping)
        self.assertFalse(result["annotations_complete"])
        self.assertGreater(result["missing_count"], 0)
        self.assertEqual(result["reviewers"], [])

    def test_boolean_dimension_is_not_a_numeric_score(self):
        template, annotations, mapping = fixture()
        annotations["cases"][0]["dimension_scores"]["A"]["style"] = True
        with self.assertRaisesRegex(ValueError, "0, 1 or 2"):
            check(template, annotations, mapping)

    def test_changed_digest_rubric_or_duplicate_item_is_rejected(self):
        for mutate in (lambda d: d.update(input_sha256="1" * 64),
                       lambda d: d["cases"].append(d["cases"][0]),
                       lambda d: d["cases"][0]["must_checks"]["A"].update(extra=True)):
            template, annotations, mapping = fixture()
            mutate(annotations)
            with self.assertRaises(ValueError):
                check(template, annotations, mapping)

    def test_critical_violation_needs_independent_review_and_is_not_averaged_away(self):
        template, annotations, mapping = fixture("dev-28")
        row = annotations["cases"][0]
        row["must_not_violations"]["A"]["violation"] = True
        result = check(template, annotations, mapping)
        self.assertFalse(result["annotations_complete"])
        self.assertNotIn("configuration", result["defects_reported_by_humans"][0])
        row["second_reviewer"] = "independent-unit-reviewer"
        result = check(template, annotations, mapping)
        self.assertTrue(result["annotations_complete"])
        self.assertEqual(result["defects_reported_by_humans"][0]["configuration"], "baseline")
        self.assertEqual(len(result["defects_reported_by_humans"]), 1)

    def test_style_defect_does_not_invent_an_extra_approval_requirement(self):
        template, annotations, mapping = fixture("dev-19")
        annotations["cases"][0]["must_not_violations"]["A"]["violation"] = True
        result = check(template, annotations, mapping)
        self.assertTrue(result["annotations_complete"])
        self.assertEqual(len(result["defects_reported_by_humans"]), 1)

    def test_both_failed_is_a_resolved_rejection_without_an_invented_reviewer(self):
        template, annotations, mapping = fixture()
        row = annotations["cases"][0]
        row["winner"] = "both_fail"
        for arm in ("A", "B"):
            row["must_checks"][arm]["requirement"] = False
        result = check(template, annotations, mapping)
        self.assertTrue(result["annotations_complete"])
        self.assertEqual(result["human_selections"], {"both_fail": 1})
        self.assertEqual(result["unresolved_items"], [])
        self.assertEqual(len(result["defects_reported_by_humans"]), 2)
        self.assertEqual(result["release_decision"], "requires_owner_assessment")

    def test_both_failed_does_not_waive_critical_violation_review(self):
        template, annotations, mapping = fixture("dev-28")
        row = annotations["cases"][0]
        row["winner"] = "both_fail"
        row["must_not_violations"]["A"]["violation"] = True
        result = check(template, annotations, mapping)
        self.assertFalse(result["annotations_complete"])
        self.assertIn({"item": row["pair_id"], "field": "independent_second_reviewer"}, result["missing"])

    def test_single_configuration_can_fail_without_becoming_undecided(self):
        template, annotations, _ = fixture()
        template["comparison_mode"] = annotations["comparison_mode"] = "single_configuration"
        for document in (template, annotations):
            for field in ("must_checks", "must_not_violations", "dimension_scores"):
                del document["cases"][0][field]["B"]
        row = annotations["cases"][0]
        row["winner"] = "fail"
        row["must_checks"]["A"]["requirement"] = False
        mapping = [{"pair_id": row["pair_id"], "A": "candidate"}]
        result = check(template, annotations, mapping)
        self.assertTrue(result["annotations_complete"])
        self.assertEqual(result["human_selections"], {"fail": 1})
        self.assertEqual(result["unresolved_items"], [])
        row["winner"] = "both_fail"
        with self.assertRaisesRegex(ValueError, "Invalid human selection"):
            check(template, annotations, mapping)

    def test_unresolved_still_requires_an_independent_reviewer(self):
        template, annotations, mapping = fixture()
        annotations["cases"][0]["winner"] = "unresolved"
        result = check(template, annotations, mapping)
        self.assertFalse(result["annotations_complete"])
        self.assertEqual(result["unresolved_items"], ["dev-19-r1"])

    def test_unresolved_with_second_reviewer_stays_incomplete_and_blind(self):
        for mode in ("blind_pairwise", "single_configuration"):
            with self.subTest(mode=mode):
                template, annotations, mapping = fixture()
                template["comparison_mode"] = annotations["comparison_mode"] = mode
                if mode == "single_configuration":
                    for document in (template, annotations):
                        for field in ("must_checks", "must_not_violations", "dimension_scores"):
                            del document["cases"][0][field]["B"]
                    del mapping[0]["B"]
                    mapping[0]["A"] = "candidate"
                row = annotations["cases"][0]
                row.update(winner="unresolved", second_reviewer="independent-unit-reviewer")
                row["dimension_scores"]["A"]["style"] = 1
                result = check(template, annotations, mapping)
                self.assertFalse(result["annotations_complete"])
                self.assertEqual(result["reviewed_items"], 0)
                self.assertIn({"item": row["pair_id"], "field": "resolved_winner"}, result["missing"])
                self.assertTrue(all("configuration" not in defect
                                    for defect in result["defects_reported_by_humans"]))

    def test_independent_reviewer_names_are_both_trimmed(self):
        for primary, secondary in ((" unit-reviewer ", "unit-reviewer"),
                                   ("unit-reviewer", " unit-reviewer "),
                                   (" unit-reviewer ", " unit-reviewer ")):
            with self.subTest(primary=primary, secondary=secondary):
                template, annotations, mapping = fixture("dev-28")
                row = annotations["cases"][0]
                row.update(human_reviewer=primary, second_reviewer=secondary)
                row["must_not_violations"]["A"]["violation"] = True
                original = copy.deepcopy(annotations)
                result = check(template, annotations, mapping)
                self.assertFalse(result["annotations_complete"])
                self.assertIn({"item": row["pair_id"], "field": "independent_second_reviewer"}, result["missing"])
                self.assertEqual(annotations, original)

    def test_bound_packet_accepts_matching_identity_and_mapping(self):
        template, annotations, mapping = fixture()
        template["packet_id"] = annotations["packet_id"] = "a" * 32
        result = check(template, annotations, {"packet_id": "a" * 32, "mapping": mapping})
        self.assertTrue(result["annotations_complete"])
        self.assertEqual(result["packet_id"], "a" * 32)

    def test_bound_packet_rejects_missing_changed_or_invalid_identity(self):
        for packet_id in (None, "", "b" * 32, 42, " a "):
            with self.subTest(packet_id=packet_id):
                template, annotations, mapping = fixture()
                template["packet_id"] = "a" * 32
                if packet_id is not None:
                    annotations["packet_id"] = packet_id
                with self.assertRaisesRegex(ValueError, "packet"):
                    check(template, annotations, mapping)
        template, annotations, mapping = fixture()
        template["packet_id"] = annotations["packet_id"] = " "
        with self.assertRaisesRegex(ValueError, "packet"):
            check(template, annotations, mapping)

    def test_bound_packet_rejects_legacy_or_wrong_packet_mapping(self):
        for mapping_id in (None, "b" * 32):
            with self.subTest(mapping_id=mapping_id):
                template, annotations, mapping = fixture()
                template["packet_id"] = annotations["packet_id"] = "a" * 32
                if mapping_id is not None:
                    mapping = {"packet_id": mapping_id, "mapping": mapping}
                with self.assertRaisesRegex(ValueError, "packet"):
                    check(template, annotations, mapping)

    def test_legacy_packet_rejects_unexpected_binding(self):
        template, annotations, mapping = fixture()
        annotations["packet_id"] = "a" * 32
        with self.assertRaisesRegex(ValueError, "packet"):
            check(template, annotations, mapping)
        del annotations["packet_id"]
        with self.assertRaisesRegex(ValueError, "packet"):
            check(template, annotations, {"packet_id": "a" * 32, "mapping": mapping})

    def test_unsupported_items_are_not_counted_or_scored(self):
        template, annotations, mapping = fixture(reviewable=False)
        result = check(template, annotations, mapping)
        self.assertEqual(result["reviewed_items"], 0)
        self.assertEqual(result["required_items"], 0)
        annotations["cases"][0]["dimension_scores"]["A"]["style"] = 2
        with self.assertRaises(ValueError):
            check(template, annotations, mapping)


if __name__ == "__main__":
    unittest.main()
