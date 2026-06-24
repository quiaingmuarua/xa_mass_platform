# Transport WebSocket Bootstrap Assembly Template Clarity Convergence Roadmap

Status: proposed direction document.

## Summary

`WebSocketTransportAdapterBootstrap` currently has the right ownership boundary,
but the local assembly shape is still hard to read. A reader sees repeated
`WebSocketSessionRegistry`, `TransportJsonFrameParser`, and
`WebSocketServerFactoryContext` references without an obvious first-glance answer
to:

- which objects are session/final-hop state;
- which objects are inbound frame/result processing;
- which objects are only custom server runtime access;
- which values are adapter properties rather than runtime hooks.

This roadmap improves WebSocket adapter assembly readability without changing
transport routing, worker selection, mailbox ownership, endpoint lease
semantics, or the public worker channel frame model.

The wider goal is to make the WebSocket adapter a clear concrete example for
future embedded adapter onboarding. This is not a request to create a generic
adapter framework. A new adapter author should be able to read the WebSocket
bootstrap and immediately see which local pieces they must supply: property
config, descriptor/binding, session/final-hop lane, inbound/result pipeline,
optional server runtime access, and contributed host resources.

## Current Code Observations

- `WebSocketTransportAdapterBootstrap` creates all runtime parts in one private
  `WebSocketRuntimeParts` tuple:
  `adapterMailboxKey`, `WebSocketSessionRegistry`,
  `WebSocketSessionEvidenceRefresher`, `TransportJsonFrameParser`, and
  `Consumer<JsonObject> inboundFrameSink`.
- The tuple mixes three different lanes:
  assigned-delivery final hop, inbound result processing, and session evidence
  refresh.
- `WebSocketServerFactoryContext` is not a broad context. It is the custom
  embedded server's runtime access:
  `WebSocketServerSessionHandle`, raw inbound frame sink, port, and endpoint
  path.
- `WebSocketAdapterConfig` is already converged to property-only config.
  Custom server factories are carried by SDK/starter assembly into
  `WebSocketTransportAdapterBootstrap`, not stored inside adapter config.
- `WebSocketTransportAdapterBootstrap` already snapshots concrete adapter
  properties at construction time; the remaining problem is local assembly
  legibility, not owner drift.

## Owner Review

WebSocket adapter owns:

- WebSocket protocol server construction and bind/stop contribution;
- local session registry and final-hop selected-worker send;
- WebSocket protocol control-frame filtering;
- parsing raw WebSocket text frames into transport-shared JSON frame objects;
- contributing WebSocket-specific raw worker side-channel support.

Transport runtime embedded support owns:

- generic per-message command outcome normalization;
- generic adapter inbound result processing;
- generic worker-channel ACTION_REPLY result facts;
- transport JSON frame parser utility.

SDK/starter assembly owns:

- custom embedded server factory hook storage and propagation;
- bundled/supplemental WebSocket adapter assembly selection.

Custom embedded WebSocket server factories may consume a narrow runtime access
object, but they must not receive the full session registry, transport runtime
context, adapter mailbox client, endpoint lease store, worker presence ingress,
or result ingress channel.

## Adapter Onboarding Template

This roadmap should leave WebSocket as the reference shape for a new embedded
adapter. The template is the concrete WebSocket implementation itself, not a
shared framework, not a new adapter SPI, and not a module extraction.

A concrete adapter bootstrap should make these decisions obvious:

| Decision | WebSocket Example | New Adapter Question |
| --- | --- | --- |
| Adapter identity | `adapterId`, protocol, transport hint | What descriptor and binding does this adapter contribute? |
| Property config | `WebSocketAdapterConfig` | Which static properties are copyable config, not runtime hooks? |
| Session lane | `WebSocketSessionLane` | How does the adapter bind selected worker ids to local channels/sessions? |
| Final-hop send | `AdapterCommandExecutor` from a send function | How does one `DispatchMessage` become a protocol message? |
| Inbound frame pipeline | `WebSocketInboundFramePipeline` | How do inbound protocol frames become result-ingress messages or local control frames? |
| Runtime access for custom server | `WebSocketServerRuntimeAccess` | What narrow hooks does a custom server need to connect sessions and forward inbound frames? |
| Host contribution | `TransportAdapterContribution` | Which bindings, mailbox consumers, raw channels, servers, and managed resources are contributed? |

The intended pattern is:

```text
adapter config snapshot
  -> descriptor + transport binding
  -> session/final-hop lane
  -> inbound/result pipeline
  -> optional protocol server runtime access
  -> explicit TransportAdapterContribution entries
```

New adapters should not copy WebSocket names or depend on WebSocket classes.
They should copy the visible shape: narrow property config, narrow final-hop
function, narrow inbound pipeline, and explicit contribution points.

## Boundary Decision

The WebSocket bootstrap should read as three explicit local lanes:

```text
WebSocketSessionLane
  selected-worker final-hop send
  raw worker side-channel send
  session evidence refresher source

WebSocketInboundFramePipeline
  raw JSON frame -> parsed JsonObject
  protocol control-frame filter
  ACTION_REPLY result ingress

WebSocketServerRuntimeAccess
  sessionHandle
  acceptInboundRawFrame(...)
  serverPort
  endpointPath
```

