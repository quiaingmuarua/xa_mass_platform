# XA Mass

Status: current cross-module architecture and repository entrypoint.

XA Mass is a closed-loop execution Runtime for heterogeneous, changing Workers.
It connects business work with resources under explicit scheduling and execution
authority, and uses execution feedback and external observations to update work
progress and subsequent resource selection.

The work side tracks Task/Item progress, retries, termination and later results.
The resource side tracks Worker scheduling state, facts and candidate resources.
Three behavioral domains connect these two state lines:

- **Matching** organizes supply, qualification and candidate selection for work.
- **Execution** obtains execution authority, delivers Commands and invokes Worker Handlers.
- **Convergence** interprets internal feedback and external observations to update
  work progress, resource state, facts and business/resource observations.

The supported load shape is a small bounded active Task set, many Items per
Task, and many Workers inside finite Groups.

## Reading Path

1. Read the summary above, then the complete
   [behavior model](doc/kernel/scheduling-overview.md#system-behavior-model):
   the work/resource loop, feedback sources and participating Owners.
2. Use the module map below or the [Kernel Owner index](doc/kernel/README.md)
   to find the affected contract. Follow its production caller and assembly,
   using the mainline's [code/proof pointers](doc/kernel/scheduling-overview.md#production-and-proof-pointers).
3. Use [Proof Registry](doc/testing/proof-registry.md) for claims and nonclaims,
   then [TESTING](TESTING.md) for commands and CI selection. Read the relevant
   business scenario when its workload is involved.

The [Documentation Index](doc/README.md) identifies each document's role.
[AGENTS](AGENTS.md) governs changes, including the
[evolution principles](AGENTS.md#evolution-principles). Current implementation,
authorized plans, possible directions and historical evidence have different
status; source pointers alone do not establish a passing proof.

## Authority And Dispatch

| Module | Responsibility and detailed Owner |
| --- | --- |
| [Kernel](kernel_jvm/README.md) | Mechanical scheduling state, Scores, resources, execution admission and exact claims |
| [Pacer](kernel_pacer_jvm/README.md) | Kernel scheduling policy, bounded rounds, retry/recovery decisions and lifecycle |
| [Matching](worker_matching_jvm/README.md) | Worker/Platform Properties, query functions, indexes, Pool maintenance and candidate stock |
| [Server](server_jvm/README.md) | Runtime API, validation, identity/Binding, cross-owner use cases, routing and assembly |
| [Server Boot](server_boot_jvm/README.md) | Sole production main, Boot JAR, production YAML and explicit profiles |
| [Transport](transport/README.md) | Adapter routes/delivery; Worker-local Event Handlers and execution evidence; Java and Android SDKs |
| [Frontend](frontend/README.md) | Runtime observation, scenario pages, finite Task files, Direct Debug and public Mock demo |
| Distribution | [Server Runtime and Preview](distribution/server/README.md), [Worker SDK](distribution/worker-sdk/README.md): packaging existing owners |

Matching selects bounded candidate evidence; Kernel decides whether the current
Worker and Item can be assigned. Server routes an
already-owned command or result; Transport delivers it and invokes a local
handler. Neither Server nor Transport selects replacement Workers or decides
scheduling eligibility.

## Main Paths

```text
TASK ADMISSION
API -> Server admission with Matching -> Kernel Task descriptors and Items

POOL SUPPLY (independent of Item dispatch)
Task supply declarations -> Pacer candidate generation -> Matching Pool qualification

ITEM DISPATCH
due Items -> Matching query -> Kernel execution admission and Item claim
    -> Pacer Command -> Server / Transport -> Worker
    -> feedback to the responsible Owners -> later work/resource decisions

DIRECT_CALL
caller-selected target -> Server correlation -> Adapter / Worker -> caller observation
```

The [scheduling mainline](doc/kernel/scheduling-overview.md#dispatch-mainline)
connects supply, independent Item queries and exact execution admission.
The [delivery boundary](doc/kernel/worker-delivery-dispatch.md) follows Prepare,
Command and observation handoffs, including DIRECT_CALL's best-effort limits.
[Result storage](kernel_jvm/doc/runtime-redis/task-result-runtime-redis-shape.md)
and the [Worker Reporter contract](transport/worker-core/README.md#later-task-outcome-observations)
explain independent finality, failure windows and later observations.

## Active Surfaces

[SMS Reception](scenarios/sms-reception-jvm/README.md),
[Message Campaigns](scenarios/message-campaigns-jvm/README.md) and
[App Checks](scenarios/app-checks-jvm/README.md) validate realistic business
workloads: listening orders, message delivery/later receipts, and
one-shot lookups with assignment-window observations. Their Spring configuration
libraries consume Server application services; each scenario owns its business
assertions. They are current validation surfaces, not a commitment to separate
product deployments.

[Server Boot](server_boot_jvm/README.md) enables these scenarios in `preview`,
sharing one platform resource set. The [Preview launcher](distribution/server/PREVIEW.md)
starts Server and the independent [Worker Simulator](worker_simulator_jvm/README.md)
as separate processes. The [coexistence proof](integrations/scenario-coexistence/README.md)
checks SMS and Messages over shared Workers. The Simulator and
[Android Host](xa-android/README.md) exercise the real Worker SDK, with their own
device facts, capabilities and lifecycle.

[Projects](server_jvm/README.md#profile-projects-and-managed-tasks) provide
business attribution, configured Group associations and managed Call entrypoints.
They do not partition Kernel scheduling or Matching stock. Read scenario
workloads after the platform mainline, so business state remains distinct from
scheduling and delivery truth.

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
preset without Worker Simulator. `--profile preview` builds and starts the business
scenarios and their shared Simulator through the [Preview launcher](distribution/server/PREVIEW.md).
Profile coordinates and commands are documented
by [Server](server_jvm/README.md#run) and [distribution](distribution/server/README.md).

The [public UI demo](https://frontend-kylerrun-s-projects.vercel.app) uses Mock
data. Real Runtime observation requires a running Server; its live API
reference is `/scalar`, while the demo's static reference cannot send requests.

The [human architecture overview](frontend/public/overview.htm) is a visual
projection of the same behavior model and Owner boundaries.
