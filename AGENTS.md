# XA Mass Agent Handoff

Status: current repository change contract.

Use the root [reading path](README.md#reading-path) to move from the Runtime
summary to the complete behavior model, then the affected Owner, callers and
proof. This file governs how agents change the repository; it is not the
canonical mechanism narrative.

## Applying This Contract

Start a new investigation with this reading sequence:

1. Check the current Git HEAD, worktree and requested scope; preserve unrelated edits.
2. Read the root summary and [global behavior model](doc/kernel/scheduling-overview.md#system-behavior-model)
   to establish the work/resource loop.
3. Identify the affected input, state effect and subsequent consumer.
4. Read the responsible Owner, production callers, assembly and corresponding
   [proof](TESTING.md) before changing their behavior.

Reuse context already established in the session and refresh what the current
change affects. A small local edit does not require another whole-repository scan.
Behavioral domains do not relocate mutation authority or define new modules;
keep the full narrative in the linked mainline.

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

## Evolution Principles

- Long-term discussions explain possible directions. They do not establish
  current ownership, required work or a need to reserve interfaces. Distinguish
  implemented behavior, authorized plans, discussion and version-scoped history.
- Let concrete problems drive ordinary evolution through small, verifiable
  changes. Real scenario workloads can reveal which boundary needs to evolve.
- Foundational mechanism problems or clear high-return changes may justify
  earlier structural work. Establish the concrete risk or cost, expected benefit
  and how to verify it; a production incident need not happen first.
- Current Owner boundaries guide current work while authorized, evidence-based
  migrations can revise them with their callers and proof. These principles add
  no scoring system, proposal template or approval step, and do not interrupt an
  already-authorized implementation.

## Mainline

- `kernel_jvm/` owns stable Java mechanical contracts, Redis providers and
  score/resource mechanisms.
- `kernel_pacer_jvm/` is the fixed Java production policy and Pacer lifecycle
  over `kernel_jvm` owners.
- `worker_matching_jvm/` owns Worker/Platform Properties, fixed query functions and Pool maintenance,
  bounded Facts qualification, the independent Phone Index and query interpretation.
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

[Kernel documents](doc/kernel/README.md) locate the complete mechanical and
Policy contracts; the [scheduling mainline](doc/kernel/scheduling-overview.md)
owns cross-owner flow, independent scheduling truth and the
[vertical scale and liveness contract](doc/kernel/scheduling-overview.md#scale-and-liveness).
Read the affected Score and resource Owners before changing transitions. In
particular, the [Result Owner](kernel_jvm/doc/runtime-redis/task-result-runtime-redis-shape.md)
owns independent content/finality commits and their partial-failure limits.
The [Kernel boundaries](kernel_jvm/README.md#boundaries) govern Policy/Mechanism
placement and any authorized second-language migration.

## Kernel JVM

[Mechanical Owners](kernel_jvm/README.md) own bounded public operations,
immutable Task/Item data, passive Matching inputs, semantic event composition,
Score transitions and Redis shapes. Follow the
[production caller closure](kernel_jvm/README.md#production-call-closure) and
[change boundaries](kernel_jvm/README.md#boundaries); API convenience does not
justify widening Task operations or moving an Owner's Redis operations.

## Worker Matching JVM

[Matching Owner](worker_matching_jvm/README.md) is the complete contract for
independent supply declarations and Item queries, Properties, query admission,
Pool maintenance, indexes and partial success. Read its
[fixed resource composition](worker_matching_jvm/README.md#fixed-resource-composition)
when changing Catalog, Properties, resources or lifecycle, and the
[assignment-window contract](worker_matching_jvm/README.md#observed-assignment-window)
when changing projected qualification. The independent
[network timestamp contract](worker_matching_jvm/README.md#network-evidence-timestamps)
defines filter atomicity and fail-open separately from Score transitions.
Keep interpretation and local resource invariants at their named Owners; Pacer
receives bounded candidate evidence, not Matching implementation authority.

## Kernel Pacer JVM

[Pacer assembly](kernel_pacer_jvm/doc/application-assembly.md) and its linked
Policy documents own roots, Producers, capacity, notifications and lifecycle.
`KernelPacerRuntime` is the only externally supported production entry; the
Result and Dispatch lifecycle bridges are the narrow module-internal exception,
not additional external runtimes. Read the
[assembly guardrails](kernel_pacer_jvm/doc/application-assembly.md#guardrails),
[assignment contract](kernel_pacer_jvm/doc/dispatch/assignment-dispatch-scheduling.md)
and [Result event boundary](kernel_pacer_jvm/doc/result/result-routing-scheduling.md#application-and-guardrails)
before changing their call order, limits, fences or failure behavior.

## Server JVM

[Server Owner](server_jvm/README.md) owns API admission, application use cases
and resource assembly; [Server Boot](server_boot_jvm/README.md) owns main,
packaging and production configuration. Read Server's
[response contract](server_jvm/README.md#api-reference-and-response-contract),
[Task admission](server_jvm/README.md#task-admission),
[Properties admission](server_jvm/README.md#runtime-worker-properties-admission)
and [assembly boundaries](server_jvm/README.md#assembly-boundaries) for the
operation being changed. These contracts include independent commits,
observation loss, ownership of waiting and the permitted Scenario caller surface.
Server remains an importable configuration library over existing Owners.

## Worker Delivery Contract

[Delivery Contract](transport/worker-delivery-contract/README.md) and
[Event Catalog](transport/EVENTS.md) own DTOs, encoding, producer/target admission
and event meaning. Keep this Java 11 boundary transport-neutral; event semantics,
opaque correlation and Command target identity must retain their named Owners.
Read both contracts before changing an event or wire shape.

## Netty Adapter

[Adapter Owner](transport/netty-adapter/README.md) owns fixed composition,
Factory/Remote API, connection verification and routes, Properties projection,
physical networking and bounded shutdown. Changes to delivery must preserve the
complete [Command consumption](transport/netty-adapter/README.md#command-consumption-loop)
and [Report admission/retry](transport/netty-adapter/README.md#result-ingress-loop)
contracts, including their distinct progress, loss and duplication boundaries.
Read [lifecycle](transport/netty-adapter/README.md#lifecycle) when changing any
owned thread, executor, Channel or close path; Transport gains no scheduling authority.

## Worker Core And Platform Workers

[Worker Core](transport/worker-core/README.md), [Java Worker](transport/java-worker/README.md)
and [Android Worker](transport/android-worker/README.md) own their separate local
mechanisms. Read the [run contract](transport/worker-core/README.md#one-worker-run),
[Properties contract](transport/worker-core/README.md#properties-reporting) and
[retained Reporter contract](transport/worker-core/README.md#later-task-outcome-observations)
before changing Host control, installed Definitions or callbacks.

Stop must revoke the run before closing Client outside the state gate and return
without waiting for an admitted Handler/callback. The
[known Android implementation difference](transport/android-worker/README.md#known-android-stop-difference)
is pending repair, not a platform exception. Preserve that target when changing
platform lifecycle; RUNNING alone does not establish connectivity.

## Scenario And Android Capabilities

[Worker Simulator](worker_simulator_jvm/README.md) owns the standalone finite
Host, installed capabilities and device state. Its
[configuration](worker_simulator_jvm/README.md#one-configuration-one-entry),
[persistent inventory](worker_simulator_jvm/README.md#persistent-worker-lab),
[lifecycle](worker_simulator_jvm/README.md#runtime-lifecycle) and
[device inputs](worker_simulator_jvm/README.md#device-inputs) define validation,
serialization, publication and restart limits. Server must not own that Host.
[Android capabilities](xa-android/README.md) own device behavior and data access;
Application/Activity assembly must not move Worker identity, networking or
scheduling authority into capabilities. Local Lab/Host effects remain distinct
from independent Runtime convergence evidence.

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
  Call Performance raises configurable resources so they do not bind and measures
  mechanism limits; performance values feed its trend and never fail it, and a
  resource bound makes a case invalid rather than a slower number.
- [Android Proof](integrations/android-worker-proof/README.md) keeps assertions in
  Java and external choreography in shell. Invalid contracts/identity drift fail
  immediately; retry only permitted observation failures. Emulator controls
  do not prove background survival or another Worker platform.
- [Frontend](frontend/README.md) owns public-API observation, finite Task files,
  Adapter-scoped Direct Debug and explicit isolated Mock. Preserve its distinct
  state axes and bounded-preview limits; UI recomputation is not execution truth.
- The [human overview](frontend/public/overview.htm) projects the existing
  architecture; retain its information architecture and navigation. Mechanism,
  API, capacity and fixture details remain in Owner/proof documents.
- [Distribution](distribution/server/README.md) consumes existing executables and
  SDKs; [Preview delivery](distribution/server/PREVIEW.md) owns its launcher and
  source/ZIP contract. Neither adds fallback ownership or scheduling behavior.

## Business Scenario Composition

[SMS Reception](scenarios/sms-reception-jvm/README.md),
[Message Campaigns](scenarios/message-campaigns-jvm/README.md) and
[App Checks](scenarios/app-checks-jvm/README.md) own their business API, finite
state, idempotency and observation. Device ownership remains in
[Worker Simulator](worker_simulator_jvm/README.md#messages-and-shared-products).
Read the [Server Scenario boundary](server_jvm/README.md#worker-and-scenario-assembly)
before changing application calls, dependencies or lifecycle, and
[Boot](server_boot_jvm/README.md) for profile composition.

Business changes must retain each Owner's uncertain-submission, retained-Result,
original Reporter and observation limits; they do not authorize platform repair
or changes to Matching ownership. App Checks projection and window qualification
remain separate contracts, not a reliable quota. The
[Preview delivery Owner](distribution/server/PREVIEW.md) owns source/ZIP launch;
[Coexistence](integrations/scenario-coexistence/README.md) owns the business proof.

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
