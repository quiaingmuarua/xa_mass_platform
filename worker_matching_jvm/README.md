# XA Mass Worker Matching JVM

Status: current fixed query functions, facts, source index and bounded Pool Owner.

Matching uses an immutable, construction-time name-to-function table for Item
consumption. Functions interpret local JSON inputs and choose resource access.
Function names are protocol identities, independent of Pool resource names. Independent `workerId` and
`worker.phone` functions locate identity hints without Pool stock; storage itself is not an executor. Pool maintenance and Item
consumption are separate capabilities.

Each Group/Pool has one bounded inventory shared by its Tasks. One strategy can
serve multiple Groups, with Group passed explicitly on every call. There is no
dynamic registry, per-Task cache or Matching execution thread. Identity and exact phone lookup use fixed functions; mixed/external strategies and
dynamic property index configuration are not implemented.

## Owner Boundary

Pacer synchronously calls Matching from its existing independent refill and dispatch
Producers. For Pool supply it observes the Group HOT head and exact-acquires 1-second candidate
leases before a maintenance policy reads eligibility. Only successful new fences are offered.
Matching processing and stock waiting share the original deadline; unmatched leases
expire naturally. Kernel/Pacer retains all Score, confirmation and claim authority.

| Stage | Authority and fence |
| --- | --- |
| Supply | Pacer reads a bounded Group HOT head and exact-acquires soft leases |
| Qualification and admission | Each Pool policy reads only offered IDs and retains admitted candidates with the original fences and deadlines |
| Coordination | Catalog excludes IDs actually accepted by an earlier Pool from later Pools in that Group batch |
| Execution | Kernel exact-transfers a Pool fence or atomically acquires a current identity; only the returned sealed fence permits Item claim |

**Cross-Pool failure contract:** Pool A's successful refill remains committed if
Pool B's maintenance subsequently fails. The exception ends the remaining batch and reaches the
existing Pacer failure path. The next normal round observes new shortfalls.
There is no rollback or automatic replay. Within each current maintenance implementation,
Redis reads and fallible validation/matching finish before its bounded local commit.
This partial-success contract is independent of atomic Facts/index writes below.

```text
Worker / Platform facts -> one Lua -> enabled Matching indexes
NORMAL Task descriptors -> Group/Pool target MAX -> maintenance deficits
  -> Pacer HOT head -> Kernel exact 1-second lease -> offered held IDs
  -> qualification and admission -> shared Pool resource
messageId -> WorkerQuery(executorName, input) -> fixed function table
  -> strategy interpretation/grouping -> Pool range take / identity / phone index
  -> messageId -> WorkerCandidate -> current address
  -> Kernel strict transfer / current execution acquire -> exact Item claim -> Command
```

Maintenance policies receive opaque held fences, never a lease acquisition capability. They cannot
discover replacement IDs, renew holds, decode Kernel scores or claim Items.
Pacer carries Pool supply names, Group coordinates, correlation IDs and immutable query data
through `WorkerMatching`. It does not normalize or semantically group queries,
interpret business fields, depend on maintenance/function implementations or construct index coordinates.

## Pool Supply Admission

`normalizeRefill(workerGroupId, declarations)` validates a bounded list without Redis
access or stock changes. A declaration is `{poolName,target,count}`; targets use the
existing string-list EligibilityQuery. Equal normalized targets in the same Group/Pool
merge by MAX. Empty lists are valid and mean no supply. Matching supplies no implicit
Pool, target or Task configuration. Unknown/disabled Pools and invalid targets fail.

Server stores the complete list in TaskDescriptor.refill. Ordinary Task creation
omission means `[]`; explicit null and retired `ruleId/refillTargets` fields fail.
Managed Call registration also saves `[]` unless its Group explicitly configures
`xa.mass.task-rpc.refill-by-worker-group`. An explicit `[]` requests no supply.
Matching does not receive Task IDs or own Task lifecycle. The [Task Owner](../kernel_jvm/doc/resource-model/task-resource-model.md)
owns persistence, strict decoding and descriptor equality.

Pacer concatenates declarations by Group and passes `groupsNeedingRefill(refillByGroup)`,
then `refill(group, declarations, heldCandidates)` for acquired Groups. Matching selects
Pool maintenance by resource name, normalizes targets and MAX-merges them. Country
receives its complete bounded target set; Messaging and Proof keep target pages.
Input limits are 100 Groups and 10,000 declarations, with no 100 Group/Pool-coordinate
limit. Country receives up to 10,000 targets without a query cursor. Other policies
receive at most 100 targets per page. Observation does not advance those cursors;
an actual refill attempt does.

