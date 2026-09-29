# Worker Call Performance

Primary claim: with configurable resources set so they do not bind, the
performance of each call path at fixed offered rates, the capacity of the Task
paths, and the difference between paths on one host. The lane does not measure
turnover efficiency under scarce resources.

| Not claimed here | Owner |
| --- | --- |
| Worker reuse, candidate recycling or Pool exhaustion under scarcity | Dynamic Matching, Convergence Health |
| Group isolation or fairness under contention | Convergence Health or a dedicated scheduling proof |
| Disconnect, restart, failure retry and recovery | Loaded Recovery, Worker Correctness |
| Production SLA, device capacity, cross-machine latency, soak | Outside repository CI |
| Configuration trade-offs such as assignment ceiling 100 against 1000 | A separate configuration experiment |

Correctness floors can fail the lane. Performance values never fail it; they
enter the trend comparison.

## World And Configuration

Each job starts one world and reuses it for every case: Ubuntu 24.04 with four
logical CPUs, Java 21, Docker Redis 7.4.10, the DEFAULT Pacer preset, two Groups
(`perf-a`, `perf-b`) of 1,000 Java Workers each in one Host JVM, one WebSocket
Adapter and Project `perf-lane`. Bootstrap waits until every Worker is running,
connected, HOT and has published Properties.

Configurable resources are raised or audited so they do not bind. The runner's
`LANE_KNOB_AUDIT` is the authoritative table and every run writes it to
`evidence/knob-audit.json` with the reason and the evidence that shows the value
did not bind.

| Resource | Production default | Lane value |
| --- | --- | --- |
| Assignment ceiling `xa.mass.kernel-pacer.assignment-batch-limit` | 100 | 1000 (maximum) |
| Pool watermark `task-rpc.refill-by-worker-group[<group>]` | per profile | `any / {} / 1000` per Group |
| Adapter `report-queue-capacity` | 1000 | 10000 |
| `task-rpc.max-probe-items-per-round` | 256 | 1000 (maximum) |
| Workers / Handler | — | 2 x 1000 / MD5 |

Waiter and Direct Call bounds, Tomcat threads (calls complete through
DeferredResult) and the 4,096 in-flight Harness bound are audited and unchanged.

Mechanism constants are measured, never raised: the 50ms Dispatch and Refill
intervals, the 100ms Task Score slot, Refill round budgets, Result lane batch and
concurrency, the 5s Item claim lease, single-flight Producers and the Adapter
consume limit of 100. The Server delivery contract
(`DirectCallService.MAX_CONSUME_LIMIT`) rejects larger Adapter consumes with HTTP
400. When a mechanism constant binds, that is a finding.

## Execution

| Stage | Content |
| --- | --- |
| Calibration | Before any lane process starts: a fixed JVM MD5 workload on one and four threads (1s warmup, 2s measurement) and 5,000 sequential Redis PINGs. The values travel with the job's cases. |
| World | Redis, Server and Host once per job; Harness bootstrap. |
| Job warmup | One discarded case: `task-any` at the job's highest open rate, or `task-any-1000` for a saturation-only job. |
| Cases | Each case follows a quiesce gate: every Worker connected and idle due HOT on two observations one second apart, within 30s. A timeout makes this and every later case invalid. |

Open-loop cases offer one call per planned arrival and split the total rate
evenly across both Groups; targeted and Direct calls rotate through each Group's
Workers. Each case warms at 100/s for 30s, then measures 30s with a 1s HTTP wait
and a 5s client timeout.

| Path | Rates | Call |
| --- | --- | --- |
| `task-any` | 500, 1000, 2000 | `items:call` on each Group's managed Task, Any Pool |
| `task-targeted` | 500, 1000, 2000 | `items:call` with the `workerId` function |
| `direct` | 500, 1000, 2000 | Adapter-scoped `direct-calls`; the transport control |

