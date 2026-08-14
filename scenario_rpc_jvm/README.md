# Scenario RPC JVM

`scenario_rpc_jvm` is the Java 21 in-memory engine for the finite local Lab
Scenarios consumed by `server_jvm`.

It owns six fixed Scenario descriptors, line-to-Payload parsing, caller-bounded
virtual-thread RPC calls, input-order result assembly, compact Message IDs, and
business result validation. Its only external action is the caller-provided
`ScenarioRpcCall` function.

It does not own HTTP, files, Spring, Server assembly, Kernel operations, Redis,
Task or Worker lifecycle, persistent Scenario state, retries, or progress.

Verification:

```powershell
.\gradlew.bat :scenario_rpc_jvm:test
```
