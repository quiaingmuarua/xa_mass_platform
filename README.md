# XA Mass Kernel

Status: current cross-module architecture and repository entrypoint.

XA Mass is organized around one hard boundary:

```text
Kernel    decides and converges scheduling
Server    exposes, validates, routes and correlates
Transport delivers and executes endpoint-local events
```

The visual projection is available in
[the human architecture overview](frontend/public/overview.htm).

## Authority And Dispatch

| Layer | Owns | Must not own |
| --- | --- | --- |
| Kernel | Task, TaskItem and Worker scheduling truth; selection; lease; claim; retry, recovery and result disposition | HTTP, physical connections, endpoint handlers and request waiters |
| Server | Runtime API; provider assembly; bounded use cases; Worker identity and Binding; Command-source routing; Result destination routing and correlation | Candidate selection, Worker lease, Item claim, retry policy and Task finality |
| Adapter | Command acquisition, current verified route, physical delivery and Adapter-local handlers | Scheduling eligibility, Task priority, Worker selection and result truth |
| Worker | Local full Event Name resolution, execution and Result evidence | Task lifecycle, scheduling policy and Adapter routing |

`dispatch` has three distinct meanings:

1. **Assignment dispatch** is Kernel resource allocation: scheduling evidence
   becomes an already-targeted `DeliveryCommand`.
2. **Authority routing** is Server owner routing: an existing TASK or
   DIRECT_CALL command is exposed to an Adapter, and a Result is routed to
   its TASK or SYSTEM owner.
3. **Event dispatch** is Transport-local invocation: an Adapter or Worker
   resolves an already-delivered Command to a fixed handler.

Only the first chooses resources. A useful review question is: is this code
deciding whether a Command should exist, or processing one that already
exists? Selection, timing, lease, claim, retry and convergence belong to
Kernel. Validation, source routing and request correlation belong to Server.
Network delivery and local invocation belong to Transport.

TASK follows:

```text
API -> Kernel Task/Item truth -> assignment dispatch -> targeted Command
    -> Server authority routing -> Adapter -> Worker -> Result
    -> Server TASK owner -> Kernel result routing
```

DIRECT_CALL follows:

```text
caller-selected target -> Server admission + bounded correlation
    -> Adapter target: Server-memory FIFO
    -> Worker target: non-overwriting offer to the shared Worker mailbox
    -> the same Adapter/Worker delivery path -> Server SYSTEM waiter
```

DIRECT_CALL bypasses Task scheduling because the caller already selected the
target. It does not require pause and provides no scheduling exclusion,
drain, preemption, reliable delivery or idempotency. A Worker Direct Command
fills only an empty field in the existing Adapter-partitioned Worker Command
Hash; a later TASK append may replace it until Adapter consumption. Once
consumed, neither authority recalls the Command. Server treats `messageType`
and opaque payload as event data; execution support is decided only by the
statically assembled Adapter or Worker Handler map. API Session authorization
is a future boundary, not an event whitelist inside DIRECT_CALL.

Hosts register short Extension capability names, while TaskItem, Direct Call
and Delivery always carry the full
`(platform|extension).(worker|adapter).<capability>` Event Name. Owner-local
`events.snapshot` handlers expose the immutable names loaded
by the current process; they do not replace WorkerGroup declarations or become
scheduling input. Compatible optional payload additions may keep a name;
incompatible semantics use a new name such as `.v2`, without aliases or
fallback lookup. The
[Transport Platform Event Catalog](transport/EVENTS.md) indexes only the
platform events owned by Transport; concrete Extension contracts remain with
their capability Owners.

Long-lived identity reports the Server-issued workerId route. The Adapter emits
best-effort `ADAPTER -> KERNEL` evidence for exact connected/disconnected Route
transitions. When the optional Kernel Worker Serviceability policy is enabled,
its Dispatch Pacer also writes coalesced, Adapter-partitioned probe requests as
loss and drift compensation. It derives the WorkerGroups it checks from a
bounded page of currently due `RUNNING_VISIBLE` Tasks and never modifies Task
score. Server constructs bounded `KERNEL -> ADAPTER`
connection-snapshot Commands only after higher-priority delivery sources and
transparently appends both Report forms to the Kernel evidence handoff. Only
the Kernel Result Pacer interprets them and asks the Worker score owner to
converge a scheduling coordinate; Server and Transport never mirror connection
state or write score. Evidence age, scan cadence, positive/negative mapping and
recovery limits are current Kernel policy, not cross-module contracts.

The Adapter may also retain the latest explicitly observed Worker properties
as a process-local timestamped projection. It is queried through an Adapter
DIRECT_CALL and is deliberately not copied into Server or Kernel truth.
Canonical Worker Properties are refreshed only when an explicit Worker start
performs one Prepare request; transparent Client reconnect sends only
connection identity. WorkerGroup declarations are create-only control-plane
resources, and Worker IDs remain Server-owned rather than Host-persisted.

The stable cuts are independent Task/TaskItem/Worker score owners, score as a
scheduling coordinate rather than a resource lock, separate assignment and
result-routing planes, the frozen Netty Adapter three-layer structure, and the
Worker Client/Transport/Run Controller split. JVM parity, finite Server use
cases and concrete endpoint handlers may evolve without moving those owners.

## Active Surfaces

- [`kernel_design/`](kernel_design/) is the Python executable mechanism oracle,
  current Kernel documentation and Redis proof surface.
- [`kernel_jvm/`](kernel_jvm/) mirrors public owner contracts and selected Java
  Redis providers, including the caller-driven Task commands used by Server,
  Worker delivery owners and the Candidate Cache owner.
