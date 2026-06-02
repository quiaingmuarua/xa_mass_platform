# Integrations Java SDK Adoption Roadmap

Status: archived implemented mainline.

This roadmap follows the completed local/public-readiness slice for
[`sdk/xa-mass-java-sdk`](../../../sdk/xa-mass-java-sdk). Its goal is
not immediate external publication and not a sample-gallery expansion.
`xa-mass-java-sdk` exists so external Java task producers and worker processes
can conveniently register with a running `xa-mass-server`, submit work, poll or
receive dispatch, and report results through real public links. The repository
should prove that value through realistic SDK-backed integrations, not through
simple standalone sample apps.

## Review Finding

`integrations/xa-mass-java-sdk` is now the right standard entry point for Java
code that talks to the public task and worker HTTP APIs. Its value is not
standalone demonstration samples. Its value is that real external-process
integration surfaces can register workers, register worker topology, submit
tasks, run worker sessions, and report results through the same API that a
third-party Java application would use.

Current adoption state:

- IJS-0 inventory is recorded in
  [INTEGRATIONS_JAVA_SDK_ADOPTION_INVENTORY.md](./2026-06-01_INTEGRATIONS_JAVA_SDK_ADOPTION_INVENTORY.md).
- `integrations/xa-mass-scenario-launcher` composes `xa-mass-java-sdk` for
  task creation, item append, worker topology, polling worker sessions, and
  WebSocket worker sessions. It is the current Java SDK adopter pattern and
  has server black-box proof through `JavaScenarioLauncherBlackBoxIntegrationTest`.
- `integrations/samples/java` has been removed as a parallel Java product
  surface. The former polling, WebSocket, and socket sample proofs are replaced
  by scenario-launcher SDK proof for Java and Node sample fixtures for
  adapter-level protocol coverage.
- `xa-mass-server` uses the Java SDK only in black-box/test-style external
  proofs. Production server code must not route through the SDK.
- `integrations/xa-mass-worker-pack` owns embedded/dev worker capability,
  sample fault behavior, and realtime frame clients. It is the second
  strategic Java integration surface after scenario-launcher. Its current
  realtime clients stay local because they own sample command/fault behavior
  that is intentionally outside the SDK.
- `integrations/xa-mass-java-sdk` already contains the transport-independent
  handler runtime foundation under `com.xa.mass.client.worker.handler`,
  including `WorkerEventHandlers` and `WorkerEventHandlerRuntime`.
  Worker-pack adoption should consume that SDK-side foundation rather than
  implement a second generic dispatch handler runtime.
- Node samples remain runtime/protocol proof. A Node SDK is not part of this
  roadmap.

The main design correction is sequencing: internal Java standardization comes
before public artifact publication, and real integration consumers are more
important than standalone Java sample apps.

Simple samples are therefore a retirement target. They may be useful as
temporary black-box fixtures while a richer SDK-backed integration does not yet
exist, but they should not be expanded into a second Java product surface.

## Decision

Use `xa-mass-java-sdk` as the internal Java standard for public XA Mass API
calls made from strategic Java integration entry points.

Default rule:

- Strategic Java integration code that calls `/api/v1/**` or
  `/worker-api/v1/**` should use `MassPlatform`, typed task/worker clients, or
  worker sessions from `integrations/xa-mass-java-sdk`.
- The first strategic adopter is `integrations/xa-mass-scenario-launcher`.
- The next strategic adopter is `integrations/xa-mass-worker-pack`, only for
  public client/session behavior that the SDK actually owns.
- Simple Java samples are allowed only as temporary proof scaffolding. When
  scenario-launcher or worker-pack can prove the same public task/worker path,
  the simple sample should be removed rather than preserved as an alternate
  recommendation.

Server startup baseline:

- `xa-mass-server` starts as a platform host. It does not initialize demo
  tasks, workers, WorkerGroups, adapter nodes, or node/group bindings from
  production server startup.
- Task submission, worker topology registration, worker online/polling, and
  result submission must come from real external links: Java SDK launchers,
  external worker processes, or black-box tests that exercise public routes.
- This is the value proof for the SDK: task and worker state exists because an
  external actor registered or submitted it, not because server startup seeded
  it internally.
