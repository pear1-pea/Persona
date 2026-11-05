"""Compile the production migration against a recording SQLite API boundary.

The emitted SQL is executed by test_storage.py. This does not run Android Room.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--deps', type=Path, default=root / 'build/verification/p2-mac/deps')
    parser.add_argument('--java-home', type=Path, default=os.environ.get('JAVA_HOME'))
    parser.add_argument('--output', type=Path, default=root / 'build/verification/p3-mac/migration')
    args = parser.parse_args()
    java = str(args.java_home / 'bin/java') if args.java_home else shutil.which('java')
    if not java:
        parser.error('Set --java-home to a JDK 17 installation')
    jars = sorted(args.deps.glob('*.jar'))
    if not jars:
        parser.error('--deps must contain the Kotlin 2.0.21 compiler and existing verification jars')
    output = args.output.resolve()
    run = output / datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    run.mkdir(parents=True, exist_ok=False)
    boundaries = {
        'Migration.kt': '''package androidx.room.migration
import androidx.sqlite.db.SupportSQLiteDatabase
abstract class Migration(val startVersion: Int, val endVersion: Int) {
 abstract fun migrate(db: SupportSQLiteDatabase)
}
''',
        'Database.kt': '''package androidx.sqlite.db
interface SupportSQLiteDatabase {
 fun execSQL(sql: String)
 fun execSQL(sql: String, bindArgs: Array<out Any?>)
}
''',
        'Record.kt': '''package com.example.persona.data.local
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.Gson
fun main() {
 val statements = mutableListOf<String>()
 val db = object : SupportSQLiteDatabase {
  override fun execSQL(sql: String) { statements += sql }
  override fun execSQL(sql: String, bindArgs: Array<out Any?>) { error("Unexpected bound migration") }
 }
 MIGRATION_8_9.migrate(db)
 println(Gson().toJson(statements))
}
'''
    }
    for name, content in boundaries.items():
        (run / name).write_text(content)
    directory = root / 'app/src/main/java/com/example/persona/data/local'
    sources = [directory / 'Migrations.kt', directory / 'SeedPersonas.kt']
    source_hashes = {str(path.relative_to(root)): digest(path) for path in sources}
    frozen = []
    for path in sources:
        target = run / path.name
        shutil.copyfile(path, target)
        frozen.append(str(target))
    classpath = os.pathsep.join(map(str, jars))
    command = [java, '-cp', classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
               '-no-stdlib', '-no-reflect', '-jvm-target', '11', '-classpath', classpath,
               '-d', str(run / 'classes'), *[str(run / name) for name in boundaries], *frozen]
    compiled = subprocess.run(command, capture_output=True, text=True, timeout=120)
    (run / 'compile.log').write_text(compiled.stdout + compiled.stderr)
    if compiled.returncode:
        print(compiled.stdout + compiled.stderr)
        return compiled.returncode
    executed = subprocess.run([java, '-cp', str(run / 'classes') + os.pathsep + classpath,
                               'com.example.persona.data.local.RecordKt'],
                              capture_output=True, text=True, timeout=30)
    (run / 'execute.log').write_text(executed.stdout + executed.stderr)
    if executed.returncode:
        print(executed.stderr)
        return executed.returncode
    statements = json.loads(executed.stdout)
    assert source_hashes == {str(path.relative_to(root)): digest(path) for path in sources}
    encoded = json.dumps(statements, indent=2) + '\n'
    (run / 'migration-sql.json').write_text(encoded)
    (output / 'migration-sql.json').write_text(encoded)
    receipt = {'sources': source_hashes, 'sql_sha256': digest(output / 'migration-sql.json'),
               'dependencies': {path.name: digest(path) for path in jars},
               'run': str(run), 'android_room_validation': 'unrun'}
    (run / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print('Recorded actual MIGRATION_8_9 SQL:', len(statements))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
