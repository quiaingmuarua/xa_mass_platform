# Assignment-Dispatch Scheduling

Status: active Java Kernel Dispatch Convergence contract.

Detailed owners: [Initialization](task-initialization-policy.md),
[Task Dispatch](task-dispatch-pacer.md), [Matching](../../../worker_matching_jvm/README.md),
[Worker hold protocol](../../../kernel_jvm/doc/score/worker-hot-acquire-lease-protocol.md)
and [Delivery](../../../doc/kernel/worker-delivery-dispatch.md).

## Authority

One Main Scheduler supplies complete bounded input to fixed single-flight
initialization, Eligibility refill, Task dispatch and optional Serviceability
Producers. It reads NORMAL Task bindings once and shares the prepared views.
TaskItems only consume stock; they never generate refill demand or a matching job.

## Core Mechanism Change

Candidate supply has moved out of Matching: Pacer alone observes HOT, chooses
Group budgets and acquires leases. Matching may accept only the supplied IDs.
The old direct 5-second acquisition is replaced by S0 (1 second), S1 (5 seconds)
and execution S2. Explicit-ID queries filter stock; they do not target acquisition.
All three lease operations check time inside their exact-CAS Lua.

## Candidate Selection

```text
NORMAL RUNNING Tasks -> one prepared Binding batch
  -> refill: shared target MAX -> Group deficit -> Pacer HOT -> short S0
      -> Matching projection/acceptance -> Kernel exact extension S1 -> inventory
  -> dispatch: due Item queries -> local destructive take
      -> current Endpoint/Group -> Worker exact confirm -> Item exact claim
```

Matching owns query semantics and shared stock per Group/Rule. Kernel sees Task IDs
and opaque held identities. No Task-private candidate cache or quota exists.
Refill takes no Item input. All selectors, including ANY and explicit IDs, consume
inventory; a miss leaves the Item due without a source query or fresh hold.

Refill runs at a 50ms completion-relative interval. Pacer rotates positive-deficit
Groups and tries at most 100 IDs per Group, 1000 per round, independent of the
business deficit count. It alone observes due HOT with the preset floor and
acquires a 1-second S0. An empty round advances its bounded Group cursor too.

Matching reads only the offered IDs' current projections, after dirty was cleared
by acquisition. It rotates Eligibility/query acceptance, prioritizes constrained
queries before ANY and plans each ID for at most one Eligibility. The union is
renewed once to a 5-second S1. No S0 enters consumable inventory.

Pacer's invocation-local renewal capability captures the Group and original fences.
Only a unique subset of issued IDs is accepted, at most once, on the issuing thread,
before refill returns. Invalid, duplicate, late or cross-batch uses cannot call the
Score Owner. Rule Handlers never receive the capability. Plans remain local to the
call; there is no pending lease registry or periodic renewal.

This restores scheduling authority while accepting potentially longer waits for
rare predicates/explicit IDs. Matching cannot compensate by discovering better IDs
from its indexes. Unaccepted S0 and failed/ambiguous S1 expire naturally.

Main-selected INITIAL Tasks never prewarm. Closed, parked or disabled Tasks supply
no subsequent targets. A Main observation is round evidence; stopping a Task does
not retroactively cancel an in-flight refill. Old stock expires without renewal.

Dispatch preserves its 100-Item per-Task budget and actual-publication ordering
hint: unserved Tasks first, then least recently served. Within a Task, normalized
query demand sums actual requests; results pair with the existing Item order.
Each take returns at most 100 unique candidates and removes them before address
lookup, confirmation and claim. Failure never restores the candidate or refreshes
its fence. No Item request bypasses inventory or relaxes missing Rule evidence.

The [Matching Owner](../../../worker_matching_jvm/README.md#cost-failure-and-proof)
records supply/refill command costs. Counts and take are local. Endpoint HMGET,
Worker confirmation, Item claim and mailbox publication remain bounded Owner calls.
They are measured independently from refill, not asserted as a latency promise.

## Assignment Closure

Only the package-private `TaskAssignmentDispatcher` constructs claimed Commands:

```text
exact Worker confirmation (clean original score -> execution fence with dirty=1)
  -> exact ACTIVE Item claim
  -> ResultContext carrying the returned execution fence
  -> Adapter-partitioned Worker mailbox
```

Pacer treats scores as opaque evidence. It cannot decode, construct or calculate
coordinates. An exact-fence failure publishes no Command. A confirmed execution
is not revoked by later facts updates. Unused and publication-failed leases
recover through existing expiry semantics.

Task Dispatch independently stores failed Result before requesting terminal tag
5 for exhausted/expired Items, and owns pacing/idle close or park. Result routing
and subsequent outcome observations retain their separate lifecycle and commits.

## Failure Semantics

| Failure | Result |
| --- | --- |
| missing/unavailable Task binding | no assignment; failure/idle handling continues |
| invalid selector | Server rejects before Item mutation; stored invalid input fails bounded acquisition |
| HOT observation, projection, acquisition or renewal failure | refill Producer backoff; partial holds expire |
| changed membership, dirty or competing exact score | candidate cannot pass the required fence |
| unused hold | expires naturally; no compensation |
| restart | local stock is lost; facts/bindings persist and normal refill acquires new holds |

There is no Score/facts transaction, ACK, replay, repair scan or guarantee that
lost Properties evidence eventually arrives. Rules never couple Task lifecycles.
