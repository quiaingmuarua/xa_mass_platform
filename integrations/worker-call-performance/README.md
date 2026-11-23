# Worker Call Performance

RPC Tasks are explicitly prepared with stable fixture IDs via
`POST /api/v1/tasks/{taskId}/rpc:prepare` during setup, before readiness/load
measurement. Project directory reads only expose bound Groups. Preparation does
not alter the lane's supply configuration, workload, deadlines or assertions.

RPC call responses are validated and retained when received. Follow-up requests
use `results:take` only for unresolved IDs; an empty take cannot erase a received
Result or trigger execution resubmission. Finite saturation witnesses retain
their non-consuming export path.

All protected Runtime requests, including streamed finite Result exports, carry
the fixture's public development access key through the same request
builder in the source lane. A deployed consumer uses the access-key file from its
deployment configuration. Streaming preserves the export timeout and
identity/payload validation.

Source-lane primary claim: with configurable resources set so they do not bind, the
performance of each call path at fixed offered rates, the capacity of the Task
paths, and the difference between paths on one host. The lane does not measure
turnover efficiency under scarce resources.

| Not claimed here | Owner |
| --- | --- |
| Worker reuse, candidate recycling or Pool exhaustion under scarcity | Dynamic Matching, Convergence Health |
| Group isolation or fairness under contention | Convergence Health or a dedicated scheduling proof |
| Disconnect, restart, failure retry and recovery | Loaded Recovery, Worker Correctness |
| Production SLA, device capacity or soak | Outside repository CI |
| Configuration trade-offs such as assignment ceiling 100 against 1000 | A separate configuration experiment |

Correctness floors can fail a run. Source-lane performance values enter the trend
comparison; they are not pass/fail thresholds.

## Deployed Runtime Consumer

The same runner and Java Harness can measure an already deployed Runtime with
`--deployment-config`. This is an execution mode of this Owner, not a separate
proof lane or a replacement for the source lane below. The published schema-3
[Worker Simulator kit](../../distribution/worker-simulator/README.md) ships the
runner at `acceptance/performance/run_worker_call_performance.py` and its compiled
Harness at `acceptance/performance/harness/`; consumption needs no checkout or
build. Use Linux, Java 21 and Python 3.11 or newer.

Copy the kit's `examples/performance-deployment.json` outside the immutable kit,
replace its template values with the operator-provided deployment coordinates,
and place both credential files beside that private configuration. Relative file
paths resolve against the configuration file's directory. The configuration holds
file references, never access keys or Worker secrets:

| Field | Meaning |
| --- | --- |
| `runtimeUrl` | HTTPS administrator/API origin; requests carry the supplied user's Bearer access key |
| `workerRuntimeUrl` | Approved HTTPS Worker origin for Prepare and WSS gateway admission |
| `project`, `endpointManager` | Existing Project and Adapter identities |
| `groups` | Exactly two distinct, already configured Group IDs, in stable order |
| `workersPerGroup` | Host inventory per Group, `1..15000` |
| `consumerId` | Stable identity used to name this consumer's RPC Tasks; reuse it for the same declared bindings |
| `accessKeyFile`, `gatewayCredentialFile` | Private operator access-key and Worker gateway files |
| `hostMaxHeap`, `hostFdLimit` | Explicit owned Host budgets; the template uses `3g` and `32768` |
| `assignmentBatchLimit` | Recorded Server assignment ceiling, `1..1000`; the consumer does not change the Server |
| `serverDeployment` | Operator-recorded `version`, full `gitCommit` and `configurationId` for the target Server |

The operator must supply Group registrations, `any` Pool / `worker.any`, the
MD5 event, Project grants and gateway admission before the run. Declared deployment
metadata is attribution supplied by the operator, not a live measurement of Server
configuration or resources. A separate persistent Host must use different Group
and inventory coordinates; another local directory alone does not create distinct
Worker identities.

Run from the extracted kit, using a new private output directory outside it:

```bash
python3 acceptance/performance/run_worker_call_performance.py \
  --deployment-config /etc/xa-mass-worker/performance-deployment.json \
  --lane-modes open --lane-attribution off --diagnostics off \
  --output-root /var/lib/xa-mass-worker/runs/open-001
```

This mode retains the existing open cases, rates `500,1000,2000`, path order,
warmup and measurement windows; `--lane-rates` may select only those existing
rates. It introduces no low-rate profile. Setup explicitly prepares one stable RPC
Task per Group and reuses those Tasks on later invocations with the same consumer
identity and bindings. A conflicting saved descriptor fails setup; the runner does
not invent a replacement identity. Received RPC Results remain retained locally
when later consuming reads are empty, as in the source lane.

Deployment Worker observations read at most eight pages concurrently within one
complete scan. Every page still contains at most 100 known identities and must
return exactly that identity set; results retain request order. The next scan
starts only after the current one finishes. Source reference observations remain
serial, and the existing quiesce deadline and sampling intervals are unchanged.