Global expired-stock and inactive-cursor cleanup belongs to `groupsNeedingRefill`.
The hint and later admission may observe different stock. Refill independently
validates and works without a prior hint or Task registration. Both operations may redo
bounded local target normalization; they retain no shared execution plan, policy
view or inventory transaction. Server admission and Main do not maintain stock.

Server admission uses `WorkerMatchingCatalog.normalizeQuery(group, WorkerQuery)`.
The Pacer port exposes shortage observation, refill and
`take(group, queriesByMessageId)`. The function name belongs to each Item, not an
outer Task binding. Unknown or Group-disabled names fail without fallback; names
are matched exactly, never classified by prefix. Item functions do not depend on Task supply declarations. A Task with no supply can
consume stock maintained by another Task. Group resource enablement controls indexes
independently of demand, including Phone Index updates and startup rebuild.

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

Identity and Phone functions return `expectedScore=0` as an identity hint. Catalog
preserves that value without reading WorkerScore, filling in a fence or downgrading
a nonzero expectation. Pacer consumes zero to call `acquireCurrentHotScoreLeases`
by IDs; nonzero fences go unchanged to exact observed-score transfer. Kernel receives
no sentinel. Current acquisition admits due HOT with either mark or active soft HOT
and always seals the execution lease; it rejects active sealed HOT and RECOVERY.
It requires no earlier Pool refill. The hint carries no historical qualification
guarantee. Pool strategies continue to return strict fences.

Message IDs are call-local correlation keys. Matching neither stores them nor reads
Items, deduplicates requests across calls or owns their lifecycle. Filtering an
association or failing address lookup, confirmation or claim does not transfer its
candidate to another message ID, restore stock, trigger another take or renew a lease.

## Item Queries and Pool Maintenance

Item requests use the passive Kernel contract `WorkerQuery(executorName, input)`.
The input is an immutable JSON value: object, array, string, number or boolean.
Its root cannot be null; nested null is retained. Arbitrary Java objects and
non-finite numbers are rejected. Every container allows at most 100 members,
container depth is at most 8, and serialized input is at most 64 KiB. Kernel
captures and stores this structure without interpreting names or local fields.

Each fixed [QueryFunction](src/main/java/com/xa/mass/workermatching/QueryFunction.java)
implements `normalizeInput(group, input)` and `apply(group, inputsByMessageId)`
directly. Normalization is idempotent local admission without resource access.
Catalog validates the complete batch before invoking any strategy, groups admitted
inputs by function name and calls each strategy once. Apply receives normalized
inputs and owns selection, equivalence and resource access; it does not repeat
entry admission or the Catalog's consume-request budget check. Both methods must
be thread-safe and Group-isolated; neither owns Task or Item lifecycle.

Composition registers strategy instances with their existing Pool or Index.
Adding a strategy over those resources requires its implementation, fixed
registration and Group enablement, without changing Catalog, Kernel or Pacer.
There is no Normalizer/Executor callback pair, strategy factory or required refill
interface. The consume-request budget remains local to Catalog; resource operation
limits and entry/expiry/capacity checks retain their independent owners.

| Current function name | Local input |
| --- | --- |
| `worker.any` | Only `{}`; explicitly enabled `any` Pool, no Facts required |
| `worker.country` | `{}` or a nonempty country list such as `["CN","US"]` |
| `worker.messaging.available` | `{}` or an object with optional `country` list and `phone` string; conditions intersect |
| `proof.worker.facts` | String scalar fields `proofPool`, `proofTarget`, `proofEnabled`, or exclusive `convergenceSlot` |
| `workerId` | One nonblank Worker ID string; no facts or stock read |
| `worker.phone` | One exact nonempty Worker phone string; Group-enabled independent property index |

Each Pool function rejects unknown local fields and preserves its previous qualification
rules. Countries remain strict uppercase two-letter codes. Empty object means no
additional condition within a Pool function; empty arrays are not ANY. Messages
phone queries consume existing stock. SMS listeners use Country Pool; cancellation
uses the independent workerId function. The retired default function and ID-list
choice have no aliases or replacement multi-ID function.

```json
{"workerSelector":{"executorName":"worker.messaging.available","input":{"country":["CN"],"phone":"+8613800000000"}}}
```

The [PoolRefillPolicy interface](src/main/java/com/xa/mass/workermatching/PoolRefillPolicy.java)
now owns only the refill side:

