# Integrations Worker Pack SDK Convergence Roadmap

Status: active roadmap draft.

This roadmap follows the Java SDK adoption work. The main question is no
longer whether Java can call the platform from outside the server. That proof
now lives in `integrations/xa-mass-java-sdk` and
`integrations/xa-mass-scenario-launcher`.

The next question is what `integrations/xa-mass-worker-pack` should still own.
Worker-pack should become a real integration capability pack and fault harness,
not a place to preserve raw Java transport demos that the public Java SDK does
not support.

## Current Code Observations

- `integrations/xa-mass-worker-pack` depends on `xa-mass-sdk` and
  `xa-mass-transport-socket`; it does not depend on `xa-mass-java-sdk`.
- The `xa-mass-transport-socket` dependency is used by worker-pack socket
  sample code, especially `SocketClientStarter` and `SampleWorkerSocketClient`.
  It exists to run an embedded Java socket sample client, not to provide a
  public Java SDK worker path.
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

## Direction

`xa-mass-java-sdk` is the standard Java entry point for external worker and
task processes. It is allowed to create tasks because external producers and
worker launchers both need a public client surface. It should remain a pure
remote client and must not depend on worker-pack, server, engine, transport
implementations, or `xa-mass-base`.

`xa-mass-scenario-launcher` is the first repository consumer proving the SDK
story end to end. That is the right shape: an external Java process uses the
SDK to register topology, start worker sessions, submit tasks, and report
results.

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

## Hard Rules

- Do not add socket support to `xa-mass-java-sdk` just to keep worker-pack
  socket code alive.
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

## Non-Goals

- No public Java socket SDK in this roadmap.
- No Android/device worker host work in this roadmap.
- No Maven Central or external registry publication work.
- No redesign of engine scheduling, transport server adapters, lease, result
  convergence, or task terminal policy.
- No attempt to remove Node socket adapter fixtures from server E2E proof.

## Target Shape

```text
external Java task / worker process
  -> integrations/xa-mass-java-sdk
      -> MassPlatform
      -> tasks()
      -> workers()
      -> workerSessions().polling()
      -> workerSessions().webSocket()

integrations/xa-mass-scenario-launcher
  -> strategic SDK proof consumer
  -> task producer plus external polling/WebSocket worker sessions

integrations/xa-mass-worker-pack
  -> real worker capability pack and sample fault harness
  -> may consume xa-mass-java-sdk for public client/session behavior
  -> does not preserve raw Java socket demos
  -> keeps sample command/fault behavior local

socket adapter proof
  -> Node sample / adapter fixture / transport tests
  -> not Java SDK, not worker-pack raw Java demo
```

## Roadmap

### WPC-0: Worker-Pack Surface Inventory

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
- Every worker-pack dependency has a current owner reason or a removal target.
- Socket coverage that remains after Java socket removal is named explicitly.

### WPC-1: Retire Worker-Pack Java Socket Demo Path

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
- `rg "SampleWorkerSocketClient|SocketClientStarter"` has no production
  references.
- Worker-pack tests pass without socket sample tests.
- Socket adapter proof remains covered by Node sample or transport tests.

### WPC-2: Split Worker-Pack WebSocket Runtime From Public SDK Proof

Scope:

- Decide whether worker-pack's raw WebSocket sample client still provides
  unique fault/command harness behavior after Java SDK WebSocket session proof
  exists in scenario-launcher.
- If the raw WebSocket client only duplicates happy-path SDK behavior, replace
  it with `xa-mass-java-sdk` `WebSocketWorkerSession` or remove it.
- If it owns sample fault/command behavior that the SDK must not own, keep it
  explicitly under worker-pack fault harness ownership and document the
  retirement trigger.
- Ensure generic dispatch handling does not fork into another public handler
  runtime. Public worker handler concepts belong to the Java SDK.

Out of scope:

- Moving sample command/fault routes into the SDK.
- Making worker-pack WebSocket client a public integration recommendation.
- Building a generic `RealtimeWorkerSession` abstraction over one transport.

Acceptance:

- Worker-pack README states whether raw WebSocket code is fault-harness only or
  replaced by SDK session code.
- Non-fault happy-path Java worker proof remains scenario-launcher plus Java
  SDK.
- Any remaining raw WebSocket client has an explicit owner reason and is not
  documented as public Java SDK usage.

### WPC-3: SDK-Backed Built-In Worker Group Path

Scope:

- Define the first real worker-pack worker group that is not just a demo
  transport client.
- Register its WorkerGroup, AdapterNode, worker identity, presence/session, and
  result reporting through public APIs or `xa-mass-java-sdk`.
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

- The built-in worker group can start as an external integration package and
  register through public routes or SDK sessions.
- It does not require `xa-mass-server` production code to call the SDK.
- Worker-pack's dependency on `xa-mass-java-sdk`, if added, is justified by a
  concrete public client/session path.

### WPC-4: Command And Fault Runtime Boundary Cleanup

Scope:

- Keep sample command/fault routes in worker-pack.
- Re-evaluate `xa-mass-base/src/main/java/com/xa/mass/command` usage after
  socket retirement and WebSocket split.
- Remove, narrow, or document remaining base command residue based on active
  callers.
- Align worker-pack command/fault docs with `WORKER_FAULT_MATRIX_ROADMAP.md`.

Out of scope:

- Moving `command.event` if it is still owned by embedded runtime/event paths.
- Replacing sample command/fault behavior with public SDK APIs.
- Large rename-only cleanup without owner change.

Acceptance:

- Command/fault behavior has one clear owner.
- `xa-mass-java-sdk` has no production dependency on `xa-mass-base`.
- Worker-pack command/fault docs do not imply platform kernel ownership.

### WPC-5: Proof Registry And Runbook Cleanup

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
rg -n "xa-mass-transport-socket|SampleWorkerSocketClient|SocketClientStarter|socket" integrations/xa-mass-worker-pack doc xa-mass-server
mvn -pl integrations/xa-mass-worker-pack -am test
```

After WPC-1:

```powershell
mvn -pl integrations/xa-mass-worker-pack -am test
mvn -pl xa-mass-server -am "-Dtest=NodeSocketWorkerBlackBoxIntegrationTest,ExternalWorkerPublicContractTraceObservedIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
rg -n "SampleWorkerSocketClient|SocketClientStarter|xa-mass-transport-socket" integrations/xa-mass-worker-pack
```

After WPC-2/WPC-3:

```powershell
mvn -pl integrations/xa-mass-java-sdk,integrations/xa-mass-scenario-launcher,integrations/xa-mass-worker-pack -am test
mvn -pl xa-mass-server -am "-Dtest=JavaScenarioLauncherBlackBoxIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
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