The runner owns only its local Host, Harness and output. It does not start,
restart, configure or sample the remote Server, Redis or gateway, and cleans up
only the local processes it created. RPC Tasks are retained for reuse. TLS checks
remain enabled; credentials and full Worker identities stay private. Keep raw logs,
inventory and private configuration outside shared evidence.

Deployed mode supports open-loop execution with attribution and diagnostics off.
It supplies no Server JFR, Redis calibration or saturation evidence, no local
Server resource costs, and no reference-host or CI-trend claim. Report observed
public-path latency and outcomes together with the exact kit, Server declaration,
configuration and client resources. These measurements do not establish maximum
Server throughput, every Worker's execution, recovery or soak. The existing
source, saturation, diagnostics and A/B contracts below remain separate.

### External Actions Execution

The existing [Worker Call Performance workflow](../../.github/workflows/worker-call-performance.yml)
can run this deployed mode from a GitHub-hosted runner through SSH to a dedicated
consumer user on the Ali Worker host. Select `execution_target=external` manually
on `main`. That branch and the bounded scheduled observation below call the same
[reusable external consumer](../../.github/workflows/external-performance-consumer.yml);
they share kit consumption, orchestration and report collection. The default
target and source Nightly at 03:00 Asia/Shanghai remain `source`, with their
existing four jobs, three repetitions, build, saturation and trend behavior.
The manual quantity is normalized with `fromJSON` at the reusable-workflow
boundary: dispatch/API strings become the required numeric input; the schedule
already supplies a numeric literal. Both enter the same consumer validation.
The external job consumes the pinned published Simulator kit in its own run directory. It does
not checkout or build source on the consumer, and needs neither Git nor Gradle
there. Its kit consumption is independent of the persistent Host's current link;
selecting a workflow revision does not upgrade the GCP Server.
External selection rejects JFR, A/B and source trend-recording options before
starting the campaign.

```bash
gh workflow run worker-call-performance.yml --ref main \
  -f execution_target=external \
  -f external_workers_per_group=5000 \
  -f external_repetitions=1
```

| External input | Contract |
| --- | --- |
| `external_workers_per_group` | `1..15000`, default `5000` in each of the two declared Groups: 10,000 Workers total by default |
| `external_repetitions` | `1` or `3`, default `1`; the source `repetitions` input remains separate |

The protected `release-preview-load` Environment supplies the following
deployment and consumer coordinates. Keep credential values out of JSON, command
arguments, logs and published artifacts.

| Environment Vars | Meaning |
| --- | --- |
| `CONSUMER_SSH_HOST`, `CONSUMER_SSH_USER`, `CONSUMER_SSH_KNOWN_HOSTS` | Dedicated `xa-mass-performance` consumer account and pinned SSH host keys |
| `PERFORMANCE_DEPLOYMENT_JSON` | Deployment configuration described above, with `consumerId=public43-performance` and private credential-file references |
| `PERFORMANCE_CONNECTION_BUDGET_JSON` | Exactly three integer gateway-budget fields: `perCredential`, `total`, `reserved`; requested Workers must fit both `perCredential` and `total - reserved` |
| `PERFORMANCE_KIT_RELEASE_TAG`, `PERFORMANCE_KIT_RELEASE_COMMIT` | Exact immutable published Consumer kit Release and full source commit; mapped to the controller's `RELEASE_TAG` / `RELEASE_COMMIT` |
| `INSTALLED_RELEASE_TAG`, `INSTALLED_RELEASE_COMMIT` | Release tag and full source commit deployed to the target GCP Runtime; mapped to `SERVER_RELEASE_TAG` / `SERVER_RELEASE_COMMIT` and matched against `serverDeployment` |

| Environment Secrets | Use |
| --- | --- |
| `CONSUMER_SSH_PRIVATE_KEY` | SSH admission to the dedicated consumer account |
| `PERFORMANCE_RUNTIME_ACCESS_KEY` | Project-scoped Performance operator access key |
| `PERFORMANCE_WORKER_GATEWAY_KEY_ID`, `PERFORMANCE_WORKER_GATEWAY_SECRET` | Dedicated Performance Worker gateway credential |

The dedicated account must already have its user systemd manager and linger
enabled, alongside Java 21, Python 3.11+ and SSH admission. Its campaign unit is
`xa-mass-performance-<RunID>.service`; retained files are under `~/runs/<RunID>`
and temporary credential inputs under `/run/user/<uid>/xa-mass-performance/<RunID>`.
For example, a declared gateway budget of
`{"perCredential":20000,"total":30000,"reserved":20}` leaves 20 shared gateway
connections outside the campaign; replace these values with the actual declared
deployment limits. Host FD, local port, memory and disk checks run separately.

