# XA Mass Worker Matching JVM

Status: current fixed query functions, facts, source index and bounded Pool Owner.

Matching uses an immutable, construction-time name-to-function table for Item
consumption. Functions interpret local JSON inputs and choose resource access.
Current functions are Pool strategies named after their refill Rule IDs; the
storage resource itself is not an executor. Rule refill contracts and Item
consumption are separate capabilities.

Each Group/Rule has one bounded inventory shared by its Tasks. One strategy can
serve multiple Groups, with Group passed explicitly on every call. There is no
dynamic registry, per-Task cache or Matching execution thread. Independent Property
Index, identity, mixed and external executors are not implemented in this slice.

## Owner Boundary

Pacer synchronously calls Matching from its existing independent refill and dispatch
Producers. It observes the Group HOT head and exact-acquires 1-second candidate
leases before a Rule reads eligibility. Only successful new fences are offered.
Matching processing and stock waiting share the original deadline; unmatched leases
expire naturally. Kernel/Pacer retains all Score, confirmation and claim authority.

| Stage | Authority and fence |
| --- | --- |
| Supply | Pacer reads a bounded Group HOT head and exact-acquires soft leases |
| Qualification and admission | Each Rule reads only offered IDs and retains admitted candidates with the original fences and deadlines |
| Coordination | Catalog excludes IDs actually accepted by an earlier Rule from later Rules in that Group batch |
| Execution | Kernel exact-transfers a soft inventory fence with seal=true, then exact-claims the Item |

**Cross-Rule failure contract:** Rule A's successful refill remains committed if
Rule B subsequently fails. The exception ends the remaining batch and reaches the
existing Pacer failure path. The next normal round observes new shortfalls.
There is no rollback or automatic replay. Within each current Rule implementation,
Redis reads and fallible validation/matching finish before its bounded local commit.
This partial-success contract is independent of atomic Facts/index writes below.

```text
Worker / Platform facts -> one Lua -> enabled Rule indexes
NORMAL Task descriptors -> Group/Rule target MAX -> Rule deficits
  -> Pacer HOT head -> Kernel exact 1-second lease -> offered held IDs
  -> Rule qualification and admission -> Rule-owned local stock
messageId -> WorkerQuery(executorName, input) -> fixed function table
  -> strategy interpretation/grouping -> Pool range take
  -> messageId -> held candidate -> current address
  -> Kernel exact transfer(seal=true) -> exact Item claim -> Command
```

Rules receive opaque held fences, never a lease acquisition capability. They cannot
discover replacement IDs, renew holds, decode Kernel scores or claim Items.
Pacer carries refill Rule names, Group coordinates, correlation IDs and immutable query data
through `WorkerMatching`. It does not normalize or semantically group queries,
interpret business fields, depend on Handler implementations or construct index coordinates.

## Named Rule Admission

`resolveRefillTargets(workerGroupId, ruleId, requested)` resolves bounded immutable
targets without Redis access or stock changes. Explicit targets override configured
Group/Rule defaults, otherwise ANY 100. It normalizes queries and merges duplicates
using MAX. Empty targets, unknown/unavailable Rules and unsupported queries fail.
Server stores the resulting snapshot with the Rule name in the Kernel Task
descriptor. Matching does not accept Task IDs, persist Task configuration or own
Task lifecycle. The [Task Owner](../kernel_jvm/doc/resource-model/task-resource-model.md)
owns descriptor creation, lookup, corruption handling and configuration equality.

`RefillTarget` and `EligibilityQuery` remain shared assignment values. Rule
implementations own their interpretation; Kernel stores their structure. Main
shares its complete immutable Task descriptors with independent refill and dispatch.

The refill Producer groups and concatenates declarations by Group/Rule without
interpreting or merging queries. It calls `groupsNeedingRefill(targetsByGroup)`
once, then `refill(group, targetsByRule, heldCandidates)` for each acquired Group
batch. Matching normalizes equal targets using MAX and resolves each Rule by name.
The input is bounded to 100 Group/Rule coordinates and 10,000 declarations; each
Rule operation receives at most 100 queries through the existing rotating pages.
Shortage observation does not advance the target cursor; a refill attempt does.

