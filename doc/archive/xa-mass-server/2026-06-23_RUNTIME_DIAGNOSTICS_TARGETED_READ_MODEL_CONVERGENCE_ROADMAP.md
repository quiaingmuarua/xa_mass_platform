# Runtime Diagnostics Targeted Read Model Convergence Roadmap

Status: completed and archived on 2026-06-23.

## Summary

Before this convergence, `RuntimeDiagnosticsOperations` exposed get-all
session diagnostics:

```java
List<Map<String, Object>> listSessions();
Map<String, Object> getSessionStats();
```

Recent transport cleanup had removed the endpoint-inspector implementation
behind these methods, but the public SDK/server shape still existed. The default
implementation returned an empty list and zero counters, which avoided leaking
transport endpoint snapshots but left a misleading API surface alive.

Target principle:

```text
Diagnostics are explicit, targeted, bounded, and owner-local.
They must not be broad get-all projections over sessions, endpoints, workers,
transport routes, or connection state.
```

Cardinality is a valid diagnostic. Entity metadata inventory is not:

```text
OK:      queue size, pending count, per-adapter aggregate counters,
         locked worker count, rejected count, oldest age.
Not OK:  list every session, list every endpoint, list every worker id only to
         compute a diagnostic, return connection/session metadata rows.
```

Pagination is not a diagnostic boundary. Adding `limit`, `offset`, or
`pageToken` to a get-all diagnostic does not make the shape acceptable. In
particular, the transport module must not introduce paginated session,
endpoint, connection, or route diagnostics. Any future paginated product API
must have a strong product requirement, explicit owner, consumer, permission
model, cost model, and proof; it must not be introduced as a generic runtime or
transport diagnostic.

This roadmap removed generic session get-all diagnostics from the SDK/server
surface and kept only diagnostics that are either aggregate, targeted by an
explicit subject, or owned by a local adapter/operator surface with bounded
debug semantics.

Existing internal APIs are not automatically valid contracts. Every runtime
diagnostic route and SDK method must first pass the owner question: who owns the
fact, who consumes it, what failure does it prevent, and is it truth,
projection, evidence, hint, residue, or experimental? If the answer is weak,
delete the API instead of preserving it because tests or docs already mention
it.

## Before Convergence Observations

The following were the code facts that motivated the roadmap before the
2026-06-23 implementation:

- `RuntimeDiagnosticsOperations` still declares `listSessions()` and
  `getSessionStats()`.
- `DefaultRuntimeDiagnosticsOperations.listSessions()` returns `List.of()`.
- `DefaultRuntimeDiagnosticsOperations.getSessionStats()` returns
  `activeConnections=0` and `workerCount=0L`.
- `SessionController` still exposes:
  - `GET /api/v1/runtime/sessions`
  - `GET /api/v1/runtime/sessions:stats`
  through split annotations: class-level `@RequestMapping("/api/v1/runtime")`
  plus method-level `@GetMapping("/sessions")` / `@GetMapping("/sessions:stats")`.
- `xa-mass-server/doc/API_SURFACE_INVENTORY.md` still classifies these routes
  as console diagnostics and says the underlying live list scans current
  sessions.
- `xa-mass-server/doc/INTERNAL_API_REFERENCE.md` documents the routes as
  implemented, while explaining that the default embedded SDK reports zero.
- `ApiRouteAuthorizationCatalog` and `ApiAuthInterceptorTest` still preserve
  `/api/v1/runtime/sessions` as an authorized route surface.
- `listLockedWorkerIds()` currently shapes server worker/catalog responses:
  worker rows expose `locked`, group rows expose `lockedCount`, and group rows
  expose `reachableUnlockedWorkerCount`.
- `WorkerInspectionOperations.listReachableWorkerIds()` currently shapes the
  same server worker/catalog responses: worker rows expose `reachable` /
  `reachability`, group rows expose `reachableUnlockedWorkerCount`, and event
  capability rows expose reachable worker ids.
