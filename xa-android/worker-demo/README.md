# Android Worker Demo

`:worker-demo` is the installable Android Host for the XA Mass
local Lab. It composes existing owners instead of copying their mechanisms:

```text
AndroidWorkerDemoApplication
  -> AndroidWorker
     -> persistent client key, Prepare, WebSocket Transport
  -> AndroidDemoCapabilities
     -> extension.worker.android.state.read
     -> extension.worker.android.battery.read
     -> extension.worker.android.string.digest
  -> AndroidWorkerLabEvents
     -> extension.worker.lab.delay
     -> extension.worker.lab.fail
  -> AndroidMessageDemo (Worker sending only)
     -> extension.worker.message.send
     -> bounded local acceptance and original Reporter associations
  -> AndroidMessageNotifications / MessagesActivity / MessageReplyReceiver
     -> local notification, opened message, native quick reply
  -> AndroidCapabilityHttpServer
     -> build-variant loopback port
     -> the same business Definitions
     -> local-only host.snapshot / host.start / host.stop / host.messages.read Definitions

MainActivity
  -> observes WorkerLifecycle
  -> observes capability Snapshot
```

The independently built App depends on the published `xa-mass-android-worker`
and `xa-mass-android-capability-http` artifacts plus its local `:capabilities` module. It does not
depend on Server, Kernel, Adapter, Redis, Scenario Workers, or Task
implementation code.

## Runtime behavior

