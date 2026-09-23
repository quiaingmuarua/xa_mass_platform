# Worker Serviceability Runtime Redis Shape

Status: active Java Kernel Redis ABI for network evidence and optional
Worker Serviceability probes.

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

The Kernel provider implements both operations. The Dispatch Pacer offers
requests; Server consumes them for its bounded Adapter HTTP bridge. There is no
fallback or dual-producer mode.

## Result LIST

`append_network_evidence_results` accepts at most 100 standard
`ADAPTER -> KERNEL` DeliveryReports or the fixed internal
`SERVER -> KERNEL` Polling observation from `system-polling`. A Route-change Report carries one Worker;
a periodic snapshot Report may carry up to 100. The append Lua script admits
the complete batch only when it fits under the 10,000-item limit. Capacity
returns zero without writing a prefix, allowing Server to expose temporary
backpressure. Adapter KERNEL submission failure drops that batch under its
existing best-effort lane policy. Internal Polling append failure or zero
admission drops only the observation and does not fail Command consumption.

`consume_network_evidence_results` destructively removes at most 100 items from
the head. Corrupt or wrong-endpoint entries are discarded. There is no pending
batch, lease, replay, or result HASH.

The Java provider implements append for Server ingress and destructive consume
for the fixed Java production Result Pacer. There is no fallback or
dual-consumer mode.

## Failure Model

Both structures are best-effort evidence handoffs. Process failure after
destructive consume can lose work, and Adapter-local queue pressure can also
drop evidence. Redis LIST capacity alone does not turn evidence into a terminal
semantic rejection. Request or result loss does not mutate Score. A cold registered Worker stays
cold until a new valid connection or Polling observation; it has no cold scan
or activation replay. Eligible HOT/recovery coordinates may still receive later
periodic probes in presets that enable them. These keys are not Worker
connection, Binding, lifecycle, or scheduling truth.
