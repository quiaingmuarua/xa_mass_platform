# Integrations Worker Pack SDK Convergence Roadmap

Status: completed convergence record.

Successor roadmap:
`doc/INTEGRATIONS_EXTERNAL_SDK_WORKER_PACK_HARDENING_ROADMAP.md`.

This roadmap follows the Java SDK adoption work. The main question is no
longer whether Java can call the platform from outside the server. That proof
now lives in `integrations/xa-mass-java-sdk` and
`integrations/xa-mass-scenario-launcher`.

The next question is what `integrations/xa-mass-worker-pack` should still own.
Worker-pack should become a real integration capability pack and fault harness,
not a place to preserve raw Java transport demos that the public Java SDK does
not support.

This document records the completed worker-pack convergence decision. Follow-up
hardening of the external SDK public contract, worker session lifecycle,
WorkerGroup topology proof, and worker-pack capability productization belongs to
the successor roadmap above.

Execution stance:

- This project is not yet publicly launched. There is no need to keep compact
  compatibility paths, wrapper aliases, fallback raw clients, or parallel demo
  stories for superseded integration code.
- When a replacement proof exists, remove the old in-repo path and update
  callers/docs directly.
- `integrations/` should stop reading as a sample gallery. Its durable assets
  are the external Java SDK, strategic SDK consumers, real worker capability
  packs, and protocol/test fixtures.

## Current Code Observations

- `integrations/xa-mass-worker-pack` depends on `xa-mass-sdk` for embedded
  dev-shell support and on `xa-mass-java-sdk` only for the SDK-backed
  `tool.geo.lookup` capability path.
- The retired worker-pack Java socket demo path was
  `SocketClientStarter` plus `SampleWorkerSocketClient`. It existed to run an
  embedded Java socket sample client, not to provide a public Java SDK worker
  path.
- `integrations/xa-mass-java-sdk` owns the external Java entry point:
  `MassPlatform`, typed task and worker clients, `workerSessions().polling()`,
  and `workerSessions().webSocket()`.
- `integrations/xa-mass-scenario-launcher` is the strategic Java SDK consumer.
  It proves task creation, item append, worker topology registration, polling
  dispatch, WebSocket dispatch, and result reporting through the Java SDK.
- Java socket is not a public SDK session. Socket adapter coverage remains a
  Node sample / adapter fixture concern unless a future roadmap explicitly
  promotes socket to the Java SDK.
- Worker-pack currently owns sample command and fault behavior. That behavior
  is useful for fault-matrix and dev harness work, but it must not become the
  public Java SDK behavior by accident.
- `integrations/samples` is now fixture territory. Node polling/WebSocket/socket
  samples can remain as cross-language and adapter fixtures, but they should
  not become the recommended Java or business integration story.

## Direction

`xa-mass-java-sdk` is the standard Java entry point for external platform
actors, not a worker-loop-only SDK. External actors include task producers,
worker topology declarers, worker runtime processes, and operator/test harnesses
that need the public remote contract. It is allowed to create tasks because
external producers and worker launchers both need a public client surface. It
should remain a pure remote client and must not depend on worker-pack, server,
engine, transport implementations, or `xa-mass-base`.

The worker-facing part of the SDK has three separate responsibilities that must
not collapse into one "worker session" concept:

- WorkerGroup capability declaration: event bindings, default attributes, and
  default concurrency. This is scheduling candidate-source truth, not transport
  or process identity.
- Worker topology registration: AdapterNode endpoint identity,
  NodeGroupBinding placement relation, and Worker execution identity. This is
  the external registration path for platform topology.
- Worker session runtime: polling or realtime transport lifecycle, dispatch
  receipt, handler invocation, result submit, and presence/reconnect semantics.
  This is transport/session behavior, not capability truth.

`xa-mass-scenario-launcher` is the first repository consumer proving the SDK
story end to end. That is the right shape: an external Java process uses the
SDK to register topology, start worker sessions, submit tasks, and report
results.