Reference saturation cases (`sat-task-any`, `sat-task-targeted`) seed 150,000
Items per Group before approval. After approval they measure a 30s window without
HTTP submissions. Closing and draining happen after the fixed window. The Java
reader counts non-sampled, successful `RESULT_STORED` batch events in that
window and reconciles full-lifecycle claims, publications and stores against
unique, payload-validated public `results:export` rows. Missing coverage, event
loss, inconsistent counts or a drained backlog make the case invalid. This is
the successful Owner-call return point, not a Redis commit timestamp.

Path order rotates per repetition (ABC, BCA, CAB). The workflow builds once, runs
`rate-500`, `rate-1000`, `rate-2000` and `saturation` as parallel jobs, and merges
them. Path ratios compare cases inside one job, so on one host.

## Case Status And Validity

| Status | Meaning |
| --- | --- |
| `passed` | Offered load within path capacity and no resource bound. |
| `saturated` | Arrivals met the in-flight cap because responses outlasted their wait: the rate exceeds the path capacity. The report gives the admitted rate, not a completion ceiling. Exhausted idle Workers are then a consequence and recorded as `workersExhausted`. |
| `invalid` | The generator lagged (schedule-lag p99 above 100ms without in-flight refusal), a Group had no idle HOT Worker in a sample, a quiesce gate timed out, or a saturation backlog drained. Numbers are not comparable. |
| `failed` | A correctness floor or fast-fail rule was violated; the lane fails. |

Fast-fail rules stop early only on these signals: in-flight refusal or generator
lag at the 10-second checkpoint (stop offering; `saturated` or `invalid`), a
protocol or correlation error (immediate), 30 seconds without a new Result while
Items remain, an observed Result rate that cannot close within the budget, thread
or FD caps (512 / 8,192 per process) and unexpected process exits.

Every HTTP-accepted Task Item must have an observed Result within the drain
budget: 180 seconds within the single-Task check budget of `10 x B` Items/s,
otherwise `120 + accepted / (10 x B)` seconds. An observed failed Result is not
success; this finite fault-free oracle is not a replay guarantee.

## Metrics And Attribution

Open cases retain their response-success ratio, eventual completions per offered
second and successful-response latency distributions. These are not a strict
client deadline success rate or completions inside the arrival window.
Saturation reports window completion throughput. Resource costs use completions
inside the resource sampler's own interval; missing matching counts stay null.
Lease-held peaks are observations, not Handler utilization or measured turnaround.

Attribution enables the Server's existing Dispatch, Submission and Result Owner
events plus CPU coverage and GC (`lane-attribution.jfc`, no stack sampling).
`LaneAttribution` aggregates Dispatch events
per case window offline: Dispatch rounds and round time, checked Items/s, the
candidate shortfall (claimable Items without a candidate in their round), the
Worker acquisition rejection ratio and Refill admissions. Rejection includes
strict and identity acquisition; it is not a STALE-only counter.
`--lane-attribution off` disables optional attribution for open cases; saturation
always retains the completion evidence needed by its oracle. Shortfall is not used for
validity: targeted shortfall includes busy targets.

## Local 10k Capacity Experiment

The separate `capacity-10k` profile tests one Runtime and two Groups, with Any
as the primary path and targeted calls as controls. It changes resource
configuration only. DEFAULT Pacer, assignment ceiling 1000, Any watermark 1000
per Group, dispatch/refill intervals, Result lanes and Adapter consume limit
remain unchanged. It does not measure Item Call capacity.

Build once with `:integrations:worker-call-performance:experimentDist`. The
`xa-mass-call-experiment-*.zip` archive contains Server, Host, Harness, Python
process choreography, configurations, provenance and `SHA256SUMS`. Extract it
on a Linux filesystem (WSL ext4, not `/mnt/d`). Java 21, Python with
`redis>=5,<8`, Docker, `taskset`, `prlimit` and Redis 7.4.10 are prerequisites.
No checkout, Git or Gradle is used when running the archive:

```bash
bash run.sh --experiment-config integrations/worker-call-performance/configs/capacity-smoke.json --output-root build/smoke
bash run.sh --experiment-config integrations/worker-call-performance/configs/capacity-10k.json --output-root build/capacity
```

Java validates one experiment JSON and resolves the profile/window settings;
Python passes the same file to every phase and only manages processes, resource
samples and output files. CPU sets are nested subsets of the runner's allowed
CPUs and apply to Server, Host, Harness and Docker Redis. Process FD limits are
32768 and native-thread protection stays 512. Maximum heaps are Server 4GiB,
Host 3GiB and Harness 1GiB; Redis has an 8GiB container limit, 6GiB maxmemory
and no eviction. Each case has a fresh world and a 20-minute process deadline.
Only processes and disposable containers created by that case are cleaned.

Screen Any at (CPUs, Workers/Group): (4,1000), (8,1000), (16,1000),
(16,2000), (16,4000). Each screen pre-seeds 600,000 Items per Group, warms
15 seconds and measures 30 seconds. Select the highest valid throughput profile
and freeze it for five Any and three interleaved targeted runs. Each confirmation
pre-seeds 2,000,000 Items per Group, warms 30 seconds and measures 120 seconds.
Append batches stay 100 with concurrency 16; Item TTL is 30 minutes. After
measurement, close both Tasks, wait at most 60 seconds for quiet Workers, and
verify each export identity and MD5 payload. Unexecuted backlog is expected.

Known Worker IDs come from the Lab inventory. Runtime network/scheduling checks
use pages of 100 IDs. Properties Preview is limited to 1000 and is only a sample
above that fleet size; it does not enumerate the fleet or prove every Properties
publication.

The evidence oracle reconciles CLAIMED, COMMAND_PUBLISHED and RESULT_STORED with
the unique export count over the whole case, while counting only measurement
events for QPS. Sampled copies are excluded. JFR data loss, gaps over three
seconds in periodic coverage, missing lifecycle coverage, failure events,
inconsistent counts, drained backlog, swap activity, resource protection or
process exit prevent a valid result. Stage counts describe observations at Owner
returns; they are not additional production truth or replay guarantees.

Formal acceptance requires all five Any runs and all four 30-second subwindows
of each run to reach 10,000 completed Items/s. Valid runs below that threshold
produce `not-met`; missing or invalid evidence produces `inconclusive`.
The small smoke configuration produces `smoke-passed`, never a 10k claim.
Targeted throughput is a control and does not change Any acceptance.

Outputs include `experiment-summary.json`, `summary.md`, `resource-scan.svg`,
the resolved configuration, artifact/environment fingerprints, ten-second
throughput, aligned resource costs, GC and per-case reconciliation. Raw JFR,
logs and inventories remain private. Local records are excluded from CI trends.
Statistics version 2 separates these completion semantics from earlier records.
An unmet target bounds the tested environment, not absolute platform capacity.

## JFR Diagnostics

`--diagnostics jfr` records bounded JFR for the Server and Host with
`diagnostics.jfc`, enables the Server's optional `xa.mass.diagnostics.enabled`
servlet/executor observations and exports each case window through
`JfrDiagnostics`. Owner events additionally require their explicit JFR settings;
normal operation has neither HTTP observation beans nor enabled Owner events.
Recordings use 240 MiB plus an 8 MiB chunk allowance and the reader rejects files
above 256 MiB. Raw recordings stay under `private`, outside the artifact
whitelist. The offline reader depends only on the JDK and the Delivery Contract
and exports fixed event fields and bounded stack aggregates; it never exports
environment, properties, URLs, identity, bodies, results, exception messages or
arbitrary event fields. Diagnostics mode is not an A/B input.

