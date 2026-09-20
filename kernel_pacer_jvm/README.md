# XA Mass Kernel Pacer JVM

Status: Kernel-owned production scheduling policy and finite Pacer lifecycle.

This internal Java 21 module contains the faster-moving policy layer over the
stable owner contracts in [`kernel_jvm`](../kernel_jvm/). It is loaded into the
Server Boot JAR as an ordinary dependency; it is not deployed, published or
started independently.

The dependency direction is fixed:

```text
server_jvm -> kernel_pacer_jvm -> kernel_jvm
server_jvm -> worker_matching_jvm -> kernel_jvm
```

## Public Boundary

`com.xa.mass.kernel.pacer.KernelPacerRuntime` is the only externally supported
production entry. Its `assemble(...)` method accepts the bounded mechanical
owners needed by the two production applications plus one of its four checked
`PolicyPreset` values: `DEFAULT`, `SERVICEABILITY_DEFAULT`, `SCENARIO_LAB`, or
`RUNTIME_BOUNDARY_PROOF`. The Runtime
owns fixed policy selection, one immutable HOT eligibility floor when
periodic Serviceability is enabled, thread startup/rollback, reverse bounded shutdown
and aggregate failure state. It never closes the supplied owners.

Implementation is grouped by mechanism instead of flattened into one package:

```text
com.xa.mass.kernel.pacer
├─ KernelPacerRuntime
├─ KernelPacerPolicyConfig
├─ result
│  ├─ ResultConvergenceRuntime
│  └─ package-private Result lanes, policies and Application
└─ dispatch
   ├─ DispatchConvergenceRuntime
   └─ package-private Main Scheduler, fixed producers, policies and Application
```

The two `*ConvergenceRuntime` types are narrow module-internal lifecycle
bridges made public only because Java package visibility does not cross a
parent/subpackage boundary. No module outside `kernel_pacer_jvm` may import
them; architecture tests enforce that Server and all other production code use
only `KernelPacerRuntime`. They are not independent runtimes, extension
points, registries or deployment entries. All remaining implementation types
stay package-private in their matching source directories, so IDE package/path
validation remains exact without flattening the policy classes.

## Fixed Applications

The runtime starts Result Convergence before Dispatch Convergence and stops
them in reverse order with one shared deadline. The
[assembly Owner](doc/application-assembly.md) defines configuration, capacity,
producer scheduling and startup/rollback; keep those values there.

| Application | Responsibility | Detailed Owner |
| --- | --- | --- |
| Result Convergence | Parse and group four fixed evidence classes, then publish TaskItem/Worker semantic events | [Result routing](doc/result/result-routing-scheduling.md) |
| Dispatch Convergence | Share Main's bounded root input with initialization, refill, Task dispatch and optional Serviceability | [Assignment and Dispatch](doc/dispatch/assignment-dispatch-scheduling.md) |

Main reads Task state and descriptors once for its bounded round. Producers do
not accumulate pending snapshots or discover outside those roots. Refill obtains
candidate generations before Matching qualification and independently recycles
old candidates; Item dispatch independently
uses fixed Matching functions and verifies candidates before exact claim and
Command publication. Matching owns query interpretation and resources; Pacer
owns policy and correlation.

Result Policy stops at finite semantic event ports. The mechanical events own
ordered Result/Score/lease operations and their partial-failure semantics.
Result and Dispatch keep separate capacity and lifecycle ownership. The
[cross-owner mainline](../doc/kernel/scheduling-overview.md) explains the two
candidate paths, independent scheduling truth and liveness boundary.

## Code And Proof

Start at [KernelPacerRuntime](src/main/java/com/xa/mass/kernel/pacer/KernelPacerRuntime.java).
Use the [mainline code/proof pointers](../doc/kernel/scheduling-overview.md#production-and-proof-pointers)
to follow one handoff, then read its detailed Owner above. Policy and
[boundary tests](src/test/java/com/xa/mass/kernel/pacer/KernelPacerModuleBoundaryTest.java)
do not replace real Redis or cross-process proof; [TESTING](../TESTING.md) owns
the required commands and selection.

Spring assembly stays in Server; Redis providers and the ResultContext codec
stay in Kernel; synchronous query functions, Pool maintenance and facts/index
resources stay in Matching. This module adds no Pacer SPI, dynamic registry,
network API, Redis owner or alternate runtime.

Build:

```text
./gradlew :kernel_pacer_jvm:build
```