The task-producing part of the SDK must remain first-class. A worker-pack
capability proof is only meaningful when an external actor can also create task
shells, append items, set the worker-group selector through the public task
contract, and observe result convergence without server startup seeding.

`xa-mass-worker-pack` should only keep code that has one of these jobs:

- curated real worker group definitions or worker capabilities;
- sample/dev fault harness behavior that is not part of the public SDK;
- local dev-shell orchestration that proves platform behavior through public
  APIs or SDK sessions;
- temporary adapter-specific test harness code with an explicit owner and
  retirement trigger.

It should not keep a raw Java socket client just because the Java SDK does not
support socket. Lack of socket support in the Java SDK is a reason to retire
Java socket worker-pack code, not a reason to preserve a duplicate sample path.

The priority order is therefore:

1. retire worker-pack Java socket demo code and remove its transport dependency;
2. classify WebSocket raw-client residue as fault harness or migrate/remove it;
3. name the first real worker-pack capability and prove it through public API
   or SDK registration.

## Hard Rules

- Do not add socket support to `xa-mass-java-sdk` just to keep worker-pack
  socket code alive.
- Do not narrow `xa-mass-java-sdk` into a worker-session-only SDK. Task
  producer APIs, worker topology declaration APIs, and worker session APIs are
  all part of the external Java contract, even if their implementation lives
  under separate client facets.
- Do not treat WorkerGroup as transport-specific. WebSocket, polling, and
  future transports bind to Worker instances and AdapterNodes; WorkerGroup
  remains capability and candidate-source truth.
- Do not keep `xa-mass-transport-socket` as a worker-pack dependency unless a
  current worker-pack-owned proof requires a Java socket harness that cannot be
  covered by Node socket fixtures or transport adapter tests.
- Do not move worker-pack sample command/fault behavior into the Java SDK.
- Do not make worker-pack a dependency of the Java SDK.
- Do not give built-in worker groups privileged server startup. Built-in means
  repository-provided integration package; runtime registration still goes
  through public API or SDK sessions.
- Do not preserve old raw-client paths through wrappers or aliases once a
  replacement proof exists.
- Do not keep a demonstration-only path for readability if it does not provide
  a unique current proof or real worker capability.

## Non-Goals

- No public Java socket SDK in this roadmap.
- No Android/device worker host work in this roadmap.
- No Maven Central or external registry publication work.
- No redesign of engine scheduling, transport server adapters, lease, result
  convergence, or task terminal policy.
- No attempt to remove Node socket adapter fixtures from server E2E proof.
- No expansion of `integrations/samples` into a public product/example surface.

## Target Shape

```text
external Java task / worker process
  -> integrations/xa-mass-java-sdk
      -> MassPlatform
      -> tasks()
      -> workers() / worker topology declarations
      -> workerSessions().polling()
      -> workerSessions().webSocket()

integrations/xa-mass-scenario-launcher
  -> strategic SDK proof consumer
  -> task producer plus external polling/WebSocket worker sessions

integrations/xa-mass-worker-pack
  -> real WorkerGroup capability pack and sample fault harness
  -> may consume xa-mass-java-sdk for public client/session behavior
  -> does not preserve raw Java socket demos
  -> keeps sample command/fault behavior local

socket adapter proof
  -> Node sample / adapter fixture / transport tests
  -> not Java SDK, not worker-pack raw Java demo
```

## Roadmap

### WPC-0: Worker-Pack Surface Inventory

Status: implemented.

Priority: immediate.

Decision record:

- `doc/INTEGRATIONS_WORKER_PACK_SDK_CONVERGENCE_INVENTORY.md` records the
  worker-pack surface and dependency classification.
- Worker-pack Java socket code has no unique current proof value that must
  remain in worker-pack.
- Socket proof after worker-pack Java socket retirement remains covered by
  Node socket black-box/parity proof, server trace-observed external worker
  proof, socket adapter tests, and `SdkTransportLoadRunner`.

