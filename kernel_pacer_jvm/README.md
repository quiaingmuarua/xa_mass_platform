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
the four production applications. The Runtime owns policy JSON interpretation,
one immutable HOT eligibility floor, thread startup/rollback, reverse bounded
shutdown and aggregate failure state. It never closes the supplied owners.

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

Shutdown uses one shared deadline in strict reverse order. The current thread
names, Redis shapes, score semantics, Pacer JSON and scheduling policy remain
defined by the Kernel mechanism documents; this module introduces no Pacer
SPI, dynamic registry, network API, Redis owner or fallback path.

Spring assembly belongs to `server_jvm`. Candidate Cache/Warmup,
ResultContextCodec and all Redis providers remain in `kernel_jvm`.

Build:

```text
./gradlew :kernel_pacer_jvm:build
```