- `TransportDebugOperations` currently extends `RuntimeDiagnosticsOperations`,
  so the raw/operator transport debug side-channel also inherits generic
  runtime diagnostics methods.
- `xa-mass-server/doc/API_SURFACE_INVENTORY.md` still says generic
  `/api/v1/runtime/**` list/detail snapshots are acceptable when windowed.
- String-only residue scans for `SessionController` can false-positive on
  unrelated session domains such as `ApiKeyViewerSessionController`.
- Transport endpoint inspector production classes have been removed:
  `WorkerEndpointInspector`, `WorkerEndpointSnapshot`,
  `CompositeWorkerEndpointInspector`, and adapter endpoint inspectors.

## Owner Review

Diagnostics are not runtime truth.

Owner split:

- SDK/server may expose operator diagnostics, but only as product/API surfaces
  with clear subject, cost, and bounded output.
- Worker runtime owns worker lifecycle, reachability evidence, scheduling
  evidence, admission, and lock state.
- Worker/catalog product read models may return worker rows when they are
  explicitly product-owned. That does not make a generic diagnostics method
  that returns all reachable or locked worker ids acceptable as a hidden helper.
- Transport owns delivery, handoff, result ingress, adapter mailbox mechanics,
  and adapter-local session evidence. It must not publish a global worker
  session inventory for SDK/server to reinterpret.
- Concrete adapters may keep local session diagnostics for logs or direct
  adapter debugging, but those diagnostics must not become worker lifecycle,
  reachability, scheduling, or product API truth.

`listSessions()` and `getSessionStats()` fail this boundary because they expose
session/connection shape without naming the owner, subject, freshness, cost, or
consumer semantics. Returning empty values is not a convergence; it is a stubbed
compatibility path.

`/api/v1/runtime/sessions` also fails the API rationality gate. The valid needs
behind it are narrower: targeted worker delivery feasibility, aggregate adapter
or queue counters, or adapter-local debug. None require a server/SDK get-all
session inventory.

## Boundary Decision

Remove generic session get-all diagnostics from `RuntimeDiagnosticsOperations`
and remove the server runtime session routes. Do not preserve these APIs while
searching for a better owner; any future product requirement for session or
adapter inventory must be a new owner-specific roadmap with consumer,
permission, cost, and proof.

Allowed diagnostics after convergence:

- aggregate queue/runtime counters that do not expose per-session, per-endpoint,
  or whole-worker identity inventories
- aggregate counters grouped by stable non-entity dimensions, such as adapter
  kind/mailbox or queue owner, when the values are counts/ages/rates rather
  than worker/session metadata
- targeted worker/runtime checks, such as a single `workerId` lock or
  reachability query, when the owner is worker runtime and the subject is
  explicit
- caller-scoped batch predicates, such as lock states for an explicitly
  provided bounded subject set, if the owner is worker/admission runtime and
  the method does not expose unrelated workers
- size/count APIs, such as locked worker count or queue depth, when the caller
  does not receive the underlying identity set used to compute the count
- local adapter debug tools that send to or inspect a known worker/session
  through adapter-owned APIs, without becoming SDK/server product truth
- trace/audit query surfaces that are explicitly trace-owned and not hot-path
  runtime truth

Forbidden diagnostics:

- SDK/server `list all sessions`
- SDK/server `list all endpoints`
- SDK/server `list all active connections`
- SDK/server `list all workers by transport session`
- SDK/server `list all locked worker ids` as a generic diagnostics method
- SDK/server `list all reachable worker ids` as a generic diagnostics helper
  unless it is classified as a worker-runtime/product read model with owner,
  consumer, bounds, and proof
- default implementations that return empty/zero values to preserve an old
  shape
- paginated get-all routes that only add `limit` while preserving the same
  unbounded owner leak