- Server-side dev metadata bootstrap may still exist only for catalog,
  submitter, rule, or sample-admin setup that has not yet moved to a public
  SDK surface. It must not recreate task/worker scenario seeding.

Built-in worker group baseline:

- The platform may later ship curated, real worker groups as part of
  `integrations/xa-mass-worker-pack` or another integration bundle.
- A built-in worker group is not privileged. Its WorkerGroup, AdapterNode,
  worker identity, presence, capability, dispatch handling, and result
  reporting must still enter through the same public API / SDK session path as
  any external worker.
- Bundled definitions may make local/dev startup convenient, but they must not
  become server-owned task or worker initialization.
- The value proof remains external registration and execution: built-in means
  repository-provided integration package, not kernel-owned worker state.

Allowed exceptions:

- No current Java sample app is an exception. If a future temporary Java sample
  fixture is introduced, it must have an explicit owner reason and retirement
  trigger.
- Worker-pack realtime frame clients and sample fault runtime before a public
  realtime Java session exists.
- Dev-only bootstrap endpoints under `/sample-api/bootstrap/**` when the SDK
  intentionally does not own sample/admin setup.
- Test fake servers and assertions that inspect request paths.
- Non-platform business HTTP calls, such as fetching a page as part of a sample
  task payload.

Hard Rules:

- Do not make `xa-mass-java-sdk` depend on `xa-mass-base`,
  `xa-mass-server`, `xa-mass-sdk`, `xa-mass-worker-pack`, engine modules, or
  transport implementation modules.
- Do not make production server code call `xa-mass-java-sdk` to simulate an
  external caller.
- Do not move worker-pack command/fault behavior into the SDK.
- Do not recreate raw Java WebSocket/socket samples now that Java SDK
  scenario-launcher proof exists. Socket remains adapter-internal unless a
  future SDK session decision explicitly promotes it.
- Do not enforce a broad ban on `java.net.http.HttpClient` in all Java
  integrations. Guard platform API calls, not generic business HTTP usage.

Non-Goals:

- Do not keep `integrations/samples/java` as a parallel Java product surface
  once strategic SDK consumers cover the proof.
- Do not start a Node SDK track as part of this roadmap.
- Do not publish `xa-mass-java-sdk` to an external registry as part of this
  adoption roadmap.
- Do not start a public Java socket SDK track as part of this roadmap.

## Target Shape

```text
Strategic Java integration caller
  -> integrations/xa-mass-java-sdk
      -> MassPlatform
      -> tasks()
      -> workers()
      -> workerSessions().polling()
      -> workerSessions().webSocket()

xa-mass-scenario-launcher
  -> primary internal SDK adopter
  -> registers topology, submits tasks, runs polling and WebSocket sessions

xa-mass-worker-pack
  -> second strategic SDK adopter when SDK-owned session/client behavior exists
  -> owns curated real worker groups and sample/dev worker capabilities
  -> keeps sample command/fault/runtime ownership local
  -> transport clients adapt frames into transport-independent handlers

integrations/samples/java
  -> removed; do not reintroduce as a parallel product surface
```

## Sample Retirement Rule

Simple Java samples under `integrations/samples/java` should be removed when
both conditions are true:

1. a strategic SDK-backed path proves the same public contract from outside
   `xa-mass-server`;
2. the sample no longer adds unique protocol or cross-process evidence that is
   missing from scenario-launcher, worker-pack, or server black-box tests.

Do not preserve a sample just because it is easy to understand. The long-term
developer story should be realistic registration and execution through
`xa-mass-java-sdk`, with scenario-launcher and worker-pack as the repository's
own consumers.

`xa-mass-java-sdk` is the caller API boundary. It is not a shared base module.
If common command or handler utilities are extracted later, they should be
owned as SDK-facing contracts only when multiple public Java sessions actually
share them. Do not create a broad common utility module just to avoid a
dependency on `xa-mass-base`.

## Roadmap

### IJS-0: Adoption Inventory

Status: implemented by
[INTEGRATIONS_JAVA_SDK_ADOPTION_INVENTORY.md](./2026-06-01_INTEGRATIONS_JAVA_SDK_ADOPTION_INVENTORY.md).

Scope:

