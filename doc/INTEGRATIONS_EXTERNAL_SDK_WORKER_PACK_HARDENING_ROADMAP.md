# Integrations External SDK And Worker-Pack Hardening Roadmap

Status: proposed integrations hardening roadmap.

This roadmap follows the completed Java SDK adoption, worker-pack SDK
convergence, and control-console realistic scenario work.

The framework is now in place:

- `integrations/xa-mass-java-sdk` is the external Java entry point.
- `integrations/xa-mass-scenario-launcher` is the strategic internal SDK
  adopter.
- `integrations/xa-mass-worker-pack` has its first SDK-backed capability proof
  through `tool.geo.lookup`.
- the completed control-console scenario established the realistic probe
  vocabulary: `publicProbe`, `deviceProbe`, `dataQualityProbe`,
  `probe.phone.metadata`, `probe.url.dns`, `probe.csv.validate`,
  `probe.json.schema`, and related WorkerGroups.

The remaining work is not another demo migration. The goal is to harden the
external SDK contract and turn worker-pack from a proof scaffold into a
capability package that can support real business-facing workers.

This roadmap owns the post-convergence work. The completed
`doc/INTEGRATIONS_WORKER_PACK_SDK_CONVERGENCE_ROADMAP.md` records the decision
to retire worker-pack raw Java socket demo paths and keep worker-pack focused on
real capabilities plus fault harness behavior. This roadmap turns that decision
into a harder external actor contract.

## Current Facts

- `MassPlatform.builder()` is the stable SDK entry point, but its current auth
  behavior conflicts with the SDK README: docs say unauthenticated requests are
  allowed, while the builder requires `apiKey` or `bearerToken`.
- the Java SDK README contains at least one non-compiling example:
  `mass.tasks().readResults(...)` does not match the current `TaskClient`
  method name `results(...)`.
- `PollingWorkerSession` has a working public worker loop and uses the
  transport-neutral SDK handler runtime.
- `WebSocketWorkerSession` has first-slice support for realtime worker
  registration, WebSocket connection, canonical dispatch/result frames,
  reconnect attempts, and an outbound result queue. `JavaScenarioLauncherBlackBoxIntegrationTest`
  proves the Java SDK WebSocket happy path through scenario-launcher. It is not
  yet a mature long-running realtime SDK contract because close/reconnect/queue
  terminal semantics still need hardening.
- `GeoLookupTool` is useful as a first proof, but it is still deterministic and
  simulated. It should either become a provider-configurable capability or be
  explicitly demoted to fixture scaffolding once scenario-derived probe
  capabilities land.
- worker-pack still mixes real capabilities, embedded dev-shell support,
  Spring sample bootstrap, raw WebSocket command/fault harness code, and test
  fixture dependencies in one artifact.
- the Java SDK already exposes task clients, worker topology clients, and
  worker session clients, but the hardening proof must keep those as separate
  public responsibilities rather than collapsing the SDK into a worker-loop-only
  library.
- the completed control-console scenario is server-owned. It provides the
  target vocabulary and proof goals, but it must not become worker-pack
  implementation ownership by accident.

## Owner Review

External Java SDK public contract belongs to
`integrations/xa-mass-java-sdk`.

The Java SDK may own:

- remote HTTP clients for public task APIs;
- remote HTTP clients for worker topology declarations:
  WorkerGroup, AdapterNode, NodeGroupBinding, and Worker execution identity;
- managed polling and WebSocket worker sessions;
- transport-neutral worker event handler runtime;
- SDK-side auth, timeout, retry, lifecycle, queue, and documentation
  ergonomics.

The Java SDK must not own:

- worker-pack sample/fault semantics;
- server/control-console scenario bootstrap;
- engine scheduling, lease, result convergence, or terminal policy;
- embedded runtime assembly through `xa-mass-sdk`;
- worker-pack as a dependency.

Worker-pack capability ownership belongs to
`integrations/xa-mass-worker-pack`.

Worker-pack may own:

- curated worker capability contracts and implementations;
- WorkerGroup specs and SDK-backed worker bootstrap helpers for those
  capabilities;
- deterministic CI-safe providers and optional dev/demo provider adapters;
- sample command/fault harness behavior that is explicitly not public SDK
  behavior.

Worker-pack must not own:

- server default scenario truth;
- frontend console aggregation or read APIs;
- public SDK transport/session semantics;
- hidden privileged registration paths;
- task producer client semantics or public SDK topology/session contracts.

The completed control-console scenario belongs to `xa-mass-server`. Its probe
catalog is a source of capability requirements for worker-pack, not an active
server roadmap to re-execute.

## Boundary Decision