Global expired-stock and inactive-cursor cleanup belongs to `groupsNeedingRefill`.
The hint and later admission may observe different stock. Refill independently
validates and works without a prior hint or Task registration. Both operations may redo
bounded local target normalization; they retain no shared execution plan, Handler
view or inventory transaction. Server admission and Main do not maintain stock.

Server admission uses `WorkerMatchingCatalog.normalizeQuery(group, WorkerQuery)`.
The Pacer port exposes shortage observation, refill and
`take(group, queriesByMessageId)`. The function name belongs to each Item, not an
outer Task binding. Unknown or Group-disabled names fail without fallback; names
are matched exactly, never classified by prefix. Explicit functions may differ
from the Task's supply Rule. Group enablement still controls existing indexes.

Take accepts at most 100 nonblank message IDs. Catalog captures input order and
normalizes the complete batch before any consumption. It invokes each function
once in first-appearance order with its messageId-to-local-input Map. The current
Pool functions group equivalent selections, process groups in first-appearance
order and allocate within each group in Item order. Catalog returns an immutable
Map in original request order, omitting misses. Cross-function duplicate Workers
keep their first association; later associations are discarded without replacement.
An empty batch touches no inventory. Late invalid input consumes nothing. A later
execution exception ends the call without rolling back earlier consumption.

`WorkerCandidate(workerId, expectedScore)` carries no inventory deadline. All
production Pool strategies return their original nonzero fence. `HeldCandidate`
remains the refill/inventory value, and storage checks its original deadline.
Current Pool take reads no Redis or Facts. Function results are candidates only;
Kernel retains execution admission.

A Rule may explicitly return `expectedScore=0` as an identity hint. Catalog
preserves that value without reading WorkerScore, filling in a fence or downgrading
a nonzero expectation. Pacer consumes the zero sentinel to select Kernel's current-state
transfer by IDs; nonzero fences go unchanged to its exact observed-score transfer.
Kernel receives no optional-fence sentinel and atomically admits active soft HOT and seals
the execution lease. This hint can compete for a later soft hold and carries no
historical qualification guarantee. Production Rules all retain strict fences;
the zero-mode Rule exists only in Runtime Boundary test assembly.

Message IDs are call-local correlation keys. Matching neither stores them nor reads
Items, deduplicates requests across calls or owns their lifecycle. Filtering an
association or failing address lookup, confirmation or claim does not transfer its
candidate to another message ID, restore stock, trigger another take or renew a lease.

## Unified Queries and Fixed Handlers

Item requests use the passive Kernel contract `WorkerQuery(executorName, input)`.
The input is an immutable JSON value: object, array, string, number or boolean.
Its root cannot be null; nested null is retained. Arbitrary Java objects and
non-finite numbers are rejected. Every container allows at most 100 members,
container depth is at most 8, and serialized input is at most 64 KiB. Kernel
captures and stores this structure without interpreting names or local fields.

The fixed [function pair](src/main/java/com/xa/mass/workermatching/QueryFunctions.java)
provides `normalizeInput(group, input)` and
`execute(group, inputsByMessageId)`. Normalization is pure local admission, with no
Redis read or inventory mutation. Execution owns interpretation, equivalence and
resource choice. Both must be thread-safe and Group-isolated; neither owns Task
or Item lifecycle. They need not implement a refill interface or use Pool storage.

| Current function name | Local input |
| --- | --- |
| `worker.default` | `{}`, `{"workerId":["w1"]}`, or enabled `{"country":["CN"]}`; IDs cannot combine with country |
| `worker.country` | `{}` or a nonempty country list such as `["CN","US"]` |
| `worker.messaging.available` | `{}` or an object with optional `country` list and `phone` string; conditions intersect |
| `proof.worker.facts` | String scalar fields `proofPool`, `proofTarget`, `proofEnabled`, or exclusive `convergenceSlot` |

Each function rejects unknown local fields and preserves its previous qualification
rules. Countries remain strict uppercase two-letter codes. Empty object means no
additional condition within that function; empty arrays are not ANY. SMS IDs and
Messages phone queries still consume existing Pool entries, not all-Worker indexes.

