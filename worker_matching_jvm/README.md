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

Pacer calls Matching from its existing refill and dispatch Producers. Refill
exact-candidateizes due ordinary HOT before qualification, preserving Worker time.
Matching receives opaque generation fences and starts its own 60-second TTL at
actual admission. Only TaskItem assignment creates an execution lease.

| Stage | Authority and fence |
| --- | --- |
| Supply | Pacer observes mark=0 HOT and exact-candidateizes; aged mark=1 is independently recycled |
| Qualification/admission | Each Pool reads offered identities, retains their fences and establishes local TTL |
| Coordination | Several Pools may accept the same generation; total budget counts actual entries |
| Execution | Kernel exact-acquires a due Pool fence or current due identity; only its new execution fence permits Item claim |

**Cross-Pool failure contract:** Pool A's successful refill remains committed if
Pool B's maintenance subsequently fails. The exception ends the remaining batch and reaches the
existing Pacer failure path. The next normal round observes new shortfalls.
There is no rollback or automatic replay. Within each current maintenance implementation,
Redis reads and fallible validation/matching finish before its bounded local commit.
This partial-success contract is independent of atomic Facts/index writes below.

```text
Worker / Platform facts -> one Lua -> enabled Matching indexes
NORMAL Task descriptors -> Group/Pool target MAX -> maintenance deficits
  -> Pacer HOT head -> Kernel exact candidateize -> offered generation fences
  -> qualification and admission -> shared Pool resource
messageId -> WorkerQuery(executorName, input) -> fixed function table
  -> strategy interpretation/grouping -> Pool range take / identity / phone index
  -> messageId -> WorkerCandidate -> current address
  -> Kernel strict observed / current execution acquire -> exact Item claim -> Command
```

Maintenance policies receive opaque candidate fences, never a lease acquisition capability. They cannot
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
then `refill(group, declarations, candidateScores)` for candidateized Groups. Matching selects
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
production Pool strategies return their original nonzero fence. Refill takes a
Map of opaque fences; storage creates and checks its own admission TTL.
Current Pool take reads no Redis or Facts. Function results are candidates only;
Kernel retains execution admission.

Identity and Phone functions return `expectedScore=0` as an identity hint. Catalog
preserves that value without reading WorkerScore, filling in a fence or downgrading
a nonzero expectation. Pacer consumes zero to call `acquireCurrentHotScoreLeases`
by IDs; nonzero fences go unchanged to exact observed acquisition. Kernel receives
no sentinel. Both paths require strictly due HOT with either mark and write a
mark=0 execution deadline. Current/future HOT and RECOVERY cannot be acquired.
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
| `refill(group, targets, offered, maxAccepted)` | Qualify supplied IDs and return actual admissions with opaque fences and local TTL |

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
Item encoding, Facts and index keys remain unchanged. Worker Score cutover
separately requires a new scope for the high-mark layout.

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
| `refill` | Target interpretation, supplied-ID qualification and membership calculation |
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
| `any` | Empty target only, unconditional candidate stock; no Facts or property views |
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
executor interface. It stores one identity Entry per Group/Pool, the opaque
candidate fence, local TTL, admission order and finite view memberships. It never receives a
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
Properties time invalidation remains a separate commit and never edits Pool entries directly.
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
its identity, range memberships, admission order, opaque generation fence and local TTL deadline.
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
admission; range decisions remain local. Each Group batch may offer the same
fences to later Pools. The 100-entry call budget counts actual admissions, including
replacement of an existing identity, and does not promise to fill every Pool.

Global lazy expiry runs once at Group shortage observation by invoking current resource-owned
pool cleanup. It does not store a second inventory. Group access expires only its
own pool; diagnostics and capacity reads do not sweep unrelated pools. Admission
uses observed shortages and establishes each entry's local TTL and rechecks shared
hard capacity at commit, without revalidating the whole inventory.
Other expiry may release capacity after the round's budget was observed, so a
round may conservatively underfill. A Pool with no available capacity reports zero
refill deficit, preserving the existing full-stock supply suppression. Duplicate same-fence admission does not extend TTL.