Keep one active roadmap for SDK and worker-pack hardening because the current
blockers are coupled at the external actor boundary:

```text
external Java actor process
  -> xa-mass-java-sdk public contract
  -> task producer clients
  -> worker topology declaration clients
  -> SDK session lifecycle and handler runtime
  -> worker-pack capability handlers
  -> public task/worker APIs
  -> server/runtime/console observations
```

Do not split into separate roadmaps until the SDK contract hardening and
worker-pack capability boundary are stable enough to progress independently.

## WebSocket Support Classification

Current Java SDK WebSocket worker support is real but first-slice:

Supported now:

- public builder: `mass.workerSessions().webSocket()`;
- SDK-side AdapterNode, NodeGroupBinding, and realtime worker registration;
- JDK `HttpClient`/`WebSocket` connection to the server WebSocket endpoint;
- canonical task dispatch frame decoding;
- transport-neutral handler invocation;
- canonical result frame enqueue and send through the WebSocket connection;
- scenario-launcher black-box proof for one happy-path WebSocket task.

Not yet stable enough for final external SDK positioning:

- close semantics with pending queued results;
- reconnect behavior when results are queued and the socket is absent;
- bounded terminal outcome for abandoned or permanently failed results;
- operator-visible callbacks for dropped, retried, abandoned, or failed queued
  results;
- documented handshake/auth/session protocol beyond `workerId` / `routeKey`
  query parameters;
- worker command/fault harness behavior.

Worker-pack can use Java SDK WebSocket sessions only for normal capability
dispatch after EWH-2 hardens lifecycle semantics. Worker-pack command/fault
WebSocket harness code remains separate until the SDK has an explicit adapter
hook for that behavior.

## Target Shape

```text
integrations/xa-mass-java-sdk
  MassPlatform contract
  task producer typed clients
  worker topology declaration clients
  managed polling session
  managed WebSocket session with explicit lifecycle semantics
  transport-neutral handler runtime
  executable README/snippet tests

integrations/xa-mass-worker-pack
  capability contracts:
    tool.geo.lookup or probe.ip.geo
    probe.phone.metadata
    probe.url.dns
    probe.csv.validate
    probe.json.schema
  provider model:
    deterministic CI provider
    optional dev/demo provider
  SDK-backed worker bootstrap:
    WorkerGroupSpec
    AdapterNode setup
    NodeGroupBinding setup
    Worker identity setup
    polling first
    WebSocket for normal capabilities only after EWH-2
  isolated harness:
    command/fault WebSocket sample code remains clearly sample-only
```

## Do Not Start With

Do not start by splitting Maven modules or deleting worker-pack harness
dependencies. First make the SDK public contract correct, classify current
worker-pack surfaces, and add at least one scenario-derived capability proof.

## Non-Goals

- No public registry publication.
- No Node SDK track.
- No Java socket SDK support.
- No server/control-console scenario rewrite.
- No engine/runtime scheduling semantic changes.
- No new task terminal states for business probe outcomes.
- No public-internet dependency in CI or default tests.
- No compatibility aliases for old demo names.
- No migration of worker-pack fault commands into the Java SDK.

## EWH-0: Inventory And Contract Audit

Goal: make the remaining SDK and worker-pack risks explicit before hardening
behavior.

Scope:

- audit SDK README examples against current compiled API names;
- audit auth contract and decide whether unauthenticated clients are allowed;
- audit SDK examples and worker-pack docs for the external actor split:
  task producer, WorkerGroup capability declaration, AdapterNode registration,
  NodeGroupBinding relation, Worker execution identity, and concrete session
  startup;
- inventory WebSocket session close, reconnect, and outbound queue failure
  modes;
- inventory worker-pack dependencies and classify them as capability,
  dev-shell, command/fault harness, or test-only;
- map completed control-console probe events to worker-pack capability
  candidates.

Acceptance:

- an inventory records SDK public-contract drift and worker-pack dependency
  ownership;
- `tool.geo.lookup` is classified as configurable capability target or fixture
  scaffold;
- topology examples preserve the registration order:
  WorkerGroup -> AdapterNode -> NodeGroupBinding -> Worker -> session;
- no active example describes a WebSocket, polling, or socket-specific
  WorkerGroup;
- control-console scenario capability names are listed as inputs, not active
  server work;
- no code behavior changes are required in this slice.

Verification:

```powershell
rg -n "readResults|apiKey or bearerToken is required|WebSocket WorkerGroup|Polling WorkerGroup|Socket WorkerGroup|tool.geo.lookup|probe.phone.metadata|probe.url.dns" integrations doc xa-mass-server
```

## EWH-1: SDK Public Contract Correctness

