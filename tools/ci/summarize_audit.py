"""Summarize an actual npm audit; invalid or unavailable scans fail closed."""
import json
import os
from pathlib import Path
import sys


def main():
    try:
        report = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
        if report.get('error'):
            raise ValueError('scan unavailable')
        counts = report['metadata']['vulnerabilities']
        levels = ('info', 'low', 'moderate', 'high', 'critical')
        if any(type(counts[level]) is not int or counts[level] < 0 for level in levels):
            raise ValueError('invalid scan counts')
        if counts['total'] != sum(counts[level] for level in levels):
            raise ValueError('inconsistent scan counts')
    except (OSError, ValueError, KeyError, TypeError, IndexError):
        print('Audit unavailable or invalid; no clean security result was established.')
        if os.environ.get('GITHUB_STEP_SUMMARY'):
            with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as stream:
                stream.write('\n### Audit: UNKNOWN / FAILED\n')
        return 1
    text = ', '.join(f'{level}: {counts[level]}' for level in levels)
    print(text)
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as stream:
            stream.write('\n### Production dependency audit\n\n' + text + '\n')
    return 1 if counts['high'] or counts['critical'] else 0


if __name__ == '__main__':
    sys.exit(main())
