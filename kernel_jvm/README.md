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
| `task` | `TaskRuntime` and `TaskResourceCatalog` contracts |
| `worker` | Worker runtime, catalog, and dynamic-attribute contracts |
| `score` | Task, TaskItem, and Worker score owner contracts |
| `assignment` | Candidate cache and warmup schedule contracts |
| `delivery` | DeliveryCommand and DeliveryReport runtime contracts |
| `serviceability` | Adapter probe request/result handoff contract only |
| owner-local `redis` packages | Selected Redis implementations |

The current implemented provider subset is:

```text
TaskRuntime
  appendItems
  loadTaskItemSuccessResults

TaskResourceCatalog
  loadTaskAllocationDescriptors

WorkerResourceCatalog
  upsertWorkerGroup
  getWorkerGroupDescriptors
  getWorkerDescriptors
  sampleWorkerDescriptors
  patchWorkerPlatformProperties

WorkerRuntime
  upsertWorker

WorkerPropertyIndexRuntime
  updateIndexedProperties
  loadIndexedPropertyValues

WorkerScoreCore
  getScoreStates
  initializeHotAcquireScore
  rewriteCurrentScores
  releaseScoreHolds

WorkerCommandRuntime
  non-overwriting bounded offer plus point and bounded random batch consume;
  authoritative append remains an explicit JVM gap

WorkerResultRuntime
  append

WorkerServiceabilityRuntime
  consumeProbeRequests
  appendAdapterEvidenceResults
```

Worker score initialization fixes laneRank at zero. Candidate acquisition,
observed lease acquisition/renewal, dirty marking, polarity changes, and
recovery exhaustion remain explicit gaps in the JVM provider.
The Serviceability-only pre-epoch HOT read is also an explicit JVM provider gap
because no Java production caller owns that Pacer.
`rewriteCurrentScores` preserves polarity,
lane rank, and dirty while moving the time coordinate forward;
`releaseScoreHolds` preserves the same fields and uses the complete observed
score as an exact CAS fence. Task creation, TaskItem record reads,
success-result writes, DeliveryCommand append, and DeliveryReport consume
likewise remain Python-owned or unimplemented on this provider surface.
Implementing these two transitions does not imply that Worker scheduling or
the complete owner has migrated.

Every other translated operation is explicit and throws
`KernelOperationNotImplementedException` when invoked by a partial provider.
There are no default-method fallbacks.

`RedisWorkerServiceabilityRuntime` implements only the two operations required
by the Java Server bridge: destructive Adapter request consume and bounded
Kernel-result append. Request offer, Result consume, both Pacers, and all score
policy remain Python Kernel responsibilities; invoking either unimplemented JVM
operation fails explicitly.

The shared
[`kernel_owner_contract_manifest.json`](../kernel_design/executable_spec/kernel_owner_contract_manifest.json)
is test evidence only. Python generates and checks the semantic side; JVM
reflection checks the normalized Java side. It does not generate source and is
not an external protocol.

The external Runtime API belongs to [`server_jvm/`](../server_jvm/). Its
controllers and services depend on these owner contracts. Server assembly
chooses a Python HTTP provider, Java Redis provider, or explicit unimplemented
provider per operation. Provider selection never appears in HTTP controllers
or business services.

There is no TaskData runtime, combined WorkerDelivery runtime, Pacer,
scheduling policy, or Kernel application lifecycle in this module. Those
boundaries must be migrated through separate parity slices.

`kernel_jvm` targets JDK 21 and is not an Android module. A future
Android-compatible Worker SDK belongs in a separate Gradle module with its own
toolchain and API baseline. Shared Worker Delivery DTOs and codecs require a
separate Android-compatible contract module; the SDK must not depend directly
on this JDK 21 implementation module.

Build:

```text
./gradlew :kernel_jvm:build
```
