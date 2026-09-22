# Kernel Application Assembly

Status: active Kernel application and production lifecycle contract.

## Production Assembly

```text
Java Server
  -> KernelPacerAssembly                 Spring lifecycle adapter
     -> KernelPacerRuntime               kernel_pacer_jvm
        -> ResultConvergenceApplication
           -> TASK_SUCCESS virtual batches
           -> TASK_FAILURE virtual batches
           -> NETWORK_EVIDENCE virtual batch             every preset
           -> TASK_OBSERVATION virtual batches           every preset
        -> DispatchConvergenceRuntime
           -> one Task Score scan and INITIAL subset filter
           -> DispatchMainScheduler fixed input planning
              -> TASK_INITIALIZATION resource producer
              -> ELIGIBILITY_REFILL resource producer
              -> TASK_DISPATCH resource producer
              -> WORKER_SERVICEABILITY resource producer optional
```

## Production Configuration

Spring owns only the finite lifecycle envelope:

```yaml
xa.mass.kernel-pacer:
  enabled: true
  preset: DEFAULT
  shutdown-timeout: 5s
```

`kernel_pacer_jvm` owns the checked presets and interprets all policy values.
Server passes the selected preset to `KernelPacerRuntime.assemble(...)`; it
does not inspect lane policy. There is no production Pacer JSON, dynamic lane
registry, or per-field Server override.

Every preset consumes Network Evidence. Runtime samples one immutable millisecond
activation floor and supplies it to the event Mechanism for bounded CONNECTED
activation. Serviceability-enabled presets also use that value for refill and
candidate recycling. DEFAULT keeps no Assignment scan floor or periodic Probe.
Score Owner alone aligns time. The floor is not stored in Redis or exposed by an
API, and restart of the same Runtime loops does not resample it.

Network evidence uses the existing bounded Score operation. Past polarity changes
advance generation and clear candidate mark; same-polarity normal observations,
including Polling, leave it alone. Below-floor HOT activation is the exception.
Current/future holds retain their exact absolute coordinate. Reconnected due HOT
can re-enter ordinary Refill without waiting for aged candidate recycling; no Pool
compensation, new Producer or additional clock sampling is introduced.

## Mechanical Owners

Matching exposes only its three-operation `WorkerMatching` port to Pacer: observe
refill deficits, supply candidate fences, and take query results. Its Catalog
delegates supply organization and both local cursors to `PoolRefillCoordinator`.
Server-only Properties reads/writes use the separate `WorkerProperties` port.
Both interfaces are assembled under one MatchingComposition lifetime; this split
does not change Pacer Producers, Group rotation or Score operations.

