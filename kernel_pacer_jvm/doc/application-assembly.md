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
           -> ADAPTER_EVIDENCE virtual batch             optional
        -> DispatchConvergenceApplication
           -> one Task Score scan and INITIAL subset filter
           -> DispatchMainScheduler fixed input planning
              -> TASK_INITIALIZATION resource producer
              -> WORKER_ALLOCATION resource producer
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

When Serviceability is enabled, Runtime mints one Worker-Score-slot-aligned
`hotEligibilityFloorMillis`. Serviceability Dispatch and Assignment candidate
acquisition receive the same immutable value. Adapter Evidence changes only
polarity and does not receive the floor. It is not stored in Redis or exposed
through Health or Runtime APIs.

## Mechanical Owners

The finite Java caller closure is:

```text
TaskRuntime / TaskResourceCatalog
TaskScoreBandCore / TaskItemScoreBandCore
WorkerRuntime / WorkerResourceCatalog / WorkerScoreCore
TaskItemResultEvents / WorkerExecutionResultEvents / WorkerServiceabilityEvents
TaskInitializationCheck / WorkerCandidateMechanism
TaskExecutionMechanism / WorkerServiceabilityDispatchMechanism
CandidateWorkerCache
WorkerCommandRuntime / TaskResultRuntime
WorkerServiceabilityRuntime
```

Candidate Cache is a disposable derived owner. There is no Candidate retry or
warmup index: due RUNNING Task score is the only demand source for allocation,
dispatch, and serviceability policy.

The module direction remains:

```text
server_jvm -> kernel_pacer_jvm -> kernel_jvm
```

- `kernel_jvm` owns mechanical contracts, Redis providers, finite cross-owner
  Mechanisms, Candidate Cache, and codecs.
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

`DispatchConvergenceApplication` owns one non-daemon Main Scheduler and one
virtual thread per non-empty eligible Producer round. Every Producer is
single-flight. The Main Scheduler reads the original Task Source at most once
per eligible sweep, then supplies each Producer only its complete root input. A
busy Producer skips that source snapshot and retains no memory hint; unchanged
Task score lets a later observation rediscover the Task.

The fixed Producers are:

| Producer | Main-planned root input | Responsibility |
| --- | --- | --- |
| TASK_INITIALIZATION | INITIAL RUNNING | one due-Item check and exact batch promotion to NORMAL |
| WORKER_ALLOCATION | PRECOMPUTED NORMAL Tasks | Candidate deficit acquisition and cache publication |
| TASK_DISPATCH | NORMAL RUNNING | Item finality, Worker lease, Item claim, Command publication, Task pacing/idle lifecycle |
| WORKER_SERVICEABILITY | ordered unique WorkerGroup IDs from NORMAL Tasks | offer Adapter route probes |

Allocation and Task Dispatch may run concurrently. A Candidate produced during
one batch is not guaranteed to be consumed in the same batch; later RUNNING
discovery provides convergence. Candidate entries left behind after a Task is
parked or closed expire with their existing lease/cache deadline.

A Task Source or INITIAL-classification failure defers every currently eligible
Producer. Descriptor loading failure defers only NORMAL Producers; already
formed Initialization input may still run. Empty input or a Producer
`RuntimeException` defers only that Producer by its own interval. A JVM
`Error`, rejected execution, or unexpected Main Scheduler exit fails Dispatch
Convergence and therefore Kernel readiness.

## Result Convergence

Result Convergence owns one coordinator and ten shared virtual-batch slots.
Its fixed lanes are Task SUCCESS, Task FAILURE, and optional Adapter Evidence.
Weighted-fair targets and maxima remain Kernel-internal policy. Result lanes
and Dispatch Resource Producers do not share queues, lifecycle state, topology,
or executors.

Its policies terminate `DeliveryReport` and JSON interpretation, perform
bounded last-wins grouping, and publish finite semantic callbacks. The Runtime
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

Shutdown is strictly reversed. Both Applications share one shutdown deadline;
neither resets the budget per Producer, lane, or thread. Startup failure rolls
back every started Application in reverse order. Any required scheduler or worker-loop
death moves `KernelPacerRuntime` to `FAILED`.

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
```

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
