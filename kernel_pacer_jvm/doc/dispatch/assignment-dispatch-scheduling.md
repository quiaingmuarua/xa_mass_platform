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

The refill Producer calls `prepareRefill` once using those prepared views. Matching's
invocation-local batch supplies a Group demand hint and accepts Group observation batches.
Pacer intersects the hint with Main's Group roots and retains its own rotation and
supply budgets. Each Group batch serves multiple Tasks' Rule demands; no per-Task
refill call or per-Worker Rule call is introduced. Server admission does not prepare
refill batches. Group hints are checked against current stock again at admission.

## Core Mechanism Change

**Pre-Matching acquisition and the subsequent inventory extension are removed.**
Pacer alone reads HOT candidates and supplies their original opaque scores.
Matching qualifies that closed batch, then Kernel acquires a 1-second inventory
lease for the accepted subset. Execution confirmation remains a separate exact
transition. Explicit-ID queries filter the supplied batch and stock, without
point acquisition. Redis-time validation remains inside each lease CAS.
The qualification-to-acquisition facts window is explicitly best-effort.

## Candidate Selection

```text
NORMAL RUNNING Tasks -> one prepared Binding batch
  -> refill: shared target MAX -> Group deficit -> Pacer read-only HOT page
      -> Matching projection/acceptance -> Kernel exact 1-second lease -> inventory
  -> dispatch: due Item queries -> local destructive take
      -> current Endpoint/Group -> Worker exact confirm -> Item exact claim
```

Matching owns query semantics and shared stock per Group/Rule. Kernel sees Task IDs
and opaque held identities. No Task-private candidate cache or quota exists.
Refill takes no Item input. All selectors, including ANY and explicit IDs, consume
inventory; a miss leaves the Item due without a source query or fresh hold.

Refill runs at a 50ms completion-relative interval. Pacer rotates positive-deficit
Groups and tries at most 100 IDs per Group, 1000 per round, independent of the
business deficit count. It alone observes due HOT with the preset floor through
one read-only page operation. Per-Group rank offsets advance after observation,
including no-match and projection-failure rounds, and wrap at the range end.
Offsets are retained only for current roots (at most 100); removed Groups forget
them. Observation failure invents no progress. Group rotation also advances on
attempts. Pagination does not claim a stable view under concurrent Score changes.

Matching reads only supplied IDs' current projections before any lease write.
It rotates Eligibility/query acceptance, prioritizes constrained queries before
ANY and plans each ID for at most one Eligibility. The accepted union is exact-
acquired once, with a deadline computed as callback time plus 1 second. Full-score
comparison accepts due dirty=0 or dirty=1 and clears dirty on success. Matching
retains only returned TRANSITIONED fences; the observed score never enters stock.

Pacer's invocation-local acquisition capability captures the Group and original scores.
Only a unique subset of issued IDs is accepted, at most once, on the issuing thread,
before refill returns. Invalid, duplicate, late or cross-batch uses cannot call the
Score Owner. Rule Handlers never receive the capability. Plans remain local to the
call; there is no pending lease registry or periodic renewal.

This restores scheduling authority while accepting potentially longer waits for
rare predicates/explicit IDs. Matching cannot compensate by discovering better IDs
from its indexes. Unmatched observations cause no lease write. Committed holds
from ambiguous acquisition or failed insertion expire naturally.

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
| HOT observation or projection failure | refill Producer backoff; no new hold |
| acquisition failure or response loss | no stock from unconfirmed results; committed holds expire |
| competing exact score or dirtying a held candidate | reject the stale acquisition or execution fence |
| unused hold | expires naturally; no compensation |
| restart | local stock is lost; facts/bindings persist and normal refill acquires new holds |

There is no Score/facts transaction, ACK, replay, repair scan or guarantee that
lost Properties evidence eventually arrives. Rules never couple Task lifecycles.

### Known Serviceability Regression

**The 1-second candidate lease has not passed the task-fault convergence proof.**
A retained target can repeatedly reacquire unused, expired inventory and refresh
its HOT Score time. The existing Serviceability loss-compensation scan requires
an older HOT coordinate, so those Workers can remain outside its observation
range after their routes disconnect. Lease acquisition is not network evidence.
This interaction requires resolution before claiming fault-recovery acceptance;
the Serviceability time contract and probe selection have not been changed here.