No root key or GCP administrator credential belongs in CI. Before load, verify the
consumed kit manifest and configured pins, the two declared Groups and Project,
and the explicit host/gateway budgets for the requested inventory. This records
the declared limits; it does not tune the Server or infer a higher capacity from
an FD limit. Group inventory coordinates must remain exclusive to this campaign.
The ordinary persistent 20-Worker Host has its own Group, inventory, credentials
and system service; Actions never starts or stops it. Both roles still share the
physical consumer host's CPU, memory and network failure domain.

Consumer kit and target Server pins are independent. The kit must pass the strict
immutable Release, asset digest and schema-3 manifest checks for its own commit;
`PERFORMANCE_DEPLOYMENT_JSON.serverDeployment` must match the declared Server
pins. Their versions and commits need not match when the existing API contract is unchanged.
Neither identity falls back to the other or to `latest`. `consumption.json`
traces the kit, while the summary's `serverDeployment` traces the target Server;
using a patched Consumer kit does not upgrade GCP or the persistent Host.

The campaign is one owned systemd unit, identified by the GitHub Run ID. A rerun
of that same Run ID only attaches to its existing unit or collects its receipt;
it must not execute the workload again. A new independent run may follow a
completed run whose evidence contains unknown responses or HTTP 429. Preserve
those outcomes without resubmitting their request identities. Before starting,
reconcile any still-active old campaign process or unknown cleanup result;
unknown workload outcomes and unknown process cleanup are different evidence.
Manual External Performance runs serialize in the `release-preview-load`
Actions concurrency group with cancellation disabled; local Loaded Recovery
runs independently. The fixed-date schedule below bypasses that waiting queue
and uses nonblocking admission on the same remote consumer instead.
The remote campaign budget
is 45 minutes for one repetition or 100 minutes for three; GitHub allows 120
minutes, and owned-unit shutdown has a 120-second budget. Cleanup and receipt
collection remain limited to this run's processes, credential copies and files.

Before the first temporary credential is written, preparation confirms a one-shot
user timer `xa-mass-performance-<RunID>-prepare-cleanup.timer`, with a five-minute
deadline and one-second timer accuracy. This finite cleanup timer never starts a
campaign or renews its deadline. Its handler shares the run's control lock: an
existing final receipt is immutable, and an actually running/stopping campaign
unit retains cleanup ownership. Otherwise the handler writes a stop fence,
removes the same private input copies and records `prepare-expired`; a late start
cannot execute. Execute's fence check and runner spawn share that same short
control-lock section, so a queued unit cannot slip between expiry's status
snapshot and stop fence. Execute releases the control lock before waiting for the
runner; its global Performance lease remains nonblocking. An unconfirmed timer
registration writes no credentials. Campaign
handoff, explicit stop and finalization stop the timer outside the control lock.
The transient cleanup attempt has a 120-second bound; lock or removal failure
remains unconfirmed rather than claiming that private input was removed. The
lease is tied to the current boot: a reboot without a final receipt remains
unknown and cannot renew or replay the same Run. This timer does not change the
45/100-minute campaign budgets or manage the separate persistent Host.

After stopping its owned processes, the shared deployed runner observes exactly
its inventory IDs in pages of 100, with at most eight concurrent page reads and
one complete scan at a time. All scans share the original 30-second observation
deadline. A page must return its exact requested identity set; only a complete
scan received before the deadline, with every identity `unknown` or
`disconnected`, confirms cleanup. A complete scan with connected Workers can be
observed again after the existing one-second pause, without renewing the budget.
Missing, malformed, failed or late pages remain unconfirmed. Safe nested
`cleanup.workerConnections.observation` diagnostics report page counts, observed
identity count, elapsed time, deadline expiry and error-type counts, without IDs,
error bodies or authentication text. Active HTTP reads retain their original
remaining-budget timeout; executor shutdown does not wait to revise the
observation decision, and late completions cannot change its status.

### Reporting And Run Attribution

The standard-library-only [reporting module](reporting.py) owns measurement
projection and Markdown for both the runner and external collection. Its
`lane_case_record` and `lane_markdown` preserve the existing case metrics;
`collect_measurement(result_path)` reads the known summary/case/sample format,
including raw `case.json` `originalMetrics` and formal, discarded-warmup and
inner-warmup sample counts. Missing or malformed evidence remains explicitly
incomplete or unavailable; available counts are retained without inventing zeros.
The schema-3 kit ships this same-directory runner dependency without changing its
manifest schema. Collection can read the existing pinned kit's output without
upgrading that kit or selecting a version-specific reporting implementation.