Pre-finding:

- Node socket proof already exists through server black-box / parity coverage,
  and socket adapter behavior also has transport/runtime test coverage. WPC-0
  should not rediscover whether socket coverage exists. It should confirm
  whether worker-pack's Java socket client owns any hidden Java-only invariant
  that those existing proofs do not cover.

Scope:

- Inventory worker-pack production and test code by owner:
  - embedded dev-shell runtime composition;
  - worker topology discovery;
  - WebSocket sample client;
  - socket sample client;
  - sample command runtime;
  - fault profile state;
  - starter/orchestration code;
  - docs and runbook references.
- List every worker-pack dependency and why it exists.
- Specifically classify `xa-mass-transport-socket`:
  - direct production imports;
  - tests depending on socket sample code;
  - docs that present Java socket as a worker-pack capability;
  - proof surfaces that would be lost if Java socket worker-pack code is
    removed.
- Identify whether Node socket black-box tests and transport adapter tests
  already cover the remaining socket proof requirement.

Out of scope:

- Code removal.
- SDK dependency changes.
- Worker fault matrix changes beyond classification.

Acceptance:

- The inventory states whether worker-pack Java socket code has unique current
  proof value.
- The inventory explicitly says whether the current evidence supports
  proceeding to WPC-1, or names the exact Java-only socket invariant that blocks
  removal.
- Every worker-pack dependency has a current owner reason or a removal target.
- Socket coverage that remains after Java socket removal is named explicitly.

### WPC-1: Retire Worker-Pack Java Socket Demo Path

Status: implemented.

Decision record:

- Removed the worker-pack Java socket demo client, starter, socket-only tests,
  socket-only config fields, and worker-pack `xa-mass-transport-socket`
  dependency.
- Updated server/module/runbook/testing docs so worker-pack no longer presents
  Java socket as an embedded sample client.
- Verification passed:
  `mvn -pl integrations/xa-mass-worker-pack -am test` and
  `mvn -pl xa-mass-server -am "-Dtest=NodeSocketWorkerBlackBoxIntegrationTest,ExternalWorkerPublicContractTraceObservedIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`.

Priority: first implementation slice after WPC-0. Existing evidence already
leans toward removal because Java socket is not an SDK path and Node/transport
socket proof exists.

Prerequisite:

- WPC-0 finds no unique worker-pack-owned Java socket proof that must remain.

Scope:

- Remove `SampleWorkerSocketClient`, `SocketClientStarter`, and tests that only
  prove the Java socket demo path.
- Remove worker-pack socket config fields that exist only for the retired Java
  socket client.
- Remove the `xa-mass-transport-socket` dependency from worker-pack if no other
  worker-pack production code needs it.
- Update `xa-mass-server` README, verified runbook, testing docs, and fault
  roadmap references so worker-pack no longer presents Java socket as an
  embedded sample client.
- Keep Node socket sample E2E and socket transport adapter tests as the socket
  proof surface.

Out of scope:

- Removing the socket adapter module.
- Removing Node socket sample tests.
- Adding Java SDK socket support.

Acceptance:

- `integrations/xa-mass-worker-pack/pom.xml` no longer depends on
  `xa-mass-transport-socket`.
- `rg "\bSampleWorkerSocketClient\b|\bSocketClientStarter\b"` has no production
  references.
- Worker-pack tests pass without socket sample tests:
  `mvn -pl integrations/xa-mass-worker-pack -am test`.
- Socket adapter proof remains covered by Node sample or transport tests.

### WPC-2: Split Worker-Pack WebSocket Runtime From Public SDK Proof

Status: implemented as classification and documentation.

Decision record:

- `doc/INTEGRATIONS_WORKER_PACK_SDK_CONVERGENCE_INVENTORY.md` classifies the
  raw WebSocket code by happy-path dispatch/result behavior, command frame
  handling, fault result mutation, disconnect behavior, and reconnect/session
  support.