- transport-owned paginated diagnostics over sessions, endpoints, connections,
  routes, route owners, mailboxes, or worker session metadata
- raw `List<Map<String,Object>>` rows of sessions, endpoints, workers,
  connections, or transport internals
- diagnostics that can be used by scheduling, dispatch, retry, lifecycle, or
  reachability decisions

## Target Shape

`RuntimeDiagnosticsOperations` now keeps narrow runtime diagnostics only:

```java
public interface RuntimeDiagnosticsOperations {
    Map<String, Object> getQueueDetail();
    Map<String, Object> getQueueMetrics();
    boolean isWorkerLocked(String workerId);
}
```

`listLockedWorkerIds()` was removed from generic runtime diagnostics. It was a
get-all worker identity projection, not equivalent to a count. If product
surfaces need lock state, prefer one of:

- `isWorkerLocked(workerId)` for single-worker detail
- an admission-owned count for group/summary cards
- a caller-scoped batch predicate over a caller-provided bounded subject set

Do not keep a diagnostics method that returns every locked worker id just so a
server controller can compute size or annotate rows.

`listReachableWorkerIds()` was removed from the SDK worker inspection surface.
Product responses that need row annotation now use targeted reachability
predicates over the already selected worker rows; group summary cards should
prefer owner-provided counts. Do not use a hidden all-id set only to compute
product fields.

`TransportDebugOperations` can remain a raw/operator side-channel only because
the inherited `RuntimeDiagnosticsOperations` surface is now aggregate/targeted.
It must not regain generic session, endpoint, reachable-worker-id, or
locked-worker-id inventories.

Server API target:

```text
DELETE /api/v1/runtime/sessions
DELETE /api/v1/runtime/sessions:stats
```

Do not replace them with `/sessions?limit=...`. A bounded get-all still leaks
the wrong owner shape and remains an attractive false truth for future agents.

## Non-Goals

- Do not redesign worker runtime reachability in this roadmap.
- Do not delete legitimate worker/catalog product read models merely because
  they return worker rows; this roadmap targets generic diagnostics helpers and
  hidden all-id projections that product surfaces use as side inputs.
- Do not add a new adapter endpoint inventory API.
- Do not add a new generic `DiagnosticQuery` or map-based query language.
- Do not introduce compatibility aliases or fallback empty implementations.
- Do not remove adapter-local session maps needed for final-hop delivery.
- Do not introduce pagination as a diagnostics mitigation. Transport runtime
  and adapters must not grow paginated session, endpoint, connection, route,
  mailbox, or route-owner diagnostic APIs.
- Do not remove trace/audit history APIs that are trace-owned and already
  separated from runtime truth.
- Do not turn queue metrics into a worker/session projection.
- Do not protect an internal API merely because it exists, has tests, or appears
  in API inventory. Existing shape is evidence to classify, not a preservation
  requirement.

## Do Not Start With

Do not start by adding filtering, pagination, or sanitized response maps to
`/runtime/sessions`.

The first problem is not payload size. The first problem is that the API shape
claims a global session read model whose owner and correctness semantics are not
valid. Delete or retarget callers first, then remove the routes and methods.
Do not replace the deleted route with a paginated transport diagnostic.
Do not start by writing tests that preserve the route as unauthorized, empty, or
stubbed; absence of the route is the target.

## RD-0 Inventory And Classification

Goal: classify every `RuntimeDiagnosticsOperations` method and every server
route that consumes it.

Scope:

- `RuntimeDiagnosticsOperations`
- `DefaultRuntimeDiagnosticsOperations`
- `DefaultTransportDebugOperations`
- `TransportDebugOperations`
- `MassSdkApplication.runtimeDiagnostics()`
- `WorkerInspectionOperations`
- `WorkerQueryOperations`
- `MassSdkApplication#getAllWorkers()`
- `MassSdkApplication#listReachableWorkerIds()`
- `SessionController`
- `QueueController`
- `CatalogController`
- `WorkerApiController`
- `ApiRouteAuthorizationCatalog`
- `ApiAuthInterceptorTest`
- `xa-mass-server/doc/API_SURFACE_INVENTORY.md`
- `xa-mass-server/doc/INTERNAL_API_REFERENCE.md`
- tests that mock or verify `listSessions()` / `getSessionStats()`
- worker/catalog response fields that currently consume
  `listLockedWorkerIds()`: `locked`, `lockedCount`, and
  `reachableUnlockedWorkerCount`
- worker/catalog response fields that currently consume
  `listReachableWorkerIds()`: `reachable`, `reachability`,
  `reachableWorkerIds`, and `reachableUnlockedWorkerCount`
- load/soak/runner callers that read diagnostics

Acceptance:

- Each diagnostics method is classified as `aggregate`, `targeted`, `get-all`,
  `debug side-channel`, or `residue`.
- `listSessions()` and `getSessionStats()` are classified as residue. This
  roadmap does not allow a product-owner exception for keeping the current
  server/SDK session get-all shape.
- Any proposed future session/adapter inventory API is recorded as a separate
  product decision outside this roadmap, not as a reason to preserve
  `/api/v1/runtime/sessions`.
- `listLockedWorkerIds()` is explicitly classified; it is not silently kept
  just because it already exists.
- `listReachableWorkerIds()` is explicitly classified; it is not silently kept
  as a hidden worker-id inventory just because current product controllers use
  it for annotation.
- `locked`, `lockedCount`, and `reachableUnlockedWorkerCount` response fields
  have an owner decision before RD-2 starts:
  - keep via admission/worker-runtime count or caller-scoped batch predicate
  - remove from the API
  - or move to a clearly owner-specific debug surface
- `reachable`, `reachability`, `reachableWorkerIds`, and
  `reachableUnlockedWorkerCount` response fields have an owner decision before
  RD-2 starts:
  - keep via worker-runtime product read model or caller-scoped batch predicate
  - keep as product-owned count/summary fields
  - remove from the API
  - or move to a clearly owner-specific debug surface
- `TransportDebugOperations` inheritance from `RuntimeDiagnosticsOperations`
  is classified as intentional, removed, or replaced by composition.
- Any proposal to keep or add pagination is classified as a separate product
  decision, not a diagnostics workaround. RD-0 must name owner, consumer,
  permission, cost, and proof before any paginated API is considered.
- Server routes are classified by consumer and owner, not only by URL.
- Runtime session route proof must account for split annotations:
  `@RequestMapping("/api/v1/runtime")` plus `@GetMapping("/sessions")` or
  `@GetMapping("/sessions:stats")`.
- `API_SURFACE_INVENTORY.md` generic `/api/v1/runtime/**` windowing language is
  classified as stale unless it names a product owner and bounded read model.
- The first implementation slice is scoped after caller classification.

Verification candidates:

```bash
rg -n "RuntimeDiagnosticsOperations|TransportDebugOperations|listSessions\\(|getSessionStats\\(|listLockedWorkerIds\\(|listReachableWorkerIds\\(|/runtime/sessions|sessions:stats|reachableUnlockedWorkerCount|reachableWorkerIds|runtime/\\*\\*" sdk xa-mass-server xa-mass-testing integrations -g "*.java" -g "*.md" --glob "!**/target/**"
```

## RD-1 Remove Generic Session Diagnostics

Goal: delete the generic session diagnostics methods and server endpoints.

Scope:

- remove `listSessions()` from `RuntimeDiagnosticsOperations`
- remove `getSessionStats()` from `RuntimeDiagnosticsOperations`
- remove empty/zero implementations from `DefaultRuntimeDiagnosticsOperations`
- delete or retire `SessionController`
- remove session routes from `ApiRouteAuthorizationCatalog`
- update `ApiAuthInterceptorTest`, `SessionControllerTest`, `MassSdkTest`, and
  server docs
