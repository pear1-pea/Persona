# CI evidence and failure paths

Run helper contracts without Android or a database:

```sh
python3 -B -m unittest discover -s tools/ci/tests -v
```

`check_junit.py DIRECTORY [DIRECTORY ...] --output FILE` checks every variant,
rejecting missing/malformed XML, inconsistent counts, failures/errors and variants
with no executed tests. It writes count-only JSON and a GitHub job summary without
test names, output or private messages. Output parent directories are created so
missing-report failures can still retain evidence.

`environment.py --output FILE [--node] [--android]` records the checked-out source,
OS, architecture, Python and requested actual runtime versions. Android package
revisions come from installed SDK metadata. It never enumerates environment values.

`summarize_audit.py FILE` parses npm audit JSON. Missing/invalid/unavailable scans
fail explicitly; high/critical findings also fail. The workflow preserves the npm
exit code and audit JSON. An unavailable registry is never classified as clean.

`bash scripts/ci-wait-http.sh URL PID [ATTEMPTS=30] [DELAY=1]` requires the owned
process to remain alive and the HTTP request to finish successfully. Each attempt
has a one-second connect timeout and two-second overall timeout. The host workflow
retains readiness/server logs and cleans up its own child process on exit.

`backend/scripts/smoke-auth.test.mjs` runs real loopback HTTP fixtures covering
successful cleanup, partial registration failure, independent account cleanup,
primary-error preservation, and response-header/body stalls. It is part of npm test.

The opt-in `scripts/ci-probe-lint.sh` uses real Gradle lint: an unguarded API-33 call
must fail with NewApi, then removing the owned temporary source must restore lint
success. Failure evidence is copied before recovery overwrites the normal report.
It is enabled only by manual `ci.yml` input `run_failure_probes=true`. That run can
also call the independent Windows workflow with `run_windows=true` before the new
Windows entry point has been merged to master.

Normal CI must return green after restoring any deliberately broken fixture.
These contracts and hosted builds do not establish physical Android inference,
performance, SAF behavior or signed Release installation readiness.
