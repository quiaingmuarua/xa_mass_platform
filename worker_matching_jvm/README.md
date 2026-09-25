# XA Mass Worker Matching JVM

Status: current Worker Properties, fixed query functions, bounded Facts qualification, Phone Index and Pool Owner.

In the [global behavior loop](../doc/kernel/scheduling-overview.md#system-behavior-model),
this module supplies Matching candidates and maintains the admitted Properties
and indexes used by subsequent decisions. The latter is also part of Convergence.
Kernel retains execution admission; this Owner defines the local resource and
query contracts rather than the entire behavioral domain.

Matching saves accepted Worker attributes, maintains property indexes, and supplies
bounded candidates through Pool or Index resources. Its public entrypoints follow
three caller paths:

| Path | Entrypoint and flow |
| --- | --- |
| Properties update/observation | Server calls `WorkerProperties` for complete Worker replacement, independent Platform patch and bounded Facts reads. `FactsIndexStore` implements the interface directly. |
| Pool supply | Pacer calls `WorkerMatching.observeRefillDeficits/refill`; `PoolRefillCoordinator` organizes targets and rotation, policies qualify offered identities, and Pools retain candidates. |
| Query consumption | Server admits queries through `WorkerMatchingCatalog`; Pacer calls `WorkerMatching.take`. `DefaultWorkerMatchingCatalog` validates, groups and correlates fixed function calls to Pool or Index resources. |

`WorkerMatchingCatalog` extends the three-operation Pacer port with query and supply
normalization only. Properties types and mutation results belong to `WorkerProperties`;
Catalog has no Facts JSON, Redis connection or resource-close responsibility.

The four internal responsibilities are Properties/index persistence, Query interpretation
and correlation, Refill coordination/qualification, and in-memory candidate storage.
`MatchingComposition` creates stable Catalog and Properties instances and owns their
shared resources. Facts and property HASHes persist across restart; Pool entries and
Refill cursors remain local. This interface split changes no keys, formats or atomic
write rules and requires no new scope or deployment configuration.

Matching uses an immutable, construction-time name-to-function table for Item
consumption. Functions interpret local JSON inputs and choose resource access.
Function names are protocol identities, independent of Pool resource names. Independent `workerId`,
`worker.phone` and qualified `worker.messaging.phone` functions locate identity hints without Pool stock; storage itself is not an executor. Pool maintenance and Item
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
| Coordination | Admission removes the generation from the remaining Pool inputs; budget counts actual admissions |
| Later supply | Only newly candidateized generations enter refill; existing Pool stock is never copied |
| Execution | Kernel exact-acquires a due Pool fence or current due identity; only its new execution fence permits Item claim |

**Cross-Pool failure contract:** Pool A's successful refill remains committed if
Pool B's maintenance subsequently fails. The exception ends the remaining batch and reaches the
existing Pacer failure path. The next normal round observes new shortfalls.
There is no rollback or automatic replay. Within each current maintenance implementation,
Redis reads and fallible validation/matching finish before its bounded local commit.
This partial-success contract is independent of atomic Facts/index writes below.

```text
Worker facts -> one fixed Lua -> Worker Facts and enabled property HASHes
Platform patch -> one fixed Lua -> Platform Facts only
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

Pacer concatenates declarations by Group and passes `observeRefillDeficits(refillByGroup)`.
The immutable Map retains input Group order and only positive shortage counts;
absent Groups have no observed demand. Pacer limits its raw candidate read to the
smaller of that count and its per-Group ceiling, then calls
`refill(group, declarations, candidateScores)` for candidateized Groups. Matching selects
Pool maintenance by resource name, normalizes targets and MAX-merges them.
Composition supplies the rotation base order: proof-facts, country, any, messaging.
The Refill coordinator interprets the policy's fixed targetBatching capability, never its name:
Country uses ALL; the other fixed policies use PAGED.
Input limits are 100 Groups and 10,000 declarations, with no 100 Group/Pool-coordinate
limit. Country receives up to 10,000 targets without a query cursor. Other policies
receive at most 100 targets per page. Observation does not advance those cursors;
an actual refill attempt does.

Inactive-cursor cleanup belongs to `observeRefillDeficits`; expired stock is reclaimed there only under capacity pressure.
Counts retain existing target-page, Pool aggregation and capacity rules; they are
neither reservations nor distinct Worker counts, because target predicates may overlap and counts do not reserve identities.
`count` is a shortage watermark, not an inventory ceiling: 200 matching residents
against a target of 100 need no refill and are not removed; 80 against 100 request
20. Pacer bounds its observation by this shortage. Once candidates have been
supplied, the selected target predicates decide qualification, without clipping
admission to their counts. Qualified offers are selected within the call budget
in offered order, then appended by bucket; actual storage capacity bounds admission. Existing target-page selection and Pool rotation are unchanged.
An already-satisfied watermark therefore does not skip a supplied Facts batch;
its bounded qualification read and corruption checks still apply. No supplied
candidates means no qualification read.
The hint and later admission may observe different stock. Refill independently
validates and works without a prior hint or Task registration. Both operations may redo
bounded local target normalization; they retain no shared execution plan, policy
view or inventory transaction. Server admission and Main do not maintain stock.

Each successful Pacer candidateization is supplied once. Catalog offers the remaining
identities to each Pool in the existing rotation; actual accepted IDs are removed
before the next Pool's qualification. Rejected identities may reach a later Pool.
Empty input never scans or republishes existing stock. There is no replay ledger,
Worker-to-Pool registry or cross-Pool transfer operation.

This isolates each generation in the production supply path, not every cached
Worker identity for all time. A new generation can enter another Pool while an
old entry remains until take or TTL expiry. Direct functions find identities
independently; execution changes WorkerScore without notifying or editing a Pool.
A Pool may still return its old fence, which Kernel rejects at exact acquisition.

Server admission uses `WorkerMatchingCatalog.normalizeQuery(group, WorkerQuery)`.
The Pacer port exposes shortage observation, candidate refill and
`take(group, queriesByMessageId)`. The function name belongs to each Item, not an
outer Task binding. Unknown or Group-disabled names fail without fallback; names
are matched exactly, never classified by prefix. Item functions do not depend on Task supply declarations. A Task with no supply can
consume stock maintained by another Task. Group resource enablement controls indexes
independently of demand, including Phone Index updates. Startup preserves existing mappings without scanning Facts.

Take accepts at most 1000 nonblank message IDs at the Catalog entry. Catalog captures input order and
normalizes the complete batch before any consumption. It invokes each function
once in first-appearance order with its messageId-to-local-input Map. The current
Pool functions group equivalent selections, process groups in first-appearance
order and allocate within each group in Item order. Catalog returns an immutable
Map in original request order, omitting misses. Duplicate Workers within the Catalog call, including duplicates returned by one Pool,
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
| `worker.messaging.available` | `{}` or an object with optional `country` list; consumes Messaging Pool stock |
| `worker.messaging.phone` | Object with required nonblank `phone` and optional `country` list; qualified Direct lookup |
| `proof.worker.facts` | String scalar fields `proofPool`, `proofTarget`, `proofEnabled`, or exclusive `convergenceSlot` |
| `workerId` | One nonblank Worker ID string; no facts or stock read |
| `worker.phone` | One exact nonempty Worker phone string; Group-enabled independent property index |

Each Pool function rejects unknown local fields and preserves its previous qualification
rules. Countries remain strict uppercase two-letter codes. Empty object means no
additional condition within a Pool function; empty arrays are not ANY. Messages
with a sender phone use qualified Direct lookup and declare no Pool supply. Other
Messages use Messaging Pool ANY/country stock. SMS listeners use Country Pool; cancellation
uses the independent workerId function. The retired default function and ID-list
choice have no aliases or replacement multi-ID function.

```json
{"workerSelector":{"executorName":"worker.messaging.phone","input":{"country":["CN"],"phone":"+8613800000000"}}}
```

The [PoolRefillPolicy interface](src/main/java/com/xa/mass/workermatching/PoolRefillPolicy.java)
now owns only the refill side:

| Operation | Contract |
| --- | --- |
| `targetBatching()` | Fixed ALL or PAGED target organization; independent of registration name |
| `normalizeQuery(group, query)` | Idempotent target admission; no Redis read or inventory mutation |
| `deficits(group, targets)` | Immutable observed shortages, never reservations |
| `refill(group, targets, offered, maxAccepted)` | Qualify supplied IDs and return actual admissions with opaque fences and local TTL |

`EligibilityQuery` remains the quantity-free string-list structure for supply.
`RefillTarget(poolName,target,count)` carries the resource name and quantity. TaskDescriptor
stores `refill` with 0..100 declarations; each count is 1..1000. Item queries remain
WorkerQuery and must be explicit at both finite append and managed Call. They cannot
generate, modify or imply Pool supply.

Country maintenance receives up to 10,000 targets; other policies receive at most
100 per call. Catalog admits at most 1000 unique offered IDs. Internal maxAccepted
is the remaining admitted input budget, without another fixed size ceiling. All structure and fallible qualification checks precede admission.

Task descriptors switch once to `refillJson`; recreate Tasks in a new scope. Old
Rule/target fields, missing supply and corrupt entries fail reading. No dual read,
conversion, default supply, migration or cleanup is provided. Existing WorkerQuery
Item encoding and Facts keys remain unchanged. Property HASH and Worker Score
cutovers each require a new scope; neither has a compatibility reader.

## Fixed Resource Composition

`MatchingComposition` creates each enabled resource once and injects it into its
users. Pool maintenance and named consumer functions receive the same WorkerCandidatePool;
Direct functions receive the PropertyIndex lookup interface, backed by one shared
RedisHashPropertyIndex bound to `phone`. Resources do not know executorName.
Functions need not implement a maintenance interface. There is no dynamic registry
or executorType, and composition does not create a Pool or policy for Identity/Phone-only Groups.

| Package | Owned implementation |
| --- | --- |
| `functions` | Local input interpretation, resource access and candidate correlation |
| `pool` | WorkerCandidatePool single-bucket queues, admission age and CandidateBudget |
| `refill` | Target interpretation, supplied-ID qualification and one bucket key per offer |
| `buckets` | Pure Proof tuple encoding and partial-query bucket matching |
| `index` | PropertyIndex lookup contract and property-bound Redis HASH reads/key definitions |
| `storage` | Facts encoding, fixed atomic Facts/HASH writes and shared Redis connection |

WorkerCandidatePool receives only a clock and shared CandidateBudget. Composition
retains the fixed Pool map; the Refill coordinator invokes lazy cleanup only when a requested
Group-Pool has no room and reads budget diagnostics. There is no self-registration
or resource manager. Index resources have no Pool, capacity, policy or function dependency.
Country and Messaging receive the bounded Worker Facts reader; Proof receives an
atomic Worker/Platform snapshot reader. Messaging uses its existing pure eligibility
helper. Proof refill and query share the complete tuple codec and bucket matching.
All fixed policies use PoolMaintenance for fence validation, complete fallible
qualification, batch budgeting and grouped offers. No admission observes resident
Worker identities, compares generations, replaces entries or removes old mismatches.
Country counts its complete target set from one bucket-count snapshot.

`FactsIndexStore` receives immutable Group-to-property-name sets. Worker replacement
uses one fixed preflight-and-write Lua; Platform patch uses a separate fixed script
and never updates Worker property mappings. There are no injected Lua fragments,
per-Group generated scripts or index-specific write callbacks. Group function
configuration enables `phone` once for either Phone function, without separate
index configuration or Task demand. Other property names use the same mechanical
HASH implementation; no account query is configured by this change.

Server registers one managed `MatchingComposition` Bean and two interface Beans,
`WorkerMatchingCatalog` and `WorkerProperties`, without independent destroy callbacks.
`catalog()` and `properties()` always return the same instances. Composition performs
no Redis I/O or rebuild at startup; failed assembly closes its store. Composition
closes the store idempotently after platform callers stop, never the Server-owned
RedisClient. Properties and indexes share the store's one lazy connection. Pure
Pool/Identity Catalog tests construct their resources without a Redis Store.

| Pool | Maintenance |
| --- | --- |
| `any` | Empty target only, unconditional candidate stock; no Facts or property views |
| `country` | Valid Worker country Facts and local country buckets |
| `messaging` | enabled messaging and country buckets; empty or country-only targets |
| `proof-facts` | complete proof tuple buckets with rule-owned partial matching |

Composition explicitly supplies the immutable global-function set containing
`workerId`; Catalog applies no function-name exception. It remains available even
for an unconfigured Group. Every Pool and Pool function requires
explicit Group configuration; functions require their corresponding resource. A
Group with no Pools can use Identity and an explicitly enabled Phone Index. No
implicit Pool stock or managed supply is created.

## Country Facts and Memory Buckets

Country stores each entry in one country bucket. Its ANY means any entry with a
valid `[A-Z]{2}` Worker country; the `any` Pool does not require Facts. Single-country
poll visits one bucket; multi-country poll visits only selected buckets in key order,
with FIFO inside each. ANY visits buckets in creation order. Removing an entry
releases one capacity unit; empty buckets are removed without reverse links.

Country refill issues at most one Worker Facts HMGET for its live offered IDs,
never Platform Facts, all-Worker scans or a public Catalog read. The shared stored
object decoder rejects malformed JSON before any admission. Missing Facts and
missing/invalid country values skip that Worker. Read failures admit nothing.
All decoding and target interpretation occur outside the inventory lock.

Targets retain string-list syntax. `CN+US / 100` means 100 combined entries, not
100 per country. Equivalent normalized sets use MAX. Overlapping sets each count
their matching entries, while capacity counts one Entry once. One bucket-count
snapshot serves the entire target set; no Entry copying or per-target stock scan
is needed. Admission matches the selected country union, or every valid country
when ANY is included; counts neither prioritize candidates nor stop admission.
There is no Country target page, cursor or source ZSET.

The old country, messaging and proof index namespaces are neither read, written,
validated, rebuilt nor deleted. Phone remains the independent discovery index.

## Messaging and Proof Facts Qualification

Every policy reads only the remaining Catalog-admitted identities, at most 1000. Any makes
no read. Country and Messaging each use one Worker Facts HMGET. Proof uses one
fixed EVAL_RO containing two HMGETs, so Worker and Platform rows share a Redis
execution snapshot. A missing Worker row is ineligible; missing Platform is an
empty map. Malformed present Facts fail before this Pool changes inventory. This
strict internal read does not alter public loadWorkerFacts, whose two independent
reads retain row-local null-on-corruption behavior.

Messaging requires the exact string messaging.enabled=true and a valid uppercase
country. A fixed pure Java predicate is shared by Pool qualification and qualified
Direct Phone lookup. Pool entries have one country bucket; missing phone does
not disqualify an otherwise eligible Pool candidate. Country values are strings
without numeric Redis prefixes. Pool queries and supply targets reject phone conditions.

Proof keeps its three fixed proof fields and independent convergenceSlot.
proofTarget and proofEnabled normalize to yes only for the exact string yes;
other stored values normalize to no. Each entry uses one complete JSON tuple,
including convergenceSlot. Partial queries select matching tuple keys in Java
without another view or copying entries. Omitted query fields impose no condition. Missing proofPool and convergenceSlot
values are JSON null, never the literal strings *, ~ or a delimiter. Quotes, Unicode and separator
characters round-trip through the same helper for qualification and query selection.

These reads transfer complete supplied Facts. They remove projection upkeep but
can transfer larger payloads; command counts alone establish no throughput gain.
Facts and WorkerScore still commit independently; this snapshot is not a property
version transaction with candidateization or execution acquisition.

## Identity and Phone Query Functions

```json
{"executorName":"workerId","input":"w-123"}
{"executorName":"worker.phone","input":"+8613800000000"}
```

Identity input is a nonblank string. It returns identity evidence without Redis,
Facts or Pool access. Pacer still checks existence, Group and Endpoint before Kernel
admission. Phone input is a nonempty exact string; there is no trim or telephone
format conversion. Its index depends only on Worker `phone`, not country,
`messaging.enabled` or Platform Properties. Generic `worker.phone` does not apply
Messaging qualification.

Qualified `worker.messaging.phone` uses that same index and then one strict Worker
Facts HMGET for the returned, deduplicated IDs. It requires a nonblank exact phone,
Messaging eligibility, the current Facts phone equal to the requested phone, and
any supplied country condition. Requests are grouped by phone for a single bounded
lookup. Each phone has at most one mapped Worker, assigned to the first compatible
request in original order. Repeated requests do not obtain additional Workers.
No match after filtering means no candidate: there is no overfetch, substitute
lookup, retry or allocation optimizer. Empty lookup skips Facts entirely. Malformed
present Facts fail the function; earlier function consumption remains committed.
The two reads are independent snapshots; a changed phone seen in Facts is rejected,
but there is no enduring qualification guarantee through execution.

Either Phone function enables the same physical index exactly once. The qualified
function needs neither Messaging Pool nor generic `worker.phone` enablement. It
returns `expectedScore=0` without observing Score or editing Pool inventory. Kernel
still acquires only strictly due HOT; an active execution hold cannot be preempted.
If that Worker remains cached in a Pool, Direct acquisition makes the old fence
stale without a Pool notification.

Cutover removes phone inputs from `worker.messaging.available` and `worker.phone`
conditions from Messaging supply targets. End affected old Tasks before deployment
or use a new scope. Stored Task/Item queries are not migrated or compatibility-read;
Facts and historical Result formats are unchanged. The property HASH cutover below
requires a new scope. No old data is deleted.

`PropertyIndex.lookup(group, values)` accepts caller-bounded unique nonempty strings
and returns an immutable value-to-workerId map in request order, omitting misses.
An empty batch performs no Redis I/O. RedisHashPropertyIndex uses one HASH per Group
and property: raw property value is the field and workerId is the value. One HMGET
serves the batch. Values are not trimmed, hashed or converted to numeric scores.
The index is persistent and non-consuming, with no TTL, sampling or Pool capacity.

Each value maps to the last valid Worker Properties writer. Cross-call order is
Redis execution order; within a batch the supplied member order determines the
winner. Other Workers retain their Facts but are not fallback candidates. Removing
the winner leaves a miss until a later Worker report establishes a mapping again.
An unchanged Worker report also reasserts its mapping while Facts may return
UNCHANGED. Platform patches do not reassert Worker mappings. Qualified lookup does
not seek an older mapping when the current winner fails eligibility.

Index reads fail on wrong HASH types rather than repairing them. Phone observation
and execution admission remain independent, with no property version or lasting
value guarantee. Functions depend on lookup capability, not Redis implementation;
this exact lookup interface does not prescribe the storage of other future indexes.

`PoolMaintenance` handles supply target interpretation, offered-ID qualification
and one bucket key per candidate. QueryFunctions interpret Item input independently.
The package-private `PoolCandidates.take` helper groups equivalent admitted inputs
in first-appearance order, invokes each already-prepared batch poll and associates
results in original message order. It owns no inventory or persistent correlation.

The internal `WorkerCandidatePool` is a concrete memory container:

```text
Rule Pool instance -> Group -> bucketKey -> ArrayDeque<Entry>
Entry = immutable WorkerCandidate + admission time
```

Country and Messaging use country keys. Any uses one ordinary fixed key. Proof
uses a canonical JSON tuple of proofPool, proofTarget, proofEnabled and convergenceSlot;
missing values remain distinct from all strings. Partial Proof queries decode each
key once from one directory snapshot per call and select matching keys in Java.
They visit bucket metadata, not candidate entries, and maintain no second index.
This exchanges the former multiple-view writes for directory work on partial queries;
it is not a throughput claim.

- `offerBatch(group, key, candidates)` appends the capacity-fitting prefix and returns
  accepted candidates. Repeated identities and fences are independent occurrences.
- `pollBatch(group, keys, limit)` uses lexicographic key order and FIFO within a bucket;
  empty keys match nothing. `pollAnyBatch(group, limit)` uses bucket creation order.
- Poll removes each physical entry once under the Pool lock, skips entries at least
  60 seconds old, and returns immutable candidates with no TTL. It samples time once.
  There is no global FIFO, priority, identity deduplication or select/commit window.
- `countByKey` reads queue sizes only. Counts include lazily retained old entries
  and duplicates, and are neither unique-Worker nor executable-Worker counts.
- Empty buckets and Groups are removed during consumption or cleanup. Shared
  capacity accounts for physical entries and has no generation replacement privilege.

Full input admission and all fallible qualification/key interpretation precede
inventory mutation. Facts I/O is outside the Pool lock. Later failures retain earlier
admissions or consumption. Catalog still filters repeated Worker associations in
one take call without restoring entries or polling replacements.

Refill may underfill after concurrent consumption or exceed an observed target
after concurrent admission. Approximate resident counts may also defer refill until
old entries are consumed or reclaimed. Targets reserve neither identities nor leases.

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
          functions: [worker.country, worker.messaging.available, worker.messaging.phone, worker.phone]
    task-rpc:
      refill-by-worker-group:
        demo-sim:
          - '{"poolName":"country","target":{},"count":100}'
```

Managed supply uses complete JSON declaration strings so an explicit empty target
survives Boot binding and missing target still fails the shared strict decoder.
Lab explicitly enables any/worker.any and supplies 1000. Preview SMS supplies
country/{} /100; only Messages without senderPhone declares messaging supply. Unconfigured managed and
ordinary Tasks save no supply.
Phone Index depends on Group resource composition, never Task demand.

## Inventory and Refill Bounds

Current Pools retain candidates only in this process: at most 100 resident
Group/Pool pools, 1000 entries per pool and 10000 in total. Each entry carries
its immutable WorkerCandidate, opaque generation fence and admission time.
Tasks have no reserved share. Restart discards all stock without adoption.

The single-flight refill Producer uses Main-selected NORMAL RUNNING Tasks, with
a 50ms completion-relative interval. INITIAL does not prewarm. Closed, parked or
disabled Tasks supply no later demand; an in-flight round is not a lifecycle lock.
Pacer rotates Groups with instance ceiling `B` (default 100, 1..1000) and 1000
raw rows per round. A positive deficit requests `min(B, deficit, remainingBudget)`
rows and charges that exact request, even if no candidates qualify. Each attempt
supplies only its successful candidateizations. Increasing `B` does not change
Task declarations, watermarks or Pool capacity. Remaining shortage
waits for a normal round; no existing Pool stock is observed for supply.

`PoolRefillCoordinator` rotates the explicitly ordered Pool policies. PAGED policies retain
bounded query pages, including empty attempts; ALL policies receive the complete
bounded target set. Observation never advances the target cursor. A maintenance policy
qualifies the supplied batch completely before appending candidates by bucket.
The call budget selects qualified offers in input order; target counts do not cap
acceptance. Each Pool receives only identities not already admitted by an earlier
Pool in this batch. The admitted input collection bounds actual admissions; internal helpers do not repeat the Catalog ceiling. Single-Pool
handoff is a production supply contract, not identity uniqueness enforced by storage.

TTL is a local 60-second age filter from each actual admission, independent of
Worker generation and Pacer candidate recycling. A repeated Worker/fence creates
another entry with its own admission time and does not renew the old entry. A new
generation is another entry, may coexist with the old one and can be refused at
capacity. Failed requalification leaves existing entries untouched.

Normal operations do not perform an unconditional global expiry sweep. Counts and
capacity reads do not expire stock. Nonempty shortage observation checks whether
any requested, registered Group-Pool has zero capacity. If so, the coordinator invokes each
Pool's lazy cleanup at most once before computing deficits. Cleanup removes expired
heads per bucket, including idle Groups, without evicting live entries. Empty supply
still clears inactive cursors but does not sweep stock. Independent strategies without
this container retain their own stock behavior. A full Pool still reports zero deficit;
a race after observation can refuse admission until a later normal round.

Pool consumption is destructive. TTL only governs stock take; once taken, a
candidate carries no TTL and is checked only against current exact/due Score
conditions. Matching does not renew or repair cached fences. Properties or another
assignment can invalidate a cached fence; Dispatch rejects it without fallback.
No match, capacity refusal, failed admission, ambiguous response and process loss
leave committed candidates for Pacer's normal bounded recycling, not rollback.
Pool queries never trigger targeted HOT reads; rare predicates may wait. Direct
queries can independently acquire due Workers without stock. No allocator or
rule-change sweep is added.

## Persistent Properties and Indexes

All keys use `xa_mass:<scope>`:

```text
:matching:worker:facts:<group>                         HASH workerId -> Worker JSON
:matching:worker:platform-properties:<group>           HASH workerId -> Platform JSON
:matching:worker:index:<encoded-group>:<encoded-property>
                                                      HASH exact property value -> workerId
```

Group and property names use UTF-8 Base64 URL without padding; values are raw HASH
fields. Worker and Platform Facts retain their existing keys and JSON formats.
The earlier property-HASH cutover required a fresh scope; the current interface split
uses that layout unchanged. Normal restarts retain HASH mappings;
startup does not scan Facts, clear indexes or reconstruct overwritten winners.
Retired reverse HASHes, value SETs and qualification indexes are not read, written,
validated or cleaned. There is no compatibility reader, migrator or version marker;
mixed old/new processes are unsupported. Missing indexes remain empty until normal
Worker Properties reports populate them, including unchanged reports.

## Facts Writes and Index Maintenance

`WorkerProperties` owns `upsertWorkerFactsBatch`, `patchWorkerPlatformProperties`
and `loadWorkerFacts`, including input validation and mutation result mapping in
`FactsIndexStore`. Display reads preserve missing/corrupt rows as null entries;
internal qualification reads remain strict and Proof still uses one atomic
Worker/Platform snapshot. Neither read path is routed through Catalog.

Prepare creates no facts. An admitted Adapter observation replaces the complete
Worker string Map, including an observed empty Map, while preserving Platform
Properties. Platform patch requires an existing Worker facts row and changes
only supplied Platform fields; null removes a field. Nested Platform JSON,
including empty arrays and objects, retains its shape.

A batch of 1..100 Worker replacements uses one fixed Lua. It checks relevant HASH
types and decodes every supplied row's existing Worker/Platform Facts and new Worker
Facts before any write. For each Worker and configured property, the old value is
read from existing Facts, so no reverse index is stored. Absent/empty values have no
mapping. When a value changes, the old field is deleted only if it still maps to
that Worker at the actual write step. The new nonempty value is then mapped to the
Worker and changed Facts are stored. Earlier rows in the batch cannot leave a stale
delete decision that erases a later ownership change. Known preflight corruption
fails before mutation; this is not a rollback guarantee for arbitrary Redis failures.

One bounded Platform patch uses its own fixed Lua and does not access property
indexes. Both paths retain stored Facts validation, opposite-Facts preservation,
existing statuses and nested JSON shape semantics. Neither pre-reads in Java nor
retries conflicts. There is no uniqueness rejection or automatic fallback search.

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

Startup performs no index or Facts scan. Corruption is reported by the affected
read/write operation; startup neither repairs it nor chooses a new duplicate winner.
Refill reads Facts only for the remaining
Pacer-issued identities not yet admitted in that batch; there is no
autonomous source take, index repair scan, lease registry or per-Task publication.

## Cost, Failure and Proof

- Target resolution: local only, no Redis; Task configuration reads belong to Task Owner.
- Group shortage observation: zero Redis commands; local target aggregation and
  capacity-pressure cleanup only when a requested Group-Pool has no room. Country uses one bucket-count snapshot for
  all targets; other policies receive only visited target pages.
- Pool stock counts and take: zero Redis commands or facts reads.
- Identity take: zero Redis commands. Nonempty Phone take: one HMGET for the Catalog-admitted
  unique exact values, returning at most one identity per value, without a lease read.
- Qualified Messaging Phone take: the same bounded lookup plus at most one HMGET
  for returned identities, transferring complete Worker Facts. No Pool or Score read.
- Refill recycling: one read-only candidate-head Lua per selected Group and one
  exact recycle Lua for a nonempty batch, even without supply shortage. It has a
  separate 100-per-Group/1000-per-round budget and its own Group rotation hint.
- Each Group needing fresh supply: one mark=0 due-head Lua, one exact candidateize
  Lua for a nonempty head, then one HMGET for Country/Messaging or one EVAL_RO
  (two HMGETs) for Proof, only when that policy needs qualification. A full-stock
  deficit suppresses normal Pacer supply. If a batch is supplied anyway, it still
  qualifies before capacity refusal; no resident-identity pre-read skips that work.
- Any needs no qualification read and never discovers substitute IDs.
- Final execution: one Lua per at-most-100-member chunk of each nonempty strict/current partition,
  each with Redis TIME. That storage chunk is not a logical assignment ceiling. Only strictly due HOT can gain execution. Address, Item claim,
  publication and Result paths retain their separate costs.

These are command budgets, not throughput promises. Diagnostics report actual
stock, admissions, consumption, expiry and capacity. Pacer's CANDIDATEIZE stage
retains attempted batchSize and transitioned count; a failed call is ambiguous,
not proof that every member was rejected. No diagnostics thread is added.

Candidate failures do not gate Item expiry, exhaustion or idle settlement. Pool
misses do not query a source or acquire a lease. Direct misses do not retry another
identity. Restart loses stock; Main-supplied Groups recover generations through
normal candidate recycling and refill, without adoption or replay.

Focused tests cover qualification, target paging, single-Pool admission, independent
duplicate entries, bucket ordering, lazy TTL, pressure cleanup, at-most-once entry
consumption and cross-Pool partial success. Name-independent ALL/PAGED behavior and explicit rotation have focused
proof. Redis Owner proves bounded strict Facts snapshots, ignored retired indexes,
candidateize-before-qualification, no substitute discovery,
Properties/assignment ordering, equal-score head progress, execution races and
command budgets. Controlled Pool clocks establish admission-time TTL. Runtime
Boundary and Dynamic Matching retain their workload and real execution witnesses;
Call Performance retains its separate measurement claim. See [TESTING](../TESTING.md).

Runtime Boundary uses Tasks with empty supply and proves Identity and Phone
queries executing due Workers through real delivery and Result observation. Redis
Owner checks phone replacement/removal, last-writer duplicate handling, conditional
old-value deletion, batch preflight, concurrent writes, restart retention and ignored
legacy keys, plus direct/Pool execution races.
Qualified Phone proofs cover no-Pool configuration, one lookup plus one Facts read,
phone changes between reads, whole-batch admission, corruption and earlier Pool
consumption. Real Pacer refill places a Worker in Country Pool before qualified
Direct acquisition invalidates its cached fence; Product Coexistence retains the
original send/receipt/lifecycle deadlines.

Task configuration now lives in the Task descriptor. Recreate Tasks in a new scope;
old descriptor formats are rejected, and old Matching data is neither read nor
cleared. HTTP and Facts formats are unchanged; property HASH indexes require the
fresh scope described above. Worker Score uses
the high-mark layout in a new scope, without compatibility decoding. Local stock
is lost on restart; candidate recycling and execution expiry have separate roles.
