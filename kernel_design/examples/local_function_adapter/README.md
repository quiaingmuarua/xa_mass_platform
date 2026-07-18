# Local Function Adapter

Status: current Python executable-spec closure example.

Kernel boundary:
[DeliverSeed Outbound Delivery](../../doc/scheduling/deliver-seed-outbound-delivery.md).

## Goal

Provide the smallest external process that proves the kernel can complete this
path:

```text
DeliverSeed
  -> LocalFunctionTransportAdapter
  -> local Worker + EventHandler
  -> SeedResult
  -> SeedResultRuntime outcome-class queue
  -> ResultRoutingPacer
```

This is an executable closure example, not a production transport service. It
must keep kernel score, lease, lifecycle, matching, and result-classification
rules outside the adapter.

## Minimal External Contracts

The independent adapter process needs two stable clients rather than internal
runtime objects:

```python
class DeliverSeedConsumerClient:
    def consume_deliver_seeds(
        self,
        *,
        endpoint_manager_id: str,
        limit: int,
    ) -> tuple[DeliverSeed, ...]:
        ...


@dataclass(frozen=True, slots=True)
class SeedResult:
    opaque_result_context: str
    outcome_code: str
    opaque_result_payload: str | None = None


class SeedResultCommandClient:
    def append_seed_results(
        self,
        *,
        results: Sequence[SeedResult],
    ) -> int:
        ...
```

`SeedResultCommandClient` is only the independent adapter process's protocol
client. It forwards the append operation to the kernel-owned
`SeedResultRuntime`; it is not another result owner or queue. The runtime
partitions by outcome class only, so append carries no partition coordinate and
consume carries no `endpointManagerId`.

`SeedResult` deliberately does not duplicate `taskId`, `messageId`, `workerId`,
claim score, or Worker lease score. Those correlations already travel inside
`opaque_result_context` and remain opaque to the adapter.

`outcome_code` uses a fixed kernel protocol:

```text
"200"  -> Worker execution success
"1xxx" -> Worker execution failure after entering the Worker
"3xxx" -> Adapter rejected delivery before entering the Worker
```

`1xxx` and `3xxx` are exactly four ASCII digits. Exact subcodes belong to the
producer; Result-Routing understands only the class. The contract does not
accept integer codes, trim values, or provide aliases such as `success` or `ok`.
`200` always carries non-empty JSON text; a successful handler returning no
business value is encoded as `"null"`.

`append_seed_results` is batch-shaped because one bounded adapter drain can
produce multiple results. Its return value is the number accepted by the
runtime. A mixed batch is partitioned into three best-effort class queues; a
cross-class append is not atomic and an exception does not report partial
acceptance.

## Worker Startup

Worker startup uses two distinct declarations:

```text
adapter.register_worker(workerId, WorkerMeta)
  process-local invocation reachability

ResourcesCommandClient.upsert_worker(WorkerDeclaration)
  platform resource upsert and scheduling presence/reconnect
```

The safe order is:

```text
upsert WorkerGroup
register event handlers
register adapter-local Workers
upsert platform Worker declarations
start bounded adapter draining
```

Local registration must happen first. Otherwise kernel scheduling may select a
Worker before its endpoint-manager process can invoke it.

`WorkerMeta` is local invocation context only. It must not mirror platform,
Worker, or dynamic matching attributes. The platform descriptor's
`endpointManagerId` must equal the adapter's `endpoint_manager_id`.

## Example Shape

```python
resources = ResourcesCommandClient.from_json(config_json)
deliver_seeds = DeliverSeedConsumerClient.from_json(config_json)
seed_results = SeedResultCommandClient.from_json(config_json)

adapter = LocalFunctionTransportAdapter(
    endpoint_manager_id="local-endpoint",
    deliver_seed_consumer=deliver_seeds,
    seed_result_commands=seed_results,
)

adapter.register_event_handler("event1", event1_handler)
adapter.register_event_handler("event2", event2_handler)

adapter.register_worker("worker-1", WorkerMeta(...))
resources.upsert_worker(
    declaration=WorkerDeclaration(
        worker_id="worker-1",
        worker_group_id="local-workers",
        endpoint_manager_id="local-endpoint",
        attributes={"runtime": "python"},
        dynamic_attribute_names=frozenset(),
    )
)

while running:
    adapter.drain_once(limit=100)
```

