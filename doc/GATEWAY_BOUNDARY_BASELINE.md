# Gateway Boundary Baseline

Last updated: 2026-04-24

Purpose:

- keep one platform source of truth
- keep `xa-mass-gateway` thin
- keep `xa-mass-transport-api` transport-neutral
- keep business execution in worker runtime
- prevent "transport-neutral" changes from becoming renamed WebSocket compatibility layers
- keep default WebSocket bootstrap inside the gateway adapter instead of teaching SDK mainline about gateway runtime internals

Use the canonical trust order in [../AGENTS.md](../AGENTS.md).
For active gateway-local compatibility debt, also use [../DEPRECATION_LEDGER.md](../DEPRECATION_LEDGER.md) and [./refactor/GATEWAY_CURRENT_INVENTORY.md](./refactor/GATEWAY_CURRENT_INVENTORY.md).

## 1. Mainline

Read the platform in four layers:

- platform truth
  - `engine`: task lifecycle, assignment, retry, timeout, result acceptance, terminal convergence, audit truth
  - `sdk`: runtime composition, registration, event permission, runtime catalog, producer/worker entry
- transport-neutral runtime contract
  - `transport-api`: dispatch/result/system-event seams, endpoint registry, transport server contracts
- adapter implementation
  - `gateway`: WebSocket server, session registry, frame codec, transport forwarding, transport diagnostics
- worker runtime
  - `eventCode -> local EventHandler -> result`

Mainline direction:

- platform responsibilities move up into `engine` / `sdk` / platform services
- concrete event execution moves down into worker runtime
- `xa-mass-gateway` stays a WebSocket adapter, not a policy owner
- `xa-mass-transport-api` stays free of hidden WebSocket semantics

## 2. Reference Anchors

Boundary-only mental models. Borrow role separation and ownership discipline only; do not copy product semantics, completeness targets, or framework structure.

| Area | Reference | Borrow | Do not copy |
| --- | --- | --- | --- |
| transport adapter | EMQX | thin adapter/broker boundary, connection/session handling, protocol adaptation, platform semantics staying out of the adapter | MQTT/topic/QoS product semantics, broker-centric capability model |
| task lifecycle | Celery / Sidekiq | retry discipline, timeout/result write-back, finality managed by the platform | broker/queue infrastructure assumptions, queue product semantics as kernel truth |
| SDK design | Telegram Bot API / Stripe SDK | stable public surface, typed contracts, low leakage of runtime internals, good producer ergonomics | pure remote-HTTP-client assumptions, exposing internal runtime composition as the primary API |

If a reference conflicts with current kernel truth or verified runtime behavior, current kernel truth wins.

## 3. Ownership

Ownership map:

- task lifecycle, retry, timeout, terminal convergence: `xa-mass-engine`
- event auth, submitter scope, event permission, runtime catalog: `xa-mass-sdk` / platform services
- worker matching and routing decisions: engine/runtime rule evaluation
- transport online/offline facts: adapter via `WorkerSystemEventChannel`
- worker capability truth: `eventCode` + `supportedEventCodes`
- business event execution: worker runtime local handlers
- transport addressability: endpoint/session registry
- transport diagnostics: adapter-local logs and metadata

Hard rules:

- `eventCode` is the platform capability identity.
- `supportedEventCodes` is worker capability truth.
- `supportedProjects` is scope/filter metadata only.
- session/endpoint facts are transport reachability, not task eligibility truth.
- diagnostics metadata is never fallback truth for auth, matching, retry, timeout, or terminal policy.

## 4. Gateway Owns

Allowed in `xa-mass-gateway`:

- WebSocket server lifecycle
- connection/session registry
- endpoint reachability and send
- frame encode/decode
- workerId / connection-role extraction
- input/output forwarding
- heartbeat/connect/disconnect translation
- transport-level error frames
- transport-level diagnostics
- bridge from WebSocket frames into transport-neutral channels or runtime entrypoints

Forbidden in `xa-mass-gateway`:

- task lifecycle transitions
- task result convergence rules
- retry budget truth
- timeout truth
- terminal policy truth
- event authorization
- submitter/client permission
- project or event catalog truth
- worker capability truth
- business event execution
- platform audit truth
- worker matching decisions

Tuple frame routing such as `TASK/step` is adapter compatibility only. Control traffic uses root-level event-first frames, and new platform capabilities must not be introduced by new gateway tuple identities.

## 5. Transport-Neutral Contract

`xa-mass-transport-api` may define:

- task dispatch channels
- task result ingest channels
- worker system event channels
- worker endpoint registry/inspection
- transport server lifecycle
- optional task-pull channels

`xa-mass-transport-api` must not define:

- WebSocket/Netty/frame/session implementation types
- gateway tuple routing as capability identity
- request/reply frame semantics as platform lifecycle truth
- adapter-only fields promoted into canonical runtime models

Rule:

- transport-neutral does not mean "WebSocket semantics with neutral names"
- if a field exists only for one adapter, keep it in the adapter
- canonical runtime contracts may express dispatch/result/system-event meaning, not frame/session meaning
- compatibility labels such as `websocket`, `ws`, `push`, `pull`, and `queue` must normalize into canonical worker transport identities before runtime selection or diagnostics
- adapter implementation labels such as `protocol()` are not runtime transport truth; runtime selection must key off canonical worker transport hint
- runtime dispatch must not silently fall back to a default adapter when a worker transport hint is missing or unsupported
- pull session opening must resolve from explicit worker transport identity, not from a runtime-wide default pull adapter