Pool TTL starts at actual admission and is 60 seconds, independent of the Worker
generation time and Pacer's separate candidate age threshold. Same Worker/fence
supply is a duplicate: it neither consumes admission budget nor extends TTL.
A new generation is qualified again and replaces its old entry and views even
when storage is full. Replacement uses the same capacity unit but consumes this
call's processing budget. A changed generation which no longer matches removes
the old entry. Stale selection references cannot consume a replacement.

Pool consumption is destructive. TTL only governs stock take; once taken, a
candidate carries no TTL and is checked only against current exact/due Score
conditions. Matching does not renew or repair cached fences. Properties or another
assignment can invalidate a cached fence; Dispatch rejects it without fallback.
No match, capacity refusal, failed admission, ambiguous response and process loss
leave committed candidates for Pacer's normal bounded recycling, not rollback.
Pool queries never trigger targeted HOT reads; rare predicates may wait. Direct
queries can independently acquire due Workers without stock. No allocator or
rule-change sweep is added.

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

Server separately asks Worker Score Owner to advance past times after APPLIED
facts writes. Past HOT atomically becomes mark=0 at Redis current time; past
non-cold RECOVERY retains mark when advancing. Both preserve polarity; cold
RECOVERY and current/future holds do not change. The cold exception preserves
initial network activation.
If invalidation wins first, the old Pool fence fails execution acquisition.
If assignment wins first, the execution hold and its result association survive.
Facts/index and Score remain separate commits: there is no property-version
transaction, guaranteed retry or repair. Candidate qualification happens after
candidateize, so invalidation after that point cannot be cleared by a second
admission mutation. Changed past HOT returns to the ordinary lane with a new
generation; subsequent qualification cannot revive the old fence. After the
current slot passes, normal Refill may supply it without waiting for candidate
recycling. Group roots, deficits and round budgets still govern admission.

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
- Refill recycling: one read-only candidate-head Lua per selected Group and one
  exact recycle Lua for a nonempty batch, even without supply shortage. It has a
  separate 100-per-Group/1000-per-round budget and shares existing Group rotation.
- Each Group needing supply: one mark=0 due-head Lua, one exact candidateize Lua,
  then at most one projection read per participating policy. No successful
  candidateization means no Matching call. Full stock skips this supply path.
- Any needs no qualification read and never discovers substitute IDs.
- Final execution: one bounded Lua per nonempty strict/current partition, each
  with Redis TIME. Only strictly due HOT can gain execution. Address, Item claim,
  publication and Result paths retain their separate costs.

These are command budgets, not throughput promises. Diagnostics report actual
stock, admissions, consumption, expiry and capacity. Pacer's CANDIDATEIZE stage
retains attempted batchSize and transitioned count; a failed call is ambiguous,
not proof that every member was rejected. No diagnostics thread is added.

Candidate failures do not gate Item expiry, exhaustion or idle settlement. Pool
misses do not query a source or acquire a lease. Direct misses do not retry another
identity. Restart loses stock; Main-supplied Groups recover generations through
normal candidate recycling and refill, without adoption or replay.

Focused tests cover qualification, target paging, shared Pool supply, replacement
at capacity, unchanged-fence TTL, stale Entry references and cross-Pool partial
success. Redis Owner proves candidateize-before-projection, no substitute discovery,
Properties/assignment ordering, equal-score head progress, execution races and
command budgets. Controlled Pool clocks establish admission-time TTL. Runtime
Boundary and Dynamic Matching retain their workload and real execution witnesses;
Call Performance retains its separate measurement claim. See [TESTING](../TESTING.md).

Runtime Boundary uses Tasks with empty supply and proves Identity and Phone
queries executing due Workers through real delivery and Result observation. Redis
Owner checks independent phone replacement, removal, shared numbers, corruption,
concurrent writes and startup rebuilding, plus direct/Pool execution races.

Task configuration now lives in the Task descriptor. Recreate Tasks in a new scope;
old descriptor formats are rejected, and old Matching data is neither read nor
cleared. HTTP and existing facts/index formats are unchanged. Worker Score uses
the high-mark layout in a new scope, without compatibility decoding. Local stock
is lost on restart; candidate recycling and execution expiry have separate roles.
