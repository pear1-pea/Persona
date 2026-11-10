"""Record the source and selected tool versions without dumping configuration."""
import argparse
import json
import os
from pathlib import Path
import platform
import subprocess
import sys


def version(label, command):
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=60)
    if result.returncode:
        raise RuntimeError(label + ' version check failed')
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--android', action='store_true')
    parser.add_argument('--node', action='store_true')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    data = {'sourceRevision': version('Git', ['git', 'rev-parse', 'HEAD']),
            'os': platform.platform(), 'architecture': platform.machine(),
            'python': platform.python_version()}
    if args.node:
        data['node'] = version('Node', ['node', '--version'])
        command = ['cmd.exe', '/d', '/c', 'npm.cmd', '--version'] if os.name == 'nt' else ['npm', '--version']
        data['npm'] = version('npm', command)
    if args.android:
        data['java'] = version('Java', ['java', '-version'])
        command = ['cmd.exe', '/d', '/c', 'gradlew.bat', '--version'] if os.name == 'nt' else ['bash', './gradlew', '--version']
        data['gradle'] = version('Gradle', command)
        sdk = Path(os.environ['ANDROID_HOME'])
        data['androidPackages'] = {}
        for relative in ['platforms/android-36', 'build-tools/36.0.0',
                         'ndk/27.0.11718014', 'cmake/3.22.1']:
            text = (sdk / relative / 'source.properties').read_text(encoding='utf-8')
            revisions = [line.split('=', 1)[1].strip() for line in text.splitlines()
                         if line.split('=', 1)[0].strip() == 'Pkg.Revision']
            if len(revisions) != 1:
                raise RuntimeError('Android package revision missing')
            data['androidPackages'][relative] = revisions[0]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(data, indent=2))
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as stream:
            stream.write('\n### Environment\n\n```json\n' + json.dumps(data, indent=2) + '\n```\n')


if __name__ == '__main__':
    main()
