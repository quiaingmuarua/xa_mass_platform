# XA Mass Kernel Pacer JVM

Status: Kernel-owned production scheduling policy and finite Pacer lifecycle.

This internal Java 21 module contains the faster-moving policy layer over the
stable owner contracts in [`kernel_jvm`](../kernel_jvm/). It is loaded into the
Server Boot JAR as an ordinary dependency; it is not deployed, published or
started independently.

The dependency direction is fixed:

```text
server_jvm -> kernel_pacer_jvm -> kernel_jvm
```

## Public Boundary

`com.xa.mass.kernel.pacer.KernelPacerRuntime` is the only public top-level
type. Its `assemble(...)` method accepts the bounded mechanical owners needed by
the four production applications plus one of its four checked `PolicyPreset`
values: `DEFAULT`, `SERVICEABILITY_DEFAULT`, `SCENARIO_LAB`, or
`RUNTIME_BOUNDARY_PROOF`. The Runtime
owns fixed policy selection, one immutable HOT eligibility floor when
Serviceability is enabled, thread startup/rollback, reverse bounded shutdown
and aggregate failure state. It never closes the supplied owners.

All Assignment, Result Routing and Serviceability implementation types are
package-private. Their source files remain grouped under `assignment/`,
`result/`, `serviceability/` and `internal/`, but share the Runtime package so
Java package visibility enforces the single-entry boundary without reflection
or public factory types.

## Fixed Applications

Startup order:

```text
Result Routing
-> Worker Serviceability Result       optional
-> Worker Serviceability Dispatch     optional
-> Assignment Dispatch
```

Result Routing consumes exactly two Task result lanes in order: `SUCCESS`,
then `FAILURE`. Server validates endpoint-owned outcome codes and selects the
lane; the Pacer does not read `DeliveryReport.outcomeCode`. SUCCESS stores and
promotes the Item before its narrow completed-HOT release, while FAILURE only
releases the exact assignment lease and never changes Worker polarity.

Shutdown uses one shared deadline in strict reverse order. `DEFAULT` keeps
Serviceability disabled, `SERVICEABILITY_DEFAULT` enables it at the normal
production cadence, `SCENARIO_LAB` is the checked fast local-Lab policy, and
`RUNTIME_BOUNDARY_PROOF` is the deterministic boundary-proof policy. Server
accepts that proof-only preset only with a `test_*` Redis scope. These presets
compose existing configuration value objects; there is no Java policy file,
per-field runtime tuning, Pacer SPI, dynamic registry, network API, Redis owner
or fallback path.

Spring assembly belongs to `server_jvm`. Candidate Cache/Warmup,
ResultContextCodec and all Redis providers remain in `kernel_jvm`.

Build:

```text
./gradlew :kernel_pacer_jvm:build
```