## 6. Worker Runtime Contract

Worker runtime owns:

- `eventCode -> handler` resolution
- business execution
- result materialization

Transport client code owns only:

- connect / reconnect
- encode / decode
- send / receive

Rules:

- WebSocket client code is transport client code, not the business handler framework.
- local handler registration keys off canonical `eventCode`, not frame subtype
- transport clients may adapt delivery mechanics but must not define execution semantics

## 7. Unified Lifecycle Semantics

Push, pull, and polling may differ in delivery mechanics. They must not silently fork platform lifecycle semantics.

Must stay platform-level:

- task/message assignment
- attempt creation and closure
- success/failure finality
- retry eligibility and retry budget exhaustion
- timeout effects
- terminal convergence
- worker offline signals as input into platform decisions
- audit/trace truth

May differ by transport:

- how work is obtained
- push vs poll delivery
- frame/protocol shape
- keepalive implementation

Must not fork by transport:

- what counts as a logical attempt
- what counts as a final result
- who decides retry
- who owns timeout truth
- who owns terminal convergence

If a behavior needs adapter-specific fallback logic to exist, it is not transport-neutral yet.

## 8. Reachability Vs Eligibility

Keep these separate:

- transport reachability: worker connected, channel active, endpoint writable, heartbeat observed
- execution eligibility: worker online, supports event, context usable, routing matches, lock available

Gateway reports reachability.

Engine/runtime decides eligibility.

`connected == eligible` is forbidden.

## 8.1 Gateway Wiring Rule

`DispatchRuntimeContext` is an adapter-local wiring snapshot, not a mutable registration surface.

Rules:

- construct the gateway message codec and explicit bridge ports before starting the adapter
- resolve the gateway `endpointRegistry` once during runtime assembly and pass that exact instance into both dispatcher wiring and transport-server creation
- route inbound task-result transport shells into the canonical `TaskResultReport -> TaskResultIngestChannel` seam
- keep `TaskResultIngestChannel` as a runtime-level seam; do not model it as worker transport binding ownership
- resolve `WorkerSystemEventChannel` from gateway runtime assembly, not from transport binding ownership
- keep adapter-specific endpoint bootstrap inside the gateway adapter module; SDK runtime config must not hardcode session-manager implementation branching
- keep default transport-server bootstrap inside the gateway adapter module; SDK `transportServerFactory(...)` is an advanced override seam, not the mainline startup path
- `MassApplication.configureGatewayRuntime(...)` is the pre-start wiring seam for gateway-local bridge ports; deprecated late setters are compatibility only
- resolve transport-contributed bridge ports during runtime assembly, then inject them once
- do not grow post-construction `setHandler(...)` or `registerRoute(...)` seams on gateway runtime context
- if a new adapter path needs another port, add an explicit field or constructor dependency instead of a late-bound generic registry

## 9. Audit Boundary

Gateway may emit transport facts:

- connected
- disconnected
- frame received
- frame rejected
- delivery failed

Gateway must not become truth for:

- task status transitions
- result acceptance semantics
- retry exhaustion
- terminal closure

Those belong in engine/runtime trace.

## 10. Active Adapter Seams

These seams are adapter-local, not platform truth:

- raw inbound JSON plus connection facts
- raw outbound JSON plus explicit transport addressability
- `OutboundDelivery` as the current minimal outbound delivery record
- tuple routing such as `TASK/step`
- explicit gateway input/output processors that convert frames into canonical bridge calls and transport sends
- adapter-level metadata extraction such as canonical `eventCode` diagnostics from explicit fields

Rules:

- preserve only what the current adapter path still needs
- do not add platform semantics through these seams
- do not use them as fallback truth when canonical runtime state exists
- remove them by converging callers to canonical runtime contracts, not by adding another bridge layer

## 11. Forbidden Coupling

These are regressions:

- gateway directly mutates `Task`, `TaskMsg`, or `TaskMsgAttempt`
- gateway performs permission checks on `eventCode`
- gateway decides retry / terminal / timeout outcomes
- engine depends on Netty/session objects
- transport-api exposes WebSocket or Netty types
- worker business handler APIs depend on WebSocket frame DTOs
- endpoint/session connectedness is treated as worker capability or task eligibility truth
- old and new transport paths are both kept authoritative

## 12. Regression Requirements

Gateway/transport changes must preserve:

- WebSocket dispatch
- WebSocket callback/result write-back
- callback replay rejection and idempotency behavior
- delayed worker availability behavior
- polling/pull worker mainline behavior outside WebSocket

## 13. Working Rule

Before changing `xa-mass-gateway` or `xa-mass-transport-api`, answer:

1. Is this a transport concern or a platform concern?
2. If it is a platform concern, why is it still in the gateway path?
3. Which module owns the source of truth after the change?
4. Is the touched path a canonical runtime contract or only an adapter-local seam?
5. Which integration tests or trace events prove behavior is preserved?
