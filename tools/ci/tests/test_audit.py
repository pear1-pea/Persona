from pathlib import Path
import json
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'summarize_audit.py'


class AuditTests(unittest.TestCase):
    def test_clean_vulnerable_and_unavailable_are_distinguished(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / 'audit.json'
            counts = dict(info=0, low=0, moderate=0, high=0, critical=0, total=0)
            for value, expected in [({'metadata': {'vulnerabilities': counts}}, 0),
                                    ({'metadata': {'vulnerabilities': {**counts, 'high': 1, 'total': 1}}}, 1),
                                    ({'error': {'code': 'UNAVAILABLE'}}, 1), ({}, 1)]:
                report.write_text(json.dumps(value))
                result = subprocess.run([sys.executable, str(SCRIPT), str(report)], capture_output=True)
                self.assertEqual(result.returncode, expected)
