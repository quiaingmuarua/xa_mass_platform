# XA Android external consumer

Status: current Runtime ZIP and Worker SDK ZIP reference consumer.

`capabilities` and `worker-demo` form an independent Gradle build. They consume
only the verified SDK Maven repository, with no platform source substitution.
AgentForge and this App use the same public Runtime and Worker SDK contracts.
The platform still owns and publishes `capability-http`; its root-build task path
is `:android-capability-http`, explicitly mapped to this directory's producer.
It is not included in the standalone consumer build.

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
Normal startup selects `consumer`, explicitly declares Project `default`, loads
`config/worker-groups.json` through Runtime's existing create-only initialization,
and starts the extracted JAR with its extracted frontend. It uses DEFAULT Pacer.
It neither provisions RPC Tasks nor controls Android/freezer/Redis cleanup.
A different archive pair requires a new installation directory.
Immutable ZIP contents are checked again on reuse. Runtime-owned finite Task
archives use the installation's separate `data/task-archives` directory; they
remain Server-owned state and do not change the accepted binary identity.

Server and Adapter default to `18182` and `18183`. Build with
`-PxaMassRuntimeApiBaseUrl=http://127.0.0.1:18082` when using the Proof's `18082/18083`
ports. Runtime endpoints, APK address and device port mappings must agree.

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

## Batch Checks through an Android Pool

The same verified consumer can explicitly enable Batch Checks without Preview,
SMS, Messages or a Simulator. Use a new candidate containing the independent
composition and `string-digest-md5-prefix-v1`; published 3.0.0 predates this feature.
Never modify an extracted JAR to add it.

From this directory, after preparing the absolute `$installation` path above:

```powershell
$env:XA_MASS_APP_CHECKS_CONFIG_PATH = Join-Path $installation 'data/app-checks/configuration.json'
$env:XA_MASS_APP_CHECKS_SEED = ([System.Uri](Resolve-Path './config/batch-checks-configuration.json').Path).AbsoluteUri
python .\run_consumer.py --runtime-archive $runtime --sdk-archive $sdk --version $candidate.version --commit $candidate.gitCommit --runtime-sha256 $candidate.archives.runtime.sha256 --sdk-sha256 $candidate.archives.sdk.sha256 --work-dir $installation --redis-url redis://127.0.0.1:6379/15 --redis-scope profile_android_checks --runtime-config ./config/batch-checks-runtime.yaml
```

`--runtime-config` resolves the host YAML to an absolute file URI. The two
variables explicitly select the writable configuration and seed addresses.
Omitting the option retains clean consumer startup. The launcher does not parse
business configuration, fund accounts or infer Pool enablement.

Group JSON declares events; host YAML enables `any` and `worker.any`; check JSON
binds the existing Android digest protocol. Registration does not install a Handler
or prove stock availability. The App and Handler still consume only the verified SDK.
The supply target of 100 is not a concurrency or exclusive Worker allocation.

Open `/app-checks`, select text and Android MD5 prefix matching, import seeds and
confirm the quantity/fee. An administrator explicitly credits the Task owner first.
The sample charges 1 point per persisted seed. Existing locks, fingerprints,
manual restart activation and Task snapshots apply. Existing files are not reseeded.
Other protocols require a pure Scenario contract, not a Group-specific branch.

## Explicit RPC consumption

The reference uses declared development user `1000` / role `admin`, like the
existing Proof. It grants no internal API privilege and is not authentication.
Every Task request below supplies `X-XA-Dev-User: MTAwMA` and
`X-XA-Dev-Role: admin`. Consumer Project/User declarations obey normal admission.

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