Before preparing a new Run, the controller captures reporter and orchestration
helper bytes from its own commit, then copies `reporting.py` before
`orchestrator.py` into the Run directory. State and public summary/orchestration
`helpers` attribution retain `controllerCommit` and both SHA-256 fingerprints.
An existing helper fixes the Run's implementation: later invocations only attach,
stop or collect, without replacing either file, preparing again or starting new
work. The orchestration helper owns lifecycle, preparation, observation and receipt
fields plus final scrubbing; it does not redefine measurement statistics.

Only collection loads the Run's reporting module, after verifying its fixed
fingerprint. A missing, invalid or failed reporter is an explicit
`reportCollection` unavailable outcome. Stop and finalization do not import it,
so reporting failure cannot prevent owned-process cleanup or rewrite the
lifecycle receipt.

Public evidence contains only the allowlisted `summary.json`, `summary.md`,
`consumption.json` and `orchestration.json`; bootstrap Worker IDs, inventory, raw
logs and private configuration remain on the consumer. These receipts identify
the consumed Release and declared deployment without exposing access keys or
Worker identities. The public API, request/Result oracle and existing case
statuses are unchanged. Exit status `0` alone does not establish that every case
was valid: inspect each `passed`, `saturated`, `invalid` or `failed` outcome before
comparing measurements. Delivery checks of this mode do not establish a real
10,000-Worker public-path result; that needs its own actual run and evidence.
For a smaller delivery check, set `external_workers_per_group=1000` to request
2,000 Workers total. That leaves the default 10k command and measurement oracle
unchanged and provides evidence only for the selected size.

Worker scheduling observations retain the same five-second interval after both
successful and failed samples. Interrupting a read or its pause ends the sampler
immediately. Failures remain unavailable samples under the existing case oracle;
the sampler does not hot-retry an unavailable observation. The four public
receipt files additionally retain safe `preparation` lease/cleanup diagnostics;
private boot identity, credential paths and Worker/request identities stay local.

### Seven-Date External Observation

The [observation workflow](../../.github/workflows/external-performance-observation.yml)
is a schedule-only entrypoint, without `workflow_dispatch`. Its seven explicit
UTC cron entries correspond to 05:30 Asia/Shanghai on **2026-11-20 through
2026-11-26**, inclusive (21:30 UTC on November 19 through 25). The source
Performance Nightly remains independent. The existing workload is unchanged:
two Groups of 5,000 Workers, one repetition, `open` mode, the three Task Any,
Task Targeted and Direct paths, and rates `500,1000,2000`.

The reviewed [window configuration](../../.github/external-performance-observation.json)
has schema 1, `windowId=external-10k-20261120`, `startDate=2026-11-20`, `days=7`,
`localTime=05:30`, `utcOffset=+08:00`, `workersPerGroup=5000` and `repetitions=1`.
The cron identifies the original target date; the actual event or job arrival
date cannot replace it. A delayed event may start new work only while still on
that target Beijing calendar date. Admission also requires the remote consumer's
nonblocking load lock, confirmed prior cleanup and an exclusive daily claim for
this window/date. The schedule does not wait behind a manual campaign to run later.
An admitted campaign retains its existing 45-minute budget and 120-second stop
budget; this does not add a midnight restart or extend a deadline.

This is a roster of **seven calendar dates, not seven successful results**.
A cross-date arrival, rerun, missing event, missed date, lock collision or
unconfirmed previous cleanup admits no new workload. There is no automatic retry,
catch-up, replacement date or eighth target date. A missing trigger can have no GitHub Run
or artifact at all: compare actual evidence against all seven configured dates,
and retain the absence rather than assembling the next seven available reports.
Do not delete a daily claim or replace request identities to obtain another try.
Same-Run receipt collection does not grant another load execution.
Independent manual measurements remain manual evidence; they do not fill an
absent scheduled date in this window.

The reusable consumer retains the same five-minute preparation cleanup timer,
owned-process cleanup, immutable Release pins and four-file public evidence
allowlist. Scheduled artifacts are retained for **30 days**; manual artifacts keep
their seven-day default. Read case validity, HTTP 429, unknown responses, resource
evidence and cleanup alongside the GitHub job outcome. Public-path reports remain
outside source reference history and do not acquire a new performance threshold.

This change delivers configuration and orchestration; it is not evidence that
the seven-date window has executed or that another public 10k run passed. It
does not start an immediate campaign, upgrade GCP or a Release, change the ordinary
20-Worker service, or add a continuous health/testing loop. Existing run evidence
and the fixed-date roster determine the later observation record.

### Bootstrap Readiness

Source and deployed execution use the same Java Harness bootstrap. The current
Lab inventory supplies the known Worker IDs. Group Properties Preview remains a
bounded global sample of registered identities, including historical Workers;
it is not the current run's inventory. Per Group, bootstrap fixes a target of all
current IDs when there are at most 1,000, or the first 1,000 sorted current IDs
for a larger inventory. Across Preview responses it accumulates target IDs whose
Properties are nonempty. Historical IDs, empty Properties and repeats do not
contribute coverage. Each incomplete Group continues sampling; a changed Lab
identity set clears the accumulated coverage.

