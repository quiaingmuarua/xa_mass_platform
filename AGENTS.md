# XA Mass Agent Handoff

Status: current repository change contract.

Read the root [architecture entrypoint](README.md) for current system behavior
and [TESTING.md](TESTING.md) for proof ownership. This file governs how
agents change the repository; it is not the canonical mechanism narrative.

## Applying This Contract

- Start with the current Git HEAD and worktree. Preserve unrelated edits and
  read the affected Owner, callers and proof before changing their behavior.
- Follow the user's requested outcome and scope. An authorized implementation
  plan covers its necessary slices; a slice boundary, status label or missing
  template field does not require another approval. Review-only requests remain
  read-only. Ask only when a material unresolved choice affects the next action;
  continue independent authorized work.
- The ownership and composition rules below govern changes within the current
  architecture. An explicitly requested migration may revise the affected
  contract together with its callers, Owner document and proof. Words such as
  "fixed" or "frozen" do not prohibit that authorized migration, and permission
  to harden an implementation does not authorize unrelated boundary changes.
- Owner links lead to the complete applicable contract. Keep mechanism flow,
  storage shape, configuration values and scenario thresholds there; retain
  change constraints here. Read the linked detail when changing that mechanism.
- Skills and historical memory provide working guidance, not current project
  truth or permission to expand the task. Retrieve historical versions only
  when the requested investigation needs them; do not restore retired designs
  or historical defects as default constraints.

## Mainline

- `kernel_jvm/` owns stable Java mechanical contracts, Redis providers and
  score/resource mechanisms.
- `kernel_pacer_jvm/` is the fixed Java production policy and Pacer lifecycle
  over `kernel_jvm` owners.
- `worker_matching_jvm/` owns Worker/Platform Properties, fixed query functions and Pool maintenance,
  materialized eligibility indexes and bounded query interpretation.
- `server_jvm/` is the Runtime API and application assembly, not a scheduler.
- `server_boot_jvm/` owns the sole production main, Boot JAR and explicit
  platform/preview configuration. It owns no business or resource logic.
- `distribution/server/` owns Runtime and Scenario Preview delivery, frontend
  builds, diagnostic dictionaries and the Preview process launcher; it consumes
  the executable artifact.
- `transport/` delivers already-decided Commands and executes endpoint-local
  handlers.
- Scenario, Android, integration and frontend modules are finite assembly,
  capability, acceptance or observation surfaces.

The stable authority rule is:

```text
Kernel    decides and converges scheduling
Matching  interprets Worker facts and rules into bounded identity evidence
Server    exposes, validates, routes and correlates
Transport delivers and executes local events
```

Do not move candidate selection, Worker lease, TaskItem claim, retry, recovery
or Task finality into Server or Transport.

## Trust Order

1. Java production Owner and Pacer code.
2. Focused JVM tests and verified Redis behavior.
3. Current Owner documents.
4. Runtime Boundary and end-to-end proofs.
5. Historical material, when relevant, only as version-scoped evidence.

Use code and tests to establish current behavior, and the requested outcome
and applicable contracts to establish intended behavior. A planned migration
will differ from its starting implementation. For documentation drift, correct
the description within scope; do not change production behavior just to make
it match prose. Report a confirmed code defect with its evidence and repair it
only when the task covers that behavior.

## Repository-Wide Rules

- Core authority or scheduling-flow changes must be explicitly called out in the
  implementation plan, report and Owner document; never hide them as cleanup.
  Implement only the core mechanism changes explicitly discussed and authorized.
- Preserve explicit owners for truth, evidence, address, correlation,
  projection and hints.
- Treat every new Kernel operation as a long-lived cost commitment. Prefer
  caller-bounded identities, same-key aggregation and owner-local operations.
- Keep cross-key fan-out, global discovery, owner-spanning aggregation and
  background coordination in the caller or policy unless a named invariant
  proves otherwise.
- Keep scores opaque outside score-owner operations. Opaque is a usage
  constraint, not a requirement to wrap every score in another class: a Pacer
  may retain, associate, exact-compare and return a raw score to its Owner, but
  must not decode, construct or calculate score coordinates.
