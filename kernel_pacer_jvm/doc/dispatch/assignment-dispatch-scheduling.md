# Assignment-Dispatch Scheduling

Status: active Java Kernel Dispatch Convergence contract.

Detailed owners: [Initialization](task-initialization-policy.md),
[Task Dispatch](task-dispatch-pacer.md), [Matching](../../../worker_matching_jvm/README.md),
[Worker protocol](../../../kernel_jvm/doc/score/worker-hot-acquire-lease-protocol.md)
and [Delivery](../../../doc/kernel/worker-delivery-dispatch.md).

## Authority

Main supplies complete bounded Group roots and immutable NORMAL Task descriptors
to fixed single-flight Producers. Task supply declarations name Pools; Item queries
independently name functions. Items never generate refill demand. Pacer forwards
opaque queries and scores without interpreting facts, indexes or coordinates.

Matching normalizes declarations and merges equivalent targets using MAX.
`observeRefillDeficits` returns positive Group shortage counts in an immutable Map;
observations do not advance target pages or reserve inventory. Matching owns the
count interpretation; Pacer uses it only to bound candidate supply. Actual refill
attempts retain Pool/target rotation. Country uses its complete
bounded target set; other policies retain bounded target pages. Server admission
has no inventory observation or maintenance authority.

## Candidate Generation Boundary

**Refill candidateizes due ordinary HOT without changing its time. Assignment
alone establishes a future execution lease.** Matching receives only successful
candidate fences as Map<workerId, Long>, not held-lease DTOs or deadlines.

The 50ms completion-relative Refill Producer shares Main's Group rotation:

1. Observe a bounded old mark=1 head in each selected Group and exact-recycle it
   to mark=0 at Redis execution time, even when there is no Pool shortage.
2. For Groups needing supply, observe at most `min(observed deficit, 100)` raw
   rows from the due mark=0 head at Assignment's optional floor, then
   exact-candidateize before qualification.
3. Supply only returned TRANSITIONED new fences to Matching.
4. If the observed shortage remains, ask Matching to requalify at most 100 retained
   local Pool entries. New supply plus reuse admits at most 100 entries per Group
   attempt. Reuse preserves the original source TTL and does not write WorkerScore.

Candidateization and recycling each have independent 100-per-Group and
1000-per-round budgets; each Group gets at most one batch of each operation per
round. Every refill attempt reserves 100 budget even for a smaller requested
head: at most ten Groups receive refill attempts per round. Empty reads and
partial/failed attempts do not refund budget. The observation stage records the
actual requested limit. Zero deficit skips ordinary observation but not recycling.
Attempts advance Group rotation, including empty reads and failures. No
Worker offset, extra thread, supplementary scan or durable cursor is introduced.
Matching may rotate through its existing bounded local Pool stock to serve later
Pool demand. This does not change either Redis head or the Group attempt budget.
Recycling and refill honor the same optional floor; DEFAULT retains no Assignment
scan floor. Runtime Boundary uses 10ms candidate age; production and Scenario Lab
use 60 seconds. This is distinct from Pool TTL and Serviceability HOT staleness.

Both heads count raw rows, including corruption, against their budgets. Successful
candidateize moves a member to another mark band. Recycling advances generation,
so an old head cannot immediately repeat the same aged cycle. No match, capacity
refusal, qualification exception, lost response or process exit leaves a rollback
or retry; normal age-based recycling is the recovery path while Main supplies the
Group. Old stock independently expires. Initial/closed/parked Tasks do not create
new demand, and a Task stop does not cancel an already admitted refill call.

## Candidate Selection

```text
NORMAL descriptors -> Group declarations and deficits
  -> old candidate recycle; due ordinary HOT -> exact candidateize
  -> Matching current qualification -> per-Pool local inventory
Item messageId/query -> fixed Matching function -> Pool take or direct lookup
  -> current Endpoint/Group -> execution acquisition -> exact Item claim
```

