# Scenario RPC JVM

`scenario_rpc_jvm` is the Java 21 in-memory engine for the finite local Lab
Scenarios consumed by `server_jvm`.

It owns six fixed Scenario descriptors, line-to-Payload parsing, compact
Message IDs, one batch append, pending-only result polling, incremental result
sink delivery, input-order memory result assembly, and business result
validation. Its external actions are supplied through
`ScenarioRpcBatchExchange` and `ScenarioRpcResultSink`.

It does not own HTTP, files, Spring, Server assembly, Kernel operations, Redis,
Task or Worker lifecycle, concurrent call scheduling, persistent Scenario
state, retries, or recovery.

Verification:

```powershell
.\gradlew.bat :scenario_rpc_jvm:test
```