After Properties coverage, every current Worker must still be Lab `RUNNING`,
network `connected`, and scheduling `held-hot` or `hot-score-overdue`, within the
existing 240-second bootstrap deadline. Properties evidence is cumulative and
adds no freshness timestamp requirement. `complete-known-fixture` covers all
known identities at up to 1,000 per Group; larger inventories retain the
`bounded-sample` claim and do not prove every Worker's Properties publication.
Historical registrations are retained. Do not clear their data or replace a
Group to make readiness pass. Runtime APIs, Worker protocol and later quiesce,
case and Result checks are unchanged.

## World And Configuration

Source open-loop jobs start one world and reuse it for every case: Ubuntu 24.04 with four
logical CPUs, Java 21, Docker Redis 7.4.10, the DEFAULT Pacer preset, two Groups
(`perf-a`, `perf-b`) of 1,000 Java Workers each in one Host JVM, one WebSocket
Adapter and Project `perf-lane`. Bootstrap waits until every Worker is running,
connected, HOT and has published Properties.

The saturation job uses [lane-saturation.json](configs/lane-saturation.json):
four CPUs, two Groups of 4,000 Workers (8,000 total), and 1GiB maximum heaps
for Server, Host and Harness. Redis has a 2GiB container limit and 1.5GiB
`maxmemory`, with `noeviction`. Saturation FD/thread guards are 16,384/512;
open-loop guards remain 8,192/512. These are
explicit experiment settings, not minimum production resource requirements.
CPU affinity applies to every experiment JVM and the Redis container.

Configurable resources are raised or audited so they do not bind. Open-loop
settings come from the runner's `LANE_KNOB_AUDIT`; configured saturation uses
the resolved experiment file. Each job records its settings in
`evidence/knob-audit.json` and preserves resource samples for the audit.

| Resource | Production default | Lane value |
| --- | --- | --- |
| Assignment ceiling `xa.mass.kernel-pacer.assignment-batch-limit` | 100 | 1000 (maximum) |
| Pool watermark `task-rpc.refill-by-worker-group[<group>]` | per profile | `any / {} / 1000` per Group |
| Adapter `report-queue-capacity` | 1000 | 10000 |
| `task-rpc.max-probe-items-per-round` | 256 | 1000 (maximum) |
| Workers / Handler | — | open: 2 x 1000; saturation: 2 x 4000 / MD5 |

Waiter and Direct Call bounds, Tomcat threads (calls complete through
DeferredResult) and the 4,096 in-flight Harness bound are audited and unchanged.

Mechanism constants are measured, never raised: the 50ms Dispatch and Refill
intervals, the 100ms Task Score slot, Refill round budgets, Result lane batch and
concurrency, the 5s Item claim lease, single-flight Producers and the scenario
profile's Adapter consume limit of 500 (the Server delivery contract,
`DirectCallService.MAX_CONSUME_LIMIT`, admits up to 1000). When a mechanism
constant binds, that is a finding.

## Execution

| Stage | Content |
| --- | --- |
| Calibration | Before any lane process starts: a fixed JVM MD5 workload on one and four threads (1s warmup, 2s measurement) and 5,000 sequential Redis PINGs. The values travel with the job's cases. |
| World | Open jobs reuse Redis, Server and Host; configured saturation creates them per case. Harness bootstrap verifies the complete Worker inventory. |
| Job warmup | Open jobs discard `task-any` at the highest offered rate. Configured saturation warms each fresh world for 15s under its measured backlog. |
| Cases | Each case follows a quiesce gate: every Worker connected and idle due HOT on two observations one second apart, within 30s. A timeout makes this and every later case invalid. |

The shared process sampler keeps its one-second interval and resource ceilings.
When `/proc` disappears or FD access is revoked, the existing 50ms wait can confirm
process exit. If reaping is still unavailable, only Linux `Z`/`X` state with exactly
one remaining thread confirms a terminal process. A dead group leader with live
threads, a nonterminal state or unavailable/malformed lifecycle evidence still
invalidates resource sampling. No terminal sample is invented, and Harness exit
codes, case/Result checks and all existing deadlines remain required. Remaining
read failures include the safe role/state/thread-count context for diagnosis.

Open-loop cases offer one call per planned arrival and split the total rate
evenly across both Groups; targeted and Direct calls rotate through each Group's
Workers. Each case warms at 100/s for 30s, then measures 30s with a 1s HTTP wait
and a 5s client timeout.

| Path | Rates | Call |
| --- | --- | --- |
| `task-any` | 500, 1000, 2000 | `items:call` on each Group's managed Task, Any Pool |
| `task-targeted` | 500, 1000, 2000 | `items:call` with the `workerId` function |
| `direct` | 500, 1000, 2000 | Adapter-scoped `direct-calls`; the transport control |

