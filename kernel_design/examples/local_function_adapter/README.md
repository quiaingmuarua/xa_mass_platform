# Local Function Adapter

Status: planned executable-closure example. The adapter, external DeliverSeed
consumer, SeedResult runtime/client, and ResultRoutingPacer do not exist yet.

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
  -> SeedResultRuntime unified queue
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
`SeedResultRuntime`; it is not another result owner or queue. The runtime uses
one logical result queue, so neither append nor consume carries
`endpointManagerId`.

`SeedResult` deliberately does not duplicate `taskId`, `messageId`, `workerId`,
claim score, or Worker lease score. Those correlations already travel inside
`opaque_result_context` and remain opaque to the adapter.

`outcome_code` uses one exact success code:

```text
"200"
  success

any other non-empty string
  failure evidence
  Result-Routing policy decides retryable or final-failed
```

`"200"` is a kernel result code, not an HTTP response status. The contract does
not accept integer `200`, trim values, or provide aliases such as `success`,
`SUCCESS`, `ok`, or `OK`.

`append_seed_results` is batch-shaped because one bounded adapter drain can
produce multiple results. Its return value is the number accepted by the
runtime; the first runtime appends one batch to the unified queue as an
all-or-error operation. It does not return ambiguous partial acceptance.

## Worker Startup

Worker startup uses two distinct registrations:

```text
adapter.register_worker(workerId, WorkerMeta)
  process-local invocation reachability

ResourcesCommandClient.register_worker(WorkerDescriptor)
  platform resource registration and first scheduling presence
```

The safe order is:

```text
register WorkerGroup
register event handlers
register adapter-local Workers
register platform Worker descriptors
start bounded adapter draining
```

Local registration must happen first. Otherwise kernel scheduling may select a
Worker before its endpoint-manager process can invoke it.

`WorkerMeta` is local invocation context only. It must not mirror platform
system/static/dynamic matching metadata. The platform descriptor's
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
resources.register_worker(
    descriptor=WorkerDescriptor(
        worker_id="worker-1",
        worker_group_id="local-workers",
        endpoint_manager_id="local-endpoint",
        system_metadata={},
        static_attributes={"runtime": "python"},
        dynamic_attribute_names=frozenset(),
    )
)

while running:
    adapter.drain_once(limit=100)
```

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
            continue

        item = decode_delivery_item(seed.opaque_delivery_item)
        handler = self.handlers.get(item.event_code)
        if handler is None:
            continue

        handled = handler(item.payload, worker)
        results.append(
            SeedResult(
                opaque_result_context=seed.opaque_result_context,
                outcome_code=handled.outcome_code,
                opaque_result_payload=encode_result_payload(handled.payload),
            )
        )

    if not results:
        return 0
    return self.seed_result_commands.append_seed_results(results=tuple(results))
```

The adapter counts only runtime-accepted results. Consuming a queue record is
not delivery or result success.

Handlers return `outcome_code="200"` only when execution succeeded. Every
other non-empty code enters Result-Routing as failure evidence.

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

`SeedResultRuntime` stores accepted evidence in one logical queue.
`ResultRoutingPacer` consumes it, applies the TaskItem outcome, and coordinates
Worker-owner release/retention. When the adapter drops a seed, crashes, or
cannot submit a result, TaskItem claim and Worker lease time coordinates remain
the recovery fallback.

Handlers run with at-least-once semantics. The first handler API stays small as
`handler(payload, WorkerMeta)`; handlers requiring idempotency will need a
later contract that exposes decoded delivery identity without parsing result
context.

## Current Gaps

The next implementation order is:

```text
1. SeedResult DTO + SeedResultRuntime unified queue
2. independent DeliverSeedConsumerClient
3. external SeedResultCommandClient for the runtime append operation
4. LocalFunctionTransportAdapter + bounded process loop
5. ResultRoutingPacer and Worker-owner handoff
```

Current `KernelApplication.consume_deliver_seeds` is start-gated and therefore
is not the independent adapter-process client. Current Worker registration also
returns `CONFLICT` when an existing Worker score is found; this quick example
may use fresh Worker IDs, while restart-safe idempotent registration remains a
separate resource-command correction.

## Non-Goals

- production authentication, deployment, or compatibility policy;
- transport protocol abstraction beyond local Python functions;
- adapter-owned retries, compensation queues, or score writes;
- dynamic attribute handlers or Worker capacity policy;
- exactly-once handler execution or cross-result transactions.
