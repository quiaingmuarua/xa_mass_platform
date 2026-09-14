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

## Candidate Selection

```text
NORMAL RUNNING Tasks -> one prepared Binding batch
  -> refill: shared target MAX -> deficit -> source -> Kernel initial hold
      -> current membership/projection -> Matching shared inventory
  -> dispatch: due Item queries -> local destructive take
      -> current Endpoint/Group -> Worker exact confirm -> Item exact claim
```

Matching owns query semantics and shared stock per Group/Rule. Kernel sees Task IDs
and opaque held identities. No Task-private candidate cache or quota exists.
Refill takes no Item input. All selectors, including ANY and explicit IDs, consume
inventory; a miss leaves the Item due without a source query or fresh hold.

Refill runs at a 50ms completion-relative interval. It attempts at most 100 holds
per Eligibility, 1000 per round; capacity and query page rotation stay Matching-owned.
Kernel's narrow `InitialHold` port supports Group ANY and at most 100 explicit
identities, observes HOT with the preset floor and requests a 5-second exact hold.
It returns the Owner's opaque fence and cleanup deadline. Post-hold projection
must be read because acquisition clears dirty. Unused/rejected holds expire.

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
records source/refill command costs. Counts and take are local. Endpoint HMGET,
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
| source, projection or initial hold failure | refill Producer backoff; partial holds expire |
| changed membership, dirty or competing exact score | candidate cannot pass the required fence |
| unused hold | expires naturally; no compensation |
| restart | local stock is lost; facts/bindings persist and normal refill acquires new holds |

There is no Score/facts transaction, ACK, replay, repair scan or guarantee that
lost Properties evidence eventually arrives. Rules never couple Task lifecycles.