- Inventory Java code under `integrations/` that calls public platform routes.
- Classify each caller as:
  - strategic SDK consumer
  - temporary sample fixture
  - worker-pack embedded/dev runtime
  - dev-only bootstrap caller
  - test-only assertion or fake server
- Record every current Java raw platform route call that is allowed to remain.
- Decide whether each Java sample under `integrations/samples/java` is still
  needed, can be removed immediately, or must wait for scenario-launcher /
  worker-pack replacement proof.
- Record the realistic replacement proof for each sample that remains.

Out of scope:

- Realtime SDK implementation.
- Worker-pack dependency migration.
- External publication.

Acceptance:

- The inventory lists every Java `HttpClient`, `WebSocket`, raw `Socket`, and
  public platform route caller under `integrations/`.
- Each remaining raw platform API call has an explicit owner reason.
- Scenario launcher is confirmed as the primary standard SDK consumer.
- Each Java sample has a keep/remove/replacement decision.
- Samples kept temporarily have an explicit retirement trigger.

### IJS-1: Standardize Scenario Launcher As The Java SDK Consumer

Status: implemented.

Scope:

- Keep `integrations/xa-mass-scenario-launcher` as the formal internal adopter
  for task seeding, worker topology, and polling worker runtime.
- Remove `integrations/samples/java/worker-polling` once scenario-launcher
  covers the same black-box polling proof.
- Delete `JavaPollingWorkerBlackBoxIntegrationTest` after the scenario-launcher
  black-box proof exists.
- Update docs to name `xa-mass-java-sdk` as the Java standard for public
  task/worker API calls from strategic Java integrations.
- Keep raw HTTP escape hatches in the SDK marked unstable and avoid exposing
  them as the recommended integration path.

Out of scope:

- Adding SDK dependency to worker-pack.
- Adding public admin/catalog bootstrap SDK surfaces.
- Designing a Node SDK.

Acceptance:

- Scenario launcher compiles and runs through SDK typed clients/session.
- Scenario launcher can be used as the Java SDK proof for:
  task shell -> item append -> worker topology -> polling dispatch -> result
  submit.
- A scenario-launcher black-box proof exists through
  `JavaScenarioLauncherBlackBoxIntegrationTest`.
- `JavaPollingWorkerBlackBoxIntegrationTest` and
  `integrations/samples/java/worker-polling` are removed.
- The Java SDK is documented as a real registration/execution tool, not a
  sample helper.

### IJS-2: Add Narrow Adoption Guards

Status: implemented in
`integrations/xa-mass-java-sdk/src/test/java/com/xa/mass/client/JavaExternalSdkArchitectureGuardTest.java`.

Scope:

- Add an architecture/documentation guard that prevents new Java integrations
  from hard-coding public platform API routes when an SDK typed client exists.
- The route-literal scan must cover Java sources under `integrations/` outside
  `integrations/xa-mass-java-sdk`. A guard that only scans the SDK module does
  not satisfy IJS-2.
- Candidate owner: a dedicated `integrations`-level architecture test or a
  scan anchored at the repository `integrations/` directory root.
- Detect platform route usage by scanning Java source string literals and
  simple concatenations for public platform route prefixes such as `/api/v1/`
  and `/worker-api/v1/`. Do not fail on `HttpClient` imports alone.
- Apply package/module/path allowlists for SDK internals, dev bootstrap
  callers, test fake servers/assertions, and temporary sample fixtures.
- Allow current temporary samples, dev bootstrap callers, and test fixtures by
  explicit allowlist with owner comments.
- Keep the existing SDK dependency guard: the SDK must not import platform
  runtime/server/transport implementation modules.

Out of scope:

- Guarding Node samples.
- Banning business HTTP calls.
- Banning test fake-server assertions.
- Keeping Java sample apps alive just to satisfy the guard.

Acceptance:

- A new raw Java `/worker-api/v1/workers/{id}:poll` integration caller fails
  the guard unless it is in an approved temporary fixture or test path.
- The same violation is caught when it appears in
  `integrations/xa-mass-scenario-launcher`,
  `integrations/xa-mass-worker-pack`, or a future Java integration module, not
  only inside `integrations/xa-mass-java-sdk`.
- A sample task that fetches a user URL with `HttpClient` is still allowed.
- A Java source file that imports `HttpClient` but has no platform route prefix
  literal is not a guard violation.
