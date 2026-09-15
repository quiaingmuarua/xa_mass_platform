# XA Mass Kernel

Status: current cross-module architecture and repository entrypoint.

XA Mass schedules TaskItems onto Workers and observes their execution through
explicit, independent owners. The supported load shape is a small bounded
active Task set, many Items per Task, and many Workers inside finite Groups.

## Authority And Dispatch

| Owner | Responsibility |
| --- | --- |
| Kernel | Task/TaskItem/Worker scheduling truth, selection, lease, claim, retry, recovery and finality |
| Worker Matching | Worker/Platform Properties, fixed Rule Handlers, Task bindings, materialized eligibility indexes and shared inventory |
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
API -> Server binds Task to a shared Matching Rule, then writes Kernel Task/Items
    -> Pacer acquires 1-second candidate leases; Matching qualifies and stocks the same fences
    -> Item selectors consume held stock; Kernel checks exact clean fences
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
Default Rule ANY/explicit IDs can use identity without facts; named Rules require
indexed eligibility. Polling currently has no Properties reporting path and uses
default identity selection for new Workers. Live facts update indexes without
re-Prepare. Reporting is lossy.
After actual Worker or Platform facts changes, Server requests best-effort
candidate invalidation through the Score Owner. Final exact confirmation rejects invalidated holds without cache cleanup. Confirmed
work continues; the [HOT lease protocol](kernel_jvm/doc/score/worker-hot-acquire-lease-protocol.md)
owns the separate commits and expiry limits.
WorkerGroup event declarations likewise do not prove that handlers are loaded;
process-local event snapshots report the actual immutable assembly.

## Active Surfaces

`scenarios/` hosts real business-shaped workloads that validate XA Mass. SMS and
Messages own Controllers, business state and result interpretation as Spring
configuration libraries. The aim is inexpensive integration, realistic execution
and failures exposed under load. Let demonstrated business and operational needs
drive later graduation into independently deployed products.

The first such workload, [SMS Reception](scenarios/sms-reception-jvm/README.md),
uses a separate business module and a page in the unified console, plus the SMS scene of the
independently launched [Worker Simulator](worker_simulator_jvm/README.md#sms-scenario).
Lab, SMS and Messages share its Java Worker management and local console. The
[Server Boot composition](server_boot_jvm/README.md) imports platform and
both business scenarios into one Server; `preview` enables both APIs and jobs.
SMS calls the
existing Server services and shares their Owner instances.
It owns listening orders and SMS routing; Kernel retains assignment and scheduling.

[Message Campaigns](scenarios/message-campaigns-jvm/README.md) adds finite Rule-bound
batches and later delivery, read and repeated reply observations. The
[shared Preview](distribution/server/PREVIEW.md) runs both scenarios on the
same Server, Adapter and Worker pool. Scenarios remain independent libraries using
Server application services; the [coexistence proof](integrations/scenario-coexistence/README.md)
exercises their shared runtime and preserves each scenario's business semantics.

| Surface | Entry and owner |
| --- | --- |
| Kernel mechanisms | [kernel_jvm](kernel_jvm/README.md): stable contracts, Redis providers, Scores, resources and bounded identity ports |
| Kernel policy | [kernel_pacer_jvm](kernel_pacer_jvm/README.md): fixed Result/Dispatch Convergence behind one KernelPacerRuntime |
| Matching | [worker_matching_jvm](worker_matching_jvm/README.md): persistent facts/bindings and bounded Rule index queries |
| Runtime API | [server_jvm](server_jvm/README.md): Spring API and provider/lifecycle assembly |
| Executable composition | [server_boot_jvm](server_boot_jvm/README.md): sole Boot entry and platform/preview composition |
| Delivery and execution | [transport](transport/README.md): shared contract/Core, Netty Adapter, Java and Android Workers |
| JVM simulation | [worker_simulator_jvm](worker_simulator_jvm/README.md): one independent Host for Lab fixtures, SMS numbers and message recipients |
| Android | [xa-android](xa-android/README.md): capabilities, local Host controls and demo assembly |
| Proof clients | [TESTING](TESTING.md): Owner, boundary, Worker, Android and distribution claims; [Dynamic Matching](integrations/worker-dynamic-matching/README.md) observes live facts during execution |
| Frontend | [frontend](frontend/README.md): shared console for Runtime observation, SMS, Messages, finite Task files, Direct Debug and references |
| Releases | [Server Runtime](distribution/server/README.md) and [Worker SDK](distribution/worker-sdk/README.md): packaging of existing owners |

## Runtime And Deployment

The [Boot executable](server_boot_jvm/README.md) starts Server configuration,
which assembles one KernelPacerRuntime and the Matching catalog. Only one
Server per Kernel Redis scope may enable the Pacer lifecycle; there is no
distributed Pacer leader election. Profile selects assembly and policy preset;
Redis scope selects the data boundary. The executable owns all production
application YAML; Server binds the values and owns resource lifecycle. Provider and lifecycle details belong
to the Server and Pacer documents.

The Server Runtime ZIP contains the Boot Server and compiled frontend and
requires external Redis and Java 21. Worker SDKs are published separately.
Worker Simulator has an independent process lifecycle and one `--config` entry
for Lab, SMS and Messages capabilities over shared inventory. Built-in Groups need
only Group selection; optional configuration, including initialization templates,
resolves to complete settings once. Its configuration examples ship in the install distribution and Scenario Preview;
the production Runtime ZIP excludes it. Server never starts Worker processes. AgentForge consumes release artifacts
and public APIs instead of copying source modules.

For local work, `python run_local_runtime.py` builds the frontend and starts
the Scenario Lab. `--profile agentforge` selects the clean Server/Adapter
preset without Worker Simulator. `--profile preview` builds and starts both business
scenarios and their shared Simulator through the [Preview launcher](distribution/server/PREVIEW.md).
Profile coordinates and commands are documented
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

All Tasks bind to a Matching Rule and resolved refill targets before Kernel
creation. Main prepares NORMAL bindings once for independent refill and dispatch.
**Pacer acquires the 1-second candidate lease before Matching reads eligibility.**
Matching qualifies only the leased IDs and admits their original fences and deadlines
to shared Group/Rule inventory. Matching time and stock waiting share that second;
unmatched leases expire naturally. TaskItems only consume stock. Kernel retains HOT, Score and exact confirmation/
claim authority; no Task-private candidate cache or Item-triggered supply exists.
