# XA Mass Kernel JVM

Status: JVM Kernel owner-contract parity and selected owner provider
implementation.

This module mirrors the public owner boundary exported by
[`kernel_design.executable_spec.kernel`](../kernel_design/executable_spec/kernel/).
Python remains the mechanism oracle. JVM contracts use idiomatic camelCase but
preserve the Python method set, DTO fields, enum values, nullability, and key
score constants. Owner packages are `@NullMarked`; Python optional values are
spelled with explicit JSpecify `@Nullable` type-use annotations.

Package responsibilities:

| Package | Intended responsibility |
| --- | --- |
| `task` | `TaskRuntime` and `TaskResourceCatalog` contracts |
| `worker` | Worker runtime, catalog, and dynamic-attribute contracts |
| `score` | Task, TaskItem, and Worker score owner contracts |
| `assignment` | Candidate cache and warmup schedule contracts |
| `delivery` | WorkerCommand and WorkerResult runtime contracts |
| owner-local `redis` packages | Selected Redis implementations |

The current implemented provider subset is:

```text
TaskRuntime
  appendItems
  loadTaskItemSuccessResults

TaskResourceCatalog
  bounded descriptor reads

WorkerResourceCatalog
  WorkerGroup descriptor reads
  bounded random Worker descriptor samples

WorkerCommandRuntime
  point and bounded random batch consume

WorkerResultRuntime
  append
```

Every other translated operation is explicit and throws
`KernelOperationNotImplementedException` when invoked by a partial provider.
There are no default-method fallbacks.

The shared
[`kernel_owner_contract_manifest.json`](../kernel_design/executable_spec/kernel_owner_contract_manifest.json)
is test evidence only. Python generates and checks the semantic side; JVM
reflection checks the normalized Java side. It does not generate source and is
not an external protocol.

The external Runtime API belongs to [`server_jvm/`](../server_jvm/). Its
controllers and services depend on these owner contracts. Server assembly
chooses a Python HTTP provider, Java Redis provider, or explicit unimplemented
provider per operation. Provider selection never appears in HTTP controllers
or business services.

There is no TaskData runtime, combined WorkerDelivery runtime, Pacer,
scheduling policy, or Kernel application lifecycle in this module. Those
boundaries must be migrated through separate parity slices.

`kernel_jvm` targets JDK 21 and is not an Android module. A future
Android-compatible Worker SDK belongs in a separate Gradle module with its own
toolchain and API baseline. Shared Worker Delivery DTOs and codecs require a
separate Android-compatible contract module; the SDK must not depend directly
on this JDK 21 implementation module.

Build:

```text
./gradlew :kernel_jvm:build
```