- A Java source file that builds a platform route through a string literal or
  simple concatenation is treated as a platform route caller and must either
  use the SDK or be listed in the allowlist.
- Guard failure messages point callers to `MassPlatform` or the relevant
  roadmap exception.

### IJS-3: Worker-Pack SDK Adoption

Status: implemented audit keeps worker-pack explicitly non-adopting for the
current mainline. See
[INTEGRATIONS_JAVA_SDK_ADOPTION_INVENTORY.md](./2026-06-01_INTEGRATIONS_JAVA_SDK_ADOPTION_INVENTORY.md)
and [integrations/xa-mass-worker-pack/README.md](../../../integrations/xa-mass-worker-pack/README.md).

Scope:

- Re-audit `integrations/xa-mass-worker-pack` after IJS-1/IJS-2.
- Add `xa-mass-java-sdk` only if worker-pack has a real public HTTP/session
  caller to migrate.
- The transport-independent handler runtime already exists in the SDK
  (`WorkerEventHandlers`, `WorkerEventHandlerRuntime`). IJS-3 scope is
  worker-pack adoption of this runtime where it fits, not SDK-side handler
  infrastructure implementation.
- Keep worker-pack sample command runtime, fault profiles, and realtime frame
  behavior local.
- The public SDK WebSocket session now exists, but worker-pack's current
  WebSocket client remains a sample fault/command fixture. It is not migrated
  until the non-fault builtin worker path exists or SDK result queues expose
  the needed fault harness hooks.
- Prefer worker-pack over standalone Java sample apps as the long-term
  repository proof for Java worker behavior that includes fault/command
  scenarios.
- Use worker-pack to prove SDK-backed worker integration where the proof needs
  realistic worker lifecycle, command, fault, or transport behavior.
- Split worker-pack dispatch handling from transport clients. WebSocket,
  socket, and polling clients should adapt delivery into the SDK-owned
  transport-independent event handler runtime rather than owning business
  dispatch in transport-specific frame handlers.
- Keep curated worker group definitions in worker-pack or another integration
  bundle, but require those groups to register through SDK/public worker APIs
  at runtime.

Out of scope:

- Making worker-pack a dependency of the SDK.
- Moving sample command/fault behavior into the SDK.
- Replacing embedded dev-shell runtime composition with remote SDK calls.
- Giving bundled worker groups server-side privilege or bypassing public
  registration.

Acceptance:

- Worker-pack either remains explicitly non-adopting with a current audit, or
  consumes the SDK only for public client/session behavior.
- No worker-pack dependency change is justified by directory placement alone.
- Worker-pack README records the decision and current exception set.
- Any Java sample kept only for worker behavior has a retirement path through
  worker-pack or scenario-launcher.
- Worker-pack transport clients no longer own generic event dispatch handling
  once the SDK handler/runtime can support the same behavior.
- Built-in worker groups provided by worker-pack prove the same external path:
  declare/register -> online/session -> dispatch handler -> result report.

### IJS-4: WebSocket Java Session Adoption Through Strategic Consumer

Status: implemented through `workerSessions().webSocket()` and
`JavaScenarioLauncherBlackBoxIntegrationTest`.

Prerequisite:

- `JAVA_EXTERNAL_SDK_REALTIME_PROTOCOL_ROADMAP.md` delivers the public
  WebSocket worker session contract and SDK implementation through WSDK-3 and
  WSDK-4.

Scope:

- Adopt the SDK WebSocket worker session delivered by WSDK-4 through a
  strategic integration consumer.
- Scenario-launcher is the current adopter because it can prove the SDK
  realtime session without worker-pack's sample fault harness.
- Remove `integrations/samples/java/worker-websocket` after black-box parity
  exists through scenario-launcher.

Out of scope:

- Socket session convergence.
- Collapsing WebSocket and socket into an opaque generic transport.
- Changing server/transport frame truth just to simplify the SDK.
- Implementing the WebSocket worker session itself. That is WSDK-4 scope.

Acceptance:

- Java WebSocket black-box proof passes through an SDK-backed strategic
  consumer.
- The old raw Java WebSocket sample is removed.
- The SDK handler contract remains transport-neutral from the caller's point of
  view.