- update API surface inventory and internal API reference
- update or remove generic API inventory wording that says windowed
  `/api/v1/runtime/**` list/detail snapshots are acceptable as runtime
  diagnostics
- add or update a focused server/SDK guard that fails when a controller combines
  `@RequestMapping("/api/v1/runtime")` with `@GetMapping("/sessions")` or
  `@GetMapping("/sessions:stats")`; allowlist unrelated domains such as
  `ApiKeyViewerSessionController`

Acceptance:

- `RuntimeDiagnosticsOperations` no longer declares `listSessions()` or
  `getSessionStats()`.
- No default implementation returns empty session lists or zero session stats to
  preserve old behavior.
- `/api/v1/runtime/sessions` and `/api/v1/runtime/sessions:stats` are no longer
  active server routes.
- Server route authorization does not special-case `/api/v1/runtime/sessions`.
- Auth tests and test controllers do not preserve `/api/v1/runtime/sessions` as
  a route stub.
- Server/SDK guard detects the split annotation form of the runtime session
  route, not only the full `/api/v1/runtime/sessions` string.
- Guard and scans do not fail on legitimate API-key viewer session routes or
  controller names.
- API docs and API surface inventory do not list these routes as current
  implemented APIs.
- API surface inventory no longer uses response windowing as the generic
  justification for runtime list/detail diagnostics.
- No in-repo caller relies on session get-all diagnostics.

Verification candidates:

```bash
rg -n "listSessions\\(|getSessionStats\\(|/runtime/sessions|sessions:stats|activeConnectionsByAdapter|session diagnostics" sdk xa-mass-server xa-mass-testing integrations -g "*.java" -g "*.md" --glob "!**/target/**"
rg -n "RequestMapping.*api/v1/runtime|GetMapping.*sessions" xa-mass-server/src/main/java/com/xa/mass/api/internal xa-mass-server/src/test/java -g "*.java" --glob "!**/target/**"
./mvnw -q -pl sdk/xa-mass-embedded-sdk,xa-mass-server -am -DskipTests compile
./mvnw -q -pl sdk/xa-mass-embedded-sdk,xa-mass-server -am test "-Dtest=MassSdkTest,SessionControllerTest,QueueControllerTest,CatalogControllerTest,WorkerApiControllerTest,ApiAuthInterceptorTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

`SessionControllerTest` should be deleted or renamed only if another targeted
runtime-diagnostics controller remains.
`SessionControllerTest` must not remain only to prove a removed route returns a
stub.

## RD-2 Tighten Remaining Runtime Diagnostics

Goal: ensure remaining diagnostics are targeted or aggregate, not hidden get-all
views.

Scope:

- `getQueueDetail()`
- `getQueueMetrics()`
- `isWorkerLocked(workerId)`
- `listLockedWorkerIds()`
- `listReachableWorkerIds()`
- worker/catalog fields derived from reachable and locked all-id sets
- `TransportDebugOperations`
- `sdk/README.md`
- `sdk/xa-mass-embedded-sdk/README.md`
- `integrations/README.md`
- load/soak runners that use diagnostics
- docs and tests around runtime diagnostics

Acceptance:

- Queue diagnostics remain aggregate and do not expose worker/session endpoint
  inventories.
- Adapter-level queue counters, such as `queueByAdapter.<adapter>.queuedItems`,
  are allowed when they contain only counts, rates, ages, or capacity values.
- Queue diagnostics must not contain worker ids, session ids, endpoint ids,
  route keys, connection ids, or per-worker/per-session row lists.
- `isWorkerLocked(workerId)` remains targeted by explicit worker id.
- `listLockedWorkerIds()` is removed from generic runtime diagnostics unless
  RD-0 approves an owner-specific debug surface. Product responses that need
  lock size use a count. Product responses that need row annotation use a
  caller-provided bounded subject set, not an all-id metadata list.
- `listReachableWorkerIds()` is either reclassified as a worker-runtime
  product read-model method with explicit consumer/proof, or replaced by
  caller-scoped reachability predicates/counts. It must not remain a generic
  diagnostics helper.
- `locked`, `lockedCount`, and `reachableUnlockedWorkerCount` are either
  removed from server responses or backed by the RD-0-approved owner path.
- `reachable`, `reachability`, `reachableWorkerIds`, and
  `reachableUnlockedWorkerCount` are either removed from server responses or
  backed by the RD-0-approved owner path.
- `TransportDebugOperations` no longer inherits broad runtime diagnostics unless
  RD-0 explicitly approves that as the operator debug owner path.
- If `WorkerInspectionOperations` or
  `MassSdkApplication#listReachableWorkerIds()` behavior or signature changes,
  update `sdk/README.md`, `sdk/xa-mass-embedded-sdk/README.md`, and
  `integrations/README.md` in the same slice.
