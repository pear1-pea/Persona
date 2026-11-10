import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'check_junit.py'
spec = importlib.util.spec_from_file_location('check_junit', SCRIPT)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class EvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def report(self, directory, xml):
        directory.mkdir(exist_ok=True)
        (directory / 'TEST-example.xml').write_text(xml, encoding='utf-8')

    def test_missing_and_empty(self):
        for directory in (self.root, self.root / 'missing'):
            with self.subTest(directory=directory):
                self.assertFalse(checker.inspect([directory])['ok'])

    def test_failure_and_error_are_counted_without_content(self):
        self.report(self.root, '<testsuite tests="2" failures="1" errors="1">'
                    '<testcase name="PRIVATE"><failure>SECRET</failure></testcase>'
                    '<testcase><error>SECRET</error></testcase></testsuite>')
        result = checker.inspect([self.root])
        self.assertFalse(result['ok'])
        self.assertEqual((result['tests'], result['failures'], result['errors']), (2, 1, 1))
        self.assertNotIn('SECRET', json.dumps(result))
        self.assertNotIn('PRIVATE', json.dumps(result))

    def test_invalid_reports(self):
        for xml in ('<', '<other/>', '<testsuite tests="1"/>',
                    '<testsuite tests="0"/>', '<testsuite tests="-1"/>',
                    '<testsuite skipped="1"><testcase/></testsuite>',
                    '<testsuites tests="2"><testsuite><testcase/></testsuite></testsuites>'):
            with self.subTest(xml=xml):
                self.report(self.root, xml)
                self.assertFalse(checker.inspect([self.root])['ok'])

    def test_multi_directory_cli_json_and_summary(self):
        first, second = self.root / 'one', self.root / 'two'
        self.report(first, '<testsuite tests="2" skipped="1"><testcase/>'
                    '<testcase><skipped/></testcase></testsuite>')
        self.report(second, '<testsuites tests="1"><testsuite tests="1">'
                    '<testcase/></testsuite></testsuites>')
        output, summary = self.root / 'result.json', self.root / 'summary.md'
        summary.write_text('existing\n', encoding='utf-8')
        run = subprocess.run([sys.executable, str(SCRIPT), str(first), str(second),
                              '--output', str(output)], capture_output=True, text=True,
                             env={**os.environ, 'GITHUB_STEP_SUMMARY': str(summary)})
        self.assertEqual(run.returncode, 0, run.stderr)
        result = json.loads(output.read_text())
        self.assertEqual((result['reports'], result['tests'], result['skipped']), (2, 3, 1))
        self.assertTrue(summary.read_text().startswith('existing\n'))
        self.assertIn('PASS', summary.read_text())
        self.assertEqual(checker.inspect([first, first])['tests'], 2)

    def test_cli_fails_closed(self):
        run = subprocess.run([sys.executable, str(SCRIPT), str(self.root)],
                             capture_output=True, text=True)
        self.assertEqual(run.returncode, 1)
        self.assertFalse(json.loads(run.stdout)['ok'])

    def test_empty_variant_cannot_borrow_other_variant_tests(self):
        first, second = self.root / 'debug', self.root / 'release'
        self.report(first, '<testsuite tests="1"><testcase/></testsuite>')
        self.report(second, '<testsuite tests="0"/>')
        self.assertFalse(checker.inspect([first, second])['ok'])

    def test_missing_reports_still_write_failure_evidence(self):
        output = self.root / 'new' / 'summary.json'
        run = subprocess.run([sys.executable, str(SCRIPT), str(self.root / 'missing'),
                              '--output', str(output)], capture_output=True, text=True)
        self.assertEqual(run.returncode, 1)
        self.assertFalse(json.loads(output.read_text())['ok'])

    def test_all_skipped_is_not_executed_evidence(self):
        self.report(self.root, '<testsuite tests="1" skipped="1"><testcase><skipped/></testcase></testsuite>')
        self.assertFalse(checker.inspect([self.root])['ok'])

    def test_summary_failure_is_reflected_in_saved_json(self):
        self.report(self.root, '<testsuite tests="1"><testcase/></testsuite>')
        output = self.root / 'result.json'
        run = subprocess.run([sys.executable, str(SCRIPT), str(self.root), '--output', str(output)],
                             capture_output=True, text=True,
                             env={**os.environ, 'GITHUB_STEP_SUMMARY': str(self.root / 'missing' / 'summary.md')})
        self.assertEqual(run.returncode, 1)
        self.assertEqual(json.loads(output.read_text()), json.loads(run.stdout))
        self.assertFalse(json.loads(output.read_text())['ok'])

    def test_output_failure_cannot_emit_pass_summary(self):
        self.report(self.root, '<testsuite tests="1"><testcase/></testsuite>')
        summary = self.root / 'summary.md'
        run = subprocess.run([sys.executable, str(SCRIPT), str(self.root), '--output', str(self.root)],
                             capture_output=True, text=True,
                             env={**os.environ, 'GITHUB_STEP_SUMMARY': str(summary)})
        self.assertEqual(run.returncode, 1)
        self.assertFalse(json.loads(run.stdout)['ok'])
        self.assertIn('FAIL', summary.read_text())
        self.assertNotIn('PASS', summary.read_text())


if __name__ == '__main__':
    unittest.main()
