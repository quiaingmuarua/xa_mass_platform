# XA Android external consumer

Status: current Runtime ZIP and Worker SDK ZIP reference consumer.

`capabilities` and `worker-demo` form an independent Gradle build. They consume
only the verified SDK Maven repository, with no platform source substitution.
AgentForge and this App use the same public Runtime and Worker SDK contracts.
The platform still owns and publishes `capability-http`; its root-build task path
is `:android-capability-http`, explicitly mapped to this directory's producer.
It is not included in the standalone consumer build.

Day-to-day App, SDK and authentication development uses a local Server/Adapter
and the existing Owner tests and CI proofs. Gateway behavior can be exercised
with a local gateway and a normally trusted test CA when that boundary is under
test; certificate and hostname validation remain enabled. GCP access and live
credentials are not prerequisites for an ordinary development task or PR. Public
provisioning adds no Computer Use gate; UI changes still require their affected
local interaction checks. Public deployment acceptance is selected only when explicitly requested.

## Pinned inputs and preparation

Java 21, Python 3.11+, Android SDK 36 and the pinned Gradle wrapper are required.
Third-party dependencies resolve through Google and Maven Central; only
`com.xa.mass` is exclusively resolved from the supplied SDK ZIP.

Obtain the two ZIPs and `candidate.json` from the same successful candidate run.
A published release instead supplies the version, complete commit and both
asset SHA256 values through the consuming project's own lock. This entry does
not download releases or manufacture release pins.

From this directory, the following PowerShell preparation validates the pair and
creates a caller-owned installation. Replace the three input paths first:

```powershell
$candidate = Get-Content '<candidate.json>' -Raw | ConvertFrom-Json
$runtime = '<Runtime ZIP>'
$sdk = '<SDK ZIP>'
$installation = Join-Path $env:TEMP 'xa-consumer-installation'
python .\run_consumer.py --runtime-archive $runtime --sdk-archive $sdk --version $candidate.version --commit $candidate.gitCommit --runtime-sha256 $candidate.archives.runtime.sha256 --sdk-sha256 $candidate.archives.sdk.sha256 --work-dir $installation --prepare-only
$sdkRoot = (Get-Content (Join-Path $installation 'consumption.json') -Raw | ConvertFrom-Json).sdkRoot
.\gradlew.bat --no-daemon "-PxaMassSdkRoot=$sdkRoot" "-PxaMassVersion=$($candidate.version)" "-PxaMassCommit=$($candidate.gitCommit)" :capabilities:testDebugUnitTest :worker-demo:testDebugUnitTest :worker-demo:assembleDebug :worker-demo:assembleLab1 :worker-demo:assembleLab2 :worker-demo:assembleLab3
```

On Linux use `./gradlew` with the same three explicit properties. The build checks
all four actually resolved SDK binaries against the verified archive hashes and
writes `build/sdk-consumption.json`. No Maven-local or source fallback is allowed.
The App's own version remains independent of the Runtime/SDK version.

## Normal Runtime and App startup

Use the same archive arguments without `--prepare-only`, adding
`--redis-url redis://127.0.0.1:6379/15 --redis-scope profile_consumer_example`.
Normal startup selects `consumer`, defaults to Project `default` when no external Runtime YAML is supplied, loads
`config/worker-groups.json` through Runtime's existing create-only initialization,
and starts the extracted JAR with its extracted frontend. It uses DEFAULT Pacer.
It neither provisions RPC Tasks nor controls Android/freezer/Redis cleanup.
A different archive pair requires a new installation directory.
Immutable ZIP contents are checked again on reuse. Runtime-owned finite Task
archives use the installation's separate `data/task-archives` directory; they
remain Server-owned state and do not change the accepted binary identity.

Server and Adapter default to `18182` and `18183`. Build with
`-PxaMassRuntimeApiBaseUrl=http://127.0.0.1:18082` when using the Proof's `18082/18083`
ports. This fixes the Runtime address in the APK; there is no private address
override. Runtime endpoints, the compiled App address and device port mappings
must agree for the local path. `-PxaMassGatewayCredentialRequired` defaults to
`false`; this local build ignores any private gateway credential file.

For a normal API 33 Emulator example, bind every ADB command to its serial:

