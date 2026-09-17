# Assignment-Dispatch Scheduling

Status: active Java Kernel Dispatch Convergence contract.

Detailed owners: [Initialization](task-initialization-policy.md),
[Task Dispatch](task-dispatch-pacer.md), [Matching](../../../worker_matching_jvm/README.md),
[Worker hold protocol](../../../kernel_jvm/doc/score/worker-hot-acquire-lease-protocol.md)
and [Delivery](../../../doc/kernel/worker-delivery-dispatch.md).

## Authority

One Main Scheduler supplies complete bounded input to fixed single-flight
initialization, Eligibility refill, Task dispatch and optional Serviceability
Producers. It shares complete immutable NORMAL Task descriptors, including optional Pool
supply declarations, without another Matching lookup. TaskItems use
Pool stock or fixed direct identity queries;
they never generate refill demand or a matching job.

The refill Producer concatenates declarations by Group and calls Matching's
`groupsNeedingRefill` before acquiring held candidates. Pacer intersects the hint
with Main's Group roots and retains its rotation and supply budgets. It then calls
`refill(group, declarations, held)` directly. Matching resolves names on each call,
merges targets using MAX and preserves Pool rotation. Country processes complete
bounded targets against memory bucket counts; other Pools retain target paging.
Hint reads do not advance their query cursors; actual refill attempts do. No inventory snapshot or
executable preparation is required between those calls. Server admission does not
observe shortages or maintain inventory.

## Candidate Lease Boundary

**For Pool refill, Pacer acquires a 1-second candidate lease before Matching reads projections.**
Only successful new fences are supplied. Matching admits the qualified subset
using those same fences and original deadlines; processing and stock waiting share
the second. Unmatched leases expire naturally. Execution confirmation remains a
separate exact transition. Unconditional Any requires explicit Pool/function
enablement and declared supply. Redis-time validation remains inside each lease CAS.
Matching has no acquisition callback or inventory renewal capability.

## Candidate Selection

```text
NORMAL RUNNING Tasks -> complete immutable Task descriptors
  -> refill: shared target MAX -> Group deficit -> Pacer read-only HOT head
      -> Kernel exact 1-second lease -> Matching projection/acceptance -> inventory
  -> dispatch: messageId -> due Item query -> fixed Matching function
      -> Pool take or direct identity/property lookup -> messageId -> candidate
      -> current Endpoint/Group -> Worker execution admission -> Item exact claim
```

Matching owns fixed query functions and shared stock per Group/Pool. Pacer forwards Group,
message IDs and Item WorkerQuery envelopes through `WorkerMatching`, without normalization or
semantic aggregation. No Task-private candidate cache or quota exists.
Refill takes no Item input. Pool queries, including explicit ANY, consume
inventory; a miss leaves the Item due without a source query or fresh hold.
Direct workerId/worker.phone queries need no stock: they return identity hints and
leave current execution eligibility to Kernel, without changing Task refill demand.

Refill runs at a 50ms completion-relative interval. Pacer rotates positive-deficit
Groups and tries at most 100 IDs per Group, 1000 per round, independent of the
business deficit count. **Each observation starts at the due head from the preset
HOT floor, in ascending Score/member order; no within-Group offset is retained.**
Successful acquisition moves the head forward before Matching, including no-match
and projection-failure batches. Expired leases retain their newer time coordinates.
Group rotation still advances on attempts, including empty observations and failures.
Observation returns at most 100 raw rows, filtering corrupt scores without a
replacement scan or automatic progress through a fully corrupt head. Score changes
between observation and acquisition are handled by exact CAS, without a same-round
rescan or a stable-snapshot promise.

For each nonempty Group batch Pacer computes now plus 1 second and calls the existing
exact acquisition once. Full-score comparison accepts due mark=0 or mark=1 and
establishes a soft hold on success. Only TRANSITIONED new fences reach Matching; all-failed
acquisition skips it. Owner response loss does not trigger a confirmation read.

Matching calls each participating Pool policy synchronously with the remaining held IDs.
Each policy owns qualification and shortages, and reads only
its offered identities. Catalog rotates Pools/target pages and excludes IDs actually
admitted by an earlier Pool. Current policies prioritize constrained targets before ANY
and recheck expiry/capacity at local commit, retaining original fences and deadlines.
If a later Pool maintenance call fails, earlier admissions remain consumable; the exception ends the
remaining batch and reaches the existing Producer failure path. The next round
re-observes shortages without rollback or replay. Maintenance policies have no lease capability,
pending registry or separate Matching execution thread.

Rare predicates/explicit IDs can wait under bounded Group supply. Pool refill cannot
discover substitute IDs from its indexes; Direct query functions independently locate IDs. Zero-match batches still write short
leases, and partial matches can acquire more Workers than they admit: this is the
accepted cost of pre-Matching acquisition. Unmatched leases, ambiguous acquisition,
expired processing and failed insertion recover by natural expiry, without renewal.

Main-selected INITIAL Tasks never prewarm. Closed, parked or disabled Tasks supply
no subsequent targets. A Main observation is round evidence; stopping a Task does
not retroactively cancel an in-flight refill. Old stock expires without renewal.

