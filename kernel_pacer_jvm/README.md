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

Startup order:

```text
Result Convergence
-> Dispatch Convergence
```

Result Convergence owns exactly four fixed lane definitions:
`TASK_SUCCESS`, `TASK_FAILURE`, `NETWORK_EVIDENCE`, and `TASK_OBSERVATION` in every preset. One platform
coordinator schedules at most ten bounded Batches by the smallest
`inflight / targetConcurrency` ratio; priority only breaks equal ratios. Every
non-empty Batch runs on a named virtual thread. Production target/max values
are SUCCESS `6/10`, FAILURE `3/10`, Network Evidence `1/1`, and Observation `1/10`. Task lanes may
borrow idle capacity while Network Evidence remains single-flight.
These values are internal constants, not configuration or a public lane model.
Server validates exact Report event contracts and producers and selects the Task lane; Task
policy does not read `DeliveryReport.diagnosticCode`. Result policies stop after
strict Report parsing, bounded grouping and publication to the fixed
`TaskItemResultEvents`, `WorkerExecutionResultEvents`, and
`WorkerServiceabilityEvents` ports. They do not import Task/TaskItem/Worker
score owners or expose raw Worker lease scores. The default event Mechanisms in
`kernel_jvm` implement the current store, promotion, exact release and
Serviceability transitions. Observations retain each Item's maximum state target
and latest content by tag then reported milliseconds; their Mechanism never
touches Worker leases. All four lanes share this finite lifecycle.

Dispatch Convergence owns one Main Scheduler and three fixed single-flight
Resource Producers. The Main Scheduler reads one bounded descending
`taskId -> opaque score` map, asks the Task Score Owner for its INITIAL subset,
and loads Descriptors once for only the NORMAL complement. It explicitly plans
the complete root input for Initialization,
Task Dispatch and optional ordered WorkerGroup Serviceability. There is no Task
Score point recheck; exact downstream transitions reject stale observations.
A busy Producer skips the current source snapshot without storing a pending
hint.

Dispatch resolves one bounded Task binding batch through Matching, then owns
HOT observation, initial hold, membership recheck, exact confirmation, Item claim
and delivery. Default ANY/IDs use identity selection; other queries consume the
bound Rule index. Pacer does not load Rules, Properties or interpret conditions.
Package-private mechanisms protect exact Score fences and claim/Command ordering.
Producers discover only resources under the Main Scheduler's root identities;
Serviceability retains sweep hints only for Groups in that Task batch.

The production load model is intentionally a small bounded active Task set,
many TaskItems per Task, and many Workers inside a finite WorkerGroup set. The
vertical Item acquisition/lease/claim/delivery/result chain is the primary
backpressure surface. The Pacer targets work-conserving convergence rather than
per-Task fairness: fully utilized Workers are normal backpressure, while a
bounded scan, exact CAS or bounded index take may add short convergence delay.
Persistently due work and persistently idle compatible Workers failing to form
an assignment across repeated eligible rounds is a liveness defect. A full Task
page by itself proves neither starvation nor sufficient capacity. Massive
active Task/WorkerGroup counts, multi-tenant fairness and sharding are outside
this Pacer contract.

Shutdown uses one shared deadline in strict reverse order. `DEFAULT` keeps
Serviceability disabled, `SERVICEABILITY_DEFAULT` enables it at the normal
production cadence, `SCENARIO_LAB` is the checked fast local-Lab policy, and
`RUNTIME_BOUNDARY_PROOF` is the deterministic boundary-proof policy. Server
accepts that proof-only preset only with a `test_*` Redis scope. These presets
compose existing configuration value objects; there is no Java policy file,
per-field runtime tuning, Pacer SPI, dynamic registry, network API, Redis owner
or fallback path.

Spring assembly belongs to `server_jvm`. ResultContextCodec and mechanical Redis
providers remain in `kernel_jvm`; facts, bindings and synchronous Rule index
operations remain in `worker_matching_jvm`;
dispatch-only mechanisms remain package-private here.

Build:

```text
./gradlew :kernel_pacer_jvm:build
```