`KernelPacerRuntime.assemble` also accepts one non-blocking
`Consumer<KernelPacerRuntime.WorkerObservation>`. The nested immutable record
contains only Group, Worker IDs, source time, message event name and observation
event name. It is a passive best-effort notification, not a mechanical event or
an acknowledgement. Assemblies without consumers pass an explicit no-op.
Assignment emits `worker.assigned` after confirmed execution acquisition and
exact Item claim, before Command encoding/publication. Batches share Group and
message event; Pacer forwards exact `TaskItem.eventCode` strings without business
interpretation. Ordinary sink exceptions cannot prevent publication. This adds
no Pacer thread, queue, deduplication, replay or new Kernel operation.
The [Server consumer](../../server_jvm/README.md#worker-allocation-observations)
owns asynchronous delivery and any downstream projection.

The finite Java caller closure is:

```text
TaskRuntime / TaskResourceCatalog
TaskScoreBandCore / TaskItemScoreBandCore
WorkerResourceCatalog / WorkerScoreCore
TaskItemResultEvents / WorkerExecutionResultEvents / WorkerServiceabilityEvents
TaskInitializationPolicy
TaskAssignmentDispatcher / TaskIdleSettlement
WorkerMatching
WorkerCommandRuntime / TaskEvidenceRuntime
WorkerServiceabilityRuntime
```

Main-selected NORMAL RUNNING Tasks supply refill targets, dispatch input and
Serviceability Groups. Main shares complete immutable Task descriptors. Refill groups
those declarations, asks Matching for Group shortage hints, and passes explicit
Group/Pool targets with each candidateized Group batch. Matching removes admitted
identities before offering the remainder to the next Pool. Dispatch calls Matching with the
Task's Group and messageId-to-WorkerQuery Maps, receiving messageId-to-WorkerCandidate
Maps. Each Item names its own function; candidates carry strict fences or identity
hints. Only Matching normalizes and groups queries;
Pacer keeps correlation and mechanical checks. No executable view or refill closure crosses the port.
Property HASHes retain the last Writer for each exact value independently of Pool
stock. Direct functions use the lookup interface; no index rebuild runs at startup.
Candidate inventory
is replenished from Task-declared targets without inspecting Items.
Messages with an explicit sender phone declares empty supply and uses Matching's
qualified Direct Phone function. Its identity hint follows the existing current
execution acquisition; Pacer adds no Pool notification or business qualification.

The module direction remains:

```text
server_jvm -> kernel_pacer_jvm -> kernel_jvm
```

- `kernel_jvm` owns mechanical contracts, Redis providers, finite cross-owner
  Mechanisms, bounded query ports, and codecs.
- `kernel_pacer_jvm` owns Main Scheduler input planning, Resource Producer
  policy, presets, and finite thread lifecycle.
- `server_jvm` owns Owner assembly, Spring lifecycle delegation, and Health.

## Dispatch Convergence

`DispatchMainScheduler` obtains two projections from one bounded Task Score
scan and plans the root input of the fixed Resource Producers:

```text
NORMAL projection
  -> newest due RUNNING scores at or above 10,100ms

INITIAL projection
  -> fixed RUNNING slot 10,000ms after all returned NORMAL scores
```

The Score Owner returns one ordered `taskId -> opaque score` map and separately
filters its INITIAL subset. The Main Scheduler treats the remaining identities
as NORMAL, loads only their Descriptors once, validates identity, and performs
no Task Score point recheck. INITIAL needs no Descriptor wrapper. These values
are round evidence, not locks; every later mutation still uses exact owner
fences.

Inside its single-flight Producer, Task Dispatch reorders the selected batch
using bounded recent-service history: unserved Tasks first, then least recently
served. Successful Command publication advances the hint; no-progress rounds do
not. It has no persistent cursor, additional discovery or capacity reservation.

`DispatchConvergenceRuntime` owns one non-daemon Main Scheduler thread.
`DispatchMainScheduler` owns one virtual thread per non-empty eligible Producer
round. Every Producer is single-flight. The Main Scheduler reads the original
Task Source at most once per eligible sweep, then supplies each Producer only
its complete root input. A busy Producer skips that source snapshot and retains
no memory hint; unchanged Task score lets a later observation rediscover the
Task.

If an eligible Serviceability or refill Producer receives no NORMAL Task input, it waits
for the next source observation already triggered by another fixed Producer.
An empty page does not consume another full Serviceability interval: otherwise
its deadline can repeatedly coincide with Task pacing's current-slot gap. This
wait stores no Task or Group input and neither wakes Main nor adds source reads.
A non-empty round or source failure restores the ordinary completion/backoff
interval. `DispatchBudgetTest` checks progress and the unchanged source-call count
under deliberately aligned clocks.

DEFAULT Task Dispatch checks at most 100 Items per Task per round. Its next
eligibility is set 50ms after the Main Scheduler processes producer completion;
the interval does not start at dispatch launch. Slow producers remain single-flight
and do not catch up with overlapping rounds. A continuously full single Task is
therefore bounded above by `100 / (0.05 + round_seconds)` checked Items/s, before
scan/observation delay and unsuccessful assignments. This is a policy budget,
not a platform QPS guarantee. Group-managed calls share that Task budget.
`DispatchBudgetTest` proves the bounded check and completion-relative scheduling
with controlled execution and time. These values remain preset-owned and have no
Server override.

The completion interval is not the only eligibility gate. Task Score scheduling
excludes the current 100ms Redis slot, and Task Dispatch rewrites each visited
claimable Task to the round's start time. With aligned clocks and successful
rewrites, a continuously loaded single Task therefore normally becomes visible
at most once per slot: roughly 1,000 checked Items/s at the current 100-Item
budget, before slower rounds and unavailable Workers reduce progress. The 50ms
completion budget alone does not establish 2,000/s. This composition is observed
in the [2026-09-21 attribution](../../integrations/worker-call-performance/baselines/2026-09-21-task-any-attribution.md);
it is not a global multi-Task capacity limit or a change to Score interpretation
inside Pacer.

Initialization keeps its 100ms interval. Dispatch and refill each use
50ms so candidate availability can be consumed without adding another full
100ms idle interval after a mixed-Task round. The 100-Item ceiling, single-flight
Producer, latest-due Item ordering and completion-relative backoff are unchanged;
this is additional checking headroom, not Item fairness or an all-load SLA.

Default-off `xa.mass.TaskDispatch` and `xa.mass.TaskResult` JFR events observe
existing refill/refill-observation/candidateize/round/check/candidate/confirmation-rejection/claim/publish
and Result consume/process/release
calls. Counts describe attempts or batches, not unique completed Items. Owner-local
events add no registry, queue, Redis operation or Score interpretation; sampled
correlation is joined only by the offline [call proof](../../integrations/worker-call-performance/README.md#rpc-mainline-diagnosis).
In particular, `WORKER_RELEASE` counts a normally returned Worker event call,
not successful per-Worker Score transitions. The current semantic event discards
the mechanical release statuses; release success cannot be inferred from this
JFR count alone.

The fixed Producers are:

| Producer | Main-planned root input | Responsibility |
| --- | --- | --- |
| TASK_INITIALIZATION | INITIAL RUNNING | one due-Item check and exact batch promotion to NORMAL |
| ELIGIBILITY_REFILL | NORMAL Task descriptors | Group shortage observation, independent aged-candidate recycle, due-head candidateization and Matching admission; no Item read |
| TASK_DISPATCH | NORMAL RUNNING descriptors | consume inventory, acquire execution, Item finality/claim, Command publication, Task pacing/idle lifecycle |
| WORKER_SERVICEABILITY | ordered unique WorkerGroup IDs from NORMAL Tasks | offer Adapter route probes |

Main shares the already-read NORMAL Task descriptors. Matching performs only
named eligibility calls, with no Task configuration read. Candidate work is not
a prerequisite for dispatch expiry/exhaustion or idle settlement. Refill is
single-flight and targets shared Group/Pool stock. It has no Task-private cache,
queue or additional Task discovery. See [Matching](../../worker_matching_jvm/README.md).

A Task Source or INITIAL-classification failure defers every currently eligible
Producer. Descriptor loading failure defers only NORMAL Producers; already
formed Initialization input may still run. Empty input or a Producer
`RuntimeException` defers only that Producer by its own interval. A JVM
`Error`, rejected execution, or unexpected Main Scheduler exit fails Dispatch
Convergence and therefore Kernel readiness.

## Result Convergence

Server supplies execution failure and success target tags (currently 5 and 6)
through `KernelPacerRuntime.assemble`. Dispatch receives the failure tag and the
TaskItem result mechanism receives the success tag. These are application
contract values, not Score Owner business categories; the Score Owner only
validates generic terminal tags 2..9 and monotonic progression.

Result Convergence owns one coordinator and ten shared virtual-batch slots.
Its fixed lanes consume Task EXECUTION_SUCCESS, Task EXECUTION_FAILURE,
Network Evidence, and Task OUTCOME_OBSERVATION in every preset.
`TaskEvidenceRuntime` retains the execution LISTs and adds one observation LIST.
The observation lane follows the existing priorities, with target concurrency 1,
maximum 10, batch size 100 and the shared Task evidence idle interval. It uses
the same coordinator and global ten-slot capacity.
Weighted-fair targets and maxima remain Kernel-internal policy. Result lanes
and Dispatch Resource Producers do not share queues, lifecycle state, topology,
or executors.

Its policies terminate `DeliveryReport` and JSON interpretation, perform
bounded grouping, and publish finite semantic callbacks. Execution retains
last-wins grouping; observations retain maximum tag/time state and content
independently for each Item. The Runtime
composition point constructs the default TaskItem, Worker execution and Worker
Serviceability event Mechanisms from the supplied mechanical owners. Policies do
not directly store Task results, promote Item scores, release Worker scores,
read Worker scores, or select Serviceability score transitions.

## Lifecycle

Startup is:

```text
Result Convergence
-> Dispatch Convergence
```

Shutdown is strictly reversed. Result Convergence and Dispatch Convergence
share one shutdown deadline. Dispatch shutdown interrupts and joins its Main
Scheduler thread; the Scheduler then interrupts outstanding virtual Producer
tasks. It has no stop queue, latch, or monitoring thread. Startup failure rolls
back every started runtime in reverse order. A required scheduler or worker-loop
death observed by runtime health moves `KernelPacerRuntime` to `FAILED`.

Spring readiness requires the Runtime and Kernel Redis to be UP. Liveness
remains a JVM-process signal. Health exposes only:

```text
javaResultConvergenceState
javaDispatchConvergenceState
```

It does not expose Redis coordinates, Task batches, policy content, HOT floor,
payloads, or results.

Exactly one Server per Redis scope may enable Kernel Pacers. There is no
distributed leader election.

## Proof Boundary

Runtime Boundary starts one Java Spring context and real Redis, with no
auxiliary Kernel process, and proves:

```text
Task API
-> Java Task owners
-> Dispatch Main Scheduler
-> Java Dispatch Convergence
-> Worker Delivery and execution
-> Java Result Convergence
-> TaskItem finality + result + exact Worker release
-> retained Worker reporter -> later Item observations + latest Result queries
```

Serviceability retains the 1-second production Producer interval and 100-successful-
hold round budget. It reads current HOT heads, falling back to RECOVERY only for an
empty raw HOT result in that Group. Range observations go directly through Binding
validation to the exact write; no Score-state point read is used. Pacer supplies
opaque observed Scores and one fixed batch delay to `deferObservedToRecovery`;
Score Owner writes the next eligible recheck time using Redis time before the Probe offer.
HOT reads two mark ranges, RECOVERY keeps TIME plus two ranges; each is bounded
by limit, then merged and truncated to limit raw rows. Deferral keeps one EVAL;
there is no Score-state point read. There are
no per-Group scan cursors or empty-range restart timers. Recheck delay defaults to
15 seconds, independently of the 60-second HOT stale threshold. This delay does
not promise execution at 15 seconds. Recovery has no attempt limit or age cutoff; cold
parking is reserved for excluded Endpoints. No cleanup thread is installed. CONNECTED evidence keeps a
future recheck coordinate and mark. Execution must await strict due eligibility.
Candidateize and recycling use separate 100-per-Group/1000-per-round budgets on
the existing Refill Producer. Each refill attempt reserves 100 budget, but its
raw head limit is `min(observed Group deficit, 100)`; smaller shortages do not
increase the per-round Group call ceiling. Matching supplies numeric shortage
hints without reserving stock. Target counts are watermarks: Matching does not
truncate already-supplied qualified candidates at those counts; admission still
honors its batch budget and actual capacity. Candidate age is 60 seconds in production/Scenario
Lab and 10ms in Runtime Boundary; Pool TTL is independently 60 seconds and checked
when entries are polled. Matching counts resident entries, including duplicates and
old entries not yet reclaimed; these remain shortage hints rather than executable
Worker counts. Capacity pressure can reclaim expired queue heads during the existing
shortage observation. No additional Pacer task or cleanup thread is introduced.
Each Group attempt supplies only the successful new candidateizations, once.
Remaining shortage does not trigger stock copying, a supplementary scan or replay.
Matching retains Pool/target rotation while each generation enters at most one Pool.
Its composition supplies Pool order and target batching capability; Pacer does
not interpret them. Matching qualifies offered IDs from Facts (Proof uses an
atomic Worker/Platform snapshot), never source-index discovery or substitute IDs.
Direct queries bypass stock; successful execution acquisition sends no notification
to a Pool. Redis ordinary/aged candidate heads remain cursor-free.
The event Mechanism chooses target polarity and minimum activation time for the mechanical
Score operation. Provider construction and close ownership stay unchanged; the
package-private encoding helper has no separate lifecycle or assembly.

The Serviceability boundary proves that the Main Scheduler derives the ordered
WorkerGroup input from the same due RUNNING Task source and that Adapter
Evidence converges through the Result Application. Focused Pacer tests and
Runtime Boundary proof own this contract.

## Guardrails

- Do not restore Candidate demand hints, a second Task scan, or a pending Batch
  queue inside Dispatch Convergence.
- Do not add a dynamic Pacer/Producer registry, public policy SPI, or fallback
  owner.
- Do not assemble Pacer subpackage types from Server;
  `KernelPacerRuntime` is the only externally supported Pacer entry. The
  Result and Dispatch lifecycle bridges exist only for internal cross-package
  composition and are guarded against imports outside `kernel_pacer_jvm`.
- Do not decode opaque Score structure outside its owner.
- Do not move candidate selection, Worker lease, Item claim, retry, recovery,
  or Task finality into Server.
