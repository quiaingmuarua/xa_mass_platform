# XA Mass Agent Handoff

Status: current repository change contract.

This file governs repository changes; it is not the mechanism narrative.
Use the root [reading path](README.md#reading-path) to select the reading scope
for an overall handoff, cross-owner change or local edit.

## Applying This Contract

Check the current Git HEAD, worktree and requested scope; preserve unrelated edits.

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
- The complete applicable contract means the relevant Owner sections and their
  mandatory linked clauses, not every chapter of each linked document. Follow
  those clauses for the affected input, state effect and consumer. Keep mechanism
  flow, storage shape, configuration values and scenario thresholds in Owners;
  retain repository-wide change constraints here.
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
  owners or speculative modules. The bounded Pacer lifecycle bridges in the
  [public boundary](kernel_pacer_jvm/README.md#public-boundary) are the explicit
  internal assembly exception.
- Keep module-coded exceptions local: `errorCode + owner.method operation +
  message + cause`. Context belongs in safe logs and traces.
- JVM-only modules use `System.Logger`. Android-consumed Java 11 modules use
  `java.util.logging`.
- Never log opaque Worker payload or result content.
- Update the owning mechanism document in the same change as behavior.
- Use focused owner tests first and real Redis proof for Redis concurrency or
  atomicity claims.

## Java Kernel

[Kernel documents](doc/kernel/README.md) index mechanical and Policy contracts.
For cross-owner flow, read the affected parts of the
[scheduling mainline](doc/kernel/scheduling-overview.md), including
[scale and liveness](doc/kernel/scheduling-overview.md#scale-and-liveness) when applicable.
Score/content changes also require the relevant mechanical Owner and
[Result commit contract](kernel_jvm/doc/runtime-redis/task-result-runtime-redis-shape.md).

## Kernel JVM

[Mechanical Owners](kernel_jvm/README.md) own Task/Item data, semantic events,
Scores and Redis state. Before changing operations or dependencies, read the
[production caller closure](kernel_jvm/README.md#production-call-closure),
[boundaries](kernel_jvm/README.md#boundaries) and affected Score/resource contract.

## Worker Matching JVM

[Matching Owner](worker_matching_jvm/README.md) owns supply/query admission,
Properties, Pool resources, indexes and candidate evidence. Changes require the
relevant query/refill and failure clauses; resource or lifecycle changes also
require [fixed composition](worker_matching_jvm/README.md#fixed-resource-composition).
Use the [fixed-window Pool](worker_matching_jvm/README.md#fixed-window-pools)
and [network timestamp](worker_matching_jvm/README.md#network-evidence-timestamps)
contracts for those paths.

Window eligibility belongs to refill of its independent Pool. Consumption uses
admitted snapshot evidence without another Facts read; do not restore the retired
Any-stock post-filter or promote observed counts to reserved quotas. Scenario Pool
declarations are immutable startup values; resource ownership and lifecycle stay
in Matching, without external strategy callbacks or runtime registration.
Messages uses a [qualified-country declaration](worker_matching_jvm/README.md#qualified-country-declarations)
over Worker Properties. Its Pool refill and independent Phone query share the
declared qualification; keep index/resource ownership in Matching and preserve
the distinct stock-consumption and directed-identity paths.

## Kernel Pacer JVM

[Pacer assembly](kernel_pacer_jvm/doc/application-assembly.md) owns production
policy, roots, Producers, budgets and lifecycle. Read the affected Policy and
[assembly guardrails](kernel_pacer_jvm/doc/application-assembly.md#guardrails):
[assignment](kernel_pacer_jvm/doc/dispatch/assignment-dispatch-scheduling.md) for
candidate-to-Command flow, and [Result event boundaries](kernel_pacer_jvm/doc/result/result-routing-scheduling.md#application-and-guardrails)
for returning evidence.

## Server JVM

[Server Owner](server_jvm/README.md) owns API admission, application use cases
and resource assembly; [Server Boot](server_boot_jvm/README.md) owns main,
packaging and production configuration. Read the affected operation contract,
its [response rules](server_jvm/README.md#api-reference-and-response-contract), and
[assembly boundaries](server_jvm/README.md#assembly-boundaries) for dependency or
lifecycle changes.

## Worker Delivery Contract

[Delivery Contract](transport/worker-delivery-contract/README.md) owns the Java 11
transport-neutral DTOs and encoding; [Event Catalog](transport/EVENTS.md) owns event
meaning and producer/target admission. Read both relevant contracts before changing
an event or wire shape.

## Netty Adapter

[Adapter Owner](transport/netty-adapter/README.md) owns composition, remote API,
connection/routes, Properties projection, physical networking and shutdown.
Delivery changes require the [Command loop](transport/netty-adapter/README.md#command-consumption-loop)
and [Report admission/retry](transport/netty-adapter/README.md#result-ingress-loop)
clauses they affect; resource/lifecycle changes require the
[lifecycle contract](transport/netty-adapter/README.md#lifecycle).

## Worker Core And Platform Workers

[Worker Core](transport/worker-core/README.md), [Java Worker](transport/java-worker/README.md)
and [Android Worker](transport/android-worker/README.md) own their separate local
mechanisms. Use the [run](transport/worker-core/README.md#one-worker-run),
[Properties](transport/worker-core/README.md#properties-reporting) and
[Reporter](transport/worker-core/README.md#later-task-outcome-observations) contracts
for the affected path.

For lifecycle changes, preserve the stop target: revoke the run before closing
Client outside the state gate and return without waiting for an admitted
Handler/callback. Read the [known Android implementation difference](transport/android-worker/README.md#known-android-stop-difference);
it remains pending repair, not a platform exception.

## Scenario And Android Capabilities

[Worker Simulator](worker_simulator_jvm/README.md) owns the finite Host,
installed capabilities and device state. Read the affected
[configuration](worker_simulator_jvm/README.md#one-configuration-one-entry),
[inventory](worker_simulator_jvm/README.md#persistent-worker-lab),
[lifecycle](worker_simulator_jvm/README.md#runtime-lifecycle) or
[device-input](worker_simulator_jvm/README.md#device-inputs) contract.
[Android](xa-android/README.md) provides the capability and Application/Activity
ownership entrypoints.

## Integration And Frontend

[Proof Registry](doc/testing/proof-registry.md) owns lane claims and nonclaims;
[TESTING](TESTING.md#shared-infrastructure) owns shared proof boundaries and
execution rules. Before changing a proof, read its Registry entry and the linked
Integration Owner's affected workload, fault, evidence and assertion clauses.
[Frontend](frontend/README.md) owns public-API observation, Mock and UI boundaries;
its [human overview](frontend/public/overview.htm) is an architecture projection.
[Distribution](distribution/server/README.md) and [Preview delivery](distribution/server/PREVIEW.md)
own executable/SDK packaging and source/ZIP launch contracts.

## Business Scenario Composition

[SMS Reception](scenarios/sms-reception-jvm/README.md),
[Message Campaigns](scenarios/message-campaigns-jvm/README.md) and
[App Checks](scenarios/app-checks-jvm/README.md) own business API, state,
idempotency and observation; [Worker Simulator](worker_simulator_jvm/README.md#messages-and-shared-products)
owns their device side. Read the affected business contract,
[Server Scenario boundary](server_jvm/README.md#worker-and-scenario-assembly) for
application/dependency/lifecycle changes, and [Boot](server_boot_jvm/README.md)
for profile composition. [Coexistence](integrations/scenario-coexistence/README.md)
is the business proof entrypoint.

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