```json
{"workerSelector":{"executorName":"worker.messaging.available","input":{"country":["CN"],"phone":"+8613800000000"}}}
```

The [RuleHandler interface](src/main/java/com/xa/mass/workermatching/RuleHandler.java)
now owns only the refill side:

| Operation | Contract |
| --- | --- |
| `normalizeQuery(group, query)` | Idempotent target admission; no Redis read or inventory mutation |
| `deficits(group, targets)` | Immutable observed shortages, never reservations |
| `refill(group, targets, offered, maxAccepted)` | Qualify held IDs and return actual admissions with original fences/deadlines |

`EligibilityQuery` remains the quantity-free string-list structure for supply.
`RefillTarget` and TaskDescriptor keep their existing HTTP, YAML and Redis shape:
`{"query":{"worker.country":["CN"]},"count":100}`. Target counts are 1..1000;
normalization and MAX merging stay in Matching. Omitted targets resolve at Task
creation as before. Item requests cannot change or drive these declarations.

Each Rule call has at most 100 targets, offered candidates remain at most 100 unique
IDs, and maxAccepted remains 0..100. Default identity targets saturate at their
unique ID count. All structure and fallible qualification checks precede admission.

This is a one-time Item protocol cutover. Old direct Maps, including old ANY and
ID records, are unreadable; HTTP rejects them. Recreate verification Tasks in a new
scope. There is no compatibility parser, conversion to ANY, migration or automatic
cleanup. Task descriptors, refill declarations, Facts and index encodings remain.

## Adding a Rule

Assembly supplies fixed Rule and query-function Maps. Current Pool strategies
supply method references for their consumption entries. Duplicate names fail
assembly; there is no registration API, plugin loading or executor type inferred
from its name. Future property executors can use property names when implemented;
`worker.phone` and `workerId` are not standalone registered functions today.

`PoolRule` handles current strategy interpretation and offered-ID qualification.
The internal `CandidatePool` is a concrete memory resource with no Redis or
executor interface. It stores one identity Entry per Group/Rule, the original
held candidate, admission order and finite view memberships. It never receives a
business predicate callback.

- An identity map provides exact stock lookup. Country buckets and bucket entries
  are ordered JDK collections; multi-bucket take merges their admission ordinals.
- Each Entry belongs to at most one value in each view. Bucket sizes therefore
  provide exact range counts without copying or filtering the whole inventory.
- Messaging phone and country/phone views are references to existing entries, not
  separately budgeted Pools. Proof views follow only actual finite memberships.
- A removable deadline-ordered structure expires due entries. Consumption and
  expiration remove all view references and empty buckets immediately.
- Counts visit selected buckets; take walks selected ranges. Expiration work is
  separately measured by actual expired entries rather than hidden as query cost.

Selection retains only bounded Entry references. Commit checks the exact original
Entry object and deadline. Replaced, expired or removed entries are skipped with
no reselection, pool revision or retry. Concurrent unrelated changes do not prevent
other selected entries from committing. Qualification and fallible strategy work
remain outside the state lock. Shared capacity coordinates counts only.

Refill may underfill after concurrent consumption or exceed an observed target
after concurrent admission. Current hard pool/process capacity and deadlines still
apply. Later rounds converge; targets do not reserve stock or extend leases.

`RedisRuleStorage` still owns implementation key construction, index-write assembly
and the shared source connection. Facts and all enabled indexes prepare before any
write in one Lua, independently of the local refill/consume failure contracts.
The test-only [Bucket Rule](../server_jvm/src/test/java/com/xa/mass/server/testsupport/BucketRuleHandler.java)
retains its SET/HASH source and real Worker proof. Separate test functions accept
string/integer inputs without implementing a Rule or owning Pool stock.

```yaml
xa:
  mass:
    worker-matching:
      rules:
        worker-groups:
          demo-sim: [worker.country, worker.messaging.available]
        default-refill-targets:
          demo-sim:
            "[worker.default]":
              - query: {"worker.country": [CN]}
                count: 10
```

Rare partitions and identities still need suitable supply targets. The
scenario-workers preset targets ANY 1000; the general default remains 100.

## Inventory and Refill Bounds