- Build Redis keys from the fixed `xa_mass:<scope>` `RedisKeyspace` base;
  each owner appends only its own domain suffix. Proofs use unique `test_*`
  scopes and may clean only that exact scope with `SCAN` plus `UNLINK`; never
  use `KEYS`, `FLUSHDB`, or `FLUSHALL`.
- Best-effort hints must not become correctness prerequisites.
- A bounded evidence handoff may remain lossy across process failure, but local
  queue capacity must be surfaced as retryable backpressure when the upstream
  already owns a bounded retry path; do not misclassify it as semantic input
  rejection.
- Do not add bridge layers, compatibility aliases, mirrored DTOs, fallback
  owners or speculative modules. The bounded Pacer lifecycle bridges below
  are the explicit internal assembly exception.
- Keep module-coded exceptions local: `errorCode + owner.method operation +
  message + cause`. Context belongs in safe logs and traces.
- JVM-only modules use `System.Logger`. Android-consumed Java 11 modules use
  `java.util.logging`.
- Never log opaque Worker payload or result content.
- Update the owning mechanism document in the same change as behavior.
- Use focused owner tests first and real Redis proof for Redis concurrency or
  atomicity claims.

## Java Kernel

[Kernel documents](doc/kernel/README.md) own the mechanisms; the
[scheduling mainline](doc/kernel/scheduling-overview.md) owns cross-owner flow.

- Keep Task, TaskItem and Worker Score truth independent. Scores are scheduling
  coordinates, not resource write locks.
- TaskItem ACTIVE is tag 1; tags 2..9 end scheduling while allowing monotonic
  outcome observation. Preserve exact ACTIVE claims and strictly increasing
  existing scores. Do not gate retained Item observations on Task completion or
  closure, or reopen scheduling through a terminal observation.
- Preserve independent Result content and Score finality. SUCCESS stores Result
  before requesting finality; Dispatch stores failure before terminalizing an
  exhausted/expired Item. Retryable FAILURE releases only the correlated Worker
  lease. Observation advances Score before optional content and never changes
  Worker leases. Read the [Result Owner](kernel_jvm/doc/runtime-redis/task-result-runtime-redis-shape.md)
  for independent maximum targets, corruption and partial-write semantics.
  Do not imply an ACK, replay or unconditional Result-to-Score repair guarantee.
- Result Policy owns parsing/grouping; finite TaskItem, Worker execution and
  Serviceability events own legal mechanical transitions. DeliveryReport, JSON,
  lane names and raw Worker lease scores must not cross those event ports.
  Worker execution evidence must not infer connection polarity.
- A Policy may call an existing mechanical owner directly. Add a finite internal
  Mechanism only to compose a legal transition or protect an exact fence; keep
  priority, matching, deficits, retry cadence and lifecycle in Policy.
