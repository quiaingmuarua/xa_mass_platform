# Transport Result Routing Envelope Convergence Inventory

Status: current code inventory for
`TRANSPORT_RESULT_ROUTING_ENVELOPE_CONVERGENCE_ROADMAP.md`.

## Symbols

| Symbol | Current Owner | Caller / Module | Classification | Target |
| --- | --- | --- | --- | --- |
| `RoutingEnvelope` | `transport_api` | result producers, result inboxes, starter bridge | transport carrier | Keep as the single result-ingress queue/process-boundary carrier. |
| `RoutingTarget.resultIngress(...)` | `transport_api` | WebSocket/socket/pull result producers, `TaskResultCallbackCodec` | result routing target | Keep; target owner is `result-ingress`, not `engine`. |
| `TransportResultIngressChannel` | `transport_api` | adapters, embedded pull session, Redis/buffer implementations | producer seam | Accepts `RoutingEnvelope`; no result-specific carrier overload. |
| `TransportResultIngressHandler` | `transport_api` | buffer/pump to starter bridge | consumer seam | Handles `RoutingEnvelope`; task-result decode stays above transport. |
| `BufferedTransportResultIngressChannel` | `transport_runtime` | embedded/local result path | queue owner | Stores `RoutingEnvelope`. |
| `RedisTransportResultIngressChannel` | `transport_runtime` | split result inbox path | queue owner | Stores and claims encoded `RoutingEnvelope`. |
| `RoutingEnvelopeCodec` | `transport_runtime` | Redis result inbox | codec | Encodes target, payload, diagnostics, and creation time only. |
| `ClaimedTransportResultIngress` | `transport_runtime` | Redis poller / pump | claimed inbox value | Carries `RoutingEnvelope` plus claim ref. |
| `TransportResultIngressInboxPump` | `transport_runtime` | Redis result pump | relay | Passes claimed `RoutingEnvelope` to starter bridge and acks only after ackable outcome. |
| `WebSocketResultIngressFrameReader` | `websocket-adapter` | WebSocket inbound result frames | adapter producer | Recognizes result shell without parsing success; produces `RoutingEnvelope`. |
| `WebSocketInputProcessor` | `websocket-adapter` | WebSocket inbound processor | adapter producer | Submits `RoutingEnvelope` to `TransportResultIngressChannel`. |
| `SocketTransportFrameCodec` | `socket-adapter` | socket inbound frames | adapter-local protocol codec | Recognizes result shell without parsing success and returns opaque payload JSON. |
| `SocketTransportServer` | `socket-adapter` | socket inbound server | adapter producer | Produces `RoutingEnvelope`. |
| `EmbeddedPullWorkerSession` | `xa-mass-embedded-sdk` | embedded polling result submit | producer | Uses starter-owned codec to produce `RoutingEnvelope`. |
| `WorkerClientOperations.submitResult(...)` | `xa-mass-embedded-sdk` | SDK worker API facade | public worker API | Submits worker result request; does not own transport carrier shape. |
| `ExternalWorkerApiController.submitResult(...)` | `xa-mass-server` | external HTTP worker API | public/server boundary | Maps public request through SDK/starter result submission, not transport-specific DTOs. |
| `TaskResultCallbackCodec` | `xa-mass-embedded-sdk` starter | result bridge | starter-owned payload codec | Encodes `WorkerResultSubmission` to `RoutingEnvelope`; decodes payload and validates target owner ref. |
| `RuntimeTaskResultIngestChannel` | `xa-mass-embedded-sdk` starter | result bridge to engine | engine-facing handler | Handles `RoutingEnvelope` and returns `TransportResultIngressOutcome`. |
| `TransportResultIngressEnvelope` | removed | guard only | stale residue | Must not return in production code or tests as compatibility API. |
| `TransportResultIngressEnvelopeCodec` | removed | guard only | stale residue | Must not return. |

## Decisions

- Result ingress carrier is `RoutingEnvelope(target=result-ingress:<resultCorrelationRef>)`.
- The current task-result payload remains opaque callback JSON owned by
  `TaskResultCallbackCodec`.
- Adapter result recognition may read `resultCorrelationRef` and `eventCode`
  for shell classification, but must not require or validate `success`.
- Transport result inbox code must not parse `success`, `resultCode`, `result`,
  task id, message id, attempt id, lease token, retry policy, or finality.
- Diagnostics are bounded debug facts only and do not participate in routing,
  lifecycle, retry, or result correctness.

## Proof Surfaces

| Invariant | Current Proof |
| --- | --- |
| Routing target vocabulary includes `result-ingress` | `RoutingEnvelopeTest` |
| In-memory and Redis result inboxes store `RoutingEnvelope` | `BufferedTransportResultIngressChannelTest`, `RedisTransportResultIngressChannelTest` |
| Starter bridge decodes payload and validates correlation consistency | `TaskResultCallbackCodecTest`, `RuntimeTaskResultIngestChannelTest` |
| WebSocket/socket adapters do not parse result success for shell recognition | `WebSocketFrameReadersTest`, `WebSocketInputProcessorTest`, `SocketTransportFrameCodecTest`, `SocketTransportServerTest`, `TransportConvergenceArchitectureGuardTest` |
| Old transport result carrier is gone | `TransportConvergenceArchitectureGuardTest` plus source scan |
