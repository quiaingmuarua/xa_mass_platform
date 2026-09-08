# Worker Call Performance

Primary claim: offered online call load, observed completion, latency distributions
and saturation behavior of the existing Task Call path, including coexistence
with one finite PRECOMPUTED Task. The 100-Worker world fixes measurement conditions;
it is not another correctness, recovery or scale tier.

The [2026-09-08 reference baseline](baselines/2026-09-08-baseline.md) records the
first three-pair comparison. Its same-key append candidate was withdrawn after
targeted-call regression; the lane and Owner proofs remain. The baseline retains
the rejected patch for isolated replay and does not describe active production behavior.

## Owners And World

The Python runner owns fresh Docker Redis containers, Runtime/Host/Harness
processes, safe resource sampling and comparison. Java owns public HTTP actions,
offered load, correlation, observation and acceptance. It depends only on the
Delivery Contract; it neither calls implementations nor reads Redis domain data.
Inventory generation reuses `worker_proof_support`, without transferring any
existing lane's primary claim.

Reference runs use Ubuntu 24.04, Java 21, Redis 7.4.10 and the explicitly selected
DEFAULT Pacer preset. One Group contains 100 Java Workers behind one WebSocket
Adapter. Host assembly uses existing MD5 and 100ms Lab delay capabilities. Every
case has a fresh Redis container, scope, Server and Host. Bootstrap waits for all
100 known Lab identities, connected routes, HOT scheduling observations and
persistent Properties. Bootstrap is a prerequisite, not a second identity proof.

All Java processes use `-Xms256m -Xmx1g -XX:+ExitOnOutOfMemoryError`.
The evidence records JVM options, server overrides, complete configuration sources and hashes,
Git versions, OS/kernel, CPU count and the Redis image identity. DEFAULT
production queue, retry and Task Call settings remain represented by the checked
profile and explicit runner configuration. Private inventory, process logs and
capability files are excluded from CI artifacts.

## Fixed Cases

| Case | Online offered load | Background |
| --- | --- | --- |
| `any-100` | 100 calls/s, ANY | None |
| `any-500` | 500 calls/s, ANY | None |
| `any-1000` | 1,000 calls/s, ANY | None |
| `any-2000` | 2,000 calls/s, ANY | None |
| `targeted-500` | 500 calls/s, explicit Worker IDs in round-robin order | None |
| `mixed-500` | 500 calls/s, ANY | One PRECOMPUTED Task, 50,000 Items, 100ms delay, at most 50 candidates |

Each case warms up at 100 calls/s for 20 seconds, observes successful warmup
closure, then measures for 30 seconds. A call submits one Item with a fresh
Message ID and a fixed 64-byte MD5 input. HTTP wait is 1 second, client timeout
5 seconds and Item TTL 120 seconds. These are fixture values, not a production
SLA. Calls use the registered Group's managed ON_DEMAND Task.

The background Task is fully seeded and approved once after warmup, and must
produce a successful Result within 60 seconds. A bounded observer checks its
presence, nonterminal preview and unobserved Items in a fixed 1,000-ID sample
spanning all 50,000 Items during measurement. It does not assume insertion order.
The same conditions must still hold at the end. Failure stops the case; it does
not create additional work or retry mutation. Background Items use a 600-second
TTL. This case does not impose fairness or an online-call priority guarantee.

## Measurement And Acceptance

The finite open-loop scheduler retains each planned arrival time. Sending never
waits for HTTP capacity: at most 4,096 tasks may be in flight, and a refused
admission is recorded as not sent. The explicit virtual-thread executor and
HTTP client belong to the Harness and end with that process. Each application
submission is issued once, including ambiguous HTTP/transport failures.

Safe per-call rows retain Message ID, planned/sent/ended offsets, HTTP status,
response outcome and separately observed Result status. They contain no payload.
Counters distinguish planned, sent, HTTP 200 accepted, response success, response
failure, not_observed, unknown submission, not sent and protocol error. Their
denominator remains visible. HTTP 200 is not counted as successful execution.