| Operation | Contract |
| --- | --- |
| `normalizeQuery(group, query)` | Idempotent target admission; no Redis read or inventory mutation |
| `deficits(group, targets)` | Immutable observed shortages, never reservations |
| `refill(group, targets, offered, maxAccepted)` | Qualify held IDs and return actual admissions with original fences/deadlines |

`EligibilityQuery` remains the quantity-free string-list structure for supply.
`RefillTarget(poolName,target,count)` carries the resource name and quantity. TaskDescriptor
stores `refill` with 0..100 declarations; each count is 1..1000. Item queries remain
WorkerQuery and must be explicit at both finite append and managed Call. They cannot
generate, modify or imply Pool supply.

Country maintenance receives up to 10,000 targets; other policies receive at most
100 per call. Offered candidates remain at most 100 unique IDs and maxAccepted
remains 0..100. All structure and fallible qualification checks precede admission.

Task descriptors switch once to `refillJson`; recreate Tasks in a new scope. Old
Rule/target fields, missing supply and corrupt entries fail reading. No dual read,
conversion, default supply, migration or cleanup is provided. Existing WorkerQuery
Item encoding, Facts, index keys and Score fences remain unchanged.

## Fixed Resource Composition

`MatchingComposition` creates each enabled resource once and injects it into its
users. Pool maintenance and named consumer functions receive the same CandidatePool;
Direct functions receive an existing PhoneIndex. Resources do not know executorName.
Functions need not implement a maintenance interface. There is no dynamic registry
or executorType, and composition does not create a Pool or policy for Identity/Phone-only Groups.

| Package | Owned implementation |
| --- | --- |
| `functions` | Local input interpretation, resource access and candidate correlation |
| `pool` | CandidatePool entries, range views, deadlines and CandidateBudget |
| `refill` | Target interpretation, held-ID qualification and membership calculation |
| `index` | Phone, Messaging and Proof resource definitions, reads and mutation Lua |
| `storage` | Facts encoding, persistence, atomic index-write assembly, rebuild and Redis connection |

CandidatePool receives only a clock and shared CandidateBudget. Composition retains
the fixed Pool map; Catalog expires that collection and reads budget diagnostics.
There is no self-registration or resource manager. Index resources receive Redis
access and keyspace, with no Pool, capacity, refill-policy or function-name dependency.
Messaging and Proof policies use injected index readers; Country receives the bounded
Worker Facts reader. Policies neither construct indexes nor define their write Lua.

`FactsIndexStore` deduplicates IndexMutation definitions by resource namespace before
assembling the single preflight-and-write Lua. Conflicting definitions fail assembly.
Group `pools/functions` configuration determines the fixed index dependencies, with
no separate `indexes` configuration and no Task demand prerequisite. Phone depends
only on Worker `phone`; Messaging retains its Worker enablement, country and phone
projection; Proof also consumes Platform `proofEnabled`. Country has no Redis index.

Server owns one Catalog lifecycle Bean. Composition uses one lazy shared Matching
Redis connection, completes startup rebuild before returning Catalog, and closes
the store if assembly or rebuild fails. Catalog closes its store idempotently after
platform callers stop; it never shuts down the Server-owned RedisClient. There are
no per-index connections, constructor-started threads or background repair tasks.

| Pool | Maintenance |
| --- | --- |
| `any` | Empty target only, unconditional held stock; no Facts or property views |
| `country` | Valid Worker country Facts and local country buckets |
| `messaging` | enabled messaging, country and phone views |
| `proof-facts` | finite proof memberships |

Only `workerId` is universally available. Every Pool and Pool function requires
explicit Group configuration; functions require their corresponding resource. A
Group with no Pools can use Identity and an explicitly enabled Phone Index. No
implicit Pool stock or managed supply is created.

## Country Facts and Memory Buckets

Country keeps one `country` membership string per Entry. Its ANY means any entry
with a valid `[A-Z]{2}` Worker country; the `any` Pool does not require Facts.
Single-country take visits one bucket; multi-country take merges only the selected
buckets in original admission order. Removal and expiry remove every Entry reference
and empty bucket; stock capacity is counted once.

Country refill issues at most one Worker Facts HMGET for its live offered IDs,
never Platform Facts, all-Worker scans or a public Catalog read. The shared stored
object decoder rejects malformed JSON before any admission. Missing Facts and
missing/invalid country values skip that Worker. Read failures admit nothing.
All decoding and target interpretation occur outside the inventory lock.