- The raw WebSocket client remains only as worker-pack command/fault harness
  substrate. It is not the public Java SDK worker proof and is not documented
  as recommended Java SDK usage.
- Non-fault Java happy-path proof remains Java SDK plus scenario-launcher.

Priority: second implementation slice. Do this after socket retirement so the
WebSocket review is about real fault/command harness value, not broad realtime
demo preservation.

Scope:

- Decide whether worker-pack's raw WebSocket sample client still provides
  unique fault/command harness behavior after Java SDK WebSocket session proof
  exists in scenario-launcher.
- Classify the raw WebSocket client by method/behavior:
  - normal dispatch detection;
  - normal result construction/submission;
  - command frame handling;
  - fault delay/drop/duplicate/late/malformed/identity/disconnect behavior;
  - reconnect/session lifecycle.
- If the raw WebSocket client only duplicates happy-path SDK behavior, replace
  it with `xa-mass-java-sdk` `WebSocketWorkerSession` or remove it.
- If it owns sample fault/command behavior that the SDK must not own, keep it
  explicitly under worker-pack fault harness ownership and document the
  retirement trigger.
- If normal result construction/submission remains in worker-pack, document
  why it is still required for the fault harness rather than a duplicate
  public SDK worker-session path.
- Ensure generic dispatch handling does not fork into another public handler
  runtime. Public worker handler concepts belong to the Java SDK.

Out of scope:

- Moving sample command/fault routes into the SDK.
- Making worker-pack WebSocket client a public integration recommendation.
- Building a generic `RealtimeWorkerSession` abstraction over one transport.

Acceptance:

- Worker-pack README states whether raw WebSocket code is fault-harness only or
  replaced by SDK session code.
- A code-level inventory distinguishes WebSocket happy-path behavior from
  fault/command-specific behavior.
- Any normal dispatch/result code that remains in worker-pack is justified as
  necessary harness substrate for worker-pack fault/command behavior.
- Non-fault happy-path Java worker proof remains scenario-launcher plus Java
  SDK.
- Any remaining raw WebSocket client has an explicit owner reason and is not
  documented as public Java SDK usage.

### WPC-3: SDK-Backed Built-In Worker Group Path

Status: implemented.

Decision record:

- Added the first real worker-pack capability: `tool.geo.lookup`, owned by
  `GeoLookupTool` and published through worker group `worker-pack.tools.geo`.
- Added `GeoLookupWorkerPack`, which declares the worker group, adapter node,
  node binding, worker identity, online state, polling loop, and result
  reporting through `xa-mass-java-sdk` `MassPlatform` and
  `PollingWorkerSession`.
- Kept handler logic transport-independent through the Java SDK
  `WorkerEventHandler` runtime; the handler can be reused by later realtime
  sessions without changing the business handler.
- Updated `ToolCommandRoutes` so the dev command route delegates
  `tool.geo.lookup` to the worker-pack tool implementation instead of owning a
  parallel result shape.
- Added `WorkerPackGeoLookupExternalSdkIntegrationTest`, proving an empty
  server fixture can accept a worker-pack external worker registration and task
  submission through the Java SDK/public HTTP path. The test registers catalog
  metadata as setup, but worker topology, session presence, task creation,
  item append, result submit, and result read use external SDK clients.

Priority: start the product decision in parallel with WPC-0/WPC-1. Do not wait
for all cleanup to finish before choosing the first real capability.

Prerequisite:

- A concrete first worker-pack capability is named. The selected first
  capability is `tool.geo.lookup`, with `worker-pack.tools.geo` as the worker
  group. It has a small deterministic contract and more business value than
  `tool.time.now`; it is still clearly labeled simulated until a real provider
  exists.

Scope:

- Define the first real worker-pack worker group that is not just a demo
  transport client.
