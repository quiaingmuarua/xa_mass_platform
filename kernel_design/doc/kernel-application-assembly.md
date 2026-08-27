# Kernel Application Assembly

Status: active Kernel application and production lifecycle contract.

## Two Deliberately Separate Assemblies

Production is Java-only:

```text
Java Server
  -> KernelPacerAssembly                  Spring lifecycle adapter
     -> KernelPacerRuntime                kernel_pacer_jvm
        -> ResultRoutingApplication
        -> WorkerServiceabilityResultApplication       optional
        -> WorkerServiceabilityDispatchApplication     optional
        -> AssignmentDispatchApplication
           -> TaskWorkerAllocationPacer
           -> TaskRunningActivationPacer
           -> TaskDispatchPacer
```

The Python executable specification remains the standalone mechanism Oracle:

```text
python -m kernel_design.executable_spec.assembly --config <path>
  -> KernelApplication
     -> complete Python Result, Serviceability and Assignment mechanisms
```

A minimal isolated Oracle configuration is:

```json
{
  "redis": {
    "url": "redis://127.0.0.1:6379/15",
    "scope": "test_oracle_local"
  },
  "workerServiceability": {}
}
```

The Oracle exposes no HTTP surface, managed-child mode or selectively disabled
production mode. It is not packaged in the Server Runtime. Its required config
must select an explicit isolated `test_*` Redis scope; the CLI rejects
`profile_*` scopes before constructing the application.

## Production Configuration

Spring owns only the finite lifecycle envelope:

```yaml
xa.mass.kernel-pacer:
  enabled: true
  preset: DEFAULT
  shutdown-timeout: 5s
```

`kernel_pacer_jvm` owns exactly four checked presets:

| Preset | Assembly |
| --- | --- |
| `DEFAULT` | Ordinary Server and AgentForge; Serviceability disabled |
| `SERVICEABILITY_DEFAULT` | Normal production cadence with Serviceability enabled |
| `SCENARIO_LAB` | Fast Scenario, Capability Task and Android Lab policy |
| `RUNTIME_BOUNDARY_PROOF` | Test-only policy with accelerated boundary Serviceability |

Server passes the selected enum to `KernelPacerRuntime.assemble(...)` and does
not interpret scheduling policy. It rejects the proof-only preset unless the
configured Redis scope starts with `test_`. Unknown preset names fail Spring
binding before Runtime construction. There is no Java production Pacer JSON,
dynamic preset or per-field override. The Redis URL and scope remain
exclusively in `xa.mass.redis`; a preset cannot select a second Redis universe.
The Python executable Oracle still consumes its own required JSON configuration
and an isolated `test_*` scope; that format is not a Java production contract.

When a selected preset enables Serviceability, Runtime assembly mints one
Worker-Score-slot-aligned `hotEligibilityFloorMillis`. The same immutable floor
is injected into Serviceability Result, Serviceability Dispatch and Assignment
candidate acquisition. It is not written to Redis or exposed through health or
Runtime APIs. When Serviceability is disabled, no floor exists and Assignment
uses the full HOT due range.

## Owner Wiring

The Java production closure is caller-driven and finite:

```text
TaskRuntime / TaskResourceCatalog
TaskScoreBandCore / TaskItemScoreBandCore
WorkerRuntime / WorkerResourceCatalog / WorkerScoreCore
CandidateWorkerCache / CandidateWarmupSchedule
WorkerCommandRuntime / TaskResultRuntime
WorkerServiceabilityRuntime
```

Each Redis provider owns only its documented keys and operations. Pacers
compose bounded owner calls; they do not bypass owners, decode opaque scores,
or merge Task, TaskItem, Worker, Candidate and Delivery truth.

The module boundary is:

```text
kernel_jvm
  mechanical owner contracts, Redis providers, Candidate hints and codecs

kernel_pacer_jvm
  policy, matching, Pacer loops, configuration and finite thread lifecycle

server_jvm
  preset selection, owner wiring, Spring lifecycle delegation and Health
```

Java implements the production caller closure. Operations without a Java
production caller remain explicit `KernelOperationNotImplementedException`
gaps rather than no-op or remote fallbacks. Python retains the complete Oracle
implementation for parity and independent mechanism proof.

## Assignment Dispatch

`AssignmentDispatchApplication` owns exactly three non-daemon threads:

```text
assignment-dispatch-worker-allocation
assignment-dispatch-running-activation
assignment-dispatch-task-dispatch
```