Reference saturation cases (`sat-task-any`, `sat-task-targeted`) seed 600,000
Items per Group before approval, warm for 15s, then measure a 30s window without
HTTP submissions. Each repetition has independent Redis, Server, Host and JFR;
seed keeps one append request in flight per Task, with the two Tasks processed
concurrently. A failed batch stops further seeding after already-started requests
settle, without retry or approval. Action failures retain HTTP status and numeric
API error code in the safe summary, without response bodies or server messages.
The existing workflow repetition input still controls both paths (default 3).
Closing and draining happen after the fixed window. The Java
reader counts non-sampled, successful `RESULT_STORED` batch events in that
window and reconciles full-lifecycle claims, publications and stores against
unique, payload-validated public `results:export` rows. Missing coverage, event
loss, inconsistent counts or a drained backlog make the case invalid. This is
the successful Owner-call return point, not a Redis commit timestamp.

The workflow passes `--lane-modes saturation --experiment-config
integrations/worker-call-performance/configs/lane-saturation.json`. Java validates
the single `purpose=lane` profile; the existing Python experiment lifecycle
passes that resolved file to every phase. The merged report shows the actual
Worker count, CPU, heaps, backlog and windows for each job. A sampled Group
without an idle Worker invalidates the reference saturation configuration;
the local resource scan may retain that observation as `workersBound` instead.
Resource/evidence failures stop later repetitions and fail the configured job.
Raw recordings, logs and inventories remain outside published evidence.
The old CLI without an experiment config keeps its original 2,000-Worker world and
150,000-Item backlog; its configuration identity differs. A saturation A/B uses the
same configured world as this job (see Trend And A/B).

Path order rotates per repetition within each job. The workflow builds once, runs
`rate-500`, `rate-1000`, `rate-2000` and `saturation` as parallel jobs, and merges
them. Path ratios compare cases inside one job, so on one host.

## Case Status And Validity

| Status | Meaning |
| --- | --- |
| `passed` | Offered load within path capacity and no resource bound. |
| `saturated` | Arrivals met the consumer's in-flight cap across the whole end-to-end path, including executor dispatch before HTTP starts. This does not identify a Server capacity ceiling. The report gives the admitted rate, not a completion ceiling. Exhausted idle Workers are recorded as `workersExhausted`. |
| `invalid` | The generator lagged (schedule-lag p99 above 100ms without in-flight refusal), a Group had no idle HOT Worker in a sample, a quiesce gate timed out, or a saturation backlog drained. Numbers are not comparable. |
| `failed` | A correctness floor or fast-fail rule was violated; the lane fails. |

Fast-fail rules stop early only on these signals: in-flight refusal or generator
lag at the 10-second checkpoint (stop offering; `saturated` or `invalid`), a
protocol or correlation error (immediate), 30 seconds without a new Result while
Items remain, an observed Result rate that cannot close within the budget, thread
or FD caps (open-loop defaults: 512 / 8,192 per process; experiment files set their own
guards) and unexpected process exits.

`scheduleLagMillis` remains planned arrival to executor-task start. The additive
`offerLagMillis` measures planned arrival to the scheduler's capacity attempt;
`executorDispatchLagMillis` measures that attempt to executor-task start. Private
sample rows retain `offeredOffsetMillis` beside the existing `sentOffsetMillis`.
Neither timestamp proves bytes reached the network. Safe summary counters
`timeoutPhases` and `exceptionTypes` distinguish bounded timeout stages and
exception class names without publishing exception messages; timeouts retain
`detail=client-timeout`, HTTP status zero and an unknown submission outcome.
Non-streaming JSON exchanges retain the five-second bounded wait after request
serialization and construction, covering client queueing, connect/TLS, response
headers and the complete body. Expiry cancels the same request future without
resubmission. `connect-tls`, `before-headers` and `response-body` describe what the
caller observed; `before-headers` does not prove the request was never sent.
An already completed full response is retained when its caller is scheduled late;
the remaining wait budget cancels exchanges that are still pending.

The shared client prefers HTTP/2 and uses the JDK's HTTP/1.1 fallback when the
peer does not support it. Complete response versions are counted in `httpVersions`
and the private sample's `httpVersion`; a missing response supplies no negotiated
version evidence. The staging ingress operator must provision the HTTP/2 stream
budget for the declared load: the fixed 4,096 call cap shares a client with up to
eight observation requests. An explicit 8,192-stream deployment budget covers
that bound; the ingress's small default remains 128. A peer stream-limit exception is a client transport failure,
not proof of Server execution capacity. HTTP/2 supports connection reuse; a JDK
client may still open concurrent cold TLS connections during a cache miss after
GOAWAY. The ingress operator separately owns `admin_keepalive_requests` (default
1000) and its connection-memory tradeoff. An explicit 100000 request-reuse budget
can span the existing 3000 warmup calls and at most 60000 formal calls per case;
it does not increase request rates or the in-flight cap. Record the actual budget
and configuration fingerprint. The rates, warmup, API requests and result checks
remain unchanged. Worker WebSocket delivery keeps its HTTP/1.1
Upgrade contract.