Current Rules retain candidates only in this process: at most 100 resident
Group/Rule pools, 1000 entries per pool and 10000 in total. Each entry carries
its identity, range memberships, admission order, original opaque fence and deadline.
Tasks have no reserved share. Restart discards all stock without adoption.

The single-flight refill Producer uses Main-selected NORMAL RUNNING Tasks, with
a 50ms completion-relative interval. INITIAL does not prewarm. Closed, parked or
disabled Tasks supply no later demand; an in-flight round is not a lifecycle lock.
Pacer rotates Groups, at most 100 HOT candidates per Group and 1000 per round.
A positive deficit enables a Group but never reduces its fixed HOT scan budget.

Catalog rotates Rules and bounded query pages, including empty attempts. A Rule
prioritizes constrained targets before ANY, incrementing all overlapping target
counts for each selected candidate. Offered memberships are computed once before
admission; range decisions remain local. Each Group batch passes only remaining
IDs to later Rules; an actual acceptance, not a count estimate, removes an offer.

Global lazy expiry runs once at Group shortage observation by invoking current Rule-owned
pool cleanup. It does not store a second inventory. Group access expires only its
own pool; diagnostics and capacity reads do not sweep unrelated pools. Admission
uses observed shortages and rechecks each entry's original deadline and shared
hard capacity at commit, without revalidating the whole inventory.
Other expiry may release capacity after the round's budget was observed, so a
round may conservatively underfill. A Rule with no available capacity reports zero
refill deficit, preserving the existing full-stock supply suppression. No operation
extends a lease.

Pacer computes the original deadline immediately before acquisition. Qualification
time and inventory waiting consume that same second. Explicit IDs only filter
offered identities and stock; they never trigger a targeted HOT read. Rare targets
may wait longer under the fixed Group supply policy.

Consumption is destructive. Misses, read failures, ambiguous acquisition and failed
admission leave any held Kernel lease to expire; there is no compensation release,
renewal, reinsertion, replay or pending registry. Unobserved mark changes may
temporarily overcount stock; final execution transfer still requires the exact
soft active HOT fence and Redis time. Another caller may transfer a cached soft
fence before its original deadline. Matching keeps its original fence and expiry;
it neither renews nor repairs that entry. Subsequent execution transfer rejects
the stale fence. No allocator or cache scan is added here. Already-held candidates
are not a readiness assertion, and a sealed score does not prove execution.

## Persistent Catalog

All keys use `xa_mass:<scope>`:

```text
:matching:worker:facts:<group>                         HASH workerId -> Worker JSON
:matching:worker:platform-properties:<group>           HASH workerId -> Platform JSON
:matching:worker:index:<encoded-group>:<handler>       ZSET workerId -> source coordinate
:matching:worker:index:<encoded-group>:<handler>:partitions
                                                      HASH workerId -> partition suffixes
:matching:worker:index:<encoded-group>:<handler>:partition:<digest>
                                                      ZSET workerId -> source coordinate
```

Group encoding is UTF-8 Base64 URL without padding; partition digests use SHA-1
of the existing ZSET Handler suffixes. Those implementations retain the source coordinate `countryCode * 2^43 +
lastTakenMillis` with base-26 A..Z prefix 0..675 and milliseconds since 2000-01-01.
Proof partitions use prefix zero. The retained low time component is preserved
by facts updates; refill no longer rotates or writes these coordinates. This index coordinate is
neither a Kernel fence nor a consumable inventory entry.

## Facts Writes and Index Maintenance

Prepare creates no facts. An admitted Adapter observation replaces the complete
Worker string Map, including an observed empty Map, while preserving Platform
Properties. Platform patch requires an existing Worker facts row and changes
only supplied Platform fields; null removes a field. Nested Platform JSON,
including empty arrays and objects, retains its shape.

A batch of 1..100 Worker replacements or one bounded Platform patch uses one Lua.
That operation reads the latest opposite facts, validates stored objects/index
metadata, computes enabled projections and writes facts with the corresponding
memberships. There is no Java pre-read/CAS loop. Worker and Platform writes cannot
lose each other's independent changes. Missing eligibility removes memberships.
Unexpected/corrupt stored data fails; it is not converted to empty eligible facts.

