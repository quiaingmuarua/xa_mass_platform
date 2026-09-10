# Worker Call Performance

Primary claim: offered online call load, observed completion, latency distributions
and saturation behavior of the existing Task Call and caller-targeted Direct
Call paths. Task Call also measures coexistence with one finite PRECOMPUTED Task.
Each suite's fixed Worker count is a measurement fixture, not another
correctness, recovery or scale tier.

The [2026-09-08 reference baseline](baselines/2026-09-08-baseline.md) records the
first three-pair comparison. Its same-key append candidate was withdrawn after
targeted-call regression; the lane and Owner proofs remain. The baseline retains
the rejected patch for isolated replay and does not describe active production behavior.

The [Direct load-step attribution report](baselines/2026-09-08-direct-step-attribution.md)
separates the fixed surge and sustained windows, records HTTP configuration
candidates and preserves the scope of each causal conclusion.

The [RPC mainline attribution report](baselines/2026-09-09-rpc-mainline-attribution.md)
records three same-version Task/Direct repetitions, independent JFR diagnosis
and the eight-case acceptance run under unchanged production configuration.
It separates API response rate from successful calls and retains the 2k Task
Result-closure failures that prevent enabling the new scheduled composition.

## RPC Mainline Diagnosis

`--suite rpc-diagnosis` measures the primary `items:call` ON_DEMAND path and
uses Direct Call as a control for the shared HTTP/Transport path. It freezes
production configuration, including the 100-Item per-Task check bound and the
DEFAULT 100ms completion-relative Dispatch interval. No tuning parameter is added.

| Case | API and selector | Offered calls/s |
| --- | --- | --- |
| `rpc-any-500` | Task ANY | 500 |
| `rpc-any-1000` | Task ANY | 1,000 |
| `rpc-any-2000` | Task ANY | 2,000 |
| `rpc-targeted-1000` | Task explicit Worker, round-robin | 1,000 |
| `rpc-targeted-2000` | Task explicit Worker, round-robin | 2,000 |
| `direct-step-1000` | Direct explicit Worker, round-robin | 1,000 |
| `direct-step-2000` | Direct explicit Worker, round-robin | 2,000 |

Every main case uses Ubuntu 24.04 with four logical CPUs, Java 21, Redis 7.4.10,
one Group, 1,000 real connections in one Scenario Host JVM and one WebSocket
Adapter. Task calls share the Group's managed ON_DEMAND Task. Sorted known IDs,
64-byte MD5 input, single-item requests, one-second HTTP wait, five-second client
timeout and 4,096 in-flight bound match across the paths. Each case starts fresh,
warms at 100/s for 20 seconds and closes warmup before continuous 120-second
measurement. It reports whole, first-30-second and last-90-second cohorts plus
five-second buckets, with separate actual-response flux and generator limits.
Task TTL remains 120 seconds and public Result follow-up remains bounded at 180
seconds. Direct has no follow-up. Missing accepted Task Results fail the case;
observed failed Results are counted separately from successful throughput.

`--repetitions 3` runs the same immutable version on one host, with JFR off.
Each repetition starts with ANY 500. At both 1k and 2k, the path orders are
Direct/targeted/ANY, targeted/ANY/Direct, then ANY/Direct/targeted. This is an
attribution experiment, not a production candidate comparison; baseline-ref
and diagnostic-pair are rejected. One repetition also supports separate JFR.

```bash
python integrations/worker-call-performance/run_worker_call_performance.py \
  --suite rpc-diagnosis --repetitions 3 --output-root build/rpc-repetitions
python integrations/worker-call-performance/run_worker_call_performance.py \
  --suite rpc-diagnosis --diagnostics jfr --output-root build/rpc-jfr
python integrations/worker-call-performance/run_worker_call_performance.py \
  --suite nightly --output-root build/rpc-nightly
```