Every HTTP-accepted Task Item must have an observed Result within the drain
budget: 180 seconds within the single-Task check budget of `10 x B` Items/s,
otherwise `120 + accepted / (10 x B)` seconds. An observed failed Result is not
success; this finite fault-free oracle is not a replay guarantee.

Each open case writes its request prefix before offering load and retains both
warmup and measured request samples. A failed warmup therefore retains request
identities for diagnosis; none of these records authorize resubmission.

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
Append batches stay 100, serial within each Task and concurrent across the two Tasks;
Item TTL is 30 minutes. Seed remains outside the measured window. After
measurement, close both Tasks, wait at most 60 seconds for quiet Workers, and
verify each export identity and MD5 payload. Unexecuted backlog is expected.

Known Worker IDs come from the Lab inventory. Runtime network/scheduling checks
use pages of 100 IDs. The common [bootstrap readiness](#bootstrap-readiness)
accumulates nonempty Properties for a fixed known-identity target through
bounded Group Preview samples; above 1,000 Workers per Group this remains a
sample and does not prove every Properties publication.

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
Measurement version 2 separates these completion semantics from earlier records.
CI lane configuration version 3 identifies configured saturation fixtures;
the full resolved configuration hash separates Worker/resource settings in history.
An unmet target bounds the tested environment, not absolute platform capacity.

### Fixed-resource Worker Comparison

[worker-scale-4k.json](configs/worker-scale-4k.json) and
[worker-scale-8k.json](configs/worker-scale-8k.json) compare two Groups of 2,000
and 4,000 Workers on the same four CPUs. Both use 1GiB heaps for every JVM,
a 2GiB Redis container with 1.5GiB maxmemory, and identical backlog and windows
to the CI saturation profile. Both use an FD guard of 16,384 so 8,000
connections have headroom; this limit does not allocate that many descriptors.
CI saturation now uses the 8,000-Worker fleet; the 4k and 6k files remain explicit controls.

[worker-scale-6k.json](configs/worker-scale-6k.json) supplies an intermediate
point of 3,000 Workers per Group with the same resources and measurement settings.
Use it to check whether candidate supply becomes sufficient before 8,000 Workers;
one successful screen does not establish the minimum stable fleet size.

Use the same extracted package and host for both configurations. Run 4k, 8k,
8k, 4k in that order, with a fresh output directory each time; every invocation
includes one Any and one targeted case in independent worlds:

```bash
for run in a1-4k b1-8k b2-8k a2-4k; do
  workers="${run##*-}"
  bash run.sh --lane-modes saturation \
    --experiment-config "integrations/worker-call-performance/configs/worker-scale-${workers}.json" \
    --allow-nonreference-host --repetitions 1 --output-root "build/worker-scale/${run}" || break
done
```

Keep outputs on the Linux filesystem. These local runs are excluded from CI
history. Compare the paired throughput together with CPU calibration, actual
CPU/RSS/FD use, Redis memory and evidence validity. A resource or evidence failure
stops the sequence and must be retained. Thirty-second screens do not establish
the 10k target; a candidate still needs the five 120-second Any confirmations.

Record CPU topology as well as affinity. To compare another four-CPU set, prefix
the entire entry command with `taskset --cpu-list 0,2,4,6`; the runner selects
from that inherited set and applies it to every JVM and Redis. Use fresh outputs
and report this as a separate affinity control. WSL-reported core/thread topology
does not prove a fixed mapping to physical Windows cores.
The recorded affinity covers the experiment JVMs and Redis container. On a
larger WSL VM, Docker port proxies and host services can run outside that set;
their CPU cost is absent from the per-role JVM/Redis table. A four-CPU affinity
profile is therefore not a VM-wide four-CPU quota or a whole-host cost claim.

[capacity-10k-compact-6k.json](configs/capacity-10k-compact-6k.json) freezes the
intermediate fleet for formal local acceptance: four CPUs, 6,000 total Workers
and 1GiB heaps. It retains the five 120-second Any runs and three targeted
controls, with 30-second warmup and 2,000,000 Items per Group. Redis alone grows
to a 4GiB container / 3GiB maxmemory budget to hold the larger pre-seeded backlog;
this is a workload capacity setting, not an asserted throughput improvement.
Run it through the capacity entry, without `--lane-modes`:

```bash
bash run.sh --experiment-config integrations/worker-call-performance/configs/capacity-10k-compact-6k.json \
  --output-root build/capacity-compact-6k
```

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

Each export also lists `slowCalls`: for fixed Owner stages (Server `results:append`
and `commands:consume` handling, the Adapter's remote calls, Result
consume/process/release and the Dispatch round, append, claim and confirm), the ten
slowest calls per stage plus every call of at least 100ms, capped at 200. A call
reports its window offset, duration, batch size, its masked thread name (digits
become `#`) and what that thread did meanwhile: parked, socket and monitor time
from the 10ms-threshold JFR events, 20ms CPU samples, and the most weighted park
and CPU call sites from the first non-JDK frames (whitelisted). Overlapping GC
pauses and the nearest machine/process CPU load cover the whole process. Parked on
Lettuce with no slow Redis command points at client-side queueing; a thread neither
parked nor sampled on a saturated machine points at CPU scheduling.

Diagnostics mode also makes each disposable Redis log commands and latency events
above 5ms (`SLOWLOG`, `LATENCY`) and exports only command names, Redis epoch
seconds and execution durations to `diagnostics/redis-latency.json`; keys,
arguments and values stay inside Redis. The resource sampler additionally writes
host-wide CPU jiffies including steal, running processes and the one-minute load
to `diagnostics/machine-cpu.jsonl`, separate from `process-resources.jsonl`, with
whitelisted kernel network totals: TCP retransmitted segments, retransmission
timeouts and loss probes, backlog/receive-queue/listen drops, softnet drops and
time squeezes, and loopback drops. Transfer stalls quantized near the 200ms minimum
TCP retransmission timeout should coincide with these counters moving.
Without diagnostics none of these settings or files exist.

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
  branch; only the summary job has write permission. This orphan evidence branch
  also carries `frontend/vercel.json` with `git.deploymentEnabled=false`, maintained
  by every publication, so recording measurements does not deploy an application.
  Existing records and external acceptance evidence are retained.
  A failed merge still publishes its generated record, including failed/invalid
  case statuses, when recording is selected; the original merge exit remains a
  job failure. Missing, malformed or mismatched run records are rejected before
  publication, and cancellation does not start publication. Retaining a failed
  record does not change which metrics or configurations are comparable.
- Nightly trend: compares with the last seven records of the same ref, lane
  config version and resolved job configuration hashes, else reports
  `insufficient-history`. Values are normalized to
  the history's median host speed; a job whose calibration deviates more than 25%
  is not judged. A median that stays outside the history range in the worse
  direction after allowing this run's half spread, or a changed majority status,
  is reported as suspect. The trend never fails the lane.
- Manual A/B (`baseline_ref` and `lane_case`): one runner builds the baseline and
  the current version, starts a fresh warmed world per version and alternates
  ABBA pairs of one case. An open-loop case uses the lane world. A `sat-*` case
  requires `--experiment-config` and runs the saturation job's configured world
  (`lane-saturation.json`: 4,000 Workers per Group, 600,000-Item backlog) through
  the same experiment lifecycle, with each version's Server and Host and the
  current harness; the workflow passes that file for `sat-*` cases. A worker-bound
  or incomplete saturation world fails the A/B instead of entering a pair, and the
  earlier 1,000-Worker-per-Group saturation A/B results are not comparable. The decision metric is p50 for open-loop cases and
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
  --lane-modes saturation --experiment-config integrations/worker-call-performance/configs/lane-saturation.json \
  --repetitions 3 --output-root build/lane-saturation
python integrations/worker-call-performance/run_worker_call_performance.py \
  --lane-modes open --lane-rates 1000 --diagnostics jfr --output-root build/lane-jfr
python integrations/worker-call-performance/run_worker_call_performance.py \
  --baseline-ref <commit> --lane-case task-any-1000 --output-root build/lane-ab
python integrations/worker-call-performance/run_worker_call_performance.py \
  --baseline-ref <commit> --lane-case sat-task-any \
  --experiment-config integrations/worker-call-performance/configs/lane-saturation.json \
  --output-root build/lane-ab-saturation
```

Outputs must be fresh directories below repository `build`. `--allow-nonreference-host`
marks local diagnostics; `--skip-build` reuses existing artifacts. Each Java
process uses `-Xms256m -Xmx1g`; evidence records configuration sources, hashes,
Git versions, OS/kernel, CPU count and the Redis image identity.

## Platform configuration source

Local runs use the [shared Worker Project fixture](../../TESTING.md#worker-project-fixture)
before this lane's overlay/CLI flags; workload, budgets and assertions remain here.

## History

Historical suites and observations remain in the
[fixed baseline records](https://github.com/quiaingmuarua/xa_mass_platform/tree/fa27697b09c0d47d8b15f200d96b7a905165e5b2/integrations/worker-call-performance/baselines).
Use their measured versions and configuration when interpreting them; they do
not define this lane's current workload or open requirements.
