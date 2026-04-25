# Testing Topic Index

Last updated: 2026-04-25

Use this file when the question is not "how is testing organized?" but "I need to analyze one runtime point now."

Start with the topic row that matches the risk, then open the linked topic card.

## 1. Topic Map

| Topic | Why it matters | Core code paths | Primary lanes | Start here |
| --- | --- | --- | --- | --- |
| callback result race | duplicate callbacks, mixed ordering, double-finalize risk | `TaskManager.handleTaskMessageResult(...)`, `TaskResultService` | `concurrency`, `Boot-shell E2E` | [topics/callback_result_race.md](./topics/callback_result_race.md) |
| watchdog expiry and late results | expiry/result competition, stale in-flight attempts | `LeaseExpireWatchdog`, `TaskResultService.expireTaskMessage(...)` | `concurrency`, `Boot-shell E2E` | [topics/watchdog_expiry_and_late_results.md](./topics/watchdog_expiry_and_late_results.md) |
| worker release and redispatch | worker/context reuse, unlock timing, refill correctness | `TaskResourceReleaseListener`, dispatch-request events | `concurrency`, `Boot-shell E2E` | [topics/worker_release_and_redispatch.md](./topics/worker_release_and_redispatch.md) |
| assignment refill and batching | single-worker multi-round flow, `batchSize`, ready/running refill pressure | `TaskAssignWorker`, `TaskWorkerAssignListener`, assignment listeners | `Boot-shell E2E`, targeted `concurrency`, `perf` when hot | [topics/assignment_refill_and_batching.md](./topics/assignment_refill_and_batching.md) |
| perf load model | hot-path cost, storage counters, queue-like pressure | `TaskFlowLoadModelRunner`, `InMemoryTaskStorage`, result/release paths | `perf` via `xa-mass-testing` | [topics/perf_load_model.md](./topics/perf_load_model.md) |
| SDK transport harness | fast transport-aware runtime pressure without Boot shell | `MassSdkApplication`, `PullWorkerSession`, registered WebSocket workers | support lane for `perf` / `concurrency` / `E2E` | [topics/sdk_transport_harness.md](./topics/sdk_transport_harness.md) |
| websocket disconnect chaos | worker offline/online churn, delayed in-flight result after reconnect | `SdkWebSocketDisconnectChaosRunner`, WebSocket runtime sessions, SDK worker registration | `chaos` in `xa-mass-testing` | [topics/websocket_disconnect_chaos.md](./topics/websocket_disconnect_chaos.md) |
| lease-expiry redispatch chaos | disconnect without result, watchdog expiry, retry reset, takeover by another worker | `SdkWebSocketLeaseExpiryRedispatchChaosRunner`, `LeaseExpireWatchdog`, `TaskResultService.expireTaskMessage(...)` | `chaos` in `xa-mass-testing`, adjacent to `concurrency` | [topics/lease_expiry_redispatch_chaos.md](./topics/lease_expiry_redispatch_chaos.md) |
| Boot-shell E2E acceptance | real app-shell acceptance through `xa-mass-dev-app` | `MockApplicationSpringBootApp`, `AbstractMockE2eTest` | `Boot-shell E2E` | [../E2E_BASELINE.md](../E2E_BASELINE.md), [../INTEGRATION_TESTS.md](../INTEGRATION_TESTS.md) |
| CI lane placement and chaos | decide what belongs in PR gate vs nightly vs release | workflow policy and test taxonomy | `system-level` | [../TESTING_BASELINE.md](../TESTING_BASELINE.md) |

## 2. Entry Rule

When the touched code is clearly inside one topic:

1. start with the topic card
2. run that topic's primary command first
3. expand to adjacent lanes only if the change crosses ownership boundaries

When the touched code spans multiple topics:

- start with the topic that owns the final persisted task/message state
- then add the upstream dispatch or downstream release topic as needed
