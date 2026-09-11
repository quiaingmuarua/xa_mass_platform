# XA Mass Kernel

Status: current cross-module architecture and repository entrypoint.

XA Mass schedules TaskItems onto Workers and observes their execution through
explicit, independent owners. The supported load shape is a small bounded
active Task set, many Items per Task, and many Workers inside finite Groups.

## Authority And Dispatch

| Owner | Responsibility |
| --- | --- |
| Kernel | Task/TaskItem/Worker scheduling truth, selection, lease, claim, retry, recovery and finality |
| Worker Matching | Worker/Platform Properties, property-bound indexes and take time (currently country), PRECOMPUTED Candidate Rules and ordered filtering of a bounded held pool |
| Server | Runtime API, validation, external identity, Endpoint configuration, cross-owner use cases, routing, correlation and assembly |
| Transport Adapter | Current verified routes, delivery and Adapter-local events |
| Transport Worker | Local Event Name resolution, execution and Result evidence |

Only Kernel decides whether an assignment should exist. Server routes an
already-owned command or result; Transport delivers it and invokes a local
handler. Neither Server nor Transport selects replacement Workers or decides
scheduling eligibility.

## Main Paths

```text
TASK
API -> Server coordinates Matching Rules and Kernel Task/Item writes
    -> PRECOMPUTED: Kernel holds a bounded pool; Matching filters into Candidate Cache
       ON_DEMAND: one workerSelector binding-to-parameters Map; explicit IDs, Matching indexed identities, or ANY
    -> Kernel confirms the Worker hold, claims the Item and publishes a Command
    -> Server -> Adapter/point delivery -> Worker -> Result evidence
    -> Server routes TASK evidence -> Kernel Result convergence

DIRECT_CALL
caller-selected target -> Server admission and bounded correlation
    -> Adapter-local FIFO or non-overwriting Worker mailbox offer
    -> the same Transport path -> Server Direct Call waiter
```

DIRECT_CALL is best-effort and provides no scheduling exclusion, drain,
preemption, reliable delivery or idempotency. TASK publication may replace an
unconsumed Direct Command. Events are resolved by the endpoint's immutable
Handler map; the Server does not maintain an execution whitelist.

Result observation and TaskItem finality remain separate. Storing a successful
Result and requesting finality are ordered Owner calls, not a transaction or
an unconditional repair guarantee. The detailed failure windows are in
[Result storage](kernel_jvm/doc/runtime-redis/task-result-runtime-redis-shape.md).