Targets retain string-list syntax. `CN+US / 100` means 100 combined entries, not
100 per country. Equivalent normalized sets use MAX. Overlapping sets each count
their matching entries, while capacity counts one Entry once. One bucket-count
snapshot serves the entire target set; no Entry copying or per-target stock scan
is needed. Constrained targets precede ANY, and each selected country updates all
of its affected deficits. There is no Country target page, cursor or source ZSET.

The old `country` index namespace is neither read, maintained, rebuilt nor deleted.
Messaging and Proof keep their existing Redis indexes and paging; Phone Index
remains independent. Facts and all remaining enabled indexes still update through
one preflighted Lua. Use a fresh scope for Tasks and Items with the retired names.

## Identity and Phone Query Functions

```json
{"executorName":"workerId","input":"w-123"}
{"executorName":"worker.phone","input":"+8613800000000"}
```

Identity input is a nonblank string. It returns identity evidence without Redis,
Facts or Pool access. Pacer still checks existence, Group and Endpoint before Kernel
admission. Phone input is a nonempty exact string; there is no trim or telephone
format conversion. Its index depends only on Worker `phone`, not country,
`messaging.enabled` or Platform Properties. Existing Messaging phone conditions
retain their separate Pool semantics.

Phone storage uses the existing encoded Group index base plus `:phone`: a reverse
HASH maps Worker ID to phone; `:value:<sha1(phone)>` SETs map exact values to IDs.
Facts and all enabled indexes preflight before writing in the same Lua. Replacement
removes the old association; absent/empty phone removes membership. Startup rebuild
uses persisted Facts before Pacer starts, without background repair.

Equal phone requests share one positive-count SRANDMEMBER; one read-only Lua handles
the batch, checks selected IDs against the reverse HASH, and returns at most the
requested count (100 total). Selection is random, non-destructive and has no cursor,
busy-worker rescan or fairness guarantee. Multiple Workers may share a phone. Missing
matches are omitted; corruption of index types fails without repair. Identity hints
are not persisted and consume no Pool capacity. Phone observation and execution
admission are separate commits, with no property version or enduring value guarantee.

`PoolMaintenance` handles supply target interpretation, offered-ID qualification and membership calculation.
Each Pool QueryFunction independently interprets Item input over an injected resource.
The package-private `PoolCandidates.take` helper groups already selected ranges
and associates returned candidates with message IDs. It accepts data rather than
normalization/selection callbacks and owns no resource or lifecycle.
The internal `CandidatePool` is a concrete memory resource with no Redis or
executor interface. It stores one identity Entry per Group/Pool, the original
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

Facts and all enabled indexes prepare before any write in one Lua, independently
of the local refill/consume failure contracts. Index queries do not consume members;
Pool consumption, expiry and capacity changes do not remove property indexes.
Properties sealing remains a separate commit and never edits Pool entries directly.
The test-only [Bucket Pool fixture](../server_jvm/src/test/java/com/xa/mass/server/testsupport/BucketPoolFixture.java)
retains its SET/HASH source and real Worker proof. Separate test functions accept
string/integer inputs without implementing Pool maintenance or owning Pool stock.

```yaml
xa:
  mass:
    worker-matching:
      groups:
        demo-sim:
          pools: [country, messaging]
          functions: [worker.country, worker.messaging.available, worker.phone]
    task-rpc:
      refill-by-worker-group:
        demo-sim:
          - '{"poolName":"country","target":{},"count":100}'
```

Managed supply uses complete JSON declaration strings so an explicit empty target
survives Boot binding and missing target still fails the shared strict decoder.
Lab explicitly enables any/worker.any and supplies 1000. Preview SMS supplies
country/{} /100; Messages retains messaging supply. Unconfigured managed and
ordinary Tasks save no supply.
Phone Index depends on Group resource composition, never Task demand.

## Inventory and Refill Bounds

Current Pools retain candidates only in this process: at most 100 resident
Group/Pool pools, 1000 entries per pool and 10000 in total. Each entry carries
its identity, range memberships, admission order, original opaque fence and deadline.
Tasks have no reserved share. Restart discards all stock without adoption.

The single-flight refill Producer uses Main-selected NORMAL RUNNING Tasks, with
a 50ms completion-relative interval. INITIAL does not prewarm. Closed, parked or
disabled Tasks supply no later demand; an in-flight round is not a lifecycle lock.
Pacer rotates Groups, at most 100 HOT candidates per Group and 1000 per round.
A positive deficit enables a Group but never reduces its fixed HOT scan budget.

