# Memory retrieval comparison

Run the actual production Kotlin `MemoryRanker` against synthetic Chinese retrieval
fixtures, using the compiler and jars already cached by the Mac verification runner.

```sh
python3 tools/persona-memory-benchmark/retrieval_compare.py
```

Optional arguments are `--samples`, `--output`, `--deps`, `--java-home`,
`--warmup` and `--repetitions`.
The output directory must be new. The default is a unique directory under
`build/verification/p3-retrieval-agent/`. No downloads or dependency changes occur.
Reports retain source snapshots, source/dependency SHA-256 hashes, Kotlin output,
ordered result IDs and exact ordered-match counts. Source changes during execution
cause a nonzero exit. A ranker mismatch also causes a nonzero exit.

Each case defaults to 200 warmup calls followed by 1000 measured calls to the actual
Kotlin `MemoryRanker.rank`. The report retains every `System.nanoTime` delta in
`elapsed_ns`, nearest-rank p50/p95/p99, min/max, candidate counts, UTF-16 corpus sizes,
JVM version and host environment. The timed region includes ranker tokenization and
sorting. Fixture filtering, budget application, Room IO and generation run outside
that region. Calls execute sequentially in one JVM, and clock overhead is included.
The supplied cases contain only 1–5 visible candidates and short synthetic content.
These host microbenchmarks establish measurements for this small fixture only.

## Compared strategies

- `recent` uses the legacy recency/ID ordering and four-item/1200 UTF-16-unit budget.
- `ranker` invokes the production Kotlin implementation, followed by that budget.
- `fts4_unicode61` runs the original query against host Python SQLite FTS4.
- `fts4_kotlin_terms` runs OR queries over tokens exported by the actual Kotlin
  ranker. Both FTS experiments use recency/ID ordering and the same budget.

All strategies retain the legacy selection for a blank query.

The fixtures cover two-character Chinese topics, an old relevant fact beyond the
four newest rows, aliases, mixed Chinese/English, original negative evidence,
no match, generic questions, cloud permissions, deterministic ties and blank query.
Repository tests separately cover an old fact behind 600 newer rows, revision and
permission changes after ranking, and content/count budgets.

## Scope

This is a synthetic host retrieval experiment. Candidate ownership, persona,
expiry, deletion and cloud permission are filtered in the Kotlin fixture adapter.
Room SQL, account changes, revision races, Android execution and model answer
quality require their separate repository/device checks. Host SQLite FTS4 does
not establish Android Room FTS availability, migration correctness or performance.
The small explicit alias vocabulary and overlapping Chinese bigrams provide
lexical matching. They do not provide general semantic or negation reasoning.
The returned memory content retains its original negation.

Embedding is deferred. Revisit this conditional experiment if a larger reviewed
Chinese corpus exposes lexical recall failures in fixed cases.

`unicode61` separates contiguous letters/numbers at punctuation and whitespace.
Chinese segmentation therefore needs its own treatment in this experiment.
[SQLite FTS4 tokenizer documentation](https://www.sqlite.org/fts3.html#tokenizer)

The extraction and storage tools in this directory have separate owners and
verification scopes. This command only reads retrieval fixtures and sources.

## Extraction and storage checks

The framework-free extraction and extractive-summary cores use fixed synthetic
samples. The model observation template remains `unrun` until actual device runs.

```sh
python3 tools/persona-memory-benchmark/extraction_check.py \
  --output /tmp/persona-p3-extraction-new-run \
  --deps build/verification/p2-mac/deps \
  --java-home /path/to/jdk17
python3 tools/persona-memory-benchmark/record_migration.py \
  --deps build/verification/p2-mac/deps \
  --java-home /path/to/jdk17
python3 -B -m unittest discover -s tools/persona-memory-benchmark -p test_storage.py -v
```

The dependency directory is an explicit local verification cache, containing the
Kotlin 2.0.21 compiler, Android API boundary jar, existing JUnit/Gson/coroutines
jars and supporting dependencies. Pass `--deps` for another prepared location.
The extraction output path must be fresh and outside the repository.

`record_migration.py` compiles the actual `MIGRATION_8_9` and records its SQL through
an SQLite API boundary. `test_storage.py` executes that SQL and actual DAO query
strings in host SQLite, verifies source hashes, and exercises invalidation,
owner-scoped updates, task recovery and rollback. Android Room-generated schema
validation and coroutine transaction behavior require the prepared instrumented
checks. The host report does not establish Android performance or model quality.