RPC Handlers remain compatible. A Handler may retain a Reporter after execution
and send later observations for the same Item. Kernel advances generic terminal
state: TERMINAL ends scheduling, not business-state changes. A retained Item may
continue accepting valid monotonic observations after Task completion or closure,
without reopening scheduling or touching the original Worker lease;
Server defines business names and exposes state and latest content separately.
The [shared Worker SDK](transport/worker-core/README.md#later-task-outcome-observations)
provides this TRACKED capability without a Task mode or new creation parameter.

Worker Prepare resolves Server-owned external identity, then establishes Kernel
Binding and cold Score membership. Valid network observations request activation. Its Properties input supplies registration coordinates
only; it does not create or refresh Matching facts. Transparent
reconnect sends identity only. Adapter Route evidence feeds Kernel
Serviceability best-effort, but Route and local Properties snapshots are
observations, not scheduling truth. Adapter Properties reporting and baseline calibration follow the
[connection Owner](transport/netty-adapter/README.md) and
[Worker Core](transport/worker-core/README.md) contracts. After installing a
complete observation, Adapter offers one SYSTEM Report; Server validates its
producer, Binding and Group in one delivery reception use case before creating
or replacing Matching-owned Worker facts. Every upstream observation replaces
the complete Worker Map; independent Platform Properties management never
patches that Map. Before the first valid observation, identity may exist without facts:
PRECOMPUTED skips that Worker while ON_DEMAND ANY/explicit IDs can use its identity;
country selection requires indexed facts. Polling
currently has no Properties reporting path and uses ON_DEMAND for new Workers.
New Matching Demands read observed facts without re-Prepare. Reporting is lossy.
After actual Worker or Platform facts changes, Server requests best-effort
candidate invalidation through the Score Owner. Existing Candidate entries
remain, while final exact confirmation rejects invalidated holds. Confirmed
work continues; the [HOT lease protocol](kernel_jvm/doc/score/worker-hot-acquire-lease-protocol.md)
owns the separate commits and expiry limits.
WorkerGroup event declarations likewise do not prove that handlers are loaded;
process-local event snapshots report the actual immutable assembly.

## Active Surfaces

`products/` currently hosts business-shaped workloads that validate XA Mass.
The aim is inexpensive integration, realistic execution and failures exposed
under load. The directory name does not imply a mature commercial product or
require separate deployment, infrastructure or a public product SDK.
Let demonstrated business and operational needs drive later extraction.

The first such workload, [SMS Reception](products/sms-reception/README.md),
uses a separate business module and a page in the unified console, plus the SMS scene of the
independently launched [Worker Simulator](worker_simulator_jvm/README.md#sms-scenario).
Lab and SMS share its Java Worker management and local console. The distribution imports platform and SMS configuration into one Server;
the `sms-reception` profile enables product routes and jobs. SMS calls the
existing Server services and shares their Owner instances.
It owns listening orders and SMS routing; Kernel retains assignment and scheduling.

[Message Campaigns](products/message-campaigns/README.md) adds finite PRECOMPUTED
batches and later delivery, read and repeated reply observations. The
[shared Preview](distribution/product-preview/README.md) runs both products on the
same Server, Adapter and Worker pool. Products remain independent libraries using
Server application services; the [coexistence proof](integrations/product-coexistence/README.md)
exercises their shared runtime and preserves each product's business semantics.

| Surface | Entry and owner |
| --- | --- |
| Kernel mechanisms | [kernel_jvm](kernel_jvm/README.md): stable contracts, Redis providers, Scores, resources and Candidate Cache |
| Kernel policy | [kernel_pacer_jvm](kernel_pacer_jvm/README.md): fixed Result/Dispatch Convergence behind one KernelPacerRuntime |
| Matching | [worker_matching_jvm](worker_matching_jvm/README.md): persistent facts/Rules and bounded PRECOMPUTED Demand consumer |
| Runtime API | [server_jvm](server_jvm/README.md): Spring API and provider/lifecycle assembly |
| Delivery and execution | [transport](transport/README.md): shared contract/Core, Netty Adapter, Java and Android Workers |
| JVM simulation | [worker_simulator_jvm](worker_simulator_jvm/README.md): one independent Host for Lab fixtures, SMS numbers and message recipients |
| Android | [xa-android](xa-android/README.md): capabilities, local Host controls and demo assembly |
| Proof clients | [TESTING](TESTING.md): Owner, boundary, Worker, Android and distribution claims; [Dynamic Matching](integrations/worker-dynamic-matching/README.md) observes live facts during execution |
| Frontend | [frontend](frontend/README.md): shared console for Runtime observation, SMS, Messages, finite Task files, Direct Debug and references |
| Releases | [Server Runtime](distribution/server/README.md) and [Worker SDK](distribution/worker-sdk/README.md): packaging of existing owners |

## Runtime And Deployment

The [distribution entry](distribution/server/README.md) starts Server configuration,
which assembles one KernelPacerRuntime and the Matching runtime. Only one
Server per Kernel Redis scope may enable the Pacer lifecycle; there is no
distributed Pacer leader election. Profile selects assembly and policy preset;
Redis scope selects the data boundary. Provider and lifecycle details belong
to the Server and Pacer documents.

The Server Runtime ZIP contains the Boot Server and compiled frontend and
requires external Redis and Java 21. Worker SDKs are published separately.
Worker Simulator has an independent process lifecycle and one `--config` entry
for Lab, SMS and Messages capabilities over shared inventory. Built-in Groups need
only Group selection; optional configuration, including initialization templates,
resolves to complete settings once. Its configuration examples ship in the install distribution and Product Preview;
the production Runtime ZIP excludes it. Server never starts Worker processes. AgentForge consumes release artifacts
and public APIs instead of copying source modules.

For local work, `python run_local_runtime.py` builds the frontend and starts
the Scenario Lab. `--profile agentforge` selects the clean Server/Adapter
preset without Worker Simulator. Profile coordinates and commands are documented
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