- Preserve the [vertical scale and liveness contract](doc/kernel/scheduling-overview.md#scale-and-liveness):
  a bounded active Task set, many Items and finite Groups. Returning compatible
  capacity must not remain monopolized by fixed Task order. Keep the ordering
  hint bounded and process-local, with no reservation, durable cursor or fairness
  queue. Neither a full Task page nor occupied Workers alone establishes starvation.
- A second language implementation requires an authorized migration, one
  production owner and explicit cutover proof.

## Kernel JVM

[Mechanical Owners](kernel_jvm/README.md) define contracts, Score transitions
and Redis shapes.

- Public operations remain caller-driven and bounded, with an explicit production
  caller and focused proof. Missing operations fail with
  `KernelOperationNotImplementedException`.
- Redis operations stay in their owning package. Server connection/health code
  must not own keys or bypass a mechanical contract.
- Task Owner stores complete immutable descriptors and Item execution data.
  Every Task has a passive projectId; descriptor creation and the first-created
  project index commit together. Global Task Score scheduling stays independent.
  `WorkerQuery`, `EligibilityQuery` and Pool supply declarations are passive
  data here; Kernel must not interpret Matching fields or retain a second
  Matching binding.
- Semantic Result event implementations compose existing owners without new
  Redis state or another truth path. Result reads do not infer Score finality,
  and Score reads do not infer Result payload or business status.
- Do not widen Task operations merely to broaden an API, or add dependencies
  on Spring, HTTP, Pacer policy or another Kernel runtime.

## Worker Matching JVM

[Matching Owner](worker_matching_jvm/README.md), especially
[resource composition](worker_matching_jvm/README.md#fixed-resource-composition),
owns query inputs, Pool maintenance, indexes, capacity and failure semantics.

- Task supply declarations name Pools; Item `WorkerQuery` values independently
  name fixed functions. Empty supply is valid. Functions cannot imply supply,
  and direct Identity/Phone functions are not Pool names.
- Pacer depends only on `WorkerMatching`. It forwards Group/Pool names, immutable
  queries and call-local message IDs without normalizing fields, interpreting
  business conditions, reading facts or constructing index coordinates.
  Matching must not accept Task IDs, retain Task configuration/message IDs or
  own Item lifecycle.
- Functions own input interpretation and resource selection. Refill policies own
  target normalization, deficits and qualification. CandidatePool owns mechanical
  range stock; indexes own physical definitions and reads. Catalog coordinates
  bounded calls and correlation without taking over those local mechanisms.
- Register QueryFunction strategies directly with normalizeInput/apply methods.
  Catalog admits the entire batch; apply uses admitted inputs without repeating
  its request-budget checks. Keep resource invariants at their own boundary.
- Fixed composition injects existing resources. Pool resources depend only on
  their clock and shared capacity budget; indexes do not depend on Pools,
  functions or refill policy. FactsIndexStore owns the shared connection, Facts,
  atomic index writes and rebuild. Failed startup closes Matching resources,
  never the Server-owned RedisClient.
- Preserve full validation before consumption and the Owner's partial-success
  contracts across functions/Pools. Earlier admissions or consumption survive a
  later failure; do not add rollback, replay or whole-selection retries.
- Pacer acquires candidate leases before Matching qualification. Matching accepts
  only supplied held identities, preserves original fences/deadlines and cannot
  discover replacements, acquire/renew leases or reserve deficits.
- Pool candidates retain strict nonzero expectations. Zero is an identity hint
  only at the Matching-to-Pacer boundary; no zero sentinel reaches Kernel.
  Do not downgrade a failed strict expectation to current identity acquisition,
  or restore/retake stock when a candidate association is dropped.
- Facts and enabled indexes prepare before one bounded Lua write. Index upkeep
  is independent of Task demand and Pool stock; Score invalidation is a separate
  best-effort commit, not a property-version transaction.
- Keep Any explicitly configured, Country on offered Facts/local buckets, and
  Phone as an independent Group index. Query/capacity limits remain Owner-local.
  Do not add dynamic registries, prefix routing, unavailable-function fallback,
  per-Task stock, targeted refill or a Matching execution thread.

## Kernel Pacer JVM

[Pacer assembly](kernel_pacer_jvm/doc/application-assembly.md) and its linked
Policy documents own workflow, capacity and lifecycle.

- `KernelPacerRuntime` is the only externally supported production entry.
  Result and Dispatch may each expose one narrow module-internal lifecycle bridge;
  other modules must not import those bridges or internal policy types.
- Keep one fixed Result application and one fixed Dispatch application with
  separate capacity and lifecycle ownership. Server adapts this runtime to
  Spring; it does not host an alternative scheduler or Task fallback.
- Main supplies the complete bounded Task/Group roots to single-flight Producers.
  Busy Producers skip snapshots; discovery stays vertical beneath those roots.
  Refill and dispatch share Main's already-read NORMAL descriptors.
- Only the package-private assignment closure constructs claimed Commands, after
  Worker execution admission and exact Item claim. Both strict transfer and
  current identity acquisition must return TRANSITIONED; the returned sealed
  fence is used for Command correlation and exact Result release.
- Refill observes the due HOT head from the floor each round without a within-Group
  offset; Group rotation is separate. Preserve the Owner's bounded raw read and
  corrupt-head limits, original lease deadline and expiry without compensation.
- Serviceability reads current HOT/RECOVERY heads without cross-round cursors or
  cooldowns. Score Owner uses Redis time and exact CAS to schedule the next
  recheck before Probe offer. Network evidence is consumed in every preset.
- Pacer owns no Redis keys, Spring/HTTP/deployment or Matching implementations.
  Do not add a SPI, dynamic registry, reflection, ServiceLoader, extra public
  internal type or second external runtime.

## Server JVM

[Server Owner](server_jvm/README.md) owns API, admission, use cases and resource
assembly; [Server Boot](server_boot_jvm/README.md) owns main, packaging and
production profiles.

- Keep Server an importable Java configuration library. Controllers/services use
  Owner contracts; only assembly selects providers and imports the public Pacer
  runtime. Server owns no selection, lease, claim, retry, recovery, Task finality,
  Adapter route/queue or Worker business/lifecycle state.
- Boot owns the sole production main and all production YAML. Server tests and
  OpenAPI use explicit test-only platform configuration. Scenario composition and
  deployment overlay tests follow Boot, with no reverse dependency or copied host
  configuration in Server tests.
- Server normalizes supply and Item queries through local Matching admission,
  then writes complete Kernel data. Admission cannot read inventory, create
  refill demand, retain a separate binding or compensate through Matching.
  Finite append rejection remains per Item.
- Profile Project declarations are immutable Server admission. Startup prepares
  Groups, then Project/Group managed PARK Tasks, before Adapter/scenario startup.
  External Group registration creates no Task; ordinary Task creation is CLOSE.
  Project reads never initialize resources.
- Prepare coordinates external identity, persistent Kernel Binding and cold
  Score membership in separate retryable stages. It must not create/refresh
  Matching facts or establish readiness.
- Delivery reception owns complete Properties admission: producer, Binding and
  Group checks followed by grouped Matching writes. It must not merge observations
  with old facts. Worker replacement and independent Platform Properties remain
  separate. An empty Runtime display must not become a stored baseline.
- Use scalar/collection/Map bodies when complete. Named DTOs require a structured
  resource, combined contract, status item or cross-field invariant. Do not add
  generic envelopes, SimpleRequest or one-field status wrappers.
- Preserve `ActionOutcome` only for shared mutation-effect semantics; whole
  request failures use ApiErrorResponse. Identity, observation and delivery
  results retain their own contracts.
- Task calls and Result reads share `succeeded | failed | not_observed`; only
  succeeded carries opaque content. Export remains finite-Task, terminal-only
  and success-only through Owner pages, with no Redis bypass or failed/all mode.
- DIRECT_CALL remains Adapter-scoped, caller-targeted and best-effort, with
  instance-local correlation and no Score observation/change or strong lock.
  Worker targets use non-overwriting offer; TASK append may replace an unconsumed
  Direct Command. Adapter-local routing uses dst, not map-key Worker identity.
  Pass event names/payloads without a Server execution whitelist.

## Worker Delivery Contract

[Delivery Contract](transport/worker-delivery-contract/README.md) and
[Event Catalog](transport/EVENTS.md) own DTOs, encoding and event contracts.

- Keep the contract Java 11 compatible and transport-neutral, without Server,
  Kernel, Redis, Netty, Android or scheduling dependencies.
- DeliveryCommand target identity stays outside the DTO. Reports identify their
  producer; event names and producer/target decide admission and correlation.
  diagnosticCode remains diagnostic, never an event discriminator.
- SERVER replies may complete Direct Call waiters; SYSTEM events enter their
  named platform owner. Do not alias them or treat Report names as Handler
  capabilities. Keep forward opaque until its downstream owner.
- Long-lived connections use identity Report followed by direct Command/Report
  JSON, without another connection envelope.

## Netty Adapter

[Adapter Owner](transport/netty-adapter/README.md) owns the complete fixed
composition, loop policies, caches, physical network and shutdown sequence.

- Preserve the aggregate, Process Manager, two same-lifetime Dispatchers,
  connection mechanism/Registry and physical Servers. An authorized ownership
  migration must update those contracts explicitly.
- Command Dispatcher owns retry state, thread and policy; DeliveryCommandProcess
  handles one batch once without queue, lifecycle or pending-batch state.
  Preserve [retry/fresh-batch progress](transport/netty-adapter/README.md#command-consumption-loop).
- Report Dispatcher owns all four finite lanes and one resident thread.
  Preserve [Report admission and retry](transport/netty-adapter/README.md#result-ingress-loop),
  including the TASK physical retry reserve, homogeneous batches, aggregated
  drop diagnostics and destination-specific loss/duplication behavior.
  Shutdown has no synchronous flush. Future concurrency stays in one bounded
  Report-owned executor, not one executor/thread per lane.
- Server rejects mixed/unsupported batches before semantic effects. New Report
  classes extend lane/routing ownership without adding HTTP paths.
- The process-scoped Factory owns a shared immutable Remote API facade/codec.
  Its HTTP client uses an explicit virtual-thread executor for raw resources.
  Keep flat public config, owner-local inputs and the finite factory boundary;
  no singleton config, cached platform-thread fallback or in-process Server shortcut.
- Connection mechanism owns identity verification and current-route use; Registry
  owns one atomic Route entry per Worker. Verification checks existing Binding
  through the injected single-item port, without registration, migration, HTTP
  endpoint or Channel/Route leakage.
- Only disconnected verification evidence is TTL/capacity cached. Active/pending
  routes cannot be cache-evicted. Channel identity is callback correlation, not
  copied verification truth. Registry contains no Group authority.
- Keep Properties as a separate immutable projection, admitted only from the
  current verified Channel. Follow the Owner's full-map update/replacement and
  one-shot SYSTEM publication contract; failed publication leaves local state
  without retry, pending state, remote ACK or connection closure.
- Route/Properties snapshots have no atomic join or shared version. Caffeine stays
  connection-local without loaders, refresh, listeners or removal side effects.
- Physical Servers own listeners, EventLoops, child Channels, framing and writes;
  connection code retains Channel only as an address. Inbound Handlers adapt
  callbacks. WebSocket/Socket share behavior tests, not a lifecycle base.
- Keep immutable Adapter-local event maps, exact current-connection ingress and
  independent TASK/KERNEL expiry evidence. Transport never interprets Score.
- Preserve owner-local bounded shutdown deadlines. Do not add Session, protocol
  SPI, dynamic Process/lane registry, reflection or ServiceLoader.
- Adapter implementations cannot depend on Server, Kernel, Spring, Redis or Pacer.
  Server consumes only the finite factory and WorkerDeliveryAdapter contract.

## Worker Core And Platform Workers

[Worker Core](transport/worker-core/README.md), [Java Worker](transport/java-worker/README.md)
and [Android Worker](transport/android-worker/README.md) own their local mechanisms.

- Core remains Java 11 and depends only on the delivery contract. Client owns
  networking/reconnect; Transport owns protocol and synchronous Handler execution;
  WorkerRunController owns RUNNING/STOPPED. RUNNING does not prove connectivity.
- Follow the [run contract](transport/worker-core/README.md#one-worker-run):
  single Prepare, complete Properties loading and explicit Host start/stop.
  Worker identity/kind policy belongs to Server. Workers do not persist/hint
  workerId; reconnect sends identity without re-Prepare.
- Properties publish explicitly through Adapter/Server admission. Core owns no
  aggregation, batch HTTP, publication scheduler, thread or executor lifecycle;
  an injected Control Executor does not change that boundary.
- Stop revokes the run before closing Client outside the state gate. It does not
  wait for an admitted Handler/callback to finish. Prepare-time stop discards the
  result; endpoint termination requires a later explicit Host start.
- Do not add pause/admission state, cross-attempt/run Handler fences, Command
  queues, in-flight registries or result caches.
- Definitions are immutable and keyed by exact full Event Name. Host extensions
  use the extension helper; platform defaults cannot be replaced. Event snapshots
  observe installed Definitions, never WorkerGroup capability truth.
  Incompatible contracts require a new name, without aliases/prefix/fallback dispatch.
- A retained TASK Reporter belongs to its original run and opaque correlation.
  Host owns association/cleanup. New runs cannot adopt it; Core owns no reporter
  registry, replay, queue or retry. Non-TASK calls cannot report later outcomes.
- Java owns JVM networking, virtual-thread/OkHttp capacity and platform resources;
  Android owns its networking/HandlerThreads and cannot depend on Java Worker.
  Neither leaks implementation types or imports Server, Kernel, Redis or policy.
- Manager per-replica Definitions are immutable construction input and use the
  same reserved-default/duplicate checks as Group-common Definitions.

## Scenario And Android Capabilities

[Worker Simulator](worker_simulator_jvm/README.md) owns inventory, device facts,
capabilities and Host lifecycle; [Android](xa-android/README.md) owns device capabilities.

- Simulator is a standalone finite Java Worker Host. It may depend on Core/Java
  Worker, never Server, Kernel, Adapter, Redis, reflection or configurable classes.
  Server must not construct or manage the Host process.
- Keep one complete `--config` process input and one Manager per nonempty Group.
  Resolve defaults once; validate complete effective inventory, capabilities and
  startup plan before commit/Manager creation. Invalid explicit input has no
  fallback. Relative files resolve from config; no CLI overrides or parallel
  configuration/provider system.
- Follow [persistent Lab inventory](worker_simulator_jvm/README.md#persistent-worker-lab):
  immutable physical coordinates, string Properties, exact reuse and checked
  atomic replacement. Existing inventories are not automatically seeded/repaired.
  Group replacement retains/restores the old directory on failed installation;
  it never resets Server identity or Redis.
- Batch Prepare and SDK calls run outside inventory/Manager lifecycle gates.
  Explicit start reloads the complete file; restart during a converging stop
  conflicts. No watcher, automatic reconcile or generic fault DSL.
- Keep embedded plans finite and startup-local, without Tasks, Worker IDs,
  Properties mutation or Kernel claims. Command checkpoints remain Lab-only
  fixtures. Bind the loopback listener before outbound connections.
- Device `:inputs` uses stable file coordinates and existing business owners,
  never raw Command/Report injection. Preserve per-Worker serialization, persistence
  before publication and SDK sends outside inventory/business gates. File-only
  PUT remains distinct; stop must not wait for publication. Local acceptance
  implies no remote ACK, retry or pending repair.
- Lab/SMS/Messages share inventory, immutable current Properties and common
  controls. Events select compiled capabilities; templates initialize files
  once. Preview seeded sampling and proof-materialized quotas remain distinct.
  Business packages add no second Provider, Host or Manager lifecycle.
- Preserve original Reporter association and device-local deduplication.
  Stop clears active Reporters before SDK stop without synthetic ending Reports.
  Restart does not restore subscriptions or adopt old Reporters. Construct
  business resources only for installed capabilities.
- Lab state establishes local mutations only. Harnesses issue actions once,
  establish their local effect, then compare independent Runtime projections.
  They must not retry/repair the world to make convergence assertions pass.
- Android capabilities own immutable Definitions and data access, without Worker
  identity, Endpoint, Task, Client, Transport or scheduler state. Application owns
  assembly/lifecycle; Activity observes and issues explicit controls. Demo delay/
  failure handlers remain proof fixtures, not SDK or schedulability evidence.

## Integration And Frontend

[Proof Registry](doc/testing/proof-registry.md) identifies Primary Owners.
Each linked Integration README owns its full workload, bounds, failure model,
artifact limits and assertions; read that contract before changing the proof.

- Integrations use public Runtime APIs without implementation imports or Report
  injection. Device/Host state establishes local effects, not network/scheduling
  truth. Runtime Preview samples must not become fleet enumeration.
- Python runners own external processes, not database protocols. Use the shared
  Redis cleanup utility with redis-py and exact test scopes; cleanup is hygiene
  and cannot replace proof outcome. Disposable Redis jobs skip cleanup. Keep
  each high-level failure sequence behind its own runner entrypoint.
- [Worker Correctness](integrations/worker-correctness/README.md) owns managed-call
  exact statuses, opaque Results and live Properties/Host restart. Runner audits
  must establish process/control/Prepare conditions before accepting phase success.
- [Dynamic Matching](integrations/worker-dynamic-matching/README.md) owns loaded
  query execution under live facts. Actual executor identity comes from the
  construction-time Handler closure; request tokens only correlate. Keep finite
  journal overflow explicit and private evidence outside artifacts.
- [Convergence Health](integrations/worker-convergence-health/README.md) owns named
  witnesses after established mutations. Unknown submission/NOT_OBSERVED is not
  failure or success; do not require all offered load to succeed, count FAILED
  as success, poll export or retry mutations to install a preferred world.
- [Loaded Recovery](integrations/worker-loaded-recovery/README.md) and
  [Call Performance](integrations/worker-call-performance/README.md) retain their
  separate scheduled/manual claims. Preserve preconditions, bounded observations,
  resource/latency evidence and explicit nonclaims; scale does not create proof.
- [Android Proof](integrations/android-worker-proof/README.md) keeps assertions in
  Java and external choreography in shell. Invalid contracts/identity drift fail
  immediately; retry only permitted observation failures. Emulator controls
  do not prove background survival or another Worker platform.
- Frontend observes Runtime truth and uses public APIs for finite Task files and
  Adapter-scoped Direct Debug. It cannot infer state from elapsed time or promote
  Direct Call responses to identity, capability, lifecycle or schedulability.
- The [human overview](frontend/public/overview.htm) projects the existing
  architecture; retain its information architecture and navigation. Mechanism,
  API, capacity and fixture details remain in Owner/proof documents.
- Distribution consumes existing executables and SDKs. Runtime excludes Simulator;
  Preview includes it. Neither adds fallback ownership or scheduling behavior.

## Business Scenario Composition

[SMS Reception](scenarios/sms-reception-jvm/README.md) and
[Message Campaigns](scenarios/message-campaigns-jvm/README.md) own business API,
finite state, idempotency and observation. Their device owners remain in
[Worker Simulator](worker_simulator_jvm/README.md#messages-and-shared-products).

- Scenarios use approved Server application services and existing value contracts,
  including query/refill DTOs. Values grant no Kernel/Matching operation access.
  Scenarios do not depend on each other or create Redis clients, Owners, Pacer,
  Adapter, HTTP waiters or Direct Call registries.
- Do not invoke Controllers/providers/policy, duplicate validation or add HTTP
  fallback, Runtime bridge/library, mirrored DTOs, generic scenario framework or
  speculative SDK. Platform regressions return to the owning proof; do not hide
  them with business scheduling/delivery repair or count uncertainty as success.
- Boot explicitly imports both scenarios under preview with one shared Group and
  event declaration, and separate configured sms/messages Projects. Scenarios
  consume the prepared Project directory without Group registration. Scenario libraries own no deployment profile. Keep one
  platform resource set and an independent Simulator process.
- Constructors/configuration remain free of premature business startup. Stop
  scenario admission/submission/observation before platform resources with bounded
  waits; partial initialization cleans created resources. Scenario shutdown
  never cleans the Redis scope.
- Validate complete campaign input before creation and every append before
  approval. Preserve uncertain submission without recreation/retry, complete
  snapshots, existing Outcome names and observation after Task scheduling ends.
- Messages arise only through actual message.send. Device facts commit before
  publication; receipt hold/release accepts existing receipt IDs, never arbitrary
  Reports. Keep SMS matching/deduplication and the original run's Reporter rules.
- Preview gates business APIs, registration and jobs while sharing frontend assets.
  SMS/Messages polling lifetimes remain independent. Catalog observation cannot
  enable business; Mock Demo makes no scenario requests. Unknown API/assets are
  not SPA routes.
- [Preview delivery](distribution/server/PREVIEW.md) owns the sole source/ZIP
  launcher; root preview delegates to it. Packaged acceptance has no checkout
  fallback. [Coexistence](integrations/scenario-coexistence/README.md) remains a
  business witness, with the large workload explicit/manual and no expansion of
  mechanical proof claims or change to Proof Gate identifiers.
- Fixed query functions, Pool maintenance and indexes remain Matching-owned.
  Scenario composition does not authorize moving those owners or changing their
  facts/index atomicity.

## Verification

Use [TESTING.md](TESTING.md) for claim-based proof selection and commands.
Behavior changes require the focused Owner lane; Redis and runtime claims
require their named real-infrastructure proof. Documentation-only changes use
Docs Contract and link/content review without selecting runtime proofs. A
document that describes a runtime mechanism is still a documentation change.

Before completion:

- run `git diff --check`;
- scan affected references when removing or renaming names, routes or files;
- confirm archive material is not linked as current truth;
- report which checks actually ran and any required proof that could not run;
- distinguish source inspection from executed proof, and submitted changes from
  externally applied results. Do not claim completion while a required external
  step remains pending; finish the independent authorized work and state the
  remaining dependency.