`adapter.unregister_worker(workerId)` removes local reachability idempotently.
It does not mutate kernel Worker score; a later consumed Seed for that Worker
produces adapter rejection evidence.

One adapter process may host many Workers. They share the process-local
`eventCode -> EventHandler` registry.

The built-in assignment encoder produces this Worker-facing envelope:

```json
{"eventCode":"event1","payload":{"input":"value"}}
```

`decode_delivery_item` therefore resolves only `eventCode` and `payload`.
Message identity and score fences remain in `opaqueResultContext`, which the
adapter forwards unchanged. Applications that use an external payload reference
place that reference inside their own payload mapping; it is not a separate
adapter contract.

## Drain Once

```python
def drain_once(self, *, limit: int) -> int:
    seeds = self.deliver_seed_consumer.consume_deliver_seeds(
        endpoint_manager_id=self.endpoint_manager_id,
        limit=limit,
    )

    results = []
    for seed in seeds:
        if now_millis() >= seed.task_item_claim_until_millis:
            continue

        worker = self.workers.get(seed.worker_id)
        if worker is None:
            results.append(SeedResult(seed.opaque_result_context, "3001"))
            continue

        item = decode_delivery_item(seed.opaque_delivery_item)
        if item is None:
            continue
        handler = self.handlers.get(item.event_code)
        if handler is None:
            results.append(SeedResult(seed.opaque_result_context, "3002"))
            continue

        try:
            handled = handler(item.payload, worker)
            outcome_code = handled.outcome_code
            result_payload = encode_result_payload(handled.payload)
        except Exception:
            outcome_code = "1500"
            result_payload = None
        results.append(
            SeedResult(
                opaque_result_context=seed.opaque_result_context,
                outcome_code=outcome_code,
                opaque_result_payload=result_payload,
            )
        )

    if not results:
        return 0
    return self.seed_result_commands.append_seed_results(results=tuple(results))
```

The adapter counts only runtime-accepted results. Consuming a queue record is
not delivery or result success.

Handlers may return only `"200"` or `1xxx`; they cannot forge `3xxx` adapter
evidence. Handler failure, invalid return type, or payload encoding failure is
reported as `1500`. Missing local Worker is `3001`; missing handler is `3002`.
Expired seeds and malformed delivery envelopes remain unclassified drops and
do not change Worker scheduling-serviceability classification.

## Ownership

The adapter owns only:

```text
endpointManagerId-local Worker registry
event handler registry
DeliverSeed decoding
local handler invocation
SeedResult encoding and submission
```

It does not:

```text
select or replace seed.workerId
parse opaque_result_context
read or mutate TaskItem / Worker score
renew or release Worker lease
classify result finality
synthesize timeout results
```

`SeedResultRuntime` stores accepted evidence in one of three class queues.
`ResultRoutingPacer` consumes, decodes, groups, and delegates the result
evidence. The built-in Task handler stores last-success payload and promotes
FINAL_SUCCESS for `200`; the built-in Worker handlers exact-release leases for
`200/1xxx` and exact-demote them for `3xxx`. Failures do not actively rewrite
Item retry time; the existing Item claim becomes due naturally.
When the adapter drops a malformed/expired seed, crashes, or cannot submit a
result, TaskItem claim and Worker lease time coordinates remain the recovery
fallback. Missing evidence is never reclassified as `3xxx`.

Handlers run with at-least-once semantics. The first handler API stays small as
`handler(payload, WorkerMeta)`; handlers requiring idempotency will need a
later contract that exposes decoded delivery identity without parsing result
context.

## Executable Surface

The implementation lives in `adapter.py`. It provides `WorkerMeta`,
`EventHandlerResult`, and `LocalFunctionTransportAdapter`; external queue access
uses `DeliverSeedConsumerClient` and `SeedResultCommandClient`. The adapter has
no internal background thread. Its process host owns the `drain_once` loop and
shutdown policy.

Worker upsert is restart-safe. Reconnect converges an existing score to positive
polarity and dirty=1 while preserving its time coordinate and lane rank. It may
return `CONFLICT` only when an
immutable declaration field such as `endpointManagerId` or
`dynamicAttributeNames` differs; an existing score alone is not a conflict.

## Non-Goals

- production authentication, deployment, or compatibility policy;
- transport protocol abstraction beyond local Python functions;
- adapter-owned retries, compensation queues, or score writes;
- dynamic attribute handlers or Worker capacity policy;
- exactly-once handler execution or cross-result transactions.