Goal: make the external Java SDK contract truthful and copy-paste safe.

Scope:

- align auth behavior and docs:
  - either support explicit no-auth clients, or document auth as required;
  - if no-auth is supported, ensure `MassHttpClient` omits auth headers cleanly;
- fix README examples to match current `TaskClient` and worker APIs;
- add a snippet or documentation guard that fails on stale public method names
  where practical;
- keep `mass.http()` unstable and advanced-only.

Acceptance:

- `MassPlatform` auth behavior matches README exactly;
- Java SDK README examples use real public methods;
- focused SDK tests cover the selected auth behavior;
- no production dependency boundary changes are introduced.

Verification:

```powershell
mvn -pl integrations/xa-mass-java-sdk test
mvn -pl integrations/xa-mass-java-sdk dependency:tree
```

## EWH-2: SDK Session Lifecycle Hardening

Goal: make managed sessions suitable for long-running external workers and make
Java SDK WebSocket support safe for normal worker-pack capabilities.

Scope:

- define `PollingWorkerSession.close()` and `WebSocketWorkerSession.close()`
  terminal semantics for in-flight dispatch and queued results;
- define WebSocket result queue behavior on close, disconnect, reconnect, and
  full queue;
- define which queued-result outcomes are retried, dropped, abandoned, or
  reported as permanently failed;
- add listener callbacks for dropped, abandoned, or permanently failed queued
  results if needed;
- make reconnect scheduling bounded and observable enough for external worker
  operators;
- document the current WebSocket handshake/auth shape and what is not yet part
  of the public compatibility contract;
- keep handler invocation transport-neutral.

Acceptance:

- WebSocket close cannot spin indefinitely with a queued result and no socket;
- queued result terminal outcomes are deterministic and observable;
- reconnect attempts have documented backoff and stop conditions;
- WebSocket normal capability users have a clear contract for close,
  reconnect, queue-full, and send-failure behavior;
- command/fault harness behavior remains explicitly out of scope for the SDK
  session unless a separate adapter hook is added;
- polling and WebSocket sessions use the same handler runtime contract;
- tests cover close-with-pending-result, reconnect failure, and queue-full
  behavior.

Verification:

```powershell
mvn -pl integrations/xa-mass-java-sdk "-Dtest=PollingWorkerSessionTest,WebSocketWorkerSessionTest" test
mvn -pl xa-mass-server -am "-Dtest=JavaExternalSdkPollingSessionIntegrationTest,ExternalWorkerPublicContractTraceObservedIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## EWH-3: Worker-Pack Boundary And Artifact Shape

Goal: prevent worker-pack from becoming a mixed sample jar by accident.

Scope:

- classify worker-pack packages into capability runtime, SDK-backed bootstrap,
  sample/dev bootstrap, command/fault harness, and test fixture;
- decide whether the first step is package-level separation inside the current
  artifact or a new Maven artifact;
- add dependency ownership notes or guards for `xa-mass-sdk`, `xa-mass-java-sdk`,
  Spring, Gson, Lettuce, and command/base imports;
- keep raw WebSocket sample code only as command/fault harness until an SDK
  adapter replacement exists.

Acceptance:

- worker-pack README and inventory explain each production dependency;
- capability code does not depend on sample/fault harness packages;
- sample/fault harness code is not documented as public SDK usage;
- no Maven split occurs without a proof that package-level separation is
  insufficient.

Verification:

```powershell
mvn -pl integrations/xa-mass-worker-pack -am test
rg -n "sample\\.client|sample\\.command|SampleWorkerWebSocketClient" integrations/xa-mass-worker-pack/src/main/java/com/xa/mass/workerpack/tool
```

## EWH-4: Configurable Geo Capability

Goal: move `tool.geo.lookup` from first proof fixture toward a configurable
worker-pack capability.

Scope:

- introduce a provider abstraction for geo lookup;
- keep a deterministic local provider as the CI/default provider;
- optionally add a dev/demo provider profile for real IP/geolocation lookup
  only when terms/rate limits are acceptable;
- document whether `tool.geo.lookup` remains the worker-pack tool event or
  whether `probe.ip.geo` becomes the scenario-aligned public event;
- preserve result shape compatibility only inside the repo until the event
  contract is declared stable.

Acceptance:

- geo capability has an explicit provider contract;
- CI/default execution does not require public internet;
- invalid/timeout/provider-failure outcomes are classified as business result
  output, not platform terminal semantics;
- server E2E still proves SDK-backed worker-pack registration and result
  readback.

Verification:

```powershell
mvn -pl integrations/xa-mass-worker-pack -am test
mvn -pl xa-mass-server -am "-Dtest=WorkerPackGeoLookupExternalSdkIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## EWH-5: Scenario-Derived Local Probe Capabilities

