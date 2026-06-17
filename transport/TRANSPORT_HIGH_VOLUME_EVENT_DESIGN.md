# Transport High-Volume Event Design

Last updated: 2026-06-17

Status: design/reference only, not current runtime truth.

Trust order: code, verified behavior, and
[TRANSPORT_BOUNDARY_BASELINE.md](./TRANSPORT_BOUNDARY_BASELINE.md) override this
document.

## Goal

Keep transport usable under sustained load without turning it into a second task
engine.

Bias:

- bounded admission over implicit buffering
- explicit `DispatchOutcome` over hidden exception paths
- queue/store replacement over protocol churn
- logs, counters, and trace over scan-heavy introspection

## Stable Shape

High-volume transport should stay on this mainline:

```text
engine assignment
  -> DeliveryCommand
  -> TransportDeliveryCommandListener
  -> TransportDeliveryService
  -> TransportDeliveryStore
  -> adapter drain/send
  -> worker
  -> TransportResultIngressEnvelope
  -> TransportResultIngressChannel / TransportResultIngressHandler
  -> engine lifecycle
```

Permanent transport concepts remain:

- `AdapterCommandExecutor.dispatch(List<DeliveryCommand>)`
- `DispatchOutcome`
- `DeliveryCommand`
- `QueuedPulledDispatch`
- `TransportDeliveryService`
- `TransportDeliveryStore`
- `TransportResultIngressEnvelope`
- `TransportResultIngressChannel`
- `TransportResultIngressHandler`

## Current Constraints

What is already true:

- transport has explicit queue admission and bounded in-memory delivery
- direct-send and queued-send share normalized `DispatchOutcome`
- polling is queue-first
- realtime adapters can direct-send through shared runtime contracts
- result ingest remains outside adapter-local lifecycle mutation

What is still missing or intentionally deferred:

- durable store implementation beyond memory
- engine policy hook for backpressure/offline outcomes
- strict token-based result security
- richer durable delivery state beyond current counters and queue stats

## High-Volume Rules

Every accepted item must pass explicit admission:

- per-route queue cap
- global delivery backlog cap
- runtime executor pending cap
- adapter endpoint/session cap

Required outcome behavior:

- `BACKPRESSURE_REJECTED` is visible and retryable
- `ENDPOINT_OFFLINE` is visible and retryable
- `INVALID_ITEM` is visible and not retryable
- `ADAPTER_UNAVAILABLE` and `FAILED` are explicit adapter/runtime outcomes

Ordering rule:

- current guarantee is per `(adapterId, routeKey)` FIFO for queued delivery
- no global ordering across workers, adapters, or tasks

Durability rule:

- introduce HA by replacing `TransportDeliveryStore`
- do not change adapter wire protocols to get durability

## Store Direction

`TransportDeliveryStore` remains the replacement seam for Redis/JDBC.

Keep it narrow:

- `enqueue`
- `drain`
- `poll`
- `stats`
- `shutdown`

Do not push engine concepts into the store. Future delivery enrichment, if
needed, must stay transport-owned: `deliveryId`, enqueue time, delivery state,
attempt count, last outcome.

## Adapter Direction

- polling remains queue-first
- websocket and socket remain peer realtime adapters
- realtime adapters must not grow private delivery lifecycle models
- adapters do not decide retry, release, or terminal state

## Migration Sequence

1. Keep current runtime bounded and observable
2. Make queue-first realtime delivery possible behind the same transport concepts
3. Add durable store implementation behind `TransportDeliveryStore`
4. Add explicit engine policy hook for offline/backpressure handling
5. Add stronger result security only after compatibility behavior is designed

## Acceptance

High-volume readiness should be judged by:

- delivery store admission/drain/poll/shutdown/stat tests
- runtime dispatch grouping and outcome tests
- adapter sent/offline/unavailable/backpressure tests
- SDK load/perf runners
- Boot-shell E2E compatibility checks

Minimum bar:

- bounded queues
- bounded executors
- observable backpressure
- explicit cleanup on failure
- shared delivery outcomes across adapters
- no adapter-private lifecycle truth
- no hidden engine mutation from transport