```text
adb -s emulator-5554 reverse tcp:18182 tcp:18182
adb -s emulator-5554 reverse tcp:18183 tcp:18183
adb -s emulator-5554 install -r worker-demo/build/outputs/apk/debug/xa-mass-android-worker-demo-debug.apk
adb -s emulator-5554 shell am start -W -n com.xa.mass.integration.androidworker/com.xa.mass.android.workerdemo.MainActivity
```

The Application owns one persistent client key. Observe its issued Worker ID in
the App or through the public Runtime catalog; do not invent or persist a Server
Worker ID as App identity. The normal example does not clear App data.

## Public Runtime connection

These static build/install/provision steps also work with a local authenticated
test gateway. Private credential provisioning is target-independent; choosing
an HTTPS target does not imply a GCP deployment or a special App mode. Access to
a public environment is part of an explicitly scoped deployment or acceptance task.

Connection selection is static: the existing `-PxaMassRuntimeApiBaseUrl` fixes
the address in the APK, and `-PxaMassGatewayCredentialRequired=true` requires a
private Worker credential at process startup. The Group remains
`android-demo-workers`. The archive-consumer build helper exposes the same choice
as `--gateway-credential-required`; neither build option accepts a secret. There
is no App settings page, runtime address override or GCP-specific product/profile.

Use the existing verified SDK preparation above, then build, install, provision
and start one selected Lab package. Run from this directory; `$sdkRoot` and
`$candidate` retain the verified SDK inputs, not an App source identity:

```powershell
$device = 'emulator-5554'
$package = 'com.xa.mass.integration.androidworker.lab1'
$workerOrigin = '<configured HTTPS Worker origin>'
$credential = (Resolve-Path '<private worker-access.json>').Path
.\gradlew.bat --no-daemon "-PxaMassSdkRoot=$sdkRoot" "-PxaMassVersion=$($candidate.version)" "-PxaMassCommit=$($candidate.gitCommit)" "-PxaMassRuntimeApiBaseUrl=$workerOrigin" -PxaMassGatewayCredentialRequired=true :worker-demo:assembleLab1
adb -s $device install -r worker-demo/build/outputs/apk/lab1/xa-mass-android-worker-demo-lab1.apk
adb -s $device shell am force-stop $package
python .\provision_worker_access.py --device $device --package $package --credential-file $credential
adb -s $device shell am start -W -n "$package/com.xa.mass.android.workerdemo.MainActivity"
```

