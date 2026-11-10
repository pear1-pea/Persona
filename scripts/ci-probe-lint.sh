#!/usr/bin/env bash
set -euo pipefail
probe=app/src/main/java/com/example/persona/CiLintFailureProbe.java
logs=build/ci/android
test ! -e "$probe"
mkdir -p "$logs"
cleanup() { rm -f "$probe"; }
trap cleanup EXIT
cat > "$probe" <<'JAVA'
package com.example.persona;
final class CiLintFailureProbe {
    void probe(android.content.Context context) {
        context.getSystemService(android.app.LocaleManager.class).getApplicationLocales();
    }
}
JAVA
status=0
./gradlew lintDebug --stacktrace --console=plain > "$logs/lint-expected-failure.log" 2>&1 || status=$?
test "$status" -ne 0
grep -q 'NewApi' "$logs/lint-expected-failure.log"
grep -q 'CiLintFailureProbe' "$logs/lint-expected-failure.log"
cp app/build/reports/lint-results-debug.xml "$logs/lint-expected-failure.xml"
cleanup
./gradlew lintDebug --stacktrace --console=plain > "$logs/lint-recovery.log" 2>&1
echo 'Real lint error failed and source restoration recovered successfully.'
