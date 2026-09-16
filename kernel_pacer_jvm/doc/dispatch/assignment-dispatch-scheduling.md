# Assignment-Dispatch Scheduling

Status: active Java Kernel Dispatch Convergence contract.

Detailed owners: [Initialization](task-initialization-policy.md),
[Task Dispatch](task-dispatch-pacer.md), [Matching](../../../worker_matching_jvm/README.md),
[Worker hold protocol](../../../kernel_jvm/doc/score/worker-hot-acquire-lease-protocol.md)
and [Delivery](../../../doc/kernel/worker-delivery-dispatch.md).

## Authority

One Main Scheduler supplies complete bounded input to fixed single-flight
initialization, Eligibility refill, Task dispatch and optional Serviceability
Producers. It shares complete immutable NORMAL Task descriptors, including Rule
names and resolved refill declarations, without another Matching lookup. TaskItems only consume stock;
they never generate refill demand or a matching job.

The refill Producer concatenates targets by Group/Rule and calls Matching's
`groupsNeedingRefill` before acquiring held candidates. Pacer intersects the hint
with Main's Group roots and retains its rotation and supply budgets. It then calls
`refill(group, targetsByRule, held)` directly. Matching resolves names on each call,
merges targets using MAX and preserves target paging and Rule rotation. Hint reads
do not advance query cursors; actual refill attempts do. No inventory snapshot or
executable preparation is required between those calls. Server admission does not
observe shortages or maintain inventory.

## Core Mechanism Change

**Pacer acquires a 1-second candidate lease before Matching reads projections.**
Only successful new fences are supplied. Matching admits the qualified subset
using those same fences and original deadlines; processing and stock waiting share
the second. Unmatched leases expire naturally. Execution confirmation remains a
separate exact transition. Explicit-ID queries filter the supplied batch and stock,
without point acquisition. Redis-time validation remains inside each lease CAS.
Matching has no acquisition callback or inventory renewal capability.

## Candidate Selection

```text
NORMAL RUNNING Tasks -> complete immutable Task descriptors
  -> refill: shared target MAX -> Group deficit -> Pacer read-only HOT head
      -> Kernel exact 1-second lease -> Matching projection/acceptance -> inventory
  -> dispatch: messageId -> due Item query -> Matching local destructive take
      -> messageId -> held candidate
      -> current Endpoint/Group -> Worker exact confirm -> Item exact claim
```

Matching owns query semantics and shared stock per Group/Rule. Pacer forwards Group/Rule coordinates,
message IDs and Item queries through `WorkerMatching`, without normalization or
semantic aggregation. No Task-private candidate cache or quota exists.
Refill takes no Item input. All selectors, including ANY and explicit IDs, consume
inventory; a miss leaves the Item due without a source query or fresh hold.

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

Matching calls each participating Rule synchronously with the remaining held IDs.
Each Rule owns qualification, inventory, shortages and atomic take, and reads only
its offered identities. Catalog rotates Rules/target pages and excludes IDs actually
admitted by an earlier Rule. Current Rules prioritize constrained targets before ANY
and recheck expiry/capacity at local commit, retaining original fences and deadlines.
If a later Rule fails, earlier admissions remain consumable; the exception ends the
remaining batch and reaches the existing Producer failure path. The next round
re-observes shortages without rollback or replay. Rules have no lease capability,
pending registry or separate Matching execution thread.

Rare predicates/explicit IDs can wait under bounded Group supply. Matching cannot
discover substitute IDs from its indexes. Zero-match batches still write short
leases, and partial matches can acquire more Workers than they admit: this is the
accepted cost of pre-Matching acquisition. Unmatched leases, ambiguous acquisition,
expired processing and failed insertion recover by natural expiry, without renewal.

Main-selected INITIAL Tasks never prewarm. Closed, parked or disabled Tasks supply
no subsequent targets. A Main observation is round evidence; stopping a Task does
not retroactively cancel an in-flight refill. Old stock expires without renewal.

