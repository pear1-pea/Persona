#!/usr/bin/env python3
"""Run the documented CLI examples against synthetic data and inspect outputs."""

import json
from pathlib import Path
import subprocess
import sys
import tempfile

TOOL = Path(__file__).resolve().parent


def run_example(*args):
    result = subprocess.run([sys.executable, "-B", str(TOOL / "evaluate.py"), *map(str, args)],
                            capture_output=True, check=False)
    if result.returncode != 0:
        raise RuntimeError("synthetic CLI example failed (output withheld)")


def main():
    fixtures = TOOL / "fixtures"
    with tempfile.TemporaryDirectory(prefix="inference-eval-synthetic-") as directory:
        output = Path(directory)
        run_example("summarize", fixtures / "baseline.json", "--output", output / "summary")
        run_example("compare", fixtures / "baseline.json", fixtures / "candidate.json",
                    "--left-metadata", fixtures / "baseline.metadata.json",
                    "--right-metadata", fixtures / "candidate.metadata.json",
                    "--output", output / "comparison")
        summary = json.loads((output / "summary/report.json").read_text())
        comparison = json.loads((output / "comparison/report.json").read_text())
        if (summary["summary"]["attempts"]["attempt_count"] != 3
                or summary["summary"]["logical_requests"]["count"] != 2
                or comparison["observed_deltas"]["status"] != "matched"
                or comparison["semantic_quality"] != "not_evaluated"):
            raise RuntimeError("synthetic report contract failed")
        initial = next(group for group in comparison["observed_deltas"]["groups"]
                       if group["task"] == "CHAT" and group["backend"] == "MNN")
        if initial["metrics"]["totalLatencyMs"]["delta"]["p50"] != 20:
            raise RuntimeError("synthetic observed delta failed")
    print("synthetic summarize + compare examples: OK (no LLM, no private payload)")


if __name__ == "__main__":
    main()