Each loop executes immediately after start and then waits interruptibly for its
configured interval. A `RuntimeException` fails one round and the loop
continues. A JVM `Error` or unexpected thread exit fails the Application and
therefore Kernel readiness.

The three loops preserve the Python Oracle mechanisms:

- Worker Allocation consumes due warmup hints, validates RUNNING suffix-zero
  Tasks, acquires and exactly leases bounded HOT candidates, publishes the
  disposable Candidate cache, and requeues incomplete warmups.
- RUNNING Activation reads due ADMISSION Tasks, applies due-Item and RUNNING
  soft-limit policy, transitions accepted Tasks to RUNNING suffix zero,
  reschedules others by priority bucket, and emits PRECOMPUTED warmup hints.
- Task Dispatch reads due RUNNING Tasks, finalizes exhausted/expired Items,
  acquires and exactly leases Workers, exactly claims Items, constructs
  `TASK -> WORKER` Commands, appends them by Adapter, then applies normal
  RUNNING pacing or the declared idle disposition.

Candidate caches and warmup schedules are hints. Exact Worker lease and
TaskItem claim operations remain the concurrency fences. Command publication
failure does not roll back claims or Worker leases; their existing expiries
provide recovery.

## Lifecycle

With Serviceability enabled, startup order is:

```text
Result Routing
-> Worker Serviceability Result
-> Worker Serviceability Dispatch
-> Assignment Dispatch
```

Without Serviceability, only Result Routing and Assignment Dispatch start.
Startup failure rolls back every already-started Application in reverse order.

Shutdown signals every loop and uses one shared deadline in exact reverse
startup order. An Application must not reset the remaining budget for each
thread. `KernelPacerRuntime` reaches `RUNNING` only after every required Java
Application starts. Any required loop death moves its aggregate state to
`FAILED`; `KernelPacerAssembly` exposes that existing state to Spring without
maintaining a second lifecycle state machine.

Spring readiness requires:

```text
KernelPacerRuntime RUNNING through KernelPacerAssembly
+ Kernel Redis UP
```

Liveness covers the JVM process and remains UP for a Pacer failure so an
external orchestrator can distinguish process death from Kernel unavailability.
Health exposes only safe lifecycle states; it does not expose Redis coordinates,
policy content, HOT floor, payloads or results.

Exactly one Server per Redis scope may enable this lifecycle. There is no
distributed Pacer leader election. Other API replicas must set
`xa.mass.kernel-pacer.enabled=false`.

## Python Oracle CLI

The Oracle CLI accepts only a policy config path and log level. It builds the
complete Python `KernelApplication`, starts every configured mechanism, blocks
on stdin, and stops in reverse order on EOF or interruption. This CLI is for
executable-spec and Redis parity work only.

Python continues to own:

- the mechanism trust source under `executable_spec/`;
- focused and integration tests;
- independent real-Redis parity fixtures.

Python does not own:

- production process lifecycle or readiness;
- production Task HTTP or Worker Delivery HTTP;
- a Server fallback or remote owner adapter;
- a wheel, venv, launcher or other Server Runtime artifact.

## Proof Boundary

The Runtime Boundary lane starts one Java Spring context and real Redis, with
no Python process. It proves:

```text
Task API
-> Java Task owners
-> Java Assignment Dispatch
-> Java Worker Delivery and Worker execution
-> Java Result Routing
-> TaskItem finality + result + exact Worker release
```

The same lane proves the Adapter snapshot request/evidence path through the two
Java Serviceability applications and the shared HOT floor. Redis Owner tests
prove Java/Python shape compatibility, exact CAS and range ordering. The Python
suite remains the independent Oracle proof.

Deterministic policy and four-stage lifecycle tests live in
`kernel_pacer_jvm`; `kernel_jvm` tests remain focused on owner contracts,
providers, Candidate hints and codecs. Server tests prove only Spring
delegation, Health projection and absence of individual Pacer beans.

## Guardrails

- Do not restore a Python HTTP host, managed child, ready/owner file protocol,
  production wheel, venv or launcher.
- Do not add a dynamic Pacer registry, public policy SPI or fallback owner.
- Do not expose package-private Pacer/Application types or assemble them from
  Server; `KernelPacerRuntime` is the only public Pacer-module entry.
- Do not allow two Pacer implementations for the same function in one Redis
  scope.
- Do not move candidate selection, Worker lease, TaskItem claim, retry,
  recovery or Task finality into Server.
- Do not decode or synthesize score structure outside its score owner.
- Do not log opaque payloads, results, allocation rules or ResultContext.
