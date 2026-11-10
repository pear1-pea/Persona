#!/usr/bin/env python3
"""Validate JUnit evidence without exposing test names or captured output."""
import argparse
import json
import os
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def inspect(directories):
    result = dict(ok=False, reports=0, tests=0, skipped=0, failures=0, errors=0,
                  issues=[])
    seen = set()
    roots = set()
    for directory in directories:
        root = Path(directory)
        if root.resolve() in roots:
            continue
        roots.add(root.resolve())
        tests_before = result['tests']
        skipped_before = result['skipped']
        if not root.is_dir():
            result['issues'].append('missing report directory')
            continue
        try:
            files = sorted(root.rglob('*.xml'))
        except OSError:
            result['issues'].append('unreadable report directory')
            continue
        if not files:
            result['issues'].append('no XML reports in directory')
        for path in files:
            identity = path.resolve()
            if identity in seen:
                continue
            seen.add(identity)
            result['reports'] += 1
            try:
                tree = ET.parse(path).getroot()
                if tree.tag not in ('testsuite', 'testsuites'):
                    raise ValueError()
                def visit(node):
                    if node.tag not in ('testsuite', 'testsuites'):
                        raise ValueError()
                    counts = dict(tests=0, skipped=0, failures=0, errors=0)
                    for child in node:
                        if child.tag == 'testcase':
                            if node.tag != 'testsuite':
                                raise ValueError()
                            counts['tests'] += 1
                            statuses = [tag for tag in ('skipped', 'failure', 'error')
                                        if child.find(tag) is not None]
                            if len(statuses) > 1 or any(len(child.findall(tag)) > 1 for tag in statuses):
                                raise ValueError()
                            for tag in statuses:
                                counts[{'failure': 'failures', 'error': 'errors'}.get(tag, tag)] += 1
                        elif child.tag in ('testsuite', 'testsuites'):
                            nested = visit(child)
                            for key in counts:
                                counts[key] += nested[key]
                    for key, actual in counts.items():
                        declared = node.get(key)
                        if declared is not None:
                            if not declared.isascii() or not declared.isdecimal() or int(declared) != actual:
                                raise ValueError()
                    return counts
                counts = visit(tree)
                for key, value in counts.items():
                    result[key] += value
            except (ET.ParseError, OSError, ValueError, RecursionError):
                result['issues'].append('invalid XML report or inconsistent counts')
        if result['tests'] == tests_before:
            result['issues'].append('zero tests in report directory')
        elif result['tests'] - tests_before == result['skipped'] - skipped_before:
            result['issues'].append('all tests skipped in report directory')
    if not result['reports']:
        result['issues'].append('zero XML reports')
    if not result['tests']:
        result['issues'].append('zero tests')
    if result['failures'] or result['errors']:
        result['issues'].append('test failures or errors')
    result['ok'] = not result['issues']
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directories', nargs='+', help='JUnit report directories (recursive)')
    parser.add_argument('--output', type=Path, help='write JSON evidence to this file')
    args = parser.parse_args(argv)
    result = inspect(args.directories)
    try:
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
        summary = os.environ.get('GITHUB_STEP_SUMMARY')
        if summary:
            with open(summary, 'a', encoding='utf-8') as stream:
                stream.write('\n### JUnit evidence: ' + ('PASS' if result['ok'] else 'FAIL') + '\n\n')
                stream.write(', '.join(f'{key}: {result[key]}' for key in
                                      ('reports', 'tests', 'skipped', 'failures', 'errors')) + '\n')
    except OSError:
        result['ok'] = False
        result['issues'].append('could not write evidence or summary')
    print(json.dumps(result))
    return 0 if result['ok'] else 1


if __name__ == '__main__':
    sys.exit(main())
