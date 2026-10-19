# Android Worker Proof

RPC Tasks are explicitly prepared with stable fixture IDs via
`POST /api/v1/tasks/{taskId}/rpc:prepare` during setup, before readiness/load
measurement. Project directory reads only expose bound Groups. Preparation does
not alter the lane's supply configuration, workload, deadlines or assertions.
Only the initial/active/baseline phase prepares the Task. Later fault and recovery
phases use that original identity without preparing or repairing it, including
the terminal phase which deliberately runs while Server is absent.

`:integrations:android-worker-proof` is the Java 21 proof owner for the real
Android Worker emulator lane. It drives both one Debug App and a fixed three-App
Lab topology through their loopback Host APIs and observes only public Runtime
APIs.

```text
Android Worker Proof
  -> Android device Host API
     -> local lifecycle, event list, active DELAY count
  -> Runtime API
     -> Adapter network projection
     -> Kernel scheduling projection
     -> Direct Call
     -> items:call and results:take
```

The module depends on the transport-neutral Delivery JSON contract only. It
does not import Android Worker, Server, Adapter, Kernel, Redis, or their
implementation types. The cross-platform launcher owns ADB, port mapping, Server and App
processes; Java owns workload, observation, assertions, and evidence.

## Correctness

The correctness proof uses a clean App identity and Redis scope. It closes one
Worker ID across Prepare, Adapter route, Properties observation, Kernel
serviceability, ten sequential `extension.worker.lab.delay` Task Items,
explicit stop/start, and App process restart. A separate thirty-second DELAY
then establishes an admitted Handler: the local stop HTTP call must return
within five seconds and its STOPPED snapshot must still show that Handler
running. The new run must reconnect and Probe successfully. This observation
budget is not a production latency SLA.

Single-Worker Task Items explicitly use `workerSelector` with
`{"executorName":"workerId","input":"<observed-worker-id>"}`, just like the
Triad's identity-targeted Items. The Group needs no Pool supply; an empty selector
object is not a valid query and must not be sent as an implicit default.

The ten Item identities and `SUCCEEDED` statuses are exact. The ten DELAY Result payloads remain opaque. A separate RPC digest witness
checks the existing Android capability content (MD5/hello) and records only the
message ID and whether the content matched. This lane does not claim throughput, concurrent Handler execution, or
device-matrix compatibility.

Task submissions use a 250 millisecond `items:call` observation window so the
accepted message identity returns well inside the HTTP request timeout. A
`NOT_OBSERVED` response is completed by passively waiting on `results:take`;
received success is retained by the proof client; the Harness never retries an ambiguously timed-out mutation request. A
`FAILED` snapshot is not counted as a successful witness; because late success
may replace failed, bounded observation continues until success or its
deadline.

## Convergence Health

The convergence proof establishes four separate mutations:

1. `extension.worker.lab.fail` returns `platform.worker.command.failed` with Worker diagnostics while the run and
   subsequent Probe remain usable.
2. A ten-second DELAY enters the Handler, then Adapter close-current closes the
   physical route. The same Worker ID reconnects and the accepted Task Item
   eventually has one visible success result.
3. A thirty-second DELAY enters the Handler, then the Android process is
   force-stopped. Network and Scheduling evidence leave their serviceable
   states; one explicit App start restores the same Worker ID and the original
   Task Item eventually has one visible success result.
4. Runtime Server absence exhausts the Client endpoint budget. The Worker stays
   `STOPPED` after Server restart until the device Host explicitly starts it,
   after which route and scheduling evidence converge again.

The local `activeDelayCount` proves only that the Demo Handler was entered. It
is not Adapter connectivity, Kernel state, or schedulability evidence.

## Phase ownership

| Scenario | Phase | External mutation | Baseline | Oracle |
| --- | --- | --- | --- | --- |
| Correctness | `initial` | none | none | 10/10 success and explicit stop/start |
| Correctness | `process-restart` | force-stop/start | `initial` | identity, Route and HOT recovery |
| Convergence | `active` | Adapter close-current | none | FAIL isolation and transparent reconnect |
| Convergence | `process-loss` | force-stop | `active` | Host, Network and Scheduling become unavailable |
| Convergence | `process-loss-recovery` | start App | `process-loss` | original Task and identity recover |
| Convergence | `terminal` | stop Server | `active` | endpoint exhaustion and local STOPPED |
| Convergence | `server-restart` | restart Server, then explicit Host start | `active` | no automatic start and explicit recovery |
| Triad | `baseline` / `outage` / `recovery` | force-stop/start `lab2` | `baseline` | partial outage isolation |