The Application creates and starts one `AndroidWorker` for
`android-demo-workers`. Android Worker owns the long-lived `clientWorkerKey`
and WorkerGroup coordinate and performs one Prepare per explicit start. Its
[Android Client](../../transport/android-worker/README.md#platform-resources)
owns transparent reconnect within that run. The platform-issued
Worker ID is held only for the current run and resolved again by Server from
the same client key on a later start. The App stores no Worker ID or Endpoint
URI and does not interpret network connection state as Worker online truth.

The Application separately owns `AndroidDemoCapabilities` and
`AndroidWorkerLabEvents`. It passes the three Android capability Definitions and
the two fixed Lab Definitions into both `AndroidWorker.create(...)` and the
local Capability HTTP server. Message sending is added only to the Worker;
HTTP additionally receives the bounded read-only message evidence Definition.
It appends three fixed Host lifecycle Definitions
only for HTTP assembly; those Definitions are never passed to `AndroidWorker`,
never appear in `platform.worker.events.snapshot`, and never become WorkerGroup
capability truth. HTTP startup failure is diagnostic-only and does not prevent
the Worker from starting. The Activity only observes Worker lifecycle,
capability state, and the fixed probe endpoint while visible.
Leaving the Activity stops neither owner; Android may still kill the process
because this demo installs no Service or WorkManager.

## Local message interaction

The independent Messages deployment uses the existing `android-demo-workers`
identity and `extension.worker.message.send` five-field protocol. Device Properties
include `messaging.enabled="true"`, `country=CN`, and a simulated sender phone
distinct for Debug/Lab variants. Host configuration separately enables the
Messaging Pool/query; neither these Properties nor Group registration creates stock.
This App does not reference or call `worker_simulator_jvm`, its Lab HTTP, or Lab JSON.

`AndroidMessageDemo` owns one short state gate, 1,000 acceptance records, at most
256 original `WorkerOutcomeReporter` references, and a 100-entry recent view.
Acceptance and association windows are ten minutes from first acceptance using
elapsed time; repeats and interactions never renew them. Each operation expires
at most 100 oldest records and validates any directly addressed record's deadline.
Known identical sends return their immutable first SENT snapshot; conflicting
inputs and full acceptance storage fail before mutation. Full Reporter capacity
still permits accepted sends. No maintenance thread, database or automatic retry
is added. Worker stop/start preserves live dedup entries but revokes associations;
process replacement loses both. This is not cross-device or durable exactly-once.

The channel commits acceptance before returning SENT. UI notification/observer
failures cannot undo it. The receipt callback captures the original Reporter under
the gate and calls it outside; stop may revoke the run while an admitted callback
finishes. The SDK run fence is authoritative; lifecycle observations only clean
local references. No replacement run receives an old association.

**本地消息演示** opens the recent inbox. Opening an actual detail reports READ;
in-app reply and Android `RemoteInput` quick reply report REPLIED. Each action uses
an opaque process-local token in an explicit Intent data URI, not only extras or
a lossy numeric message hash. A reply has one generated request ID and is never
automatically resent. Reporting success means local acceptance only; the webpage
must refresh its Server result. Stopped, missing and expired associations cannot
create messages or recover Reporters.

One low-importance notification represents the latest unhandled message. UI
refreshes are coalesced. Android 13+ notification permission is requested by the
visible App; denied/disabled notifications do not block acceptance or in-app
interaction. No notification publication reports DELIVERED. No SMS permission,
SIM card, Service or WorkManager is used. New process presentation cancels this
feature's old notification; `Application.onTerminate()` is not a cleanup guarantee.

The local-only `extension.worker.android-demo.host.messages.read` accepts
`{"messageIds":[...]}` with 1..100 known IDs. It returns `accepted` evidence
(campaign/message/Worker/phone, first `observedAtMillis`, request fingerprint and
receipt availability) plus `missing`. It never returns body/recipient content
or mutates Task stages. The fingerprint is lowercase SHA-256 over four-byte
big-endian length-prefixed UTF-8 values in order: campaignId, messageId, country,
recipientId, body. Missing means not retained here, not proof of non-execution.
Messages sending is absent from `/events`; the normal Task path is required.

The State command returns package/device data plus a persistent local counter.
The Battery command reads `BatteryManager` once per command and returns
availability, capacity percentage, and charging state. It does not install a
battery receiver or background monitor. Both handlers execute synchronously on
the Worker's serialized callback lane.

`extension.worker.lab.delay` strictly accepts
`{"delayMillis":1..30000}` and blocks the current Handler before returning JSON
`null`. `extension.worker.lab.fail` strictly accepts `{}` and produces Worker
outcome `3303` without stopping the run. The Host snapshot exposes
`activeDelayCount` only to establish that DELAY entered the Demo Handler; this
local count is not route, Kernel, or scheduling truth.

The ordinary application ID remains:

```text
com.xa.mass.integration.androidworker
```

The Java namespace is `com.xa.mass.android.workerdemo`. Keeping the
application ID, WorkerGroup ID, Android Worker preference name, and capability
preference name allows an upgrade install to preserve the existing Worker ID
and counter. If the Server Identity registry is reset independently, clear App
data before registering a replacement identity:

```powershell
adb shell pm clear com.xa.mass.integration.androidworker
```

The API 33 proof also builds three fixed Debug-derived Lab variants:

| Variant | applicationId | loopback Host port |
| --- | --- | ---: |
| `lab1` | `com.xa.mass.integration.androidworker.lab1` | 18184 |
| `lab2` | `com.xa.mass.integration.androidworker.lab2` | 18185 |
| `lab3` | `com.xa.mass.integration.androidworker.lab3` | 18186 |

They keep the same WorkerGroup and Runtime endpoints. Separate application
sandboxes provide separate persistent client keys. The proof targets each
observed Worker ID directly and independently checks its `packageName` Property
against the application ID. This is a fixed proof topology, not a general
replica facility.

## Device-local capability and Host API

The Debug App owns probe port `18084`; each Lab variant uses the port in the
table above. The selected value is passed to the reusable HTTP module. It
listens only on the device loopback address. This path does not require Redis,
the platform Kernel, Java Server, or an Adapter. Install and open the App, then
forward a host loopback port to the device:

```powershell
adb forward tcp:18084 tcp:18084
curl.exe http://127.0.0.1:18084/health
curl.exe http://127.0.0.1:18084/events
curl.exe -X POST `
  -H "Content-Type: application/json" `
  -d "{}" `
  http://127.0.0.1:18084/events/extension.worker.android.state.read:call
curl.exe -X POST `
  -H "Content-Type: application/json" `
  -d "{}" `
  http://127.0.0.1:18084/events/extension.worker.android.battery.read:call
curl.exe -X POST `
  -H "Content-Type: application/json" `
  -d '{"algorithm":"MD5","value":"hello"}' `
  http://127.0.0.1:18084/events/extension.worker.android.string.digest:call
curl.exe -X POST `
  -H "Content-Type: application/json" `
  -d "{}" `
  http://127.0.0.1:18084/events/extension.worker.android-demo.host.snapshot:call
```

These calls update the same processed-command count and last-event observation
shown by the App because HTTP and Worker delivery share one capability
instance. The fixed `extension.worker.android-demo.host.snapshot`, `.start`
and `.stop` Events expose only Demo Host lifecycle control and observation.
They reuse the same finite HTTP dispatcher but are not Worker capabilities.
Local business calls prove capability execution only; the Emulator acceptance
relates them to the real Worker transport separately. Remove the forwarding
rule when finished:

```powershell
adb forward --remove tcp:18084
```

## Manual consumer run

Use the [dual ZIP consumer instructions](../README.md#normal-runtime-and-app-startup)
for archive validation, the independent build, explicit Group initialization,
RPC preparation and the real digest result. The default consumer uses Runtime
ports `18182/18183` and the `DEFAULT` Pacer preset. Registering the Group does
not create an RPC Task. Bind every ADB command to the selected device serial.

The complete API 33 Emulator acceptance uses separate `18082/18083` ports and
its own APK build parameter. Its [Integration Owner](../../integrations/android-worker-proof/README.md)
defines all Java phases and the portable Python invocation. Java retains the
RPC digest payload in memory to check its exact content; saved evidence retains
only identity and assertion outcomes.

## Hosted Emulator proof

`Android Worker Proof` is the primary Android platform E2E lane in Proof CI.
It reuses the Debug and three Lab APKs produced by `Android APK Assembly`, boots
one API 33 x86_64 Emulator, and uses ADB only to
install/start/force-stop the Apps and map shared Runtime ports plus the four
distinct Host ports. All device-side Worker controls use the three
local Host Events above. The Java 21 `:integrations:android-worker-proof`
module owns workload, assertions, and evidence; the Python launcher owns only Emulator,
ADB, Server, App, and Redis-scope lifecycles.

Correctness proves:

1. one Worker ID closes Prepare, route, Properties, Probe, and Scheduling;
2. a separate RPC digest returns the expected content, and ten sequential DELAY Task Items return ten exact `SUCCEEDED` statuses;
3. explicit stop/start retains identity and restores the route;
4. App process restart retains identity and completes another DELAY Item.

Convergence Health proves:

1. FAIL produces Worker `3xxx` while the run and a later Probe remain usable;
2. an in-flight DELAY plus Adapter close-current eventually converges to one
   visible Task success and a connected route for the same Worker ID;
3. Server absence exhausts the Client endpoint budget and leaves the Worker
   `STOPPED`;
4. Server restart does not automatically Prepare; one explicit local start
   restores the original identity, route, Scheduling state, and DELAY witness.

The fixed Triad supplement proves:

1. three application IDs establish three distinct Worker identities in one
   WorkerGroup;
2. a `workerId` query directs one DELAY Item to each observed identity, with
   `packageName` checked independently as App-to-Worker evidence;
3. force-stopping `lab2` does not remove service from `lab1` or `lab3`;
4. restarting `lab2` restores its original worker ID and scheduling service.

The disposable API 33 Emulator runs this topology with the cached-app freezer
disabled. The setting prevents Android process-freezing policy from replacing
the Worker isolation mechanism under test; it does not make a background
execution or process-survival claim for production hosts.

The process-restart proof does not relaunch the App as soon as the Adapter
route becomes disconnected. It first observes the stopped Worker leave the
HOT scheduling projection, then relaunches and requires a fresh due-HOT
observation before submitting the Task call. This phase fence prevents stale
pre-restart HOT evidence from being promoted to schedulability.

The terminal phase does not treat Server process exit as immediate Worker
termination. It waits for the Android Client's finite unstable-connection
budget to exhaust before the Worker reaches `STOPPED`. With the current
defaults this includes up to nineteen 500 ms reconnect delays plus the actual
connection-attempt durations; the connect timeout applies to each attempt, not
once to the complete retry sequence. Changes to the Client retry policy must
therefore be reviewed together with the workflow's
`--maximum-wait-millis` budget.

It asserts IDs, states, Event names, message IDs, counts, established mutations,
and cross-stage relations. Evidence excludes full Properties, business
Payloads, opaque Results, screenshots, and video. The original lifecycle phases
do not automate UI. The Messages supplement drives the actual message page and
notification reply controls without saving UI hierarchies or business content.

## Verification

Run from the independent consumer build with the explicit verified SDK properties described in the [consumer entry](../README.md#pinned-inputs-and-preparation).

```powershell
.\gradlew.bat :worker-demo:testDebugUnitTest
.\gradlew.bat :worker-demo:assembleDebug
.\gradlew.bat :worker-demo:assembleLab1
.\gradlew.bat :worker-demo:assembleLab2
.\gradlew.bat :worker-demo:assembleLab3
```

Platform library and Java proof tests run from the platform root instead:

```powershell
.\gradlew.bat :android-capability-http:testDebugUnitTest
.\gradlew.bat :transport:android-worker:testDebugUnitTest
.\gradlew.bat :integrations:android-worker-proof:test
```

`minSdk 24` is the build baseline. Hosted CI runs both the lightweight Android
Host contract lane and the path-selected API 33 Worker Proof lane. The
documented real-device flow remains the manual proof for vendor behavior,
physical Battery readings, and background execution limits.