Default-off Owner JFR covers submission/activation, Item HASH and Score
initialization, Dispatch round/check/deferral, refill/candidateize and
confirmation-rejection batches, candidate/confirm/claim/publish, TASK Result
consume/process/store/release and Task RPC admission/probe/observation stages,
plus servlet execution, asynchronous completion, Server Binding/offer/consume,
Adapter remote calls, Report admission/drain/drop and Queue depth, and the HTTP
executor. Events use existing arguments and returned counts, never another Redis
read, queue, public endpoint, Worker label or Score interpretation, and preserve
callback, timeout, drop, retry and shutdown behavior. Probe lateness records each
activated batch's oldest actual DelayQueue due time, not per-Item latency.

Per-Item events use SHA-256 of UTF-8 `taskId + NUL + messageId`; the low six bits
of the first byte select 1/64. Only the offline reader joins these hashes, within
the Server JVM, retaining at most 10,000 keys and 64 events per key and exporting
aggregates, not per-Item traces. Duplicate, missing, retried, overlapping and
overflowed chains stay explicit. Each interval uses its own two unique,
nonoverlapping edges; timeout positions use only unambiguous same-JVM edge
ordering. Stage edges bracket Owner calls, not Redis commit instants, and no
cross-process clock or percentile subtraction is valid. Five-second buckets and
power-of-two histogram bounds are approximate and never replace the Harness
latency distributions. Missing coverage, gaps over three seconds, data loss,
overflow or reader failure make diagnosis incomplete without changing business
outcomes.

## Trend And A/B

`lane_trend.py` holds the pure comparison logic.

- Run record: the merge step writes `run-record.json` with run identity,
  `laneConfigVersion`, host calibration per job, per-case status counts and the
  median, minimum and maximum of each metric over passed repetitions. Nightly
  runs, and manual runs with `lane_record`, append it to the `perf-lane-data`
  branch; only the summary job has write permission.
- Nightly trend: compares with the last seven records of the same ref and lane
  config version, else reports `insufficient-history`. Values are normalized to
  the history's median host speed; a job whose calibration deviates more than 25%
  is not judged. A median that stays outside the history range in the worse
  direction after allowing this run's half spread, or a changed majority status,
  is reported as suspect. The trend never fails the lane.
- Manual A/B (`baseline_ref` and `lane_case`): one runner builds the baseline and
  the current version, starts a fresh warmed world per version and alternates
  ABBA pairs of one case. The decision metric is p50 for open-loop cases and
  completed Items/s for saturation; one 30s window's p99 varies about +-35% on
  the same version, so pooled p99 across pairs is reported but not decisive. The
  band is the median relative spread of that case on `main` records (floor 5%),
  or a provisional 10% before three records. Every comparable pair must agree
  (at least two): all within the band is `no-difference`, all beyond it in one
  direction is `worse` or `better`; otherwise another pair runs, up to five, then
  `inconclusive`. A decision is not a speedup claim.

```bash
python integrations/worker-call-performance/run_worker_call_performance.py --repetitions 3
python integrations/worker-call-performance/run_worker_call_performance.py \
  --lane-modes open --lane-rates 1000 --diagnostics jfr --output-root build/lane-jfr
python integrations/worker-call-performance/run_worker_call_performance.py \
  --baseline-ref <commit> --lane-case task-any-1000 --output-root build/lane-ab
```

Outputs must be fresh directories below repository `build`. `--allow-nonreference-host`
marks local diagnostics; `--skip-build` reuses existing artifacts. Each Java
process uses `-Xms256m -Xmx1g`; evidence records configuration sources, hashes,
Git versions, OS/kernel, CPU count and the Redis image identity.

## Open Questions

- The task paths at 2,000/s sit at the reference runner's capacity: three of four
  2026-09-28 runs saturated at 1,380-1,700 admitted/s, one passed.
- The two first identical-code A/B runs showed higher pooled p99 for B
  (for example 530ms against 415ms) with identical Server code. It does not affect
  the p50 decision but may indicate a systematic difference between the two
  builds' worlds.

## History

The `baselines/` reports record the retired task, direct, direct-diagnosis,
rpc-diagnosis and nightly suites (100 or 1,000 Workers, one Group, assignment
ceiling 100 unless stated). They are version-scoped evidence and are not
comparable with this lane's cases.