Response p50/p95/p99 use nearest rank from actual send to response. The separate
scheduled-response and successful-call distributions start at the planned
arrival, including generator delay. Their sample counts are explicit;
an empty distribution has zero samples and is not a latency observation.
Actual send rate, successful response rate inside the measurement window and
successful cohort count divided by offered-window duration are distinct fields.
Any not-sent request or schedule-lag p99 above 100ms marks the case generator
limited. Such evidence cannot establish server capacity or compare candidates.

After the measurement, public `results:load` pages contain at most 1,000 known
IDs, with a fixed 180-second observation budget. Followup observation never
rewrites the original call outcome or latency. Its elapsed time is a sampled
observation bound recorded separately. An observed failed Result is not success;
not_observed does not infer TaskItem finality. Unknown submissions may remain
unknown, and their IDs remain in evidence. Every HTTP-accepted Item must have
an observed Result by the end of the budget. This is a finite fault-free
scenario oracle, not an unconditional delivery/replay guarantee.

Protocol/correlation errors, missing accepted Results, failed preconditions,
unexpected process exits and incomplete evidence fail the case. Linux `/proc`
samples every second must not exceed 512 native threads or 8,192 FDs per Java
process. CPU/RSS are recorded without absolute SLA thresholds. Redis INFO
stats/commandstats/memory/cpu are aggregate cost diagnostics only; they include
internal script commands and are not a client round-trip count. Real Redis Owner
tests independently own the append operation's client-command budget.
Measurement summaries select resource samples inside the 30-second window,
record the covered duration, and report mean CPU cores and peak RSS/threads/FDs.
The complete resource stream also retains startup and drain samples for the caps.

The runner stops writers and sampling, bounds process shutdown, and removes only
its exact Docker container. Containers are disposable; no shared Redis cleanup,
KEYS or database-wide flush is used. Incomplete cleanup cannot become a passed
run. Startup/build time is outside measurements. Each case's safe summary is
preserved even if its Harness fails.

## Execution And Comparison

```bash
python -m pip install -r .github/scripts/requirements.txt
python integrations/worker-call-performance/run_worker_call_performance.py
python integrations/worker-call-performance/run_worker_call_performance.py \
  --baseline-ref <commit> --output-root build/call-performance-comparison
```

Outputs must be fresh directories below repository `build`. `--case` selects one
diagnostic case and marks the suite incomplete. `--allow-nonreference-host` is
for Linux local diagnostics and records that the host is not the reference
environment. `--skip-build` reuses existing artifacts for local iteration.
Reference acceptance uses all cases, a clean checkout and no diagnostic flags.

Comparison builds immutable baseline A and current B once, then runs A/B, B/A,
A/B with the exact same current Harness and fixed configuration. All cases use
fresh processes and Redis. Three comparable pairs establish a comparison;
missing, failed or generator-limited pairs make it inconclusive. Two pairs with
success-rate loss greater than five percentage points, or p99 growth greater
than 20% at success rates within five points, mark the candidate regressed.
Large success-rate improvements are not penalized merely for including slower
successful samples. Missing success latency is not converted to a zero ratio.
No detected regression is not a claim of speedup. Regression exits nonzero;
the engineer withdraws the candidate rather than adding compensating tuning.

The dedicated workflow owns full performance execution, independently of the
ordinary Proof Gate. JVM Contracts compiles this module and runs deterministic
Harness/runner tests. Full performance stays nightly/manual; other lanes retain
their existing claims. Single-version and comparison workflows have 45-minute
and 120-minute budgets, with safe evidence retained for seven days.
The nightly schedule is 03:00 Asia/Shanghai (19:00 UTC) and uses the current
default-branch version; manual runs can select an immutable comparison baseline.

The lane does not claim cross-machine network latency, production SLA, Handler
concurrency, exact executor identity, Task fairness, process-fault recovery,
long-running retention/soak, or larger active Task/Group cardinality. Those
claims require their own named evidence rather than a larger Worker fixture.