- [`kernel_pacer_jvm/`](kernel_pacer_jvm/) is the Kernel-owned policy and
  lifecycle module. Its sole externally supported entry assembles fixed-lane
  Result Convergence and Task-source Dispatch Convergence over `kernel_jvm`
  owners. The stability order is `kernel_jvm > kernel_pacer_jvm > server_jvm`.
- [`server_jvm/`](server_jvm/) is the Spring Runtime API and incremental
  provider assembly. Task business HTTP terminates here. Server adapts the one
  Kernel Pacer Runtime lifecycle to Spring and owns Worker Identity,
  Endpoint Binding, bounded use cases, configured WorkerGroup seeds,
  registration-owned Managed Task assembly, and Adapter startup.
  Matched Runtime use cases use coarse `200/400/429/503` classes with detailed
  business codes; framework routing/protocol failures remain ordinary HTTP
  concerns, and Worker Delivery additionally retains its machine-protocol
  `202/204` successes. It does not start Worker processes. Python exposes no
  production HTTP surface.
- [`transport/`](transport/) contains the Java 11 delivery contract, Worker
  Core, Netty Adapter, Java Worker and Android Worker implementations.
- [`scenario_workers_jvm/`](scenario_workers_jvm/) is the independently launched
  finite JVM Scenario Worker Host. It owns the checked capabilities and
  persistent local Lab Worker assembly.
- [`xa-android/`](xa-android/) owns reusable Android capabilities, a loopback
  capability/Host control surface, the demo Worker host, and its Emulator
  acceptance driver.
- [`integrations/`](integrations/) contains external acceptance clients; it
  owns no Kernel, Server or Transport mechanism.
- [`frontend/`](frontend/) is the read-only Runtime viewer plus a thin finite
  Task file client, a best-effort single-Worker Direct Debug client over the
  public DIRECT_CALL API, a build-time Scalar API Reference and architecture
  overview host.
  It owns no scheduling, Worker identity or lifecycle truth.
  The [public UI demo](https://frontend-kylerrun-s-projects.vercel.app) uses the
  explicit Mock data source; real Runtime data remains available only from a
  running XA Mass Server on the same origin. Its
  [API Reference](https://frontend-kylerrun-s-projects.vercel.app/api-reference)
  renders the committed, CI-verified OpenAPI snapshot and cannot execute
  requests. A running Server continues to expose its live reference at
  `/scalar`.
- [`distribution/server/`](distribution/server/) packages those existing
  owners into one versioned Server Runtime ZIP. It adds no scheduling or
  Transport behavior and still requires external Redis.

Exactly one Java Server instance per Kernel Redis scope may run with
`xa.mass.kernel-pacer.enabled=true`. Additional API replicas must disable that
lifecycle; there is no distributed Pacer leader election. The enabled Server
owns the `xa.mass.redis` URL and scope used by its Java Result consumers and
the Java Assignment and Serviceability owners. Spring Profile selects
assembly and one fixed Java Pacer preset; Redis scope selects the persistent
data boundary. A Pacer preset cannot select a different Redis universe.

The shared Worker contracts are also published as one version-locked Worker
SDK Maven Repository ZIP containing the Delivery Contract, Worker Core,
Android Worker and Android Capability HTTP JAR/AAR artifacts. The superseded
Java platform exists only at the annotated
tag `legacy-java-platform-final-2026-07-24` and carries no compatibility
obligation.

The Server deployment boundary is publishable independently from Worker SDKs:
`xa-mass-server-runtime-<version>.zip` contains the Spring Boot Server, the
compiled frontend and the optional Scenario Worker Host. Start the Boot JAR
directly with Java 21, an explicit Spring Profile and the frontend path; the
Profile selects its checked Pacer preset. The packaged Gradle start scripts
launch only the finite JVM Lab Host; they are independent from Server.
AgentForge uses the clean Profile plus public APIs and consumes the matching
Worker SDK Release instead of copying XA Mass source modules.

Inside the source checkout, `python run_local_runtime.py` builds the frontend
and defaults to the complete `scenario-workers` Lab; `--profile agentforge`
starts only that clean Server/Pacer/Adapter assembly and no Scenario Worker
Host.

## Reading Path

1. This cross-module authority contract.
2. [Documentation Index](doc/README.md).
3. [Kernel Design Workspace](kernel_design/README.md).
4. [Proof Lanes](TESTING.md).
5. The owning module README for the area being changed.

Read [AGENTS.md](AGENTS.md) before changing behavior. Agent rules govern how
changes are made; they are not a second mechanism narrative.

## Verification

Verification is organized by owner invariant rather than coverage percentage.
Commands, prerequisites and CI selection rules live in
[TESTING.md](TESTING.md).

```text
python -m unittest discover -s kernel_design/executable_spec/tests
python -m compileall -q kernel_design/executable_spec

./gradlew --continue \
  :transport:worker-delivery-contract:build \
  :kernel_jvm:build \
  :kernel_pacer_jvm:build \
  :transport:worker-core:build \
  :transport:java-worker:build \
  :transport:netty-adapter:build \
  :scenario_workers_jvm:build \
  :server_jvm:build \
  :integrations:worker-capability-task:build \
  :integrations:worker-fleet-acceptance:build
```

Real Redis, cross-process Runtime Boundary, Worker Fleet, Capability Task, Android
Emulator, real-device Android, and frontend proofs have separate prerequisites
and commands in the proof registry. Strict owner/capability tests fix
structures and business values; cross-process acceptance fixes stable
identity, count, ownership, correlation, protocol-status, and restart
relationships without freezing evolving payloads.
