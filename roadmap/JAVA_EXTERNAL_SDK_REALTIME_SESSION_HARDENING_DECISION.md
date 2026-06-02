# Java External SDK Realtime Session Hardening Decision

Status: active deferred SDK decision.

This short decision note replaces the implemented realtime protocol roadmap in
the active roadmap directory. The implemented record is archived at
`../doc/archive/sdk/2026-06-02_JAVA_EXTERNAL_SDK_REALTIME_PROTOCOL_ROADMAP.md`.

Current decision:

- Polling remains the stable first external worker session.
- The Java SDK owns public polling and WebSocket worker sessions plus the
  transport-neutral worker handler runtime.
- Socket is not yet a first-class Java SDK session.
- Android host support is not part of the pure Java SDK.
- A shared `RealtimeWorkerSession` abstraction is deferred until at least two
  realtime transports share a proven public lifecycle.
- Worker-pack may consume SDK handler/session APIs, but worker-pack
  command/fault behavior must not become public SDK behavior.

Open hardening topics:

- queued-result close semantics
- reconnect terminal outcomes
- queue-full behavior
- WebSocket result idempotency under reconnect
- socket and Android host decisions
- worker-pack convergence as an SDK consumer, not an SDK dependency

Verification before promotion:

```powershell
mvn -pl sdk/xa-mass-java-sdk test
mvn -pl xa-mass-server -am "-Dtest=JavaScenarioLauncherBlackBoxIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```