One supplied generation may qualify into multiple Pools. Pool TTL begins at actual
admission and lasts 60 seconds. Duplicate Worker/fence offers do not extend TTL.
Later Pool demand can reuse an already retained generation within the source's
original TTL. Reuse never replaces an existing target identity. Fresh candidate
supply runs first, preventing old retained fences from masking a new generation.
Requalification replaces older generations/views without extra storage capacity;
nonmatching new generations remove their old entries. Catalog counts every actual
admitted entry against its 100-entry batch budget. It retains Pool rotation and
partial successes if a later policy throws. There is no all-Pool fill guarantee.

Pool take removes entries before address lookup and claim. TTL only limits take;
a taken candidate has no extra expiry beyond Owner exact/due conditions. Old local
selection references cannot consume a replacement. Any needs explicit enablement
and declared supply. Direct Identity/Phone functions need no stock and generate
identity hints without creating supply or discovering replacement Pool candidates.

Dispatch retains 100 Items per Task and its bounded publication ordering hint:
unserved Tasks first, then least recently served. Matching validates the whole
request before consumption, groups equivalent selections and returns messageId
associations in original order. Later duplicate Workers or missing/wrong-Group
addresses are filtered only from their associated Item. Pacer never redistributes
another Item's candidate, restores stock or takes a replacement. Later failures
retain earlier consumption. Rare predicates may wait under bounded supply.

## Assignment Closure

Only package-private TaskAssignmentDispatcher creates claimed Commands:

```text
nonzero expected fence -> acquireObservedHotScoreLeases
identity hint          -> acquireCurrentHotScoreLeases
  -> returned execution fence -> exact ACTIVE Item claim
  -> ResultContext -> Adapter-partitioned Worker mailbox
```

Both acquisition paths require strictly past HOT with either mark and a requested
future deadline. They atomically write mark=0 and the execution deadline. They
cannot preempt a current/future hold. Only TRANSITIONED with a returned score
permits claim. A strict failure never downgrades to an identity hint. No zero
sentinel reaches Kernel; it has a separate identity-only operation.

Each nonempty partition is one bounded Owner call, split according to its existing
capacity. An exception after an earlier commit leaves execution holds to expire,
without Item claim, rollback or retries. ResultContext carries the new execution
fence, never the candidate generation. Multiple Pools and Direct acquisition race
for one execution slot; stale copies cannot claim or release the winner's hold.

Properties invalidation atomically advances past HOT time and clears candidate
mark, making it observable by ordinary Refill after the current slot passes.
Past non-cold RECOVERY retains mark when advancing; both retain polarity.
Execution-first ordering preserves the future hold. Network evidence also retains
current/future time and mark. An accepted past polarity change advances generation
and clears mark; ordinary same-polarity evidence leaves the coordinate unchanged.
CONNECTED can additionally activate a below-floor past coordinate, provided the
evidence reaches the startup floor. Reconnect can therefore return a consumed
candidate to ordinary Refill without waiting for aged recycling.
Task Dispatch independently records exhausted/expired failure before terminal Item
movement. Result content, Item finality and Worker release keep separate commits.

## Failure Semantics

| Failure | Result |
| --- | --- |
| Invalid selector | Rejected before the corresponding consumption/mutation |
| Head read failure | Existing Producer backoff |
| Candidate CAS lost | No Matching offer |
| Qualification/capacity/return loss | No compensation; committed generations age into recycle |
| Properties or competing assignment changes fence | Stale strict acquisition, no fallback |
| TTL expires before take | Inventory removed locally |
| Claim/publication fails | Execution hold expires independently |
| Restart | Local stock lost; retained generations recover through bounded recycling |

There is no facts/Score transaction, reliable SYSTEM replay, global Worker scan,
allocator or rule-change sweep. Refill alone supplies no network evidence. An
actual delivery remains necessary for the existing delivery-evidence recovery path;
periodic Probe remains governed by its preset and bounded Serviceability policy.

### Serviceability And Verification Scope

Focused Pacer tests prove bounded attempts, independent budgets and no fallback.
Redis Owner proof establishes time/exact fences and four 100/100/50 head-progress
cases. Matching proof covers independent TTL, shared generations and replacement.
Runtime Boundary retains execution, Binding and delivery witnesses. System lanes
keep their existing thresholds and nonclaims; see [TESTING](../../../TESTING.md)
and [mainline proof pointers](../../../doc/kernel/scheduling-overview.md#production-and-proof-pointers).