`WebSocketServerFactoryContext` should be renamed to a more accurate embedded
custom-server access type, preferably `WebSocketServerRuntimeAccess`. This is a
breaking in-repo SDK/starter surface change; do not keep a compatibility alias.

## Target Shape

`WebSocketTransportAdapterBootstrap.contribute(...)` should be readable at the
top level:

```java
if (!enabled) {
    return TransportAdapterContribution.empty();
}

WebSocketSessionLane sessions = createSessionLane(context);
WebSocketInboundFramePipeline inbound = createInboundFramePipeline(context);
TransportAdapterContribution.Builder contribution = TransportAdapterContribution.builder();

contributeAssignedDelivery(contribution, context, sessions);
contributeRawWorkerChannel(contribution, sessions);
contributeServer(contribution, sessions, inbound);
return contribution.build();
```

The private lane records should carry purpose-shaped fields instead of a generic
`RuntimeParts` bag:

```java
private record WebSocketSessionLane(
        String adapterMailboxKey,
        WebSocketSessionRegistry sessionRegistry,
        WebSocketSessionEvidenceRefresher evidenceRefresher,
        AdapterCommandExecutor commandExecutor,
        RawWorkerMessageChannel rawWorkerMessageChannel) {
}

private record WebSocketInboundFramePipeline(
        TransportJsonFrameParser frameParser,
        Consumer<String> rawFrameSink,
        Consumer<JsonObject> frameSink) {
}
```

The custom server factory input should expose only runtime access:

```java
public final class WebSocketServerRuntimeAccess {
    WebSocketServerSessionHandle sessionHandle();
    int serverPort();
    String endpointPath();
    void acceptInboundRawFrame(String rawJson);
}
```

Method names may use existing JavaBean style if SDK style requires it, but the
type name and documentation must make clear that this object is not broad
transport context.

The bootstrap should also serve as an example of adapter contribution anatomy:

```java
contribution.addTransportBinding(...);
contribution.addAdapterMailboxConsumer(...);
contribution.addRawWorkerMessageChannel(...);
contribution.addManagedTransportAdapter(...);
contribution.addTransportServer(...);
```

Each contribution call should be close to the lane that owns the required
runtime object. For example, assigned delivery should see a command executor,
not a parser; server contribution should see runtime access and session handle,
not transport dispatch internals.

## Non-Goals

- Do not change dispatch routing, adapter mailbox keys, endpoint lease evidence,
  worker-runtime reachability, or result-ingress queue semantics.
- Do not introduce a new module for worker-channel runtime code in this slice.
- Do not move WebSocket session registry ownership into transport runtime.
- Do not make `TransportJsonFrameParser` WebSocket-specific.
- Do not add a generic `AdapterServerFactoryContext` abstraction before Socket
  has an equivalent proven need.
- Do not extract a shared adapter bootstrap module in this roadmap. WebSocket
  should become a readable concrete template first; common code can be judged
  later from at least two clean adapters.
- Do not add common adapter interfaces, abstract base classes, framework
  packages, registries, or lifecycle owners just to make the template reusable.
  Reuse here means humans can follow the concrete WebSocket example.
- Do not keep old `WebSocketServerFactoryContext` as an alias.
- Do not add public hooks for overriding the entire WebSocket assembly
  mechanism. Only custom server construction remains customizable here.

## Do Not Start With

Do not start by creating a public common adapter bootstrap module, a generic
server context abstraction, an abstract base adapter, a registry of adapter
hooks, or a wrapper around every collaborator. The problem is local assembly
readability and naming, not a missing cross-adapter framework.

## WBA-0 - Current Shape Inventory

Scope:

- Inventory current direct uses of:
  - `WebSocketServerFactoryContext`
  - `TransportServerFactory<WebSocketServerFactoryContext>`
  - `WebSocketRuntimeParts`
  - `WebSocketSessionRegistry`
  - `TransportJsonFrameParser`
- Separate production use, SDK builder use, and test-only use.

Acceptance:

- The roadmap implementation PR identifies every production caller that must be
  renamed from `WebSocketServerFactoryContext` to the new runtime access type.
- Test-only usages are listed separately so proof updates do not preserve old
  vocabulary by accident.

## WBA-1 - Rename Custom Server Runtime Access

Scope:

- Rename `WebSocketServerFactoryContext` to
  `WebSocketServerRuntimeAccess` or an equivalent name that says "runtime access"
  rather than "general context".
- Update `TransportServerFactory<WebSocketServerFactoryContext>` callers in:
  - `WebSocketTransportAdapterBootstrap`
  - `TransportConfig`
  - `TransportRuntimeComposition`
  - `MassApplicationBuilder`
  - `MassSdk`
  - focused tests
- Update method names only if the naming improves clarity without widening the
  surface.

Acceptance:

- No main/test Java source references `WebSocketServerFactoryContext`.
- The new type exposes only session handle, raw inbound frame sink, port, and
  endpoint path.