### IJS-5: Socket Java Session Decision

Status: implemented decision: Java socket is not a public SDK session in this
roadmap.

Prerequisite:

- WebSocket SDK session has passed black-box parity and the socket protocol is
  either documented as public or explicitly kept as adapter-internal proof.

Scope:

- Record that Java external standardization stops at polling plus WebSocket.
- Remove `integrations/samples/java/worker-socket`; socket remains
  adapter-internal/Node fixture coverage unless a future socket SDK roadmap
  promotes it.
- If promoted, add a socket session with the same handler/result contract used
  by polling and WebSocket.
- If not promoted, document why Java external standardization stops at polling
  plus WebSocket.

Acceptance:

- Java socket sample no longer exists as a recommended public Java integration
  path.
- Socket decision is recorded before adding more socket-specific Java features.

### IJS-6: Internal Standard Proof

Status: implemented.

Scope:

- Make the proof set represent the intended Java external story:
  - SDK unit and architecture guards
  - scenario launcher package
  - scenario launcher black-box or boot-shell proof for Java SDK polling
  - scenario launcher black-box proof for Java SDK WebSocket
  - worker-pack tests when worker-pack consumes SDK behavior
- Keep publication readiness separate from internal adoption proof.

Acceptance:

- The documented Java standard can be exercised internally without relying on
  `xa-mass-sdk` embedding APIs.
- A scenario-launcher black-box or boot-shell proof exists before IJS-6 is
  marked complete.
- `JavaPollingWorkerBlackBoxIntegrationTest` is deleted so it no longer looks
  like the primary Java SDK adoption proof.
- Any Java integration that bypasses the SDK is either non-platform business
  HTTP, temporary fixture code, dev bootstrap, or test-only proof.
- `integrations/samples/java` is removed.

## Verification Matrix

Minimum checks after IJS-1/IJS-2:

```bash
mvn -pl integrations/xa-mass-java-sdk test
mvn -pl integrations/xa-mass-scenario-launcher -am -DskipTests package
```

Primary proof target after IJS-1:

```bash
mvn -pl xa-mass-server -am -Dtest=JavaScenarioLauncherBlackBoxIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

After worker-pack consumption changes:

```bash
mvn -pl integrations/xa-mass-worker-pack -am test
```

`JavaScenarioLauncherBlackBoxIntegrationTest` covers both polling and WebSocket
SDK worker sessions.

## Risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| SDK standardization becomes premature publication | compatibility burden before internal proof stabilizes | keep publication in the public-readiness roadmap |
| Broad raw-HTTP guard blocks legitimate task business calls | samples become harder to write | guard platform route literals, not `HttpClient` itself |
| Java sample apps return as a parallel product surface | SDK adoption proof gets fragmented again | keep Java proof in scenario-launcher or future worker-pack SDK consumers |
| SDK is treated as a showcase helper | project value is proved by toy flows instead of real external registration | center scenario-launcher and worker-pack as internal consumers |
| Realtime SDK exceeds current protocol contract | public API freezes unsupported semantics | keep socket out of SDK and keep WebSocket session aligned with current adapter contract |
| Worker-pack adopts SDK because of folder placement only | dependency churn without owner clarity | require real public client/session boilerplate removal |
| SDK turns into base/common utility module | external API boundary becomes another platform internals bucket | keep SDK pure remote client; extract only proven shared SDK-facing contracts |
| Server production code starts using Java SDK | external-client proof leaks into product runtime | server production dependency guard remains blocked |

## Owner Review

The next `integrations` roadmap should be adoption-first, not publication-first.
`xa-mass-java-sdk` is now strong enough to be the internal Java standard for
public task/worker HTTP and polling worker usage. That standard should be
proved first by `xa-mass-scenario-launcher`, then by `xa-mass-worker-pack`
where worker-pack has real SDK-owned client/session behavior to consume.

`integrations/samples/java` is no longer a strategic destination and has been
removed. That matches the current server boundary: `xa-mass-server` is a clean
platform host, and task/worker state enters through real external registration,
submission, session, and result links.

The desired proof is not "a Java sample can talk to the server." The desired
proof is "external Java producers and workers can make the platform useful
without server-owned task or worker initialization."
