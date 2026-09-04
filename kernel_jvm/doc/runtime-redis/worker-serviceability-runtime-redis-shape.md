# Worker Serviceability Runtime Redis Shape

Status: active Java Kernel Redis ABI for the optional Worker Serviceability
policy.

## Keys

```text
xa_mass:<scope>:worker:serviceability:adapter:<adapterId>:probe_requests
  HASH field = workerId
  value       = "1"

xa_mass:<scope>:worker:serviceability:evidence_results
  LIST item = canonical encoded DeliveryReport
```

`xa_mass` is fixed and `scope` is the validated profile/test isolation
boundary. No key uses a Redis Cluster hash tag. Each operation remains on one
owner key; no operation spans Adapter request HASHes or combines a request and
result key atomically.

## Request HASH

`offer_probe_requests` uses one Lua call per Adapter and accepts at most 100
explicit unique Worker ids. Existing fields return `ALREADY_REQUESTED`; empty
capacity accepts `OFFERED`; a full 10,000-field HASH returns `CAPACITY`. It
never replaces a field.

`consume_probe_requests` uses `HRANDFIELD key limit` followed by `HDEL` in one
Lua call. Consumption is unordered and destructive. A consumed request has no
in-flight record, deadline, retry, generation, or acknowledgement. A later
stale-score scan may offer the Worker again.

The Java Kernel Dispatch Pacer implements offer. The Java Server provider
implements request consumption because it is the bounded Adapter HTTP bridge.
There is no fallback or dual-producer mode.

## Result LIST

`append_adapter_evidence_results` accepts at most 100 standard
`ADAPTER -> KERNEL` DeliveryReports. A Route-change Report carries one Worker;
a periodic snapshot Report may carry up to 100. The append Lua script admits
the complete batch only when it fits under the 10,000-item limit. Capacity
returns zero without writing a prefix, allowing Server to expose temporary
backpressure and Adapter to retry the unchanged batch.

`consume_adapter_evidence_results` destructively removes at most 100 items from
the head. Corrupt or wrong-endpoint entries are discarded. There is no pending
batch, lease, replay, or result HASH.

The Java provider implements append for Server ingress and destructive consume
for the fixed Java production Result Pacer. There is no fallback or
dual-consumer mode.

## Failure Model

Both structures are best-effort evidence handoffs. Process failure after
destructive consume can lose work, and Adapter-local queue pressure can still
drop a retry. Redis LIST capacity alone does not turn evidence into a terminal
semantic rejection. Request or result loss does not mutate score; the old score
remains eligible for a future bounded scan. These keys are not Worker
connection, Binding, lifecycle, or scheduling truth.
