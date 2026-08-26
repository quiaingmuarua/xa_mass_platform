# XA Mass Kernel JVM

Status: JVM Kernel owner-contract parity and selected owner provider
implementation.

This module mirrors the public owner boundary exported by
[`kernel_design.executable_spec.kernel`](../kernel_design/executable_spec/kernel/).
Python remains the mechanism oracle. JVM contracts use idiomatic camelCase but
preserve the Python method set, DTO fields, enum values, nullability, and key
score constants. Owner packages are `@NullMarked`; Python optional values are
spelled with explicit JSpecify `@Nullable` type-use annotations.

Package responsibilities:

| Package | Intended responsibility |
| --- | --- |
| `task` | `TaskRuntime`, catalog, lifecycle and bounded Task Call command contracts |
| `worker` | Worker runtime, catalog, and dynamic-attribute contracts |
| `score` | Task, TaskItem, and Worker score owner contracts |
| `assignment` | Candidate cache and warmup schedule contracts |
| `delivery` | DeliveryCommand and DeliveryReport runtime contracts |
| `serviceability` | Adapter probe handoff plus fixed production Result Pacer |
| owner-local `redis` packages | Selected Redis implementations |

The current implemented provider subset is:

```text
TaskRuntime
  createTask
  appendItems
  loadTaskItemSuccessResults
  scanTaskItemSuccessResults

TaskResourceCatalog
  loadTaskAllocationDescriptors

TaskScoreBandCore
  getScoreStates
  initializeScore
  rewriteScore
  closeScore
  tryReleaseIdlePark
  releaseObservedScoreHold

TaskLifecycleCommands
  approveTask
  closeTask

TaskCallItemSubmission
  submit

WorkerResourceCatalog
  registerWorkerGroup
  getWorkerGroupDescriptors
  sampleWorkerGroupDescriptors
  getWorkerDescriptors
  sampleWorkerDescriptors
  patchWorkerPlatformProperties

WorkerRuntime
  upsertWorker

WorkerScoreCore
  getScoreStates
  initializeHotAcquireScore
  rewriteCurrentScores
  toggleCurrentPolarity
  exhaustRecoveryRecheck
  releaseScoreHolds
  releaseCompletedHotScoreHolds

WorkerCommandRuntime
  non-overwriting bounded offer plus point and bounded random batch consume;
  authoritative append remains an explicit JVM gap

WorkerResultRuntime
  append and bounded FIFO consume

WorkerServiceabilityRuntime
  offerProbeRequests
  consumeProbeRequests
  appendAdapterEvidenceResults
  consumeAdapterEvidenceResults
```

Worker score initialization fixes laneRank at zero. General Assignment
candidate acquisition, observed lease acquisition/renewal, and dirty marking
remain explicit gaps in the JVM provider. The bounded pre-epoch HOT and due
RECOVERY reads required by Java Serviceability Dispatch are implemented without
claiming or leasing Workers.
`rewriteCurrentScores` preserves polarity,
lane rank, and dirty while moving the time coordinate forward;
`releaseScoreHolds` preserves the same fields and uses the complete observed
score as an exact CAS fence. Java production Result Routing also implements
`releaseCompletedHotScoreHolds`: its per-Worker Lua accepts only the original
positive lease or the exact Serviceability RECOVERY counterpart derived by the
score owner. TaskItem record reads and DeliveryCommand authoritative append
remain explicit gaps. Implementing this result closure does not imply that
Worker scheduling or the complete owner has migrated.

Every other translated operation is explicit and throws
`KernelOperationNotImplementedException` when invoked by a partial provider.
There are no default-method fallbacks.

`RedisTaskScoreBandCore` implements only the six operations required by Java
Task create, lifecycle and Call submission. Candidate acquisition, dispatch
pacing, observed idle park/close and other Pacer operations fail explicitly.
`DefaultTaskCallItemSubmission` performs the bounded
`tryReleaseIdlePark -> appendItems -> tryReleaseIdlePark` composition without
inspecting Task policy or implementing scheduling.

`RedisTaskItemScoreBandCore` owns the two Java production operations required
by Task append and Result Routing: NX initialization and cross-band outcome
promotion. `RedisTaskRuntime` owns Item records and success payloads but no
longer constructs TaskItem score keys or encoding. Candidate, retry and due
Item operations remain explicit provider gaps.

`RedisWorkerServiceabilityRuntime` implements Probe Request offer for Java
Serviceability Dispatch, the two Java Server bridge operations (destructive
Adapter request consume and bounded Kernel-result append), and the Java Result
Pacer's bounded FIFO evidence consume.

The shared
[`kernel_owner_contract_manifest.json`](../kernel_design/executable_spec/kernel_owner_contract_manifest.json)
is test evidence only. Python generates and checks the semantic side; JVM
reflection checks the normalized Java side. It does not generate source and is
not an external protocol.

The external Runtime API belongs to [`server_jvm/`](../server_jvm/). Its
controllers and services depend on these owner contracts. Task business
commands use the Java Redis owners directly. `kernel_jvm` now contains the
fixed production Result Routing, Worker Serviceability Result, and Worker
Serviceability Dispatch Pacers, each with one single-threaded application
lifecycle. Java Server composes all three beside the fixed temporary Python
child; managed mode disables all three Python counterparts and passes the
single Java-minted HOT eligibility floor to both Java Serviceability Pacers and
Python Assignment. Python remains the Assignment implementation and complete
mechanism oracle, with no Task HTTP fallback. Provider selection never appears
in HTTP controllers or business services.

There is no general Pacer registry, replaceable Result Handler map, combined
WorkerDelivery runtime, or full Kernel application lifecycle in this module.
Remaining Pacers must be migrated through separate vertical parity slices.

`kernel_jvm` targets JDK 21 and is not an Android module. A future
Android-compatible Worker SDK belongs in a separate Gradle module with its own
toolchain and API baseline. Shared Worker Delivery DTOs and codecs require a
separate Android-compatible contract module; the SDK must not depend directly
on this JDK 21 implementation module.

Build:

```text
./gradlew :kernel_jvm:build
```