- Record the worker group's event code, input contract, output contract,
  worker attributes, and why it belongs in worker-pack instead of
  scenario-launcher or a one-off sample.
- Register its WorkerGroup, AdapterNode, worker identity, presence/session, and
  result reporting through public APIs or `xa-mass-java-sdk`.
- Keep the registration order explicit in code and docs:
  WorkerGroup capability declaration, AdapterNode registration,
  NodeGroupBinding registration, Worker execution identity registration, then
  concrete session startup.
- Ensure any WebSocket/polling wording describes transport-backed Worker
  instances, not transport-specific WorkerGroups.
- Add `xa-mass-java-sdk` as a worker-pack dependency only if this removes real
  public client/session boilerplate.
- Keep server startup clean: no server-owned task, worker, WorkerGroup,
  adapter-node, or binding seeding.

Out of scope:

- Server privileged worker registration.
- Embedded runtime shortcuts as proof of external worker registration.
- Public catalog/admin SDK design unless it blocks worker-pack external
  registration.

Acceptance:

- The selected worker group is named and has a minimal public event contract.
- The selected worker group has an explicit business value statement, not only
  a transport or SDK demonstration purpose.
- The built-in worker group can start as an external integration package and
  register through public routes or SDK sessions.
- The implemented proof demonstrates first external actor behavior as well as
  worker session behavior: task shell creation, item append, dispatch, result
  submit, and result readback go through the public contract.
- It does not require `xa-mass-server` production code to call the SDK.
- Worker-pack's dependency on `xa-mass-java-sdk`, if added, is justified by a
  concrete public client/session path.

Follow-up:

- Stronger external actor proof, including explicit WorkerGroup selector
  assertions, topology registration order, and long-running session semantics,
  belongs to
  `doc/INTEGRATIONS_EXTERNAL_SDK_WORKER_PACK_HARDENING_ROADMAP.md`.

### WPC-4: Command And Fault Runtime Boundary Cleanup

Status: implemented as classification and owner cleanup.

Decision record:

- `doc/INTEGRATIONS_WORKER_PACK_SDK_CONVERGENCE_INVENTORY.md` separates
  `command.core/model/runtime`, `command.event`, and `base.exception` usage.
- Worker-pack command/fault behavior remains the owner for sample command
  routes, `fault.*` state, and deterministic tool routes.
- `xa-mass-java-sdk` still has no production dependency on `xa-mass-base`.

Scope:

- Keep sample command/fault routes in worker-pack.
- Re-evaluate `xa-mass-base/src/main/java/com/xa/mass/command` usage after
  socket retirement and WebSocket split.
- Classify command-related dependencies separately:
  - `com.xa.mass.command.core`, `com.xa.mass.command.model`, and
    `com.xa.mass.command.runtime` are command runtime/model dependencies and
    must have a worker-pack owner or removal target.
  - `com.xa.mass.command.event` is a separate embedded event-runtime concern
    and must not be moved or removed as part of worker-pack cleanup unless its
    active callers are independently retired.
  - `com.xa.mass.base.exception.CommandException` and `ErrorCode` are base
    exception/value types; classify them separately from command runtime
    residue.
- Remove, narrow, or document remaining base command residue based on active
  callers.
- Align worker-pack command/fault docs with `WORKER_FAULT_MATRIX_ROADMAP.md`.

Out of scope:

- Moving `command.event` if it is still owned by embedded runtime/event paths.
- Replacing sample command/fault behavior with public SDK APIs.
- Large rename-only cleanup without owner change.

Acceptance:

- Command/fault behavior has one clear owner.
- The roadmap or inventory records which `command.core/model/runtime` imports
  remain, move, or disappear.
- `command.event` and `base.exception` usage are not accidentally treated as
  the same cleanup target.
- `xa-mass-java-sdk` has no production dependency on `xa-mass-base`.
- Worker-pack command/fault docs do not imply platform kernel ownership.

### WPC-5: Proof Registry And Runbook Cleanup