- `MassSdkTest#customTransportServerFactoryOverridesBundledWebSocketAdapter`
  or equivalent proof still verifies the custom server factory receives the
  expected runtime access and starts/stops through the host.
- No compatibility alias or deprecated old class remains.

## WBA-2 - Split Bootstrap Runtime Parts Into Purpose Lanes

Scope:

- Replace private `WebSocketRuntimeParts` with purpose-named private records:
  - `WebSocketSessionLane`
  - `WebSocketInboundFramePipeline`
- `createSessionLane(context)` owns:
  - `AdapterSessionEvidencePublisher`
  - `WebSocketSessionRegistry`
  - `WebSocketSessionEvidenceRefresher`
  - final-hop `AdapterCommandExecutor`
  - raw worker message channel
- `createInboundFramePipeline(context)` owns:
  - `TransportJsonFrameParser`
  - worker-channel ACTION_REPLY reader
  - adapter inbound result processor
  - raw frame sink and parsed frame sink
- `contributeAssignedDelivery`, `contributeRawWorkerChannel`, and
  `contributeServer` should accept the narrow lane they actually use.

Acceptance:

- `WebSocketTransportAdapterBootstrap.contribute(...)` reads in terms of
  session lane, inbound frame pipeline, assigned delivery, raw worker channel,
  and server contribution.
- No method accepts the old "bag of everything" record.
- `WebSocketSessionRegistry` is passed only where local session/final-hop access
  is needed.
- `TransportJsonFrameParser` is passed only through inbound frame pipeline or
  default server construction.
- No new module-level wrapper/facade classes are introduced.
- A new adapter author can identify the WebSocket pattern for config snapshot,
  binding contribution, final-hop send, inbound frame pipeline, runtime access,
  and host resource contribution from the top-level bootstrap flow.

## WBA-3 - Tighten Proof And Owner Docs

Scope:

- Update `transport/WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md` to reflect:
  - custom server runtime access naming and purpose;
  - session lane vs inbound frame pipeline split;
  - WebSocket as the current concrete example for new embedded adapter
    onboarding;
  - bootstrap still remains adapter-local assembly, not a public adapter
    framework.
- Extend `TransportConvergenceArchitectureGuardTest` to protect stable
  invariants:
  - no `WebSocketServerFactoryContext`;
  - no `WebSocketRuntimeParts`;
  - custom server runtime access does not expose `WebSocketSessionRegistry`,
    endpoint lease store, worker presence ingress, result ingress, mailbox
    client, or transport runtime context;
  - `WebSocketAdapterConfig` remains property-only.

Acceptance:

- Guard fails if the old context name or generic runtime-parts bag returns.
- Guard protects owner boundaries, not temporary private record names unless
  those names preserve the clarified lanes.
- Baseline document describes current implemented behavior after WBA-1/WBA-2,
  not just target direction.
- Baseline document gives a short "new embedded adapter bootstrap checklist"
  derived from the WebSocket shape without creating or requiring a shared
  framework.

## Suggested Implementation Order

1. Execute WBA-0 with `rg` and update the implementation notes if new callers
   appear.
2. Implement WBA-1 rename first. This makes the custom server surface honest
   before internal bootstrap refactoring.
3. Implement WBA-2 local lane split. Keep it private to
   `WebSocketTransportAdapterBootstrap`.
4. Implement WBA-3 guards/docs once the new shape compiles and focused tests
   pass.

## Verification Candidates

Focused tests:

```powershell
.\mvnw.cmd -q -pl transport/transport_runtime,transport/websocket-adapter,sdk/xa-mass-embedded-sdk -am test "-Dtest=WebSocketTransportAdapterBootstrapTest,TransportConvergenceArchitectureGuardTest,TransportConfigTest,MassSdkTest#customTransportServerFactoryOverridesBundledWebSocketAdapter" "-Dsurefire.failIfNoSpecifiedTests=false" "-DtrimStackTrace=true"
```

Compile:

```powershell
.\mvnw.cmd -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,transport/socket-adapter,transport/websocket-adapter,sdk/xa-mass-embedded-sdk -am -DskipTests compile
```

Residue checks:

```powershell
rg -n "WebSocketServerFactoryContext|WebSocketRuntimeParts" transport sdk --glob "*.java" --glob "!**/target/**"
rg -n "WebSocketAdapterConfig.*TransportServerFactory|WebSocketAdapterConfig.*WebSocketServer" transport/websocket-adapter/src/main/java sdk/xa-mass-embedded-sdk/src/main/java --glob "*.java"
git diff --check
```

## Completion Criteria

- WebSocket custom server factory surface is named as runtime access, not broad
  context.
- Bootstrap top-level flow is readable by lane purpose without following every
  constructor call.
- WebSocket can be used as a concrete onboarding example for a new embedded
  adapter: config snapshot, descriptor/binding, session/final-hop lane,
  inbound/result pipeline, optional custom server runtime access, and explicit
  host contributions are all visible.
- Session/final-hop, inbound frame/result, and server construction collaborators
  are not carried through a generic tuple.
- Adapter config remains property-only.
- Focused tests, compile, residue checks, and owner docs all agree with the new
  shape.
