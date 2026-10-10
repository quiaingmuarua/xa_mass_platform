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

Check HEAD, the worktree and requested scope first. Choose the route below;
reuse established session evidence and refresh only what changed.

| Task | Read | Stop expanding when |
| --- | --- | --- |
| First overall handoff | This summary, the complete [behavior mainline](doc/kernel/scheduling-overview.md), then Owner/proof navigation | State ownership, main calls, failure boundaries and evidence locations are clear. Do not open every Owner, integration or historical record. |
| Cross-owner mechanism change | This summary and authority map; the three [overview chapters](doc/kernel/scheduling-overview.md#system-behavior-model); affected Dispatch, Results, Scale or [Delivery](doc/kernel/worker-delivery-dispatch.md) sections; applicable Owners, callers, assembly and proof | Every affected input, state writer, consumer, call order and failure window has been checked. Follow adjacent modules only where they participate in that chain. |
| Local change | The responsible Owner's applicable contract, the change site and its direct callers/consumers, then relevant validation | The local effect is understood. Escalate to the cross-owner route if state, authority or lifecycle propagation crosses that boundary. A local prose/link change does not require the scheduling model or runtime proof. |

The three overview chapters are **System Behavior Model**, **Convergence Sources
And State** and **Behavior Domains And Owners**; they end before **Independent
Scheduling Truth**. The full mainline is required for an overall handoff, not
as a precondition for every new task.

Use the module map below or [Kernel index](doc/kernel/README.md) to locate an
Owner, and [code/proof pointers](doc/kernel/scheduling-overview.md#production-and-proof-pointers)
to trace its implementation. The complete applicable contract includes the
relevant chapters and their mandatory linked constraints, not every chapter in
the file. [Proof Registry](doc/testing/proof-registry.md) defines each lane's
claims; [TESTING](TESTING.md) owns commands and selection. Open only the affected
lane and scenario. These indexes are alternative navigation surfaces, not a
serial reading checklist.

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
workloads: number leases/latest SMS results, message delivery/later receipts, and
one-shot lookups with assignment-window observations. Each owns its business
state and assertions; these validation surfaces do not commit to separate product
deployments. [Boot](server_boot_jvm/README.md#platform-and-preview) owns their
shared Preview assembly, and [Coexistence](integrations/scenario-coexistence/README.md)
owns the shared-Worker business proof. The independent
[Worker Simulator](worker_simulator_jvm/README.md) and
[Android Host](xa-android/README.md) exercise real Worker SDK capabilities and lifecycle.

[Projects](server_jvm/README.md#profile-projects-and-managed-tasks) provide
business attribution, configured Group associations and managed Call entrypoints.
They do not partition Kernel scheduling or Matching stock. Read scenario
workloads after the platform mainline, so business state remains distinct from
scheduling and delivery truth.

## Runtime And Deployment

Use [Boot](server_boot_jvm/README.md) for main, profiles and page/Scenario assembly;
[Server Distribution](distribution/server/README.md) for artifacts, prerequisites
and delivery commands; and [Preview](distribution/server/PREVIEW.md) for the
Server/Simulator launcher. Worker SDKs are [published separately](distribution/worker-sdk/README.md).
Server never starts Worker processes. AgentForge consumes release artifacts and
public APIs. Within one Redis scope, only one Server may enable Pacer; the
[Pacer lifecycle contract](kernel_pacer_jvm/doc/application-assembly.md#lifecycle) defines this boundary.

For local work, `python run_local_runtime.py` starts the Scenario Lab;
`--profile preview` starts the business Preview and `--profile agentforge` selects
the clean Server/Adapter preset. The linked delivery documents own the complete
build, configuration and launch procedures.

The [public UI demo](https://frontend-kylerrun-s-projects.vercel.app) uses Mock
data. Real Runtime observation requires a running Server; its live API
reference is `/scalar`, while the demo's static reference cannot send requests.

The [human architecture overview](frontend/public/overview.htm) is a visual
projection of the same behavior model and Owner boundaries.