Server separately asks Worker Score Owner to invalidate candidate eligibility
after APPLIED facts writes. Facts/index and sealing are different Owner commits;
there is no cross-owner transaction, guaranteed retry or repair. Acquisition creates
the soft hold before Matching reads current projections. Matching never unseals it:
successful sealing after acquisition invalidates that candidate's fence,
including when it follows the projection read. Facts may still change between
projection and invalidation/transfer; mark is not a facts version and does not
make the independent commits atomic. Already committed execution is
not revoked by later facts observations. The seal operation continues marking all
existing valid scores, including due and recovery scores; it is not lease-only.

Startup rebuilds only enabled Group indexes with bounded SCAN/UNLINK and HSCAN
pages, before admission and Pacer start. Retained facts are the rebuild input;
malformed facts abort startup. Cleanup visits only the declared roots and descendants
of enabled Rules, leaving other index namespaces untouched. With no configured Groups, rebuild is a no-op
and opens no Redis connection. Refill reads their projections only for Pacer-issued IDs; there is no
autonomous source take, index repair scan, lease registry or per-Task publication.

## Cost, Failure and Proof

- Target resolution: local only, no Redis; Task configuration reads belong to Task Owner.
- Group shortage observation: zero Redis commands; local target aggregation and
  one global expiry sweep per round. Named refill independently repeats bounded
  target normalization; only visited target pages reach Rule inventory operations.
- Normalized stock counts and take: zero Redis commands or facts reads.
- Each nonempty eligible Group batch: one read-only HOT head Lua (TIME and
  ZRANGE BYSCORE LIMIT 0 limit inside), then one candidate-acquisition Lua, followed by at most one
  projection read per participating Handler. No successful acquisitions means no
  Matching call. One named Handler uses three client commands for up to 100 IDs.
  Zero-match batches also acquire leases; full stock skips observation and acquisition.
- Default without a configured projection needs no projection read; it uses the same
  Pacer Group supply, never an explicit-ID observation path.
- Final confirmation: one bounded Lua per transfer kind, with Redis TIME inside
  each operation. Current production Pool candidates all use exact transfer;
  a mixed batch splits into exact and current-state calls without a shared transaction.
  Address, Item claim, publication and Result paths retain their separate costs.

These are client command counts, not throughput promises. Matching logs aggregate
shortfall, resident/admitted stock, consumption, unused expiry and capacity limits. Pacer records observed counts and acquisition success/rejection;
offered now means successfully leased, not an unheld observation. Existing
Pacer stage evidence separates refill observation, first acquisition, confirmation rejection and
dispatch. `INITIAL_HOLD` retains attempted `batchSize` and successful `count`;
their difference counts rejected candidates only when `failed=false`. A failed
call is unconfirmed, not proof of lease rejection. No management API or diagnostics thread is added.

Candidate failures do not add a prerequisite to Item expiry/exhaustion/idle settlement. Refill infrastructure failure reaches its Producer backoff; partial
holds expire. Take misses never query a source or acquire a new hold. Restart
loses local inventory and rebuilds it through ordinary refill without adoption,
ACK, replay or a repair scan.

Focused tests cover interpretation, overlapping stock, bounded predicate evaluation,
Group target paging, cross-Rule partial success, local expiry and concurrency. Redis
Owner proves head observation and acquisition-driven progress, closed supplied batches, acquisition before
projection (including unmatched candidates), shared target MAX, soft acquisition,
execution fence invalidation, commit-time expiry and command order/counts. Controlled
Matching clocks prove that processing time consumes the original lease deadline.
Runtime Boundary adds six actual Workers in two Groups serving four Tasks and 800
Items, including shared and different Rules within one Group. Runtime Boundary and
Dynamic Matching witness real Worker execution; Call Performance separately
measures mixed-workload behavior. See [TESTING](../TESTING.md).

Task configuration now lives in the Task descriptor. Recreate Tasks in a new scope;
old descriptor formats are rejected, and old Matching data is neither read nor
cleared. HTTP, facts/index formats and Score encoding are unchanged. Local stock
is still lost on restart and outstanding holds expire.