The nightly manifest contains these seven main cases plus **only** the original
`mixed-500`: 100 Workers, 30 seconds, 50,000 PRECOMPUTED Items, 100ms Handler,
at most 50 candidates. This independent coexistence witness is not included in
aligned path-cost ratios. The runner checks exact case membership and continues
collecting remaining case evidence after a case failure. It never calls this
single mixed case a complete historical Task suite. Historical `task`, `direct`
and `direct-diagnosis` suites retain their original fixtures. The new eight-case
manifest remains manual until fixed Result-closure acceptance passes. The first
formal RPC run failed its six 2k Task cases; the existing scheduled composition
therefore stays **Task six cases followed by Direct diagnosis two cases**.
Case completion logs contain only status, generator limitation and the aggregate
accepted-Result remainder; unavailable counts remain null. A missing/duplicate
manifest is reported separately from a complete manifest containing failed cases.
`completeSuite` records full-suite selection, not successful execution; the final
status and each case's validation evidence determine acceptance.
Single/nightly execution has a 45-minute budget; three repetitions have 120
minutes, including setup and cleanup. Measurements are not shortened to fit.

Default-off Owner JFR adds submission/activation, Item HASH and Score
initialization, Dispatch round/check/deferral, candidate/confirm/claim/publish,
TASK Result consume/process/store/release and Task RPC admission/probe/observation
stages. It uses existing arguments and returned counts, never another Redis
read, queue, public endpoint, Worker label or Score interpretation. Probe
lateness records each activated batch's oldest actual DelayQueue due time (the
batch maximum, not per-Item latency); probe frequency is
not modeled as a fixed batch size divided by 100ms.

Per-Item events use SHA-256 of UTF-8 `taskId + NUL + messageId`; the low six bits
of the first byte select 1/64. Only the offline reader joins these hashes, within
the Server JVM. It retains at most 10,000 keys and 64 events per key and exports
aggregates, not per-Item traces. Duplicate, missing, retried, overlapping and
overflowed chains stay explicit. Each interval uses its own two unique,
nonoverlapping edges; a missing HTTP observation does not discard its earlier
dispatch or Result intervals. Timeout positions use only unambiguous same-JVM
edge ordering; missing evidence remains unclassified. Stage edges bracket Owner
calls, not Redis commit instants. No
cross-process monotonic-clock subtraction or percentile subtraction is valid.
Post-measurement Result evidence may close a sampled chain without changing the
original HTTP outcome. Recording coverage and sampled-chain completeness are
reported separately. Raw JFR remains private and bounded to 256 MiB per process.

The structural single-Task budget is at most `100 / (0.1 + round_seconds)` checked
Items per second for continuously full rounds. Checked Items, assignment attempts
and unique successful calls differ. The budget proof exercises real scheduling
decisions with a controlled clock/executor, including a slow single-flight round;
it does not assert a platform SLA or a Worker-count capacity tier.

## Owners And World

The Python runner owns fresh Docker Redis containers, Runtime/Host/Harness
processes, safe resource sampling and comparison. Java owns public HTTP actions,
offered load, correlation, observation and acceptance. It depends only on the
Delivery Contract; it neither calls implementations nor reads Redis domain data.
Inventory generation reuses `worker_proof_support`, without transferring any
existing lane's primary claim.

Reference runs use Ubuntu 24.04, Java 21, Redis 7.4.10 and the explicitly selected
DEFAULT Pacer preset. The historical Task suite has one Group with 100 Java Workers behind one WebSocket
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

The following Result-drain contract applies to the Task Call suite. The separate
Direct Call fixture below has its own outcome semantics inside this same
performance lane.

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
samples every second must remain below 512 native threads and 8,192 FDs per Java
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

The current Server JAR comes only from `distribution/server`. An explicitly
selected immutable comparison checkout is built from the Server entrypoint in
that checkout; older baselines retain their original build layout and sources.

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

## Direct Call With 1,000 Workers

The [2026-09-08 Direct Call report](baselines/2026-09-08-direct-baseline.md)
records the first reference run: all 30,000 calls at 1,000/s succeeded within
one second, with 130.85ms successful p99. Higher offered rates degraded and
were generator limited; they do not establish a server capacity limit.

`--suite direct` measures the existing caller-targeted DIRECT_CALL path with one
Group, exactly 1,000 Java Workers and one WebSocket Adapter. The Lab Host creates
1,000 real Worker connections in one process; this is not 1,000 physical devices.
The caller rotates through the known sorted Worker IDs, sending one HTTP request
per Worker invocation to the public Adapter-scoped `direct-calls` API. Server
does not select Workers. No Task Items or background work are submitted.

