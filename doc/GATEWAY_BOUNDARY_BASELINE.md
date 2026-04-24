# Gateway Boundary Baseline

## Purpose

`xa-mass-gateway` is the current WebSocket transport adapter.

It is not the platform gateway, scheduler, authorizer, lifecycle owner, or business runtime.

The platform kernel owns platform semantics. The gateway owns only WebSocket transport concerns.

## Platform Mainline

Read the platform in three layers:

- `engine`: task lifecycle, assignment, retry, timeout, result acceptance, terminal convergence
- `sdk`: runtime composition, resource registration, event registration, producer/worker entry
- `transport-api`: transport-neutral dispatch/result/system-event seams

The current WebSocket path is only one adapter implementation under that model.

## Gateway Owns

- WebSocket server lifecycle
- WebSocket session registry and endpoint reachability
- worker transport identity binding
- inbound frame parsing and basic validation
- outbound frame encoding and delivery
- heartbeat/connect/disconnect translation
- transport-level error frames
- transport-level diagnostics
- bridge from WebSocket frames into transport-neutral channels or runtime entrypoints

## Gateway Does Not Own

- task lifecycle transitions
- task assignment or worker matching
- retry policy
- timeout policy
- terminal policy
- event authorization
- submitter/client permission
- project or event catalog truth
- worker capability truth
- business event execution
- platform audit truth

## Module Ownership

### `xa-mass-engine`

Owns:

- task state machine
- `TaskMsg` / `TaskMsgAttempt` lifecycle
- assignment and worker matching
- result acceptance and rejection
- retry / timeout / release / refill
- terminal convergence
- lifecycle trace and audit truth

### `xa-mass-sdk`

Owns:

- runtime composition
- project / event / worker / submitter registration
- producer-facing typed APIs
- worker-facing runtime entry
- transport runtime wiring

### `xa-mass-transport-api`

Owns:

- transport-neutral SPI
- dispatch/result/system-event channels
- endpoint registry contracts
- transport server contracts

Must not leak:

- WebSocket-specific types
- Netty `ChannelHandlerContext`
- frame-specific DTOs

### `xa-mass-gateway`

Owns:

- WebSocket adapter behavior only

Must not grow into:

- a scheduler
- an event authorizer
- a worker capability registry
- a task result policy layer

### Worker Runtime

Owns:

- `eventCode -> handler` resolution
- event execution
- result materialization

Transport clients only own:

- connect / reconnect
- encode / decode
- send / receive

## Reachability vs Eligibility

Keep these two concepts separate:

- transport reachability: worker connected, channel active, endpoint writable, heartbeat observed
- execution eligibility: worker online, supports event, context usable, routing matches, lock available

Gateway may report reachability.

Engine decides eligibility.

`connected == eligible` is forbidden.

## Audit Boundary

Gateway may emit transport-level facts:

- connected
- disconnected
- frame received
- frame rejected
- delivery failed

Gateway must not become the source of truth for:

- task status transitions
- result acceptance semantics
- retry exhaustion
- terminal closure

Those belong in engine/runtime trace.

## Allowed Bridge Pattern

The current gateway may translate:

- WebSocket frame -> transport-neutral envelope
- `CONTROL/event` frame -> SDK event runtime request
- transport result -> WebSocket response frame

That bridge is allowed only if it does not perform platform policy decisions itself.

## Forbidden Coupling Examples

These are architectural violations:

- gateway directly mutates `Task`, `TaskMsg`, or `TaskMsgAttempt`
- gateway performs permission checks on `eventCode`
- gateway decides retry / terminal / timeout outcomes
- engine depends on Netty session objects
- transport-api exposes WebSocket or Netty types
- worker business handler API depends on WebSocket frame DTOs

## Regression Requirements

Gateway changes must preserve:

- WebSocket dispatch
- WebSocket callback/result write-back
- callback replay rejection and idempotency behavior
- delayed worker availability behavior
- polling/pull worker mainline behavior outside WebSocket

## Working Rule

Before changing `xa-mass-gateway` or `xa-mass-transport-api`, answer:

1. Is this a transport concern or a platform concern?
2. If it is a platform concern, why is it still in the gateway path?
3. Which module should own it after the change?
4. Which integration tests prove behavior is preserved?