Prepare a private UTF-8 JSON file excluded from source control and artifact inputs,
containing only `allowedOrigin`, `keyId` and `secret`. `allowedOrigin` is the HTTPS origin of
the compiled Runtime address, with no userinfo, query, fragment or non-root path.
Use a Worker credential, not an administrator credential. The helper requires a
stopped, installed debuggable App on the explicitly selected device. It transfers
the JSON through ADB stdin and `run-as` into the App's private no-backup directory;
it does not put the secret in command arguments, a shared device directory, APKs,
logs or build receipts. It does not install or start the App. Provision another
credential only while stopped, then explicitly start a new process. The
[App Owner](worker-demo/README.md#static-connection-and-private-access) defines
startup validation, safe errors and the file's trust boundary.

Replace `$workerOrigin` with the target gateway's HTTPS origin before building.
The public data path uses the SDK's HTTPS/WSS support and needs no SSH tunnel or
ADB reverse. The target must separately have the Group and digest event registered,
the Task's Pool/query enabled, and that Group's Prepare routes allowed at the
gateway. The App and provisioning helper do not create these resources or submit
Tasks. Device-local Host HTTP remains loopback-only and adds no credential
read/write endpoint. Local fixture port mappings remain valid for local proofs;
they simply do not establish public connectivity.

For an explicit [GCP Release Preview](../doc/guide/operations/deploy.md#release-preview)
task, select compatible published Runtime/SDK inputs and record the actual App
source commit separately from SDK version, commit, ZIP hash, resolved binaries
and APK hash. The existing CI candidate ZIP/APK chain is unchanged. Resource or
binary changes on GCP belong to that deployment task, not to an ordinary App PR.

The small [public device/API witness](../integrations/android-worker-proof/README.md#public-runtime-deployment-acceptance)
checks deployment startup and the affected use path. Existing local SDK/gateway
proofs own redirect, TLS-negative and revocation assertions; repeat them against
a public environment only when the requested acceptance scope includes them.
Record what was actually exercised and what was not. Stop owned test Apps and
restore touched mappings; retain or remove deployment resources only according
to the explicit task's scope. No browser or Computer Use gate is added.

## Batch Checks through an Android Pool

The same verified consumer can explicitly enable Batch Checks without Preview,
SMS, Messages or a Simulator. Use a new candidate containing the independent
composition and `string-digest-md5-prefix-v1`; published 3.0.0 predates this feature.
Never modify an extracted JAR to add it.

From this directory, after preparing the absolute `$installation` path above:

```powershell
$env:XA_MASS_APP_CHECKS_CONFIG_PATH = Join-Path $installation 'data/app-checks/configuration.json'
$env:XA_MASS_APP_CHECKS_SEED = ([System.Uri](Resolve-Path './config/batch-checks-configuration.json').Path).AbsoluteUri
python .\run_consumer.py --runtime-archive $runtime --sdk-archive $sdk --version $candidate.version --commit $candidate.gitCommit --runtime-sha256 $candidate.archives.runtime.sha256 --sdk-sha256 $candidate.archives.sdk.sha256 --work-dir $installation --redis-url redis://127.0.0.1:6379/15 --redis-scope profile_android_checks --runtime-config ./config/batch-checks-runtime.yaml --groups ./config/worker-groups.json
```

`--runtime-config` resolves the host YAML to an absolute file URI. The two
variables explicitly select the writable configuration and seed addresses.
Omitting the option retains clean consumer startup. The launcher does not parse
business configuration, fund accounts or infer Pool enablement.
With `--runtime-config`, the file owns the complete host Project list: the launcher adds no command-line Project override.
The Batch Checks sample explicitly retains `default`; its business Project is contributed once by the Scenario.

Group JSON declares events; host YAML enables `any` and `worker.any`; check JSON
binds the existing Android digest protocol. Registration does not install a Handler
or prove stock availability. The App and Handler still consume only the verified SDK.
The supply target of 100 is not a concurrency or exclusive Worker allocation.

Open `/app-checks`, select text and Android MD5 prefix matching, import seeds and
confirm the quantity/fee. An administrator explicitly credits the Task owner first.
The sample charges 1 point per persisted seed. Existing locks, fingerprints,
manual restart activation and Task snapshots apply. Existing files are not reseeded.

These three files are the canonical sample sources also packaged in Runtime ZIP
`examples/batch-checks`; there is no second maintained definition. The
[deployment guide](../distribution/server/src/main/dist/examples/batch-checks/README.md)
also supports source consumer and direct `java -jar` startup with the same inputs.
The Android proof reads packaged examples, records actual input hashes, and retains
the 123-seed Any/restart proof plus a separate three-seed window feedback phase.

## Messages through the Android local channel

For local source development, the unified entry builds, installs and starts the
selected Lab APKs against its own Server/Adapter:

```powershell
python run_local_runtime.py --profile consumer --runtime-config xa-android/config/messages-runtime.yaml --groups xa-android/config/worker-groups.json --android-count 1
```

The normal Consumer ports are 18182/18183; CLI and Runtime YAML can override them.
The APK URL and ADB reverse mappings follow the same resolved values. Counts 1..3
select distinct Lab packages on the existing device, not emulator instances.
Use `--android-device` when multiple devices are connected. Existing active target
Apps are not taken over. Exit stops owned Apps and restores previous mappings;
application data and installed APKs remain. There is no implicit freezer change,
AVD creation, scenario selection or Pool provisioning. Java Workers may be added
with `--java-config`; see the [mixed example](../distribution/server/README.md#source-launch).

For Messages, use the same verified consumer arguments with
`--runtime-config ./config/messages-runtime.yaml --groups ./config/worker-groups.json`
and a fresh scope. The [Messages deployment guide](../distribution/server/src/main/dist/examples/messages/README.md)
also covers source startup and direct ZIP execution. It enables only Messages
and the Messaging Pool; no Simulator, Batch Checks or SMS module is required.
The [Android inbox and notification](worker-demo/README.md#local-message-interaction)
provide local acceptance, READ and REPLIED through the original run. These are
simulated messages, not physical SMS delivery. The same sample files are packaged
in Runtime ZIP `examples/messages`.

## Group window Pool through the raw workbench

Use the same verified candidate arguments with `--runtime-config ./config/window-pool-runtime.yaml --groups ./config/worker-groups.json`
and a new explicit Redis scope. This sample declares `default` and `pool-validation`, enables no Scenario,
and explicitly binds `device-window` to `android-demo-workers`, independently of Project membership.
That one reference enables `worker.device.available` and the paired digest feedback; no second feedback binding is needed.
The window is 60 seconds with threshold 10; feedback writes `deviceAssignedAt` and `deviceAssignmentCount`.

Open `/task-workbench?project=default&tab=finite&view=create`, select `android-demo-workers`,
`extension.worker.android.string.digest`, and `worker.device.available({})`; apply its `device-window` supply suggestion.
Use payload JSONL such as `{"algorithm":"MD5","value":"a"}`. Create/import, explicitly approve, then inspect raw Results
and export successful JSONL. Runtime Workers shows the resolved binding and observed Platform Properties.
Supply count 100 is a shared watermark. Feedback is a lossy assignment observation and window qualification occurs on refill;
this does not promise an exact execution ceiling. Other events do not increment this feedback.

Registering a new Group alone does not grant the window. Bind the named Pool under `fixed-window.groups` and restart;
Project admission must independently allow the selected Group;
it must declare the feedback event and run a Worker with its Handler. The Android demo's own Group identity remains fixed.
Independent Project/Pool scopes and config-only Group activation are proved with the parameterized Java Host, not by claiming Android runtime rebinding.
Reuse the local development emulator; test APKs/data may be reset for acceptance. Stop owned Servers/Apps, restore prior ADB mappings,
and clean only the exact test scopes after verification. A new emulator is not required.
Other protocols require a pure Scenario contract, not a Group-specific branch.

## Explicit RPC consumption

The reference uses administrator `1000` of the local built-in users directory, like the
existing Proof. It grants no internal API privilege. Every Task request below supplies
`Authorization: Bearer xak_dev_1000`, that user's public development key; the local
consumer launcher selects the built-in directory unless its Runtime configuration names
a users file. Consumer Project/User declarations obey normal admission.

1. After Runtime readiness and Group registration, send one
   `POST /api/v1/tasks/xa-android-rpc-v1.android-demo-workers/rpc:prepare` with
   `{"projectId":"default","workerGroupId":"android-demo-workers"}`.
2. Send one `POST /api/v1/tasks/xa-android-rpc-v1.android-demo-workers/items:call`:

```json
{
  "items": [{
    "messageId": "<new caller-owned UUID>",
    "eventCode": "extension.worker.android.string.digest",
    "payload": {"algorithm": "MD5", "value": "hello"},
    "workerSelector": {"executorName": "workerId", "input": "<observed Worker ID>"}
  }],
  "waitTimeoutMillis": 250
}
```

3. The response is keyed by the supplied message ID. `succeeded` includes an
   `opaqueResultPayload` JSON string with `algorithm=MD5`, `input=hello`, and
   `digest=5d41402abc4b2a76b9719d911017c592`. The receiving capability owns this format.
4. For `not_observed`, passively consume
   `POST /api/v1/tasks/xa-android-rpc-v1.android-demo-workers/results:take` with
   `["<same message ID>"]` until the bounded observation deadline. Retain a success
   already obtained by `items:call` or `results:take`; consumed content need not
   remain available to a subsequent read.
5. An ambiguously timed-out submission is not replayed. Recovery keeps the same
   Task/Item identity; Group registration and Project declaration create no Tasks.

MD5 is a deterministic demonstration, not a security capability. This path adds
no business Controller, Scenario module or dedicated Matching strategy; directed
`workerId` queries use the platform's existing fixed function without a Pool.

## Proof and ownership

[Android Worker Proof](../integrations/android-worker-proof/README.md) owns the
complete real-process lifecycle/fault and three-App isolation assertions. Its
Python launcher reuses the same archive preparation and Server command, controls
only its owned processes/mappings and exact test scopes, and restores freezer
settings. Java owns every workload verdict. The single-App initial phase also
checks the RPC digest content, separately from its ten DELAY witnesses.

[SDK Distribution](../distribution/worker-sdk/README.md) verifies publication;
[Worker transport](../transport/android-worker/README.md) owns identity/networking;
[capabilities](capabilities/README.md) owns business Definitions;
[capability HTTP](capability-http/README.md) is a platform-published local adapter;
[worker-demo](worker-demo/README.md) owns Application/Activity composition.
Business Handlers are shared by Worker and local diagnostics; Host-only controls
never become Group capability truth. Local HTTP alone proves no remote Task flow.
Doze/OEM policy, physical devices, Xposed and business target Apps remain separate.