The five fixed offered rates are 100, 500, 1,000, 2,000 and 5,000 calls/s. Each
case uses fresh processes and Redis, DEFAULT Pacer, the same fixed 64-byte MD5
input, 20 seconds of warmup at 100/s, and 30 seconds of measurement. Direct wait
is 1 second and client timeout is 5 seconds; maximum in-flight remains 4,096.
The 5,000/s case plans 150,000 calls, within the common scheduler's 300,000-call
bound. The original six Task fixtures remain available manually.
Preparation checks the exact 1,000 Lab identities and bounded public
network pages of 100; all warmup calls must succeed, covering every Worker twice.
Connected routes are checked again before and after measurement. Scheduling and
Properties readiness are not Direct Call admission prerequisites.

Direct evidence distinguishes successful observed replies (`platform.worker.command.succeeded`),
observed non-success replies, unobserved timeouts, occupied-slot/HTTP-429
rejections, uncertain submission/HTTP effects, not sent and protocol errors.
Codes and reasons are counted separately. HTTP 200 does not imply admission or
successful execution. Each response must name exactly the requested Worker;
its aggregate status, fields, MD5 result and unique server Direct Call ID are
checked. Missing/bad Binding, shutdown, wrong results and protocol errors fail
this fixed-world fixture. Resource bounds and generator-limitation rules are
the same as the Task suite. Direct timeouts and rejections are measured outcomes,
not hard failures disguised as successful execution.

DIRECT_CALL has no persistent Result lookup: there is no `results:load`, drain,
replay or automatic retry. Timeout does not cancel an offered Command and does
not establish execution failure. Unknown and timeout samples stay unclosed in
safe evidence. Result/finality and Task recovery claims do not apply. In addition
to original-response latency, successful cohort throughput and actual successes
within the measurement window, the summary reports successes returned within
one second of their planned arrival divided by both sent and planned requests.
Successful p99 excludes unsuccessful/unknown requests, so read it alongside
these fractions. A passed run means valid measurement, not an RPC SLA.

```bash
python integrations/worker-call-performance/run_worker_call_performance.py \
  --suite direct --output-root build/direct-call-performance-proof
```

The manual workflow accepts `suite=direct`. Scheduled runs retain the accepted
Task six cases followed by Direct diagnosis two cases while the new RPC manifest
awaits acceptance.
Worker count fixes this requested Direct Call scenario rather than introducing
another correctness/recovery scale tier. Different Worker fixtures and separate
hosts prevent inferring a Direct-versus-Task speedup ratio from their raw QPS.

## Direct Load-Step Diagnosis

`--suite direct-diagnosis` keeps the same 1,000-Worker Direct world and adds two
fixed cases: `direct-step-1000` and `direct-step-2000`. Each warms at 100/s for
20 seconds, then offers its target rate continuously for 120 seconds with the
same client. The first 30 seconds are the surge window; the next 90 seconds are
the sustained observation window. These boundaries never wait for convergence.
Every five-second bucket and every original sample remains in the evidence.
The finite scheduler permits at most 300,000 planned calls; existing Task and
Direct fixtures retain their previous offered rates and durations. The new
reference suite additionally requires exactly four logical CPUs. Local runs
with another CPU count require the existing nonreference diagnostic flag.

Window outcome counts and exact latency percentiles use the cohort whose
planned arrival falls in the half-open window. Actual sends and HTTP/success
responses inside the window also include requests from earlier cohorts, and
exclude responses after its end. Thus HTTP response QPS, successful-cohort QPS
and actual successful-response QPS remain distinct. HTTP 200 still does not
mean successful admission or execution. Rejection reasons, 429, timeout,
uncertain effects, not sent, and in-flight counts at both boundaries are explicit.
Each window independently applies the fixed not-sent / 100ms schedule-lag p99
generator limit. A limited surge does not rewrite the sustained window, and
neither window erases the whole-run limited flag. Samples reaching 512 native
threads or 8,192 FDs fail the proof; CPU/RSS remain cost observations.