Status: implemented.

Decision record:

- Updated worker-pack README, verified runbook, testing index, proof registry,
  and server README so the current proof split is explicit:
  - Java public worker proof is SDK/scenario-launcher first;
  - worker-pack proof is real capability plus fault harness;
  - socket proof is Node/transport, not worker-pack Java socket.

Prerequisite:

- WPC-1 through WPC-4 are complete, or each unfinished slice has an explicit
  decision record explaining why its proof/doc cleanup can safely proceed.

Scope:

- Update `PROOF_REGISTRY.md`, `TESTING_INDEX.md`, `VERIFIED_RUNBOOK.md`,
  `WORKER_FAULT_MATRIX_ROADMAP.md`, and module READMEs after each code slice.
- Ensure Java public worker proof points to scenario-launcher and Java SDK.
- Ensure socket proof points to Node sample or transport adapter tests.
- Ensure worker-pack proof points to real capability/fault harness behavior,
  not raw transport demos.

Acceptance:

- No active doc recommends worker-pack Java socket as a public or strategic
  Java worker path.
- No active doc treats worker-pack as the Java SDK proof owner.
- No active doc presents `integrations/samples` as the Java product entry or a
  parallel SDK example surface.
- Proof registry distinguishes Java SDK proof, worker-pack fault/capability
  proof, and socket adapter proof.

## Stop Conditions

Stop and re-discuss before implementation if WPC-0 finds:

- worker-pack Java socket code is the only current proof for a production
  invariant that Node socket and transport tests do not cover;
- server dev-shell startup depends on worker-pack socket clients in a way that
  cannot be removed without changing current developer workflows;
- a real product requirement emerges for Java socket workers, which should
  become a separate public Java socket SDK roadmap rather than a worker-pack
  preservation argument;
- worker-pack's first real built-in worker group needs public catalog/admin
  APIs that the Java SDK does not yet expose.

## Verification Matrix

After WPC-0:

```powershell
rg -n "xa-mass-transport-socket|\\bSampleWorkerSocketClient\\b|\\bSocketClientStarter\\b|sample\\.client\\.socket" integrations/xa-mass-worker-pack xa-mass-server
mvn -pl integrations/xa-mass-worker-pack -am test
```

After WPC-1:

```powershell
mvn -pl integrations/xa-mass-worker-pack -am test
mvn -pl xa-mass-server -am "-Dtest=NodeSocketWorkerBlackBoxIntegrationTest,ExternalWorkerPublicContractTraceObservedIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
rg -n "\\bSampleWorkerSocketClient\\b|\\bSocketClientStarter\\b|xa-mass-transport-socket" integrations/xa-mass-worker-pack
```

After WPC-2/WPC-3:

```powershell
mvn -pl integrations/xa-mass-worker-pack -am test
mvn -pl xa-mass-server -am "-Dtest=WorkerPackGeoLookupExternalSdkIntegrationTest,JavaExternalSdkPollingSessionIntegrationTest,NodeSocketWorkerBlackBoxIntegrationTest,ExternalWorkerPublicContractTraceObservedIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
rg -n "\\bSampleWorkerSocketClient\\b|\\bSocketClientStarter\\b|xa-mass-transport-socket" integrations/xa-mass-worker-pack
```

## Summary

The intended Java story is:

```text
external Java caller -> xa-mass-java-sdk -> server public APIs
```

Scenario-launcher proves that story today for task creation and worker
execution. Worker-pack should either provide real worker capability or fault
harness value on top of that story, or it should get smaller. A Java socket
demo that exists only because the Java SDK does not support socket should be
removed, not preserved.

The intended `integrations/` story is:

```text
xa-mass-java-sdk            -> public Java external API
xa-mass-scenario-launcher   -> strategic SDK adopter and executable proof
xa-mass-worker-pack         -> real capability pack plus fault harness
integrations/samples        -> protocol and cross-language fixtures only
```
