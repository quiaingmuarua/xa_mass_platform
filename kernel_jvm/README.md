# XA Mass Kernel JVM

Status: empty Kotlin/JVM production implementation scaffold.

This module will implement the contracts proven by
[`kernel_design/`](../kernel_design/). It currently contains no kernel
behavior and does not preserve the superseded Java architecture.

Package responsibilities:

| Package | Intended responsibility |
| --- | --- |
| `core` | Owner contracts, stable values, and score primitives |
| `constraintdsl` | Allocation-rule compilation and evaluation |
| `scheduling` | Bounded policy and Pacer orchestration |
| `redis` | Redis-backed owner implementations |
| `assembly` | Process composition, lifecycle, and external clients |

These are packages inside one Gradle module, not promises of future Gradle
modules. Add code only through a parity slice that identifies the corresponding
Python owner contract and tests.

The external Runtime API belongs to [`server_jvm/`](../server_jvm/) and does
not depend on this module while the Python kernel remains the runtime owner.

`kernel_jvm` targets JDK 21 and is not an Android module. A future
Android-compatible Worker SDK belongs in a separate Gradle module with its own
toolchain and API baseline. Shared Worker Delivery DTOs and codecs require a
separate Android-compatible contract module; the SDK must not depend directly
on this JDK 21 implementation module.

Build:

```text
./gradlew :kernel_jvm:build
```
