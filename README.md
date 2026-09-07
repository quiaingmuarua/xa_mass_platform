# XA Mass Kernel

Status: current cross-module architecture and repository entrypoint.

XA Mass schedules TaskItems onto Workers and observes their execution through
explicit, independent owners. The supported load shape is a small bounded
active Task set, many Items per Task, and many Workers inside finite Groups.

## Authority And Dispatch

| Owner | Responsibility |
| --- | --- |
| Kernel | Task/TaskItem/Worker scheduling truth, selection, lease, claim, retry, recovery and finality |
| Worker Matching | Worker/Platform Properties, PRECOMPUTED Candidate Rules and ordered filtering of a bounded held pool |
| Server | Runtime API, validation, identity/Binding, cross-owner use cases, routing, correlation and assembly |
| Transport Adapter | Current verified routes, delivery and Adapter-local events |
| Transport Worker | Local Event Name resolution, execution and Result evidence |

Only Kernel decides whether an assignment should exist. Server routes an
already-owned command or result; Transport delivers it and invokes a local
handler. Neither Server nor Transport selects replacement Workers or decides
scheduling eligibility.

## Main Paths

```text
TASK
API -> Server coordinates Matching facts/Rules and Kernel Task/Item writes
    -> PRECOMPUTED: Kernel holds a bounded pool; Matching filters into Candidate Cache
       ON_DEMAND: Kernel acquires normalized explicit Worker IDs or ANY
    -> Kernel renews the Worker fence, claims the Item and publishes a Command
    -> Server -> Adapter/point delivery -> Worker -> Result evidence
    -> Server routes TASK evidence -> Kernel Result convergence

DIRECT_CALL
caller-selected target -> Server admission and bounded correlation
    -> Adapter-local FIFO or non-overwriting Worker mailbox offer
    -> the same Transport path -> Server SYSTEM waiter
```

DIRECT_CALL is best-effort and provides no scheduling exclusion, drain,
preemption, reliable delivery or idempotency. TASK publication may replace an
unconsumed Direct Command. Events are resolved by the endpoint's immutable
Handler map; the Server does not maintain an execution whitelist.

Result observation and TaskItem finality remain separate. Storing a successful
Result and requesting finality are ordered Owner calls, not a transaction or
an unconditional repair guarantee. The detailed failure windows are in
[Result storage](kernel_jvm/doc/runtime-redis/task-result-runtime-redis-shape.md).

Worker Prepare establishes Server-owned identity/Binding, replaces canonical
Matching Properties and initializes minimal Kernel resources. Transparent
reconnect sends identity only. Adapter Route evidence may feed the optional
Kernel Serviceability policy, but Route and local Properties snapshots are
observations, not scheduling truth. Adapter Properties reporting and baseline calibration follow the
[connection Owner](transport/netty-adapter/README.md) and
[Worker Core](transport/worker-core/README.md) contracts. These projections never
update canonical Matching facts.
WorkerGroup event declarations likewise do not prove that handlers are loaded;
process-local event snapshots report the actual immutable assembly.

## Active Surfaces

| Surface | Entry and owner |
| --- | --- |
| Kernel mechanisms | [kernel_jvm](kernel_jvm/README.md): stable contracts, Redis providers, Scores, resources and Candidate Cache |
| Kernel policy | [kernel_pacer_jvm](kernel_pacer_jvm/README.md): fixed Result/Dispatch Convergence behind one KernelPacerRuntime |
| Matching | [worker_matching_jvm](worker_matching_jvm/README.md): persistent facts/Rules and bounded PRECOMPUTED Demand consumer |
| Runtime API | [server_jvm](server_jvm/README.md): Spring API and provider/lifecycle assembly |
| Delivery and execution | [transport](transport/README.md): shared contract/Core, Netty Adapter, Java and Android Workers |
| JVM Lab | [scenario_workers_jvm](scenario_workers_jvm/README.md): independently launched finite Worker Host and local mutation fixtures |
| Android | [xa-android](xa-android/README.md): capabilities, local Host controls and demo assembly |
| Proof clients | [TESTING](TESTING.md): Owner, boundary, Worker, Android and distribution claims |
| Frontend | [frontend](frontend/README.md): Runtime observation, finite Task files, Direct Debug and API/architecture references |
| Releases | [Server Runtime](distribution/server/README.md) and [Worker SDK](distribution/worker-sdk/README.md): packaging of existing owners |

## Runtime And Deployment

Server assembles one KernelPacerRuntime and the Matching runtime. Only one
Server per Kernel Redis scope may enable the Pacer lifecycle; there is no
distributed Pacer leader election. Profile selects assembly and policy preset;
Redis scope selects the data boundary. Provider and lifecycle details belong
to the Server and Pacer documents.

The Server Runtime ZIP contains the Boot Server and compiled frontend and
requires external Redis and Java 21. Worker SDKs are published separately.
Scenario Host is a source-checkout Lab with an independent process lifecycle;
Server never starts Worker processes. AgentForge consumes release artifacts
and public APIs instead of copying source modules.

For local work, `python run_local_runtime.py` builds the frontend and starts
the Scenario Lab. `--profile agentforge` selects the clean Server/Adapter
preset without Scenario Host. Profile coordinates and commands are documented
by [Server](server_jvm/README.md#run) and [distribution](distribution/server/README.md).

The [public UI demo](https://frontend-kylerrun-s-projects.vercel.app) uses Mock
data. Real Runtime observation requires a running Server; its live API
reference is `/scalar`, while the demo's static reference cannot send requests.

## Reading Path

1. [Documentation Index](doc/README.md) for the owner map.
2. [Java Kernel Authority](doc/kernel/README.md) for mechanical and policy contracts.
3. [Proof Lanes](TESTING.md) for selection, prerequisites and commands.
4. The relevant module README, then its detailed Owner document and source.

[AGENTS.md](AGENTS.md) governs changes. The
[human architecture overview](frontend/public/overview.htm) is a visual
projection of these boundaries. Current code and named proof evidence take
precedence over summaries and historical tags.
