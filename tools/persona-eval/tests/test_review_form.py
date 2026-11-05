import copy
import json
from pathlib import Path
import shutil
import subprocess
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "review_form.js"
HARNESS = r"""
const fs = require('fs');
const vm = require('vm');
const input = JSON.parse(fs.readFileSync(0, 'utf8'));
class Element {
  constructor(tag) { this.tag = tag; this.children = []; this.listeners = {}; }
  append(...children) { this.children.push(...children); }
  addEventListener(event, handler) { this.listeners[event] = handler; }
  click() { if (this.listeners.click) this.listeners.click(); }
  insertBefore(child) { this.children.unshift(child); }
}
const sections = input.review.cases.map(() => new Element('section'));
const body = new Element('body');
const requestedKeys = [];
let downloaded;
const context = {
  document: {
    body,
    getElementById: () => ({textContent: JSON.stringify(input.review)}),
    querySelectorAll: () => sections,
    querySelector: () => sections[0],
    createElement: tag => new Element(tag),
    createTextNode: text => ({textContent: text})
  },
  localStorage: {
    getItem(key) { requestedKeys.push(key); return input.storage[key] || null; },
    setItem(key, value) { input.storage[key] = value; }
  },
  Blob: class { constructor(parts) { this.parts = parts; } },
  URL: {
    createObjectURL(blob) { downloaded = JSON.parse(blob.parts.join('')); return 'blob:test'; },
    revokeObjectURL() {}
  },
  setTimeout: callback => callback()
};
vm.runInNewContext(fs.readFileSync(process.argv[1], 'utf8'), context);
body.children.find(child => child.tag === 'button').click();
process.stdout.write(JSON.stringify({downloaded, requestedKeys, storage: input.storage}));
"""


def fixture():
    # Browser fixtures contain synthetic annotations only.
    return {"packet_id": "a" * 32, "input_sha256": "0" * 64, "split": "dev",
            "comparison_mode": "blind_pairwise", "summary": {},
            "cases": [{"pair_id": "dev-19-r1", "case_id": "dev-19", "repetition": 1,
                       "reviewable": True, "winner": None,
                       "must_checks": {"A": {"requirement": None}, "B": {"requirement": None}},
                       "must_not_violations": {"A": {}, "B": {}},
                       "dimension_scores": {"A": {"style": None}, "B": {"style": None}}}]}


@unittest.skipUnless(shutil.which("node"), "Browser script tests require Node.js")
class ReviewFormTest(unittest.TestCase):
    def run_form(self, review, saved=None, key=None):
        storage = {} if saved is None else {key: json.dumps(saved)}
        result = subprocess.run(["node", "-e", HARNESS, str(SCRIPT)],
                                input=json.dumps({"review": review, "storage": storage}),
                                text=True, capture_output=True, check=True)
        return json.loads(result.stdout)

    def test_same_packet_restores_scores_and_exports_identity(self):
        review = fixture()
        saved = copy.deepcopy(review)
        saved["cases"][0]["winner"] = "both_fail"
        key = "persona-human-review-" + review["packet_id"]
        result = self.run_form(review, saved, key)
        self.assertEqual(result["downloaded"]["cases"][0]["winner"], "both_fail")
        self.assertEqual(result["downloaded"]["packet_id"], review["packet_id"])
        self.assertEqual(result["requestedKeys"], [key])

    def test_bound_packet_ignores_legacy_export_cache(self):
        review = fixture()
        saved = copy.deepcopy(review)
        del saved["packet_id"]
        saved["cases"][0]["winner"] = "A"
        result = self.run_form(review, saved, "persona-human-review-" + review["input_sha256"])
        self.assertIsNone(result["downloaded"]["cases"][0]["winner"])

    def test_cache_identity_and_item_order_must_match(self):
        mutations = (
            lambda saved: saved.update(packet_id="b" * 32),
            lambda saved: saved.update(input_sha256="1" * 64),
            lambda saved: saved.update(comparison_mode="single_configuration"),
            lambda saved: saved["cases"][0].update(pair_id="dev-20-r1"),
            lambda saved: saved["cases"][0].update(case_id="dev-20"),
            lambda saved: saved["cases"][0].update(repetition=2),
            lambda saved: saved["cases"][0].update(reviewable=False),
        )
        for index, mutate in enumerate(mutations):
            with self.subTest(mutation=index):
                review = fixture()
                saved = copy.deepcopy(review)
                saved["cases"][0]["winner"] = "A"
                mutate(saved)
                result = self.run_form(review, saved, "persona-human-review-" + review["packet_id"])
                self.assertIsNone(result["downloaded"]["cases"][0]["winner"])

    def test_legacy_form_downloads_without_reusing_unbound_cache(self):
        review = fixture()
        del review["packet_id"]
        saved = copy.deepcopy(review)
        saved["cases"][0]["winner"] = "A"
        key = "persona-human-review-" + review["input_sha256"]
        result = self.run_form(review, saved, key)
        self.assertIsNone(result["downloaded"]["cases"][0]["winner"])
        self.assertEqual(result["requestedKeys"], [])
        self.assertEqual(result["storage"][key], json.dumps(saved))


if __name__ == "__main__":
    unittest.main()
