# XA Mass Kernel Pacer JVM

Status: Kernel-owned production scheduling policy and finite Pacer lifecycle.

This internal Java 21 module contains the faster-moving policy layer over the
stable owner contracts in [`kernel_jvm`](../kernel_jvm/). It is loaded into the
Server Boot JAR as an ordinary dependency; it is not deployed, published or
started independently.

The dependency direction is fixed:

```text
server_jvm -> kernel_pacer_jvm -> kernel_jvm
```

## Public Boundary

`com.xa.mass.kernel.pacer.KernelPacerRuntime` is the only public top-level
type. Its `assemble(...)` method accepts the bounded mechanical owners needed by
the two production applications plus one of its four checked `PolicyPreset`
values: `DEFAULT`, `SERVICEABILITY_DEFAULT`, `SCENARIO_LAB`, or
`RUNTIME_BOUNDARY_PROOF`. The Runtime
owns fixed policy selection, one immutable HOT eligibility floor when
Serviceability is enabled, thread startup/rollback, reverse bounded shutdown
and aggregate failure state. It never closes the supplied owners.

All Assignment, Result Routing and Serviceability implementation types are
package-private. Their source files live directly in the matching
`com/xa/mass/kernel/pacer` package directory so Java package visibility
enforces the single-entry boundary without reflection, public factory types,
or source-path/package mismatches in Java tooling.

## Fixed Applications

Startup order:

```text
Result Convergence
-> Dispatch Convergence
```

Result Convergence owns exactly three fixed lane definitions:
`TASK_SUCCESS`, `TASK_FAILURE`, and optional `ADAPTER_EVIDENCE`. One platform
coordinator schedules at most ten bounded Batches by the smallest
`inflight / targetConcurrency` ratio; priority only breaks equal ratios. Every
non-empty Batch runs on a named virtual thread. Production target/max values
are SUCCESS `6/10`, FAILURE `3/10`, and Evidence `1/1`. Both Task lanes may
borrow idle capacity while Adapter Evidence remains single-flight.
These values are internal constants, not configuration or a public lane model.
Server validates endpoint-owned outcome codes and selects the Task lane; Task
policy does not read `DeliveryReport.outcomeCode`. SUCCESS stores and promotes
the Item before its narrow completed-HOT release, while FAILURE only releases
the exact assignment lease and never changes Worker polarity. Adapter Evidence
retains its finite Serviceability event policy and shares the same lifecycle
without becoming a general EventBus.

Dispatch Convergence owns one coordinator and fixed single-flight virtual
Batch lanes. One bounded RUNNING source produces a NORMAL projection for
Worker Allocation, Task Dispatch and optional Worker Serviceability, plus an
INITIAL projection used only by Task Initialization. NORMAL fills the shared
Batch first. Busy lanes skip the current Batch without storing a pending hint;
Task score provides rediscovery.

Shutdown uses one shared deadline in strict reverse order. `DEFAULT` keeps
Serviceability disabled, `SERVICEABILITY_DEFAULT` enables it at the normal
production cadence, `SCENARIO_LAB` is the checked fast local-Lab policy, and
`RUNTIME_BOUNDARY_PROOF` is the deterministic boundary-proof policy. Server
accepts that proof-only preset only with a `test_*` Redis scope. These presets
compose existing configuration value objects; there is no Java policy file,
per-field runtime tuning, Pacer SPI, dynamic registry, network API, Redis owner
or fallback path.

Spring assembly belongs to `server_jvm`. Candidate Cache,
ResultContextCodec and all Redis providers remain in `kernel_jvm`.

Build:

```text
./gradlew :kernel_pacer_jvm:build
```