- No transport runtime or adapter API introduces paginated session, endpoint,
  connection, route, mailbox, route-owner, or worker-session diagnostics.
- Load/soak runners do not require a global session list to prove runtime
  behavior.
- No diagnostics method returns a raw `List<Map<String,Object>>` of runtime
  entities without a subject.
- No diagnostics method returns `List<String>` ids for a whole runtime entity
  class unless RD-0 explicitly classifies it as an owner-specific product read
  model or debug surface.

Verification candidates:

```bash
rg -n "RuntimeDiagnosticsOperations|TransportDebugOperations|listLockedWorkerIds\\(|listReachableWorkerIds\\(|reachableWorkerIds|reachableUnlockedWorkerCount|List<Map<String, Object>>|list.*Session|get.*Stats" sdk/xa-mass-embedded-sdk/src/main/java xa-mass-server/src/main/java xa-mass-testing/src/main/java -g "*.java"
./mvnw -q -pl sdk/xa-mass-embedded-sdk,xa-mass-server,xa-mass-testing -am -DskipTests compile
./mvnw -q -pl sdk/xa-mass-embedded-sdk,xa-mass-server -am test "-Dtest=MassSdkTest,QueueControllerTest,CatalogControllerTest,WorkerApiControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Treat this scan as classification input, not as a raw pass/fail guard. It will
find legitimate product read models such as `getAllWorkers()`; those must be
allowlisted only after RD-0 assigns an owner.

Add a real focused runner test before naming one in this command. Do not hide
missing runner proof with `-Dsurefire.failIfNoSpecifiedTests=false`.

## RD-3 Guards And Documentation

Goal: prevent global session diagnostics from being reintroduced.

Scope:

- `TransportConvergenceArchitectureGuardTest`
- SDK/server architecture guards if present, or new focused server/SDK guards
- `doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md`
- `xa-mass-server/doc/API_SURFACE_INVENTORY.md`
- `xa-mass-server/doc/INTERNAL_API_REFERENCE.md`
- `doc/PROOF_REGISTRY.md` if proof wording mentions session diagnostics

Acceptance:

- Guard fails if SDK/server main sources reintroduce:
  - `listSessions(`
  - `getSessionStats(`
  - `/api/v1/runtime/sessions`
  - `@RequestMapping("/api/v1/runtime")` combined with
    `@GetMapping("/sessions")` or `@GetMapping("/sessions:stats")`
  - generic `listLockedWorkerIds(` on `RuntimeDiagnosticsOperations`, unless
    RD-0 explicitly approves a narrower owner-specific replacement
  - `WorkerEndpointInspector`
  - `WorkerEndpointSnapshot`
  - `CompositeWorkerEndpointInspector`
- Guard or scan allowlists unrelated session domains such as
  `ApiKeyViewerSessionController`.
- Server/SDK guard, not transport guard, owns the route and
  `RuntimeDiagnosticsOperations` method denylist.
- Transport guard owns only transport endpoint/session inspector residue and
  adapter-local diagnostic leakage.
- Guard allows adapter-local session stores needed for final-hop delivery.
- Guard allows adapter aggregate queue counters and size diagnostics, but
  rejects worker/session/endpoint metadata inventories.
- Guard rejects transport-owned paginated diagnostic routes or APIs for
  sessions, endpoints, connections, routes, mailboxes, route owners, or worker
  session metadata.
- Transport/adapters guard or scan rejects new diagnostic APIs containing
  `pageToken`, `offset`, `list.*Endpoint`, `list.*Session`, `list.*Connection`,
  `route-owner`, or `route owner` when they target transport session,
  endpoint, connection, route, mailbox, or route-owner metadata.
- Docs state that worker reachability and lifecycle evidence come from worker
  runtime, not runtime session diagnostics.
- Docs state that transport session state is adapter-local evidence unless an
  explicit future owner-specific debug API is approved.
- Docs distinguish product-owned worker/catalog read models from generic
  runtime diagnostics helpers.

Verification candidates:

```bash
./mvnw -q -pl transport/transport_runtime -am test "-Dtest=TransportConvergenceArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false"
./mvnw -q -pl sdk/xa-mass-embedded-sdk,xa-mass-server -am test "-Dtest=MassSdkTest,QueueControllerTest,CatalogControllerTest,WorkerApiControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false"
rg -n "listSessions\\(|getSessionStats\\(|/api/v1/runtime/sessions|WorkerEndpointInspector|WorkerEndpointSnapshot|CompositeWorkerEndpointInspector" sdk xa-mass-server transport -g "*.java" -g "*.md" --glob "!**/target/**"
rg -n "RequestMapping.*api/v1/runtime|GetMapping.*sessions" xa-mass-server/src/main/java/com/xa/mass/api/internal xa-mass-server/src/test/java -g "*.java" --glob "!**/target/**"
rg -n "pageToken|offset|list.*Endpoint|list.*Session|list.*Connection|route-owner|route owner" transport -g "*.java" -g "*.md" --glob "!**/target/**"
```

If a new SDK/server architecture guard is added for this roadmap, add its exact
test class to the second command and do not rely on
`failIfNoSpecifiedTests=false` as completion proof for that new guard.

## Completion Evidence

Implementation facts from 2026-06-23:

- `RuntimeDiagnosticsOperations` no longer declares `listSessions()`,
  `getSessionStats()`, or `listLockedWorkerIds()`.
- `DefaultRuntimeDiagnosticsOperations` no longer preserves empty session lists,
  zero session stats, or global locked-worker-id inventory behavior.
- `WorkerInspectionOperations` and `MassSdkApplication` no longer expose
  `listReachableWorkerIds()`.
- `SessionController` and `SessionControllerTest` were deleted; server route
  authorization no longer special-cases `/api/v1/runtime/sessions`.
- `CatalogController` and `WorkerApiController` annotate selected worker rows
  through targeted `isWorkerReachable(workerId)` and
  `isWorkerLocked(workerId)` predicates rather than hidden all-id inventories.
- `xa-mass-server/doc/API_SURFACE_INVENTORY.md` and
  `xa-mass-server/doc/INTERNAL_API_REFERENCE.md` describe runtime session
  diagnostics as removed, not implemented.
- `doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md`, `sdk/README.md`,
  `sdk/xa-mass-embedded-sdk/README.md`, and `integrations/README.md` now state
  that runtime diagnostics are aggregate/targeted/owner-local and must not be
  used as generic session, endpoint, reachable-worker-id, or locked-worker-id
  inventories.
- `ServerMainSourceArchitectureGuardTest` rejects reintroduced SDK/server
  session diagnostics, route catalog entries, split runtime session routes,
  global locked-worker-id diagnostics, and global reachable-worker-id helper
  methods.
- `TransportConvergenceArchitectureGuardTest` remains the transport owner guard
  for endpoint/session inspector residue.

Verification run:

```powershell
.\mvnw -q -pl sdk/xa-mass-embedded-sdk,xa-mass-server,xa-mass-testing -am -DskipTests compile
.\mvnw -q -pl sdk/xa-mass-embedded-sdk,xa-mass-server,xa-mass-testing -am -DskipTests test-compile
.\mvnw -q -pl sdk/xa-mass-embedded-sdk,xa-mass-server -am test "-Dtest=MassSdkTest,QueueControllerTest,CatalogControllerTest,WorkerApiControllerTest,ApiAuthInterceptorTest,ServerMainSourceArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false"
.\mvnw -q -pl transport/transport_runtime -am test "-Dtest=TransportConvergenceArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Residue scan result:

- old SDK/server method names only remain in the new negative architecture guard
  and missing-method assertions
- removed runtime session routes only remain in removed-route documentation and
  negative guard assertions
- endpoint inspector names only remain in transport architecture guard
  denylist/proof code

## Roadmap Completion Criteria

This roadmap can be marked complete only when:

- `RuntimeDiagnosticsOperations` has no global session listing or stats API.
- Server no longer exposes `/api/v1/runtime/sessions` or
  `/api/v1/runtime/sessions:stats`.
- Server/SDK guards catch both literal route strings and split annotation
  runtime session route reintroductions.
- No empty/zero compatibility implementation preserves the removed API shape.
- Remaining diagnostics are classified as aggregate, targeted, caller-scoped
  batch predicates, size/count APIs, or owner-local debug surfaces.
- `listLockedWorkerIds()` has been removed from generic runtime diagnostics or
  replaced by a narrower owner-specific path with owner, bounds, consumer, and
  proof.
- `listReachableWorkerIds()` and its server response consumers have an explicit
  owner decision; if retained, they are product/worker-runtime read models, not
  generic diagnostics helpers.
- `TransportDebugOperations` no longer inherits broad runtime diagnostics by
  accident; its retained methods are classified as operator debug side-channel
  or removed.
- Size/count diagnostics remain allowed; get-all metadata inventories are not
  reintroduced under a different name.
- No transport-owned paginated diagnostic surface exists for session,
  endpoint, connection, route, mailbox, route-owner, or worker-session
  metadata.
- Current docs and API inventory no longer describe session get-all diagnostics
  as implemented current API.
- API surface docs no longer use generic windowing/pagination language as the
  justification for runtime list/detail diagnostics.
- Guards prevent reintroducing endpoint inspector and global session diagnostics.
- Focused compile/tests pass.

## Suggested Implementation Order

1. Execute RD-0 inventory first. Do not delete APIs before confirming server,
   SDK, tests, and runners that still reference them.
2. Execute RD-1 to remove session get-all methods and server routes.
3. Execute RD-2 to classify and tighten the remaining diagnostics surface.
4. Execute RD-3 after the target shape is stable enough to guard.

## Resolved Decisions

- `listLockedWorkerIds()` was removed from generic runtime diagnostics in the
  same convergence slice. Worker/catalog responses that need lock annotations
  use targeted `isWorkerLocked(workerId)` over selected rows.
- `listReachableWorkerIds()` was removed from the SDK worker inspection surface.
  Worker/catalog responses that need reachability annotations use targeted
  `isWorkerReachable(workerId)` over selected rows.
- `TransportDebugOperations` can remain a subtype of
  `RuntimeDiagnosticsOperations` only because the inherited surface no longer
  contains generic get-all session, reachable-worker-id, or locked-worker-id
  inventories.
- The server does not keep a runtime session diagnostics controller. Queue
  diagnostics remain under existing queue controller ownership.
- Future adapter-local diagnostics require a separate owner-specific product or
  operator decision with consumer, permission, cost, and proof. They must not
  reappear as generic SDK/server runtime session diagnostics.
