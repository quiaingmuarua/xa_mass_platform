# XA Mass Proof Selection

Status: current repository proof-lane registry entrypoint.

Tests are organized by mechanism claim, not by coverage percentage. A test is
valuable when it is the cheapest credible proof of one named invariant or
boundary. Repeating the same success path at another scale does not create a
new claim.

[Proof Registry](doc/testing/proof-registry.md) owns claims, nonclaims and
Primary Owners. Each linked Integration README owns its complete world,
workload, mutation sequence and oracle. This file owns selection, commands,
prerequisites and CI routing.

For project orientation, follow the [root summary](README.md), the
[global behavior loop](doc/kernel/scheduling-overview.md#system-behavior-model),
and the affected Owner before choosing proof. Matching, Execution and
Convergence are behavioral domains spanning these owners, not new proof lanes.
For a mechanism walkthrough, use the
[mainline code/proof pointers](doc/kernel/scheduling-overview.md#production-and-proof-pointers)
before selecting a lane. The pointers identify representative assertions, not
fresh run evidence. Docs Contract checks entrypoints, links and selected retired
terms; semantic agreement still requires comparing prose with its Owner/caller
and assertions.

Deployment configuration proof lives in `server_boot_jvm`: the default, Lab,
AgentForge and preview classpath configurations, explicit overrides and deployment
overlays. `server_jvm` tests and OpenAPI export use independent test resources;
only named real-infrastructure tests enable Redis and Pacers. Archive verification
checks the four host configurations, absence of application configuration in
nested platform/Scenario libraries, and absence of test configuration. Moving
configuration ownership retains the original proof-path selections.

The [SMS Reception business workload](scenarios/sms-reception-jvm/README.md#检查与验收)
owns its listening-order invariants and finite scenario acceptance. Business
workloads expose interactions and failure patterns beyond the focused fixtures;
discovered platform defects should gain regressions in the owning proof, while
the workload retains the business-level witness. Its dedicated
workflow runs product JVM and unified frontend checks, executable-owned
`smsCompositionIntegrationTest` with platform Task/Result HTTP routes blocked,
and the three-Worker real path through the composed Server and separate Host.
The fixed 1,000-Worker product workload is an explicit local acceptance command;
it does not replace or expand the platform proof lanes below.

The SMS Preview workflow uses the shared Distribution launcher with the preview
scenarios enabled, app_count=0 and only SMS workload submitted.
It compares the unified Scenario Preview archive against the current frontend build,
then runs the source SMS acceptance oracle against a fresh extraction's launcher
and artifacts without a Node or Gradle build step. Launcher lifecycle and archive
tests live in `distribution/server/src/test/python`; SMS assertions remain in the scenario.

```powershell
python -m pip install -r distribution/server/requirements-preview.txt
python scenarios/sms-reception-jvm/run_acceptance.py --build --scenario functional
python scenarios/sms-reception-jvm/run_acceptance.py --scenario lifecycle
python scenarios/sms-reception-jvm/run_acceptance.py --scenario concurrency
```

[App Checks](scenarios/app-checks-jvm/README.md#装配与证明) extends the same finite
scenario lane with four App Workers, hash-based one-shot outcomes, actual Handler
exceptions and independent result recomputation. Boot proves bounded preview and
Server restart reads. Its runner runs source and fresh Preview ZIP without frontend
changes or a new performance workload; SMS/Messages retain app_count=0 fixtures.

The App Checks assignment-window witness uses one real Worker and a threshold of
one: it observes the actual allocation projection, a nonempty qualification read
with rejected take, and execution after a real window transition plus normal
candidate recycling. Empty stock or a busy Worker alone cannot prove rejection.
Recovery observation is bounded at 180 seconds; deterministic function tests own
exact time boundaries and delayed/missing projection semantics. Redis Owner covers
snapshot command budgets, retained strict fences and read-failure consumption.
Non-window Boot regressions and the process runner explicitly use threshold 1000,
retain all original workloads/oracles and still execute the window function.
Preview defaults remain 60 seconds / 10 observations, without a strict quota claim.

[Scenario Coexistence](integrations/scenario-coexistence/README.md) adds a selected
Proof Gate lane for 12 shared Workers: real finite sends, SMS listening on the
same Worker, receipts after Task completion/closure, duplicates, ordering and
Worker run isolation. Ordinary Messages US/ANY Pool queries run in a separate
12-Worker scope with only Messaging demand; its managed Tasks remain INITIAL.
Functional and Pool selection run against both source artifacts and a fresh ZIP;
lifecycle runs against source artifacts. Independent CI steps retain later proof
results after an earlier failure, while every required failure still fails the job.
This lane makes no cross-Pool fairness or starvation-freedom claim.
The executable's `scenarioCompositionIntegrationTest` owns
platform/preview assembly plus real partial submission and delayed execution
evidence. The fixed 1k dual workload is explicit/manual, never part of ordinary CI:

```powershell
python integrations/scenario-coexistence/run_proof.py --build --scenario functional
python integrations/scenario-coexistence/run_proof.py --scenario pool-selection --port 18560
python integrations/scenario-coexistence/run_proof.py --scenario lifecycle
python integrations/scenario-coexistence/run_proof.py --scenario load-1k
```

Install `distribution/server/requirements-preview.txt`; use `--root` with a fresh
extracted Preview to exercise its packaged launcher. The dedicated
`.github/workflows/product-coexistence.yml` also accepts `scenario=load-1k` through
workflow_dispatch. CI uploads only safe summary/manifest evidence.

## Proof Model

Every mechanism claim has one **Primary Proof**. A repeated check elsewhere is
a prerequisite or Boundary Witness, not a second owner of that invariant.

| Proof | Contract |
| --- | --- |
| Owner Test | Local algorithm, legal transition, strict contract or concurrency fence |
| Boundary Proof | Encoding and behavior across adjacent owners or processes |
| [Worker Correctness](integrations/worker-correctness/README.md) | Exact identity, route, live Properties without Prepare, extension, Result and restart closure |
| [Worker Dynamic Matching](integrations/worker-dynamic-matching/README.md) | Loaded Matching query execution follows live facts, with actual executor and Result witnesses |
| [Worker Convergence Health](integrations/worker-convergence-health/README.md) | Named witness convergence after established state and process faults |
| [Worker Loaded Capacity + Recovery Stability](integrations/worker-loaded-recovery/README.md) | Sustained work, repeated Server recovery and resource bounds |
| [Android Worker](integrations/android-worker-proof/README.md) | Real Android lifecycle and fixed multi-process isolation |

World size is a fixture, not a proof level. Do not multiply topology, Group or
Adapter cardinality, mutation and workload dimensions into a Cartesian product.
Runtime Boundary owns protocol combinations; named convergence witnesses do not
imply all-offered success. A larger world does not replace correctness or create
a throughput, latency or resource claim. Shared Inventory materialization does
not transfer scenario or oracle ownership.

Worker registration proofs distinguish persistent Binding, Score membership and
network availability. Redis Owner proves cold NX initialization, concurrent
Binding selection, partial-stage retries and the 4/2/1 client-command budgets
for Prepare, Catalog registration and Binding reads. Runtime Boundary observes
cold Prepare followed by verified connection or Polling activation. These proofs
do not promise activation after lost evidence or atomic Binding/Score commits.

Properties/Candidate invalidation is a Redis Owner claim: one Redis-timed batch
advances past HOT generation and clears candidate mark; past non-cold RECOVERY
retains mark. Exact execution acquisition rejects old fences, and execution-first
ordering preserves future result fences.
Server tests own APPLIED-only best-effort invalidation. Facts and Score remain
separate commits without guaranteed repair.

Observed and current execution acquisition compete for strictly due HOT with
either mark. Current/future HOT, RECOVERY, missing and corrupt members cannot gain
execution. Pacer tests prove strict/current partitioning, no fallback, partial-call
failure without compensation and TRANSITIONED-only claim using the returned fence.
Runtime Boundary retains Pool, identity-hint and ordinary empty-supply witnesses.
Direct lookup needs no candidate hold and cannot preempt an active execution lease.

TaskItem generic terminal progression is a Redis Owner claim: tags 2..9,
strict maximum-score writes, same-tag slot advancement, exact ACTIVE claim
races, corrupt-score rejection, NX reappend, and the one-Lua/one-ZMSCORE batch
budgets. Server tests own application name validation and the separate
`items:states` query; its real Redis budget is one Task catalog read plus one
Score read. Redis Owner also proves conditional Result replacement by tag and
reported milliseconds, same-slot content updates, corruption rejection, and the
one-Score-Lua plus zero-or-one-Result-Lua observation budget. Runtime Boundary
uses actual Worker Handlers over WebSocket, Socket and Polling to witness send
completion followed by delivered, read and repeated reply observations, latest
content queries and subsequent execution. These proofs preserve the independent
Result/Score commit boundary; they do not claim replay or loss repair.

## Selection Decision

Project topology binding and startup order belong to Server/Boot tests. Redis
Owner proves descriptor/project-directory atomic writes, first-creation timestamps,
concurrent Project isolation, bounded ordering/truncation and corrupt-data rejection.
Project data is passive in Kernel tests; it requires no profile or Spring context.
Runtime clients resolve the configured Project/Group managed Task mapping once
before workload admission. Fixture migration preserves existing Worker counts,
Items, timeouts and execution assertions; Group registration alone creates no Task.

Task configuration and index proof belongs to Redis Owner: complete create-only
descriptors, concurrent configuration integrity and strict corruption rejection,
fixed one-Lua Worker Facts/HASH updates, independent Platform patches and restart
retention without index rebuilding. Matching tests prove the Pool maintenance interface and fixed query functions, unsupported condition
rejection, idempotent query normalization, shared MAX targets, actual Item quantities,
original fences and atomic destructive consumption. Catalog tests own messageId
correlation, equivalent/interleaved queries, shortages, immutable input-ordered
results, whole-batch admission before consumption and independent reuse of IDs
across calls. Redis Owner proves the Country path adds no Redis access during take.
Kernel tests own immutable
WorkerQuery capture and strict envelope JSON; Server tests own flat target configuration
and HTTP admission. Redis Owner rejects old property conditions without rewriting
records or substituting ANY.
The separately assembled Bucket Rule reads a bounded Worker/Platform Facts snapshot
and qualifies the offered identities through the public policy contract. Its
Redis Owner cases cover batch costs, corrupt-Facts rejection and generation invalidation;
Runtime Boundary supplies actual Worker execution for two sharing Tasks. Default
finite-ID target saturation and named-Rule identity rejection have focused proofs.
Pacer tests prove refill without Item reads, independently scheduled consumption,
round exclusions and opaque exact fences. Pacer tests also prove one unchanged
messageId-to-query submission and no reassignment or replacement take after address
or round-exclusion filtering. Runtime Boundary runs actual Workers
serving two Tasks from the same Eligibility and preserves independent Task closure.
Its Group-batch witness also uses six Workers in two Groups, four Tasks and 800
Items: two Tasks share a Rule, another uses a different Rule in the same Group,
and the second Group consumes default stock. Actual executors and the observed
Pacer batches prove qualification and Group isolation; the boundary witness checks
that every supplied fence is already held before Matching starts. Redis Owner and
Pacer tests distinguish acquisition-before-projection from the old reverse order,
including unmatched leases, partial acquisition, sealing invalidation and command
order. A controlled clock proves that Matching cannot reset the candidate deadline.
Matching unit tests own bounded target paging, incremental admission counts,
concurrent refill/take, immutable results and local versus global expiry maintenance.
Refill deficit proofs preserve numeric Group shortages across the Matching/Pacer
boundary: raw reads use the smaller of shortage and 100, while every Group
attempt still reserves 100 of the 1000-round budget. Real Pacer/Matching/Redis
composition proves that stock 99 against target 100 candidateizes only one of
100 ordinary Workers; the other 99 remain discoverable on the next normal round
without aged recycling. The stock-80 case likewise candidateizes exactly 20.
Matching tests prove 200 residents against target 100 remain valid, zero-deficit
offers still receive full qualification, and batch budgets and hard capacity
continue to bound admissions. Target counts do not truncate qualified offers.
A declined candidate is not rolled back or replaced by
a supplementary scan. These proofs retain all external convergence deadlines.
Controlled races prove entry identity checks (including equal-value reinsertion),
partial take after a lost entry, no restart after unrelated Group changes and hard
capacity under concurrent refill. Observed target watermarks may be exceeded or
left short; these tests do not require a pool-wide optimistic transaction.
A later Rule failure must preserve earlier admissions; invalid input or a Rule-local
read/matching failure must admit no candidates for that Rule. The separate Facts
script still preflights all enabled index writes atomically. These are not cross-owner
transactions, performance promises or loss-repair guarantees.

Use the lowest-cost proof that owns the changed claim:

1. A local algorithm, state transition, validation rule or race changes: run
   the focused Owner test.
2. A DTO, codec, HTTP, Redis or adjacent-owner contract changes: run the
   corresponding Boundary Proof in addition to focused Owner tests.
3. Worker identity, Prepare, long-lived delivery, extensions, shared Lab/SMS Worker Simulator or
   Worker-facing Server behavior changes: run Worker Correctness.
4. Kernel/Pacer scheduling, serviceability, Runtime projections or Worker fault
   convergence changes: run Worker Convergence Health. TaskItem finality remains
   an Owner and Runtime Boundary claim; Convergence may be selected only as a
   downstream witness.
5. Worker/Platform Properties, Matching, Candidate invalidation, assignment or
   per-replica Handler assembly changes: run Worker Dynamic Matching. It owns
   loaded execution witnesses; Redis Owner still owns exact seal/transfer
   ordering and the separate facts/Score commit boundary.
6. Java Worker connection resource ownership changes: run focused Java Worker
   tests; Worker Loaded Capacity + Recovery Stability remains nightly/manual
   unless its loaded recovery or resource-stability claim must be re-established.
7. Documentation-only changes run Docs Contract. They do not select runtime
   proofs.
8. Call-performance Harness or runner changes run their deterministic JVM/Python
   tests. Full offered-load and latency proof belongs to the independent
   nightly/manual workflow. A production optimization re-establishes its Owner
   claim and the selected existing lanes, then uses this lane for comparison;
   do not duplicate throughput or Worker-size tiers across lanes.
   `--suite direct-diagnosis` adds fixed surge/sustained observations at 1k/2k
   through the same performance owner. `--diagnostics jfr` is manual attribution,
   with private recordings and whitelist export; formal comparisons keep it off.

Inspect selection for a branch without running a proof:

```powershell
python .github/scripts/explain_proof_selection.py --base origin/main
```

The checked representative-path contract is:

```powershell
python .github/scripts/check_proof_selection.py
```

The checker uses existing tracked and non-ignored untracked files, excluding
uncommitted deletions so package moves fail locally before CI. Kernel Worker
registration, Server Endpoint configuration and Prepare select both Java Worker
Correctness and Android Worker Proof after the Binding ownership move.

## Lane Index

| Lane | Primary command | External dependency |
| --- | --- | --- |
| JVM Contracts | Explicit non-Android Gradle module `build` tasks | None |
| Redis Owner | `.\gradlew.bat :server_jvm:redisOwnerIntegrationTest` | Redis 7 |
| Runtime Boundary | `.\gradlew.bat :server_jvm:runtimeBoundaryIntegrationTest` | Redis 7 |
| Worker Correctness | `python integrations/worker-correctness/run_worker_correctness.py --redis-url redis://127.0.0.1:6379/15` | Redis, Server, Worker Simulator |
| Worker Dynamic Matching | `python integrations/worker-dynamic-matching/run_worker_dynamic_matching.py --redis-url redis://127.0.0.1:6379/15` | Redis, Server, Worker Simulator |
| Worker Convergence Health | `python integrations/worker-convergence-health/run_worker_convergence_health.py --scenario all --redis-url redis://127.0.0.1:6379/15` | Redis, Server, Worker Simulator |
| Worker Loaded Capacity + Recovery Stability | `python integrations/worker-loaded-recovery/run_worker_loaded_recovery.py --prepared-workers 15000 --retained-workers 10000 --minimum-initial-converged 14800 --minimum-retained-converged 9900 --workload-items-per-task 5000 --redis-url redis://127.0.0.1:6379/15` | Linux, Redis, Java 21 |
| [Worker Call Performance](integrations/worker-call-performance/README.md) | Schedule retains `--suite task` then `--suite direct-diagnosis`; manual `--suite nightly` (seven aligned RPC cases plus original mixed witness) awaits Result-closure acceptance. `--suite rpc-diagnosis --repetitions 3`, JFR and historical Direct are manual | Ubuntu 24.04, 4 logical CPUs, Docker Redis 7.4.10, Java 21 |
| Android Host | Android unit/library builds plus `:integrations:android-worker-proof:test` | Robolectric, MockWebServer, JDK HttpServer |
| Android APK Assembly | Debug plus three fixed Lab APK variants in Proof CI | Android SDK |
| Android Worker Proof | `Android Worker Proof` in Proof CI | Redis, KVM API 33 Emulator |
| Frontend | `pnpm lint`, `typecheck`, `test`, `build`, `build:demo` | Node, pnpm |
| Runtime Distribution | Distribution integration tests with `-PxaMassVersion=0.5.0` | Redis, Java, Android SDK, Node |
| Scenario Coexistence | `python integrations/scenario-coexistence/run_proof.py --build --scenario functional`, then `--scenario pool-selection`, `--scenario lifecycle`; fresh ZIP functional and pool-selection | Redis 7, Java 21, Python, Node for build |
| Docs Contract | `python .github/scripts/check_docs.py` | None |

The exact JVM module build list and Android assembly commands are maintained in
[Proof CI](.github/workflows/proof-ci.yml), alongside their environment setup.
The JVM lane also installs Worker Simulator and runs
`python -m unittest discover -s worker_simulator_jvm/src/test/python -p 'test_*.py'`.
This invokes its actual installed entry with one configuration from a different
working directory and verifies generation and inventory reuse without a Server.
It does not replace the independently observed Worker Correctness or product proofs.

For documentation checks, run both the checker tests and the repository scan:

```powershell
python .github/scripts/test_check_docs.py
python .github/scripts/check_docs.py
python .github/scripts/test_check_proof_selection.py
python .github/scripts/check_proof_selection.py
```

Building the unified console or either UI archive requires Node 22 and Corepack.
The Server JAR no longer builds or embeds SMS frontend assets. Composition proof
builds the console explicitly and tests both profile states against that output.

Worker one-shot runners support Python 3.11 or newer. Install their small
shared dependency set once before running them locally:

```powershell
python -m pip install -r .github/scripts/requirements.txt
```

Redis protocol, authentication, TLS and URL handling belong to `redis-py`.
Proof runners must not maintain a private RESP client. Each high-level lane
retains one orchestration entrypoint because its process topology and failure
sequence are part of that lane's evidence.

The local Worker Convergence `--scenario all` entry remains the complete
one-shot command. Proof CI runs `state` and `task-fault` as two independent
matrix jobs, each with its own Redis service, scope, evidence artifact and
summary; the matrix aggregate remains the one selected Proof Gate result.

## Proof Quality

A mechanism proof must reject at least one locally plausible but systemically
invalid implementation:

- architecture guards reject authority migration, forbidden dependencies,
  duplicate Owners and widened public APIs;
- race tests control one named interleaving rather than sleeping and hoping;
- Redis atomicity claims use real Redis and an exact `test_*` scope;
- boundary proofs relate independently observed identities, commands, reports
  and transitions without freezing opaque payloads;
- convergence proofs issue each Lab mutation once, first establish its local
  effect, then compare Adapter, Kernel and Task projections;
- Worker Loaded Capacity + Recovery Stability records resource evidence and
  thresholds without claiming functional correctness for every offered
  connection.

Delete or merge a test only when Owner, claim, failure model, evidence boundary
and failure diagnostics are all the same. Keep unique architecture guards,
strict DTO contracts, concurrency races, Redis atomicity and real process
boundaries even if their happy paths overlap.

## Shared Infrastructure

Real Redis proofs use unique `test_*` scopes; the scope is the isolation
contract. Cleanup is best-effort resource hygiene for persistent local Redis,
uses bounded `SCAN` and `UNLINK` for only that exact scope, and never changes a
Proof result. GitHub jobs explicitly skip cleanup for their disposable Redis
Service container. A proof that needs a Server or Worker Simulator owns those
process lifecycles and stops all writers before a local cleanup attempt.

The Runtime Boundary starts the Java Server for its full traversal. A separate
finite HTTP configuration test starts isolated contexts for the default pool,
prestarted pool and virtual execution; real HTTP and Redis establish Direct
success, occupied-slot rejection, timeout, late-report rejection and Owner
shutdown, with exactly one servlet completion per request. Worker Correctness and
Worker Convergence Health start Server and Worker Simulator as independent
processes. Worker Loaded Capacity + Recovery Stability is a separate
nightly/manual workflow and is not part of the pull-request Proof Gate.

Worker Correctness runs `initial -> live-properties -> Host restart -> restart`.
The live phase uses Lab HTTP to persist and publish through the running Worker,
then checks Adapter cache and Server Runtime independently. Its separate safe
evidence includes the runner's unchanged Host PID, unchanged control records
and zero additional Prepare requests, after proving that initial Prepare is
visible in the private HTTP access log. See its Owner for fixed deadlines and
full snapshot oracles; focused Host/Harness tests do not replace this real
process proof. Selection includes Server `worker/resource/**` admission, with
representative Host, Adapter Properties entry and Server admission paths checked
by the selection contract.

Worker Dynamic Matching uses 2x500 Workers in an independent selected Proof Gate
lane. Its three background Tasks overlap Worker `properties.update/replace` inputs and Platform Properties changes.
Four witness Tasks prove ineligible waiting and eligible execution on the 100
actual target replicas. Seven Tasks and 150,400 Results must close together with
the independent Host journal, unchanged processes/controls and zero Prepare
delta. Its Owner fixes negative windows, observation and drain budgets. Safe
summaries exclude inventory, full Properties, raw journal and private Harness
correlations. A successful Harness still requires the runner's independent audit.

Android Host owns deterministic SDK, capability, Demo and proof-Harness tests
plus Android library assembly. Android APK Assembly independently builds the
Debug and three fixed Lab APKs. It is required when Android Host or Android
Worker Proof is selected, uploads artifacts only for the Emulator, and may run
in parallel with Host tests. Android Worker Proof is a separate single-emulator
platform lane: its Java Harness owns Correctness and Convergence assertions,
including one DELAY Handler process-loss recovery, while its shell owns only
ADB, Server, App, and Redis-scope process choreography. Contract-invalid
observations fail immediately; only temporary HTTP transport failures remain
eligible for bounded polling. Android is not a secondary witness for the Java
Worker proof.

## Shared Eligibility refill and dispatch

### Allocation observations and App Checks projection

Pacer focused tests prove the five-field notification after both execution
acquisition and Item claim, event/Worker grouping, and notification before Command
encoding/publication. Sink errors do not alter dispatch. Server tests cover exact
Group/event selection before queue admission: unmatched traffic cannot consume
waiting capacity, trigger Facts reads or change diagnostic counters. Latch-controlled
tests retain matched whole-batch saturation and prove first-appearance projection
grouping for interleaved events, unsorted/duplicate times, local patch visibility
and overlapping-field precedence. They also cover bounded Properties reads, one
patch per Worker per drain, isolation of read/compute/write failures and lifecycle
cleanup. A different-field projection uses the same
Properties handler without changing Pacer. Function dispatch tests use consumers
with no Properties dependencies and prove one enqueue with multiple handlers,
immutable routing/input snapshots, instance-based batching across Groups/events,
receipt order including duplicate notices, handler failure isolation, and the
existing drain budget. They also cover duplicate registration, stopping before
subsequent handlers, close timeout and restart only after the previous thread exits.
Direct Properties-handler tests retain local patch/deletion visibility, whole-Worker
failure isolation and stop checks before writes without starting a queue/thread.
App Checks tests own fixed-window arithmetic,
invalid persisted values, older observations and restart continuation.

The existing `:server_boot_jvm:scenarioCompositionIntegrationTest` now also gates
success, failure and late App Checks Reports behind a test-only Handler barrier.
Before release, actual Platform Properties must match the bounded source
allocation witness while no Result has arrived. After release it keeps the
original business assertions and proves persisted properties survive restart.
The proof compares notification times/identities, not Item or Result totals.
The ordinary App Checks process acceptance retains its execution oracle. Neither
proof establishes reliable counting or window-based Matching admission.

### Existing Matching proof ownership

Redis Owner proves complete Task descriptors, preserved source coordinates, bounded qualified Messaging Phone lookup,
live Worker/Platform projection, corrupted metadata rejection and command budgets:
no Matching Task configuration access, local target resolution and direct Group/Pool operations without
executable binding views or refill callbacks, bounded named refill command counts
from the Matching Owner contract, and zero Redis access for Pool take and deficit
observation. Supplied candidates still require bounded qualification even when
their target watermark is satisfied. Candidateization and execution acquisition use bounded exact CAS
batches. Properties time invalidation rejects old fences; a new generation is
qualified again. A restarted Matching catalog cannot adopt old stock.

Focused tests cover direct named refill/take without Task registration or prior
shortage observation, Group isolation, MAX targets across Tasks, overlapping query stock, bounded
capacities, concurrent entry consumption, overlapping target qualification and source
failure after candidateization. Container tests cover single-bucket FIFO, immutable
repeated identities/fences, partial acceptance, approximate resident counts, lazy TTL
and capacity-pressure recovery of idle stock. Proof tuple tests preserve all partial
queries and literal/missing values without multiple memberships. Catalog duplicate
association filtering remains independent of the container's duplicate entries. Pacer tests keep refill independent of Item observation and dispatch
independent of Pool source reads/acquisition. Runtime Boundary uses actual WebSocket,
Socket and Polling Workers, including multiple Tasks consuming one shared pool.
Dynamic Matching preserves its 1,000 Worker/150,400 Item workload with explicit
proof-Rule refill targets. Ordinary Messages declares ANY/country Pool supply;
directed Messages uses qualified Phone queries with empty supply.
The existing mixed Call workload supplies coexistence and aggregate Redis cost
evidence; a local nonreference run is not a throughput improvement claim.

The independent property HASH has Redis Owner proofs for exact value replacement,
conditional old-field removal, last-writer collisions, Group/property isolation,
Platform independence, full-batch preflight and concurrent writes. Restart preserves
the existing winner without Facts scans or reconstruction. Retired reverse HASHes
and value SETs are ignored even when corrupt. A direct Phone batch uses one HMGET
for at most 100 unique values, returning at most one identity per value.
Mixed Pool/Identity/Phone tests retain whole-batch admission and first
association wins. Identity uses no Redis in Matching; Pacer still verifies existence
and Group before Kernel execution acquisition.

Qualified Messaging Phone shares that index, then reads only returned identities
with one strict Facts HMGET. Focused and Redis tests cover ordered association,
country/enabled/phone filtering, between-read phone changes, no-Pool configuration,
shared index retention and partial failure. No fallback to overwritten identities
or resampling follows a filter miss.
Actual Pacer/Matching/Redis composition proves a Country-cached Worker can be
acquired by qualified Direct lookup while its old Pool fence cannot execute or
release the new hold. Product functional/lifecycle keeps the original directed
send deadlines and subsequent receipt/restart assertions. This migration does
not establish a fix for the separately recorded Dynamic Matching drain failure.

## CI Gate

`.github/workflows/proof-ci.yml` runs on pull requests, `main` pushes and
manual dispatch. `.github/proof-paths.yml` selects claim-driven lanes. Manual
dispatch selects every required lane. The final Proof Gate requires each
selected lane to succeed and each unselected lane to be skipped. It checks
Android Host, Android APK Assembly and Android Worker Proof independently, and
uses the aggregate result of both Worker Convergence matrix jobs.

CI does not retry failed proofs. Evidence artifacts contain IDs, relation sets,
counts, state timelines and process logs, never full Worker payloads,
Properties or Task results.

## Deliberate Nonclaims

There is no coverage threshold, flaky-test retry, browser visual matrix,
multi-JDK matrix, Android API matrix, general topology Cartesian product,
throughput benchmark or soak lane. WebSocket, Socket and Polling combinations
remain protocol/Runtime Boundary claims; the convergence health world does not
repeat them. Physical Android device behavior remains a separate manual proof.

### Candidate Generation Before Rule Qualification

Pacer supplies only successful candidateized fences. Redis Owner offers A while
B remains indexed and proves Matching cannot discover B. Unmatched candidates,
capacity failure, failed projection and lost returns recover through normal
bounded recycling, with no compensation or replay. Each admitted generation leaves
the input for later Pools; actual admissions count against the call budget. Earlier Pool admission
survives a later Pool failure.

The actual Pacer/Matching/Redis composition proves later Pool demand cannot copy
Country stock. Direct Phone lookup still finds the cached Worker and execution
acquisition leaves the Pool entry unchanged; its old fence cannot acquire or
release the new hold. Concurrent Pool/Direct callers have one execution winner.
Normal recycling creates a new generation which can enter another Pool while the
old entry remains cached. Focused tests prove accepted IDs never reach subsequent
qualification, rejected IDs remain eligible for later Pools, and fresh batches
retain Pool rotation. Scenario Coexistence keeps its existing business assertions
and deadlines; overlapping business demand does not authorize stock copying.

Owner proof covers high-mark encoding, strict current-slot exclusion, Properties
ordering, network floor activation, pause MAX/0, relative deferral and one execution
winner across Pool/Direct callers. Ordinary candidate, aged candidate, Serviceability
HOT and Recovery heads each reach 250 equal-score members in 100/100/50 batches.
Stale stock cannot claim or release a newer hold. Local Pool tests prove admission-
time TTL for independent occurrences, capacity accounting for duplicate entries,
bucket FIFO, lazy pressure cleanup and concurrent at-most-once entry consumption. TTL limits take, not already-taken exact acquisition. Runtime Boundary
and Dynamic Matching retain their existing workload and time limits.

### Network Evidence And Candidate Generations

Redis Owner checks that current-slot evidence can correct HOT/RECOVERY polarity
while preserving time and mark; execution acquisition still requires strictly past time.
Redis-timed cases cover evidence and execution ordering, concurrent CAS,
100-Worker command cost, PAUSE and past-slot freshness rejection. Accepted past
polarity changes advance generation, clear mark and cap the target at Redis now;
same-slot evidence advances one slot so the old candidate fence cannot recur.
Ordinary same-polarity Polling leaves generation unchanged. Below-floor activation
requires post-floor evidence and is the only same-polarity refresh.
Actual Pacer, Matching and Redis Owner composition consumes stale Pool stock,
reconnects and refills a new generation without waiting for the 60-second recycle.
The isolated DEFAULT Runtime witness retains its 15-second reconnect deadline
and cannot depend on periodic Probe or a shortened recycle threshold.
Only a missed timing window may be resampled; a wrong in-window result fails.
Worker Convergence Health retains its original outage fixture and timeout;
these checks do not promise strict network ordering or evidence replay. After
Server restart, 999 reconnects and unchanged identities remain gates; idle-fleet
Score observations are diagnostic, while the directed Worker and successful
execution witnesses remain required. This does not claim idle Group discovery.

Matching consumption uses the fixed function table and `WorkerQuery` envelope.
Kernel query tests own immutable JSON bounds and strict decoding; Matching tests
own whole-batch admission, scalar functions, function ordering, Group isolation,
range counts and entry commit races. Range-access counters use a controlled clock
and report expiry separately; they are not performance or capacity evidence.
QueryFunction tests exercise direct strategy implementations: each input is normalized
once per Matching take, all admission precedes execution, and one batch per function
keeps message association and first-appearance selection order. The Catalog request
budget rejects oversized inputs before a strategy runs; pure normalization never
accesses injected resources. Shared Pool take and late-invalid-input tests retain
their inventory and original-fence assertions.
Redis Owner retains qualification command budgets and zero-Redis Pool resource polling;
the assignment-window function additionally performs one Facts snapshot per nonempty read page.
Runtime Boundary and Scenario Coexistence cover the new envelope through real
Workers. Use new scopes; old direct-selector Items are deliberately unreadable.

Matching Pool convergence is covered by the existing lanes: explicit Any without
Facts, no-Pool Identity/Phone Tasks, Country union/overlapping watermarks and full
bounded targets, one offered-ID Facts HMGET, local range take and strict candidate
invalidation. Messaging/Proof target paging and Facts/Phone atomic writes remain
separate regressions. Country fixtures no longer rely on the retired Country ZSET. Convergence
checkpoints use a single Identity target after stopping the backup; the independent
Proof Pool witness retains qualification and strict-fence coverage.

Matching resource separation uses those same proof lanes. Focused assembly tests
cover no-Pool Identity/Phone composition, one lazy shared connection, immutable
property configuration, startup without Redis I/O and idempotent Composition close. Architecture
tests enforce actual package dependency restrictions for Pool, Index, refill and
function implementations. Redis Owner covers Phone updates and restart retention
without Task demand, non-consuming shared lookup, Pool capacity/expiry independence,
single-property enablement and HASH preflight protection. Matching convergence
adds name-independent ALL/PAGED batching, explicit Pool rotation, a strict atomic
Worker/Platform snapshot and zero access to poisoned retired Messaging/Proof keys.
Messaging uses one offered-ID HMGET; Proof uses one EVAL_RO with two HMGETs.
Properties entrypoint separation retains those same lanes: pure Pool/Identity Catalog
tests use no Redis Store; Server property callers depend only on `WorkerProperties`.
Composition tests verify stable interface instances, shared Properties/index connection,
failure cleanup and that closing Matching never shuts down the Server-owned RedisClient.
Refill coordinator regressions retain target paging, Pool rotation, single-Pool
admission and partial success. Empty demand cleans cursors, not expired inventory;
expiry remains poll-driven or capacity-pressure-driven.
Malformed active Facts fail before the current Pool changes stock; public Facts
observation retains its previous row-local behavior. Literal proof values (*, ~,
separators, quotes and Unicode) no longer collide with omitted conditions.
Concurrent Worker/Platform writes, strict-fence/Direct competition and real Worker
proofs retain their assertions; these tests claim no new capacity
or performance result.
