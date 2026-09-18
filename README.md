# XA Mass Kernel

Status: current cross-module architecture and repository entrypoint.

XA Mass schedules TaskItems onto Workers and observes their execution through
explicit, independent owners. The supported load shape is a small bounded
active Task set, many Items per Task, and many Workers inside finite Groups.

## Authority And Dispatch

| Owner | Responsibility |
| --- | --- |
| Kernel | Task/TaskItem/Worker scheduling truth, selection, lease, claim, retry, recovery and finality |
| Worker Matching | Worker/Platform Properties, fixed query functions, indexes, Pool maintenance and shared candidate stock |
| Server | Runtime API, validation, external identity, Endpoint configuration, cross-owner use cases, routing, correlation and assembly |
| Transport Adapter | Current verified routes, delivery and Adapter-local events |
| Transport Worker | Local Event Name resolution, execution and Result evidence |

Only Kernel decides whether an assignment should exist. Server routes an
already-owned command or result; Transport delivers it and invokes a local
handler. Neither Server nor Transport selects replacement Workers or decides
scheduling eligibility.

## Main Paths

```text
TASK ADMISSION
API -> Server asks Matching to validate optional Pool supply and explicit Item queries
    -> Kernel stores the complete Task descriptor and Items

POOL SUPPLY (independent of Item dispatch)
Main's NORMAL Task descriptors -> Pacer groups supply declarations
    -> Pacer acquires short candidate leases before Matching qualification
    -> Matching admits qualified candidates into shared Group/Pool stock

ITEM DISPATCH
Main's NORMAL Task descriptors -> due Items -> Matching executes each Item's query
    -> Pool functions consume held stock; Identity/Phone functions return identity hints
    -> Kernel exact-transfers Pool fences or directly acquires current Worker execution holds
    -> Kernel claims the Item and publishes a Command
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

A Handler may retain a Reporter after execution
and send later observations for the same Item. Kernel advances generic terminal
state: TERMINAL ends scheduling, not business-state changes. A retained Item may
continue accepting valid monotonic observations after Task completion or closure,
without reopening scheduling or touching the original Worker lease;
Server defines business names and exposes state and latest content separately.
The [shared Worker SDK](transport/worker-core/README.md#later-task-outcome-observations)
provides this capability without a Task mode or new creation parameter.

Worker Prepare establishes identity, Binding and cold Score membership; it does
not create Matching facts or prove connectivity. Facts arrive through verified
Adapter observations and Server admission. Matching updates facts and enabled
indexes, while Server separately requests best-effort candidate invalidation.
Connection state, facts and scheduling eligibility have independent owners.
WorkerGroup event declarations likewise do not prove that handlers are loaded.
The [delivery boundary](doc/kernel/worker-delivery-dispatch.md) follows these
handoffs; the [Matching Owner](worker_matching_jvm/README.md) defines which query
functions require facts, indexes or Pool stock.

Task supply declarations and Item queries are independent. Empty supply is
valid: a Task can consume shared stock supplied by another Task or use a direct
identity query. Item queries never trigger refill. The
[scheduling mainline](doc/kernel/scheduling-overview.md) connects these branches
to production code, exact admission and representative proof.

## Active Surfaces

[SMS Reception](scenarios/sms-reception-jvm/README.md) and
[Message Campaigns](scenarios/message-campaigns-jvm/README.md) are independent
Spring configuration libraries consuming Server application services. SMS owns
listening orders; Messages presents the messages Project's finite Tasks, with
configuration in Task descriptors, counts observed from Item Scores and later
receipts read from Results.
The independent [Worker Simulator](worker_simulator_jvm/README.md) owns simulated
device facts and executes through the real Worker SDK.

[Server Boot](server_boot_jvm/README.md) enables both scenarios in `preview`,
sharing one platform resource set. The [Preview launcher](distribution/server/PREVIEW.md)
starts Server and Simulator as separate processes; the
[coexistence proof](integrations/scenario-coexistence/README.md) checks their
business behavior over shared Workers. Read these workloads after the platform
mainline, so business state remains distinct from scheduling and delivery truth.

| Surface | Entry and owner |
| --- | --- |
| Kernel mechanisms | [kernel_jvm](kernel_jvm/README.md): stable contracts, Redis providers, Scores, resources and bounded identity ports |
| Kernel policy | [kernel_pacer_jvm](kernel_pacer_jvm/README.md): fixed Result/Dispatch Convergence behind one KernelPacerRuntime |
| Matching | [worker_matching_jvm](worker_matching_jvm/README.md): facts, indexes, fixed query functions and shared Pools |
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
for Lab, SMS and Messages over shared inventory. Configuration examples ship in
its install distribution and Scenario Preview; the production Runtime ZIP
excludes it. Server never starts Worker processes. AgentForge consumes release
artifacts and public APIs instead of copying source modules.

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

1. Follow [Scheduling Mainline](doc/kernel/scheduling-overview.md) and
   [Worker Delivery Boundary](doc/kernel/worker-delivery-dispatch.md) from
   admission to execution, returned evidence and failure windows.
2. Use the [Java Kernel index](doc/kernel/README.md) and relevant module README
   to inspect the Owner, production caller and representative assertion.
3. Use [Proof Registry](doc/testing/proof-registry.md) for claims and nonclaims,
   then [TESTING](TESTING.md) for the corresponding commands and CI selection.
4. Read the SMS/Messages business Owners and their shared Preview proof.

The [Documentation Index](doc/README.md) identifies which document owns each
kind of information; configuration, storage details and scenario thresholds
stay with their respective Owners.

[AGENTS.md](AGENTS.md) governs changes. The
[human architecture overview](frontend/public/overview.htm) is a visual
projection of these boundaries. Current code and named proof evidence take
precedence over summaries and historical tags.

Task browsing and creation use profile-declared Projects. Each configured
Project/WorkerGroup pair has a managed Call Task; ordinary Tasks are finite.
Projects do not partition Kernel scheduling or Matching stock. See the
[Server Project contract](server_jvm/README.md#profile-projects-and-managed-tasks)
and [profile composition](server_boot_jvm/README.md#project-topology).
