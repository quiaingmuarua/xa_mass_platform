# Kernel Application Assembly

Status: active Kernel application and production lifecycle contract.

## Production And Oracle Assemblies

Production is Java-only:

```text
Java Server
  -> KernelPacerAssembly                 Spring lifecycle adapter
     -> KernelPacerRuntime               kernel_pacer_jvm
        -> ResultConvergenceApplication
           -> TASK_SUCCESS virtual batches
           -> TASK_FAILURE virtual batches
           -> ADAPTER_EVIDENCE virtual batch             optional
        -> DispatchConvergenceApplication
           -> one RUNNING Task Source
              -> TASK_INITIALIZATION virtual batch
              -> WORKER_ALLOCATION virtual batch
              -> TASK_DISPATCH virtual batch
              -> WORKER_SERVICEABILITY virtual batch     optional
```

The Python executable specification remains the standalone mechanism Oracle:

```text
python -m kernel_design.executable_spec.assembly --config <path>
  -> KernelApplication
     -> Result Convergence
     -> Dispatch Convergence
```

The Oracle exposes no HTTP surface, managed-child mode, or selectively disabled
production mode. It must use an isolated `test_*` Redis scope and is not
packaged in the Server Runtime.

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
`hotEligibilityFloorMillis`. Adapter Evidence, Serviceability Dispatch, and
Assignment candidate acquisition receive the same immutable value. It is not
stored in Redis or exposed through Health or Runtime APIs.

## Mechanical Owners

The finite Java caller closure is:

```text
TaskRuntime / TaskResourceCatalog
TaskScoreBandCore / TaskItemScoreBandCore
WorkerRuntime / WorkerResourceCatalog / WorkerScoreCore
TaskItemResultEvents / WorkerExecutionResultEvents / WorkerServiceabilityEvents
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

- `kernel_jvm` owns mechanical contracts, Redis providers, Candidate Cache,
  and codecs.
- `kernel_pacer_jvm` owns Sources, policies, coordination, presets, and finite
  thread lifecycle.
- `server_jvm` owns Owner assembly, Spring lifecycle delegation, and Health.

## Dispatch Convergence

`TaskSchedulingBatchSource` has two projections of one RUNNING lifecycle
surface:

```text
NORMAL projection
  -> acquire due RUNNING scores at or above 10,100ms

INITIAL projection
  -> fill the remaining batch from fixed RUNNING scores at or below 10,000ms
```

One Source call reads NORMAL first, lets INITIAL use the remaining part of the
100-Task budget, and point-reads Task Score and Descriptor once. It preserves
each projection's Score order and emits immutable `DueTaskObservation` values. These observations are
round evidence, not locks; every later mutation still uses exact owner fences.

`DispatchConvergenceApplication` owns one non-daemon coordinator and one
virtual thread per non-empty eligible lane batch. Every lane is single-flight.
When multiple RUNNING lanes are eligible, the Source is read once and the same
immutable batch is submitted to all of them. A busy lane skips that batch and
retains no memory hint; unchanged Task score lets a later Source read rediscover
the Task.

The fixed lanes are:

| Lane | Source | Responsibility |
| --- | --- | --- |
| TASK_INITIALIZATION | INITIAL RUNNING | due ACTIVE Item check and exact promotion to NORMAL |
| WORKER_ALLOCATION | NORMAL RUNNING | PRECOMPUTED Candidate deficit acquisition and cache publication |
| TASK_DISPATCH | NORMAL RUNNING | Item finality, Worker lease, Item claim, Command publication, Task pacing/idle lifecycle |
| WORKER_SERVICEABILITY | NORMAL RUNNING | derive demanded WorkerGroups and offer Adapter route probes |

Allocation and Task Dispatch may run concurrently. A Candidate produced during
one batch is not guaranteed to be consumed in the same batch; later RUNNING
discovery provides convergence. Candidate entries left behind after a Task is
parked or closed expire with their existing lease/cache deadline.

An empty Source or Source failure defers only the currently eligible lanes by
their own interval. A policy `RuntimeException` drops that best-effort batch and
defers its lane. A JVM `Error`, rejected execution, or unexpected coordinator
exit fails Dispatch Convergence and therefore Kernel readiness.

## Result Convergence

Result Convergence owns one coordinator and ten shared virtual-batch slots.
Its fixed lanes are Task SUCCESS, Task FAILURE, and optional Adapter Evidence.
Weighted-fair targets and maxima remain Kernel-internal policy. Result lanes
and Dispatch lanes do not share queues, lifecycle state, or executors.

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
neither resets the budget per lane or thread. Startup failure rolls back every
started Application in reverse order. Any required coordinator or worker-loop
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

Runtime Boundary starts one Java Spring context and real Redis, with no Python
process, and proves:

```text
Task API
-> Java Task owners
-> shared Task Source
-> Java Dispatch Convergence
-> Worker Delivery and execution
-> Java Result Convergence
-> TaskItem finality + result + exact Worker release
```

The Serviceability boundary proves that the same due RUNNING Task batch drives
WorkerGroup probe demand and that Adapter Evidence converges through the Result
Application. Python tests remain the independent policy/shape Oracle.

## Guardrails

- Do not restore Candidate demand hints, a second Task scan, or a pending Batch
  queue inside Dispatch Convergence.
- Do not add a dynamic Pacer/lane registry, public policy SPI, or fallback
  owner.
- Do not assemble Pacer subpackage types from Server;
  `KernelPacerRuntime` is the only externally supported Pacer entry. The
  Result and Dispatch lifecycle bridges exist only for internal cross-package
  composition and are guarded against imports outside `kernel_pacer_jvm`.
- Do not decode opaque Score structure outside its owner.
- Do not move candidate selection, Worker lease, Item claim, retry, recovery,
  or Task finality into Server.
- Never run Python Oracle against a production Redis scope.