Java phases own Runtime calls, observations, assertions and Evidence. The launcher
performs each ADB or process mutation once after the phase emits its stable
checkpoint marker. A device-local state establishes that a local mutation
happened; Network and Scheduling projections remain independent oracles.

Temporary HTTP connection, read and request-timeout failures from observation
reads may be observed again until the phase deadline. Mutation requests are
issued once and an ambiguous transport failure fails the phase. Invalid JSON,
unexpected HTTP status, invalid state or identity drift fails immediately.
Defaults are 180 seconds for the whole phase and 5 seconds for one request,
with `requestTimeout <= maximumWait`. The launcher uses the packaged `consumer`
profile with DEFAULT Pacer, an explicitly declared `default` Project, the
consumer-owned Group JSON and the existing independent Worker-ID query. A due
RECOVERY recheck remains owned by the Pacer and Score Owner. Connected evidence restores HOT
polarity while preserving a still-current/future coordinate; it does not bypass
the due check. The [Serviceability retry contract](../../kernel_pacer_jvm/doc/dispatch/worker-serviceability-scheduling.md)
owns the recheck delay and Score time rules. The phase budget covers those
transitions, the final DELAY witness (up to 30 seconds), and bounded setup and
observation work. It is not reset for each observation and does not change
production retry timing.
A phase can exit at most one in-progress
request timeout after its maximum wait budget.

The active phase records the accepted DELAY identity, Handler checkpoint and
confirmed close mutation as they occur, so a later timeout retains the evidence
needed to locate the same Item. Success still requires its observed Result,
stable Worker identity, connected Route and HOT scheduling polarity.

## Three-application isolation

The supplementary Triad proof installs `lab1`, `lab2`, and `lab3` as three
different application IDs on the same emulator. They use independent Android
data sandboxes and Host ports while joining the same `android-demo-workers`
Group and WebSocket Adapter.

Triad Correctness closes three distinct worker IDs and one DELAY Item per
application. Each independent `workerId` query uses one known identity as its exact target;
the independently observed `worker.packageName` Property proves the App-to-
Worker relation but is not part of the Item Worker Selector. Triad
Convergence force-stops only `lab2`, proves
that `lab1` and `lab3` remain connected, schedulable, and executable, then
restarts `lab2` and requires its original worker ID to return. The Triad does
not repeat endpoint exhaustion, Server restart, or in-flight route-loss claims
owned by the single-Worker proof.

## Batch Checks finite consumer

The suite first runs `AndroidWorkerProjectPoolMain` in its own scope with `project-window-runtime.yaml` and only the Debug App.
The host Project expands `device-window` and digest feedback to the existing Android Group. Three fixed digest vectors traverse
raw finite creation/import/approval, the actual window query, complete Results/Item states and raw JSONL export. Runtime projection
observes positive window feedback for the single known Worker. This proves no strict quota, exact execution count, or dynamic
Android Group identity; Server/Boot's two-start Project membership proof owns new-Group expansion and exact feedback deduplication.

The complete suite also runs `AndroidWorkerBatchChecksMain` initial/restart phases
in an independent test scope with only the Debug App. Host YAML enables Batch
Checks and Any; one seed maps to one existing Android digest Item. No Simulator,
RPC submission or directed Worker query stands in for the finite Pool path.

Java verifies 123 text seeds, duplicate import, one 123-point owner debit, repeated
approval, terminal finite state, all 123 raw Results/Item states, bounded raw input
and business previews, and complete all/matched/unmatched CSV. Three fixed vectors
include Unicode; additional expected digests are computed independently. Raw input
preview remains bounded and is recorded as such. Complete counts come from known
identities and full CSV. Restart reads the retained Task, results, configuration
fingerprint and ledger without replaying mutations. Python owns process/ADB and
external configuration paths. Original correctness/convergence/triad assertions
and deadlines remain. Evidence contains IDs, counts, fingerprints and verdicts,
never complete seeds or result bodies.

## Entrypoints