Goal: implement the CI-safe worker capabilities already established by the
completed control-console scenario.

Scope:

- add `probe.phone.metadata` worker-pack capability with deterministic local
  parsing behavior;
- add `probe.url.dns` capability with deterministic reserved-domain and local
  fixture failure classification;
- add `probe.csv.validate` and `probe.json.schema` local validators;
- use the common workload envelope fields where present:
  `sleepMs`, `timeoutMs`, `expectedOutcome`, and `traceLabel`;
- keep public provider backed probes out of default CI.
- implement these capabilities through polling sessions first. Add WebSocket
  variants only after EWH-2 passes and only for normal capability dispatch, not
  command/fault harness semantics.

Acceptance:

- each capability has a handler-level unit test;
- each capability has a WorkerGroup spec;
- expected business failures are returned as structured worker results;
- capability contracts align with the completed control-console event names.

Verification:

```powershell
mvn -pl integrations/xa-mass-worker-pack -am test
```

## EWH-6: Phone Device Stage-2 Proof

Goal: connect worker-pack capability implementation to the scenario's
group-first plus fingerprint matching proof.

Scope:

- create SDK-backed worker-pack setup for `phone-device-probe`;
- register at least two workers in the same WorkerGroup with different
  fingerprint attributes;
- start the first proof with polling workers. Add a WebSocket worker variant
  only after EWH-2 establishes reliable realtime session lifecycle semantics;
- submit a `deviceProbe/probe.phone.metadata` task through the Java SDK/public
  task API;
- verify the non-matching fingerprint worker is not assigned and the matching
  worker completes the item.

Acceptance:

- proof uses public SDK/API paths for task create, item append, WorkerGroup
  registration, worker registration, dispatch, and result readback;
- the proof verifies both negative and positive Stage-2 matching cases;
- no server startup path seeds privileged workers for the proof;
- trace/proof docs point to this as worker-pack capability proof, not generic
  Java SDK parity proof.

Verification:

```powershell
mvn -pl xa-mass-server -am "-Dtest=*PhoneDevice*WorkerPack*IntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## EWH-7: Proof Registry, Runbook, And Residue Cleanup

Goal: make the new contract and capability boundaries visible to future work.

Scope:

- update `PROOF_REGISTRY.md`, `TESTING_INDEX.md`, and `VERIFIED_RUNBOOK.md`
  with the new SDK and worker-pack proofs;
- update worker-pack README with capability/runtime/harness separation;
- update Java SDK README with verified public examples;
- add or update architecture guards for route literals, dependency ownership,
  and package boundaries when the implementation makes them enforceable;
- add or update documentation/architecture checks that keep WorkerGroup
  capability truth separate from transport-specific Worker instance/session
  wording;
- archive this roadmap only after mainline slices are implemented and residue
  scans are clean.

Acceptance:

- active docs no longer describe proof fixtures as final capabilities;
- completed control-console roadmap remains archived and historical;
- external SDK public contract, worker-pack capability proof, and worker-pack
  fault harness proof are distinguishable in docs;
- SDK examples and worker-pack docs show task producer APIs, topology
  declarations, and worker sessions as separate public facets;
- no active docs treat WorkerGroup as transport-specific or AdapterNode as
  capability truth;
- no active docs recommend raw worker-pack WebSocket code as public Java SDK
  usage.

Verification:

```powershell
rg -n "readResults|public SDK usage|CONTROL_CONSOLE_REALISTIC_SCENARIO_ROADMAP|WebSocket WorkerGroup|Polling WorkerGroup|Socket WorkerGroup|tool.geo.lookup" doc integrations xa-mass-server
mvn -pl integrations/xa-mass-java-sdk,integrations/xa-mass-worker-pack -am test
```

## Suggested Implementation Order

1. EWH-0 inventory.
2. EWH-1 SDK public contract correctness.
3. EWH-2 SDK session lifecycle hardening.
4. EWH-3 worker-pack boundary classification.
5. EWH-4 configurable geo capability.
6. EWH-5 scenario-derived local probe capabilities.
7. EWH-6 phone-device Stage-2 proof.
8. EWH-7 proof and residue cleanup.

Do not start EWH-5/EWH-6 before EWH-1/EWH-2 if the implementation needs
long-running SDK sessions. The worker-pack capabilities should prove the SDK,
not work around it.

Polling-only capability work may start after EWH-1 and EWH-3. Any worker-pack
WebSocket capability proof must wait for EWH-2, because the current Java SDK
WebSocket support is happy-path capable but not final long-running session
behavior.