Dispatch preserves its 100-Item per-Task budget and actual-publication ordering
hint: unserved Tasks first, then least recently served. Within a Task, Pacer submits
one complete messageId-to-query Map. Matching validates and normalizes the entire
batch, routes functions in first-appearance order, and each Pool function groups
equivalent selections and assigns candidates in its group's Item order.
The result follows original request order and omits misses and later cross-function
duplicate Workers without replacing them. A later execution exception does not
restore earlier consumed inventory. Item functions are independent of Task supply declarations and never generate supply.
Each take returns at most 100 unique candidates. Pool entries are removed before address
lookup, confirmation and claim. Pacer filters round-duplicate Workers and missing
or wrong-Group addresses for their associated message ID only; it never redistributes
another Item's candidate. Failure never restores stock, takes a replacement or
refreshes a fence. Identity/Phone functions bypass Pool stock, but never bypass
Group/address verification or Kernel execution admission.

The [Matching Owner](../../../worker_matching_jvm/README.md#cost-failure-and-proof)
records supply/refill command costs. Pool counts/take and Identity lookup are local;
Phone lookup performs one bounded read-only Redis Lua. Endpoint HMGET,
Worker confirmation, Item claim and mailbox publication remain bounded Owner calls.
They are measured independently from refill, not asserted as a latency promise.

## Assignment Closure

Only the package-private `TaskAssignmentDispatcher` constructs claimed Commands:

```text
Pacer partitions exact fences / identity hints
  -> Worker transferObservedHotScoreLeases(seal=true) / acquireCurrentHotScoreLeases
  -> exact ACTIVE Item claim
  -> ResultContext carrying the returned execution fence
  -> Adapter-partitioned Worker mailbox
```

Pacer treats scores as opaque evidence. It cannot decode, construct or calculate
coordinates. Only TRANSITIONED with a returned new fence proceeds to Item claim;
NOOP, STALE and INVALID never authorize it, even if a result echoes a sealed
current score. `RoutedWorkerCandidate` carries `WorkerCandidate.expectedScore`
alongside the verified Group and Endpoint. Pacer partitions the complete validated
batch: nonzero fences go unchanged to `transferObservedHotScoreLeases`; zero hints
become an identity list for `acquireCurrentHotScoreLeases`. That operation admits
due HOT with either mark or current/future soft HOT and always writes a sealed fence. Kernel's exact input
rejects zero, and its current-state input has no expected-score field. Empty
partitions make no Owner call. The two calls are independent atomic batches;
an exception after the first commits leaves those holds to expire, without Item
claim, rollback, compensation or retry. Transfer rejection never falls back to
the other operation. Neither the input expectation nor a Pool deadline can
substitute for the returned execution fence. A committed execution
is not revoked by later facts updates. Unused and publication-failed leases
recover through existing expiry semantics.

WorkerScore also offers transfer with seal=false for a future bounded caller.
It can only preserve or extend a soft deadline; unchanged time is NOOP. This
Pacer adds no allocator, cached-candidate discovery or preemption loop. Matching
may retain a fence after another caller transfers it; Dispatch's exact transfer
rejects that old fence without refreshing it or taking a replacement.
All production Pool functions still return their original nonzero expectations. Fixed
Identity and Phone executors produce zero hints. Runtime Boundary creates ordinary Tasks with `refill=[]` to
prove they execute due Workers without pre-existing stock or a candidate lease.
A Direct win invalidates the old Pool fence without synchronously removing stock.

Task Dispatch independently stores failed Result before requesting terminal tag
5 for exhausted/expired Items, and owns pacing/idle close or park. Result routing
and subsequent outcome observations retain their separate lifecycle and commits.

## Failure Semantics

| Failure | Result |
| --- | --- |
| unavailable function | no assignment; failure/idle handling continues |
| invalid selector | Server rejects before Item mutation; stored invalid input fails bounded acquisition |
| HOT observation failure | refill Producer backoff; no acquisition |
| projection failure | refill Producer backoff; no new Group stock; acquired holds expire |
| acquisition failure or response loss | no stock from unconfirmed results; committed holds expire |
| competing exact score or sealing a held candidate | reject the stale acquisition or execution fence |
| unused hold | expires naturally; no compensation |
| restart | local stock is lost; facts and Task descriptors persist and normal refill acquires new holds |

There is no Score/facts transaction, ACK, replay, repair scan or guarantee that
lost Properties evidence eventually arrives. Matching resources and functions never couple Task lifecycles.

### Serviceability And Verification Scope

Focused Pacer tests establish ordering and bounded policy. Redis Owner proof
establishes lease fences and admission; Runtime Boundary and system proofs
establish their named execution/failure claims. Follow the
[mainline code/proof pointers](../../../doc/kernel/scheduling-overview.md#production-and-proof-pointers)
and [TESTING](../../../TESTING.md) for current selection.

The [2026-09-15 verification notes](../../../doc/archive/verification/2026-09-15-pre-matching-lease-proof.md)
preserve version-scoped results and an unresolved failure observation. They are
historical evidence, not a current pass/fail report or a production contract.

Lease acquisition is not network evidence. The existing current-slot evidence
boundary, observation-age checks and Serviceability probe strategy are unchanged.
Refill alone emits no Adapter delivery evidence and does not guarantee repair after
a lost disconnect. Actual dispatch retains the existing delivery-evidence path.