Catalog rotates Pool policies; only non-Country policies use bounded query pages,
including empty attempts. Country visits the full target set per attempt. A maintenance policy
prioritizes constrained targets before ANY, incrementing all overlapping target
counts for each selected candidate. Offered memberships are computed once before
admission; range decisions remain local. Each Group batch passes only remaining
IDs to later Pools; an actual acceptance, not a count estimate, removes an offer.

Global lazy expiry runs once at Group shortage observation by invoking current resource-owned
pool cleanup. It does not store a second inventory. Group access expires only its
own pool; diagnostics and capacity reads do not sweep unrelated pools. Admission
uses observed shortages and rechecks each entry's original deadline and shared
hard capacity at commit, without revalidating the whole inventory.
Other expiry may release capacity after the round's budget was observed, so a
round may conservatively underfill. A Pool with no available capacity reports zero
refill deficit, preserving the existing full-stock supply suppression. No operation
extends a lease.

Pacer computes the original deadline immediately before acquisition. Qualification
time and inventory waiting consume that same second. Pool queries only consume
held stock; they never trigger a targeted HOT read. Rare targets
may wait longer under the fixed Group supply policy.

Pool consumption is destructive. Misses, read failures, ambiguous acquisition and failed
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
:matching:worker:index:<encoded-group>:<namespace>       ZSET workerId -> source coordinate
:matching:worker:index:<encoded-group>:<namespace>:partitions
                                                      HASH workerId -> partition suffixes
:matching:worker:index:<encoded-group>:<namespace>:partition:<digest>
                                                      ZSET workerId -> source coordinate
:matching:worker:index:<encoded-group>:phone            HASH workerId -> exact phone
:matching:worker:index:<encoded-group>:phone:value:<digest>
                                                      SET of Worker IDs; digest = SHA-1(phone)
```

Group encoding is UTF-8 Base64 URL without padding; partition digests use SHA-1
of the existing index partition suffixes. Those implementations retain the source coordinate `countryCode * 2^43 +
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
the soft hold before Matching reads current Pool projections. Matching never unseals it:
successful sealing after acquisition invalidates that candidate's fence,
including when it follows the projection read. Facts may still change between
projection and invalidation/transfer; mark is not a facts version and does not
make the independent commits atomic. Already committed execution is
not revoked by later facts observations. The seal operation continues marking all
existing valid scores, including due and recovery scores; it is not lease-only.

Startup rebuilds only enabled Group indexes with bounded SCAN/UNLINK and HSCAN
pages, before admission and Pacer start. Retained facts are the rebuild input;
malformed facts abort startup. Cleanup visits only the declared roots and descendants
of enabled Rules/functions, leaving other index namespaces untouched. With no configured Groups, rebuild is a no-op
and opens no Redis connection. Refill reads their projections only for Pacer-issued IDs; there is no
autonomous source take, index repair scan, lease registry or per-Task publication.

## Cost, Failure and Proof

- Target resolution: local only, no Redis; Task configuration reads belong to Task Owner.
- Group shortage observation: zero Redis commands; local target aggregation and
  one global expiry sweep per round. Country uses one country-count observation for
  all targets; other policies receive only visited target pages.
- Pool stock counts and take: zero Redis commands or facts reads.
- Identity take: zero Redis commands. Nonempty Phone take: one read-only Lua for
  grouped exact phone values, returning at most 100 identities without a lease read.
- Each nonempty eligible Group batch: one read-only HOT head Lua (TIME and
  ZRANGE BYSCORE LIMIT 0 limit inside), then one candidate-acquisition Lua, followed by at most one
  projection read per participating Handler. No successful acquisitions means no
  Matching call. One named Handler uses three client commands for up to 100 IDs.
  Zero-match batches also acquire leases; full stock skips observation and acquisition.
- Any needs no qualification read; it uses the same
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
holds expire. Pool misses never query a source or acquire a new hold; direct lookup
misses never scan alternatives or retry busy identities. Restart
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

Runtime Boundary uses Tasks with empty supply and proves Identity and Phone
queries executing due Workers through real delivery and Result observation. Redis
Owner checks independent phone replacement, removal, shared numbers, corruption,
concurrent writes and startup rebuilding, plus direct/Pool execution races.

Task configuration now lives in the Task descriptor. Recreate Tasks in a new scope;
old descriptor formats are rejected, and old Matching data is neither read nor
cleared. HTTP, existing facts/index formats and Score encoding are unchanged;
the independent phone namespace is additional. Local stock
is still lost on restart and outstanding holds expire.
