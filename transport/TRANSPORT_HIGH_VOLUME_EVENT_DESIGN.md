# Transport High-Volume Event Design

Last updated: 2026-06-22

Status: historical design/reference only; superseded for dispatch and polling
buffer ownership by [TRANSPORT_BOUNDARY_BASELINE.md](./TRANSPORT_BOUNDARY_BASELINE.md).

Trust order: code, verified behavior, and
[TRANSPORT_BOUNDARY_BASELINE.md](./TRANSPORT_BOUNDARY_BASELINE.md) override this
document.

## Goal

Keep transport usable under sustained load without turning it into a second task
engine.

Historical bias:

- bounded admission over implicit buffering
- explicit `DispatchOutcome` over hidden exception paths
- queue/store replacement over protocol churn
- logs, counters, and trace over scan-heavy introspection

## Superseded Shape

The older shape below is no longer current runtime truth:

```text
engine assignment
  -> DispatchRoutingBatch(target=adapter-mailbox:<key>)
  -> AdapterMailboxMount
  -> AdapterCommandExecutor
  -> adapter final-hop send
     or polling-adapter-owned pending pull buffer
  -> worker
  -> RoutingEnvelope(target=result-ingress:<resultCorrelationRef>)
  -> TransportResultIngressChannel / TransportResultIngressHandler
  -> engine lifecycle
```

Current transport concepts are defined in the boundary baseline. In particular:

- `TransportDispatchHandoff` owns engine-to-adapter-mailbox handoff.
- `DispatchRoutingBatch` / `DispatchRoutingItem` are the dispatch carrier.
- `DispatchOutcome` is delivery attempt evidence.
- Polling pending pull storage is owned by `polling-adapter`.
- `RoutingEnvelope(target=result-ingress:<resultCorrelationRef>)` is result
  ingress carrier.

## Historical Constraints

What is already true:

- transport has explicit queue admission and bounded dispatch handoff
- direct-send and queued-send share normalized `DispatchOutcome`
- polling uses adapter-owned pending pull buffers
- realtime adapters can direct-send through shared runtime contracts
- result ingest remains outside adapter-local lifecycle mutation

What is still missing or intentionally deferred:

- distributed dispatch and polling-buffer backend choices outside this
  historical document
- engine policy hook for backpressure/offline outcomes
- strict token-based result security
- richer durable delivery state beyond current counters and queue stats

## Historical High-Volume Rules

Every accepted item must pass explicit admission:

- adapter-mailbox dispatch cap
- polling pending pull-buffer cap
- runtime executor pending cap
- adapter endpoint/session cap

Required outcome behavior:

- `BACKPRESSURE_REJECTED` is visible and retryable
- `ENDPOINT_OFFLINE` is visible and retryable
- `INVALID_ITEM` is visible and not retryable
- `ADAPTER_UNAVAILABLE` and `FAILED` are explicit adapter/runtime outcomes

Ordering rule:

- current guarantees are defined by the dispatch handoff and polling buffer
  implementations
- no global ordering across workers, adapters, or tasks

Durability rule:

- introduce HA by replacing/operating the owning dispatch handoff or
  polling-buffer implementation
- do not change adapter wire protocols to get durability

## Store Direction

This section is superseded. Do not reintroduce `TransportDeliveryStore` as a
generic transport-core seam. Polling pull buffering belongs to
`polling-adapter`; dispatch handoff belongs to transport runtime.

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
3. Add durable backend support behind the owning dispatch handoff or
   polling-adapter pending buffer
4. Add explicit engine policy hook for offline/backpressure handling
5. Add stronger result security only after compatibility behavior is designed

## Acceptance

High-volume readiness should be judged by:

- dispatch handoff and polling pending buffer admission/poll/shutdown tests
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