Reuse an available API 33 development Emulator with no differently signed installations of
the four Demo package IDs. A signature conflict is an installation failure;
remove those test packages explicitly before a new acceptance run. App data is
reset only during initial scenario setup. Fault/recovery phases preserve the
same installed App, client key, Worker, Task and Item identities.

The Java tasks execute one phase because the launcher may need to change an
external process between phases:

```text
./gradlew :integrations:android-worker-proof:runAndroidWorkerCorrectness --args="..."
./gradlew :integrations:android-worker-proof:runAndroidWorkerConvergenceHealth --args="..."
./gradlew :integrations:android-worker-proof:runAndroidWorkerTriadCorrectness --args="..."
./gradlew :integrations:android-worker-proof:runAndroidWorkerTriadConvergenceHealth --args="..."
```

Stage the Java proof JARs with `:integrations:android-worker-proof:stageProofRuntime`.
The Python launcher runs those existing Java mains directly so each phase is an
owned process on both Windows and Linux. Pass `--android-serial`, `--runtime-archive`, `--sdk-archive`, expected
`--version`, full `--commit`, both `--runtime-sha256`/`--sdk-sha256`, `--apk-root`
and a fresh `--proof-root`. The input APKs must include `apk-provenance.json`
from the independent archive-consumption build. Preflight verifies all hashes
before ADB changes; there is no source-JAR fallback. The Host Python requires the Redis dependency
from `.github/scripts/requirements.txt`.

The complete API 33 proof is owned by:

```text
.github/scripts/run_android_emulator_worker.py
```

Evidence contains identities, states, outcome classes, established mutations,
and relation checks. It excludes Worker Properties, Task payloads, business
Results, screenshots, and video.

## Adding a claim

Before extending this lane:

1. Identify the Primary Owner and keep local mechanism behavior in its Owner
   test.
2. Add Emulator Proof only when the claim requires a physical Android process,
   data sandbox or network fact.
3. Keep API calls, oracle decisions and Evidence in Java.
4. Keep Launcher limited to ADB, port mapping and process mutation.
5. Use local Host state only to establish the mutation, never as Adapter or
   Kernel truth.
6. Update this scenario contract, the Proof Registry and proof-path selection
   when the claim boundary changes.

This lane deliberately does not prove dynamic Properties mutation end to end,
Doze/OEM background policy, a device matrix, Handler throughput or arbitrary
application counts. A future Properties proof must use a real owning surface;
it must not add a test-only Properties control API to the Demo Host.

## Verification

```text
./gradlew :integrations:android-worker-proof:test
python .github/scripts/build_android_consumer.py --help
python .github/scripts/run_android_emulator_worker.py --help
```

The real proof runs as `Android Worker Proof` in Proof CI on one API 33 x86_64
emulator. `Android APK Assembly` builds and uploads the Debug and three fixed
Lab APKs independently of `Android Host`; the Emulator consumes those APKs and the
same candidate ZIP pair. The APK job also runs consumer unit tests outside the checkout. The launcher starts one bounded Java process per phase without a Gradle
wrapper or shared daemon in its process tree. App, Server, Redis-scope and
evidence lifecycles remain the same. Before installing the Apps, the harness disables Android's
cached-app freezer and reboots the disposable Emulator so all three Lab
processes remain executable during the fixed topology proof. This is
test-environment control, not a background-liveness mechanism. The launcher
restores the original freezer settings and reboots after stopping its owned
processes and removing only its own ADB mappings and exact test scopes. Physical-device
vendor policy and background execution remain manual proofs.

## Consumer inputs and provenance

[XA Android](../../xa-android/README.md) owns the normal external-consumer entry.
This lane imports only its archive preparation and Server command construction.
It does not load `integrations/worker_proof_support/platform-runtime.yaml`.
Server uses ports 18082/18083 and `runtime-websocket`; the APK build explicitly
uses Runtime API 18082. User 1000/admin declares Task calls through public headers.
The caller's stable RPC Task ID is `xa-android-rpc-v1.android-demo-workers`.

`consumption.json` associates the expected commit, both ZIP hashes, actual Server
JAR and four resolved SDK hashes, four APK hashes, independent build provenance,
caller/Project/profile and exact suite scopes. Per-phase JSON and Java `.out`
files are uploaded with Server/filtered device logs for 30 days. Provenance does
not turn local Host state into Network or Scheduling truth.