`--diagnostics jfr` records bounded JFR for Server, Host and Harness. The runner
enables the Server's optional `xa.mass.diagnostics.enabled` servlet/executor
observations. Owner events additionally require their explicit JFR settings;
normal operation has neither HTTP observation beans nor enabled Owner events.
Recordings use 240 MiB plus an 8 MiB chunk allowance, and the reader separately
rejects files larger than 256 MiB. Raw recordings remain under `private`, outside
the artifact whitelist. An offline Java reader, depending only on the JDK and
Delivery Contract, exports fixed event fields and bounded stack aggregates.
It never exports environment, properties, URLs, identity, bodies, results,
exception messages or arbitrary JFR event fields. CPU/GC/JIT/lock/socket/allocation
and pinning samples are diagnostic observations, not new mechanical truth.

Custom observations cover initial servlet execution, asynchronous completion,
Server Binding/offer/consume duration and counts, Adapter remote-call duration,
Report admission/drain/drop counts and Queue depth, and the HTTP executor.
Servlet completion is not the client's receive timestamp. Platform executor
metrics are null when inapplicable; virtual execution is separately observed on
the real initial request thread. Queue snapshots are diagnostic and do not
reconstruct a Command's execution history. Default-off observations add no
business queue, Registry, public endpoint or Redis read. They preserve callback,
timeout, best-effort drop, retry and bounded shutdown behavior.

The reader exports five-second activity buckets and power-of-two duration
histogram upper bounds; these approximate diagnostic percentiles do not replace
the exact Harness latency distributions. Missing CPU or Server Owner/executor
coverage, gaps over three seconds, JFR data loss, bounded stack or aggregation
overflow, or reader failure make diagnosis
incomplete without changing the recorded business outcomes. Stack sampling and
any bounded stack overflow stay explicit. Formal comparisons reject JFR mode.
For a mechanism comparison on the same host, explicitly combine
`--diagnostic-pair --baseline-ref <D> --diagnostics jfr`. This runs one fresh A/B
pair and records `purpose=diagnostic_pair`; it never runs or emits the formal
benefit classifier. In the manual workflow, selecting JFR with a baseline ref
selects this separate diagnostic mode. Its evidence is never pooled with the
three formal pairs.

```bash
python integrations/worker-call-performance/run_worker_call_performance.py \
  --suite direct-diagnosis --output-root build/direct-diagnosis
python integrations/worker-call-performance/run_worker_call_performance.py \
  --suite direct-diagnosis --diagnostics jfr --output-root build/direct-jfr
```

### Configuration Candidate Acceptance

The diagnosis-only baseline D explicitly represents the existing HTTP defaults:
virtual threads disabled, Tomcat maximum 200, minimum resident 10. Candidate B1
changes only the minimum to 200. If B1 is not retained, B2 starts from D and
changes only `spring.threads.virtual.enabled` to true. They are not combined;
queue bounds, batch sizes, waiting windows and Pacer settings are unchanged.
The first candidate meeting the acceptance contract ends this bounded round.

Use the existing immutable `--baseline-ref` comparison for D/B, B/D, D/B on one
host, with identical current Harness and JFR off. The new comparison considers
the surge and sustained windows of both fixed cases. A benefit needs three
valid pairs and an improvement in at least two: success fraction increases by
at least five percentage points, or at success fractions within one point,
successful p99 or Server CPU seconds per HTTP response decreases at least 15%.
CPU per response uses sampled mean CPU divided by the actual window response
rate; the sampled coverage remains visible. The established two-pair regression
rules remain: success loses over five points, or p99 grows over 20% while success
fractions are within five points. A benefit cannot hide another guard window's
regression. Incomplete or generator-limited guard windows keep overall acceptance
inconclusive. No clear benefit and no detected regression do not establish gain.

Eligibility also requires mechanism evidence, all path-selected existing proofs,
the original six Task cases in three pairs, and full Loaded Recovery for the
final candidate. Limited Task cases retain their own inconclusive result.
Without an eligible candidate, retain D and record confirmed causes, correlated
clues and unresolved questions. These thresholds select a finite experiment;
they do not establish production SLA, physical-device capacity or long soak.

The 03:00 performance schedule retains Task six cases and Direct diagnosis two
cases with JFR off. The new eight-case RPC mainline `--suite nightly` is a manual
acceptance target; enable it only after its fixed acceptance conditions pass.
Historical Direct replay, JFR, candidate comparisons and same-version repetitions remain manual.
No extra proof lane or PR QPS gate is introduced. Safe artifacts remain seven days.