Dispatch preserves its 100-Item per-Task budget and actual-publication ordering
hint: unserved Tasks first, then least recently served. Within a Task, Pacer submits
one complete messageId-to-query Map. Matching validates and normalizes the entire
batch, groups equivalent queries and assigns candidates in each group's Item order.
The result follows original request order and omits requests without candidates.
Each take returns at most 100 unique candidates and removes them before address
lookup, confirmation and claim. Pacer filters round-duplicate Workers and missing
or wrong-Group addresses for their associated message ID only; it never redistributes
another Item's candidate. Failure never restores stock, takes a replacement or
refreshes a fence. No Item request bypasses inventory or relaxes missing Rule evidence.

The [Matching Owner](../../../worker_matching_jvm/README.md#cost-failure-and-proof)
records supply/refill command costs. Counts and take are local. Endpoint HMGET,
Worker confirmation, Item claim and mailbox publication remain bounded Owner calls.
They are measured independently from refill, not asserted as a latency promise.

## Assignment Closure

Only the package-private `TaskAssignmentDispatcher` constructs claimed Commands:

```text
exact Worker transfer(seal=true) (soft original fence -> sealed execution fence)
  -> exact ACTIVE Item claim
  -> ResultContext carrying the returned execution fence
  -> Adapter-partitioned Worker mailbox
```

Pacer treats scores as opaque evidence. It cannot decode, construct or calculate
coordinates. Only TRANSITIONED with a returned new fence proceeds to Item claim;
NOOP, STALE and INVALID never authorize it, even if a result echoes a sealed
current score. An exact-fence failure publishes no Command. A committed execution
is not revoked by later facts updates. Unused and publication-failed leases
recover through existing expiry semantics.

WorkerScore also offers transfer with seal=false for a future bounded caller.
It can only preserve or extend a soft deadline; unchanged time is NOOP. This
Pacer adds no allocator, cached-candidate discovery or preemption loop. Matching
may retain a fence after another caller transfers it; Dispatch's exact transfer
rejects that old fence without refreshing it or taking a replacement.

Task Dispatch independently stores failed Result before requesting terminal tag
5 for exhausted/expired Items, and owns pacing/idle close or park. Result routing
and subsequent outcome observations retain their separate lifecycle and commits.

## Failure Semantics

| Failure | Result |
| --- | --- |
| unavailable Rule | no assignment; failure/idle handling continues |
| invalid selector | Server rejects before Item mutation; stored invalid input fails bounded acquisition |
| HOT observation failure | refill Producer backoff; no acquisition |
| projection failure | refill Producer backoff; no new Group stock; acquired holds expire |
| acquisition failure or response loss | no stock from unconfirmed results; committed holds expire |
| competing exact score or sealing a held candidate | reject the stale acquisition or execution fence |
| unused hold | expires naturally; no compensation |
| restart | local stock is lost; facts and Task descriptors persist and normal refill acquires new holds |

There is no Score/facts transaction, ACK, replay, repair scan or guarantee that
lost Properties evidence eventually arrives. Rules never couple Task lifecycles.

### Serviceability And Verification Scope

The `d148ed516` baseline passed Proof CI run `34928709726`, including the original
task-fault and state scenarios. Earlier local failures do not describe that baseline
as currently failing, and its green result does not validate the new lease ordering.
The pre-Matching change requires its own unchanged-fixture proof results.

On 2026-09-15, the pre-Matching worktree over `d148ed516` passed Windows-native
Owner/Runtime Boundary, Worker Correctness, Dynamic Matching (150400 Items) and
Convergence Health state verification against loopback Redis 7.4.10. The unchanged
task-fault run failed its 300-second host-down scheduling assertion: 28 String
Workers remained held-hot, excluding the checkpoint target and backup, which were
already RECOVERY. Refill/admission continued while consumption stayed fixed; the
run stopped before the restart/recovery phase. The initial disconnect evidence's
delivery/rejection reason was not captured. This is a local failed acceptance,
not a same-environment attribution to the new ordering. Linux Task Call/mixed A/B
against the baseline remains unexecuted; no performance acceptance is claimed.

Lease acquisition is not network evidence. The existing current-slot evidence
boundary, observation-age checks and Serviceability probe strategy are unchanged.
Refill alone emits no Adapter delivery evidence and does not guarantee repair after
a lost disconnect. Actual dispatch retains the existing delivery-evidence path.
