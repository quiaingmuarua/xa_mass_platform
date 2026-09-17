# Server Boot composition

Status: current executable Server and business Scenario composition owner.

This module owns the sole production `com.xa.mass.server.XaMassServerApplication`
main, explicit Boot auto-configuration and Boot JAR. It imports the complete
[Server library](../server_jvm/README.md), shared Console page forwards and a
profile-gated preview. It owns no application service, Redis resource, scheduling
policy or Worker process.

The platform library is also Spring-based. This module owns executable Boot
composition; `server_jvm` retains the complete platform implementation.

## Platform and preview

Ordinary platform profiles load no business Scenario Controllers, registrations or
jobs; platform profiles may still supply their own advisory Group seeds. The existing `scenario-workers` and `agentforge` profiles retain
their platform behavior. `preview` imports both [SMS Reception](../scenarios/sms-reception-jvm/README.md)
and [Message Campaigns](../scenarios/message-campaigns-jvm/README.md). There is one
fixed preview assembly, without per-business deployment profiles or selection.

Preview enables the independent `worker.phone` query for `demo-sim`, alongside its
existing Pool Rules. Direct `workerId` is available in every Group; Matching owns
the [query and index contracts](../worker_matching_jvm/README.md#identity-and-phone-query-functions).

`PreviewConfiguration` supplies the common `demo-sim` Group and complete String,
SMS and Messages event declarations. Each scenario consumes the same declaration
through existing Server registration services. A declaration is not evidence
that the real Worker installed those handlers.

The scenario libraries consume only their approved Server services and DTOs;
they neither depend on each other nor create platform Owners. Each starts after
the platform lifecycle is ready. Failure of either startup fails the whole
context. Both stop admission, submission and observation before platform
resources close, including after partial initialization. Shutdown stays bounded
and scenarios never clean a Redis scope.

Boot configuration lives outside the platform's package scan. Server tests and
OpenAPI export use their own platform-only test bootstrap, without a dependency
on this executable or the scenarios.

## Pages and configuration

The shared Console keeps `/sms` and `/messages` with their existing finite page
forwards in platform and preview instances. Catalog observation controls feature
availability; static assets never enable business. Unknown API and asset paths
remain errors. Runtime/Reference and each business page keep separate data and
polling lifetimes. Platform OpenAPI snapshots exclude scenarios; preview's live
OpenAPI includes both business namespaces.

All production `application*.yaml` files live in this module's `src/main/resources`.
The Server library supplies binding, validation and lifecycle implementation;
it contributes no application YAML to the classpath. Tests do not inject these
resources into the Server library. External configuration continues through Boot's
standard environment, command-line and `spring.config.additional-location` inputs.

| Profile | Configuration | Default deployment |
| --- | --- | --- |
| Default | `application.yaml` | Server 18082, Redis `redis://localhost:6379/15`, scope `profile_default`, DEFAULT Pacer, no Adapter or Group seeds |
| `scenario-workers` | `application-scenario-workers.yaml` over the base | Server 18082, Adapter 18083, scope `profile_scenario_workers`, SCENARIO_LAB and three advisory Groups |
| `agentforge` | `application-agentforge.yaml` over the base | Server 18182, Adapter 18183, scope `profile_agentforge`, DEFAULT and no Group seeds |
| `preview` | `application-preview.yaml` over the base | Server 18500, Adapter 18503, required `XA_MASS_REDIS_SCOPE`, DEFAULT and both business Scenarios |

The preview profile is also copied into [Scenario Preview](../distribution/server/PREVIEW.md)
from this source. Archive checks require its external copy to match the packaged
host resource. The launcher starts the independent Simulator after Server health,
both catalogs and actual Worker routes are verified. External overrides do not
create a second maintained default configuration.

## Run

The checked `scenario-workers` profile provides one WebSocket Adapter, two JVM
Scenario WorkerGroup declarations and the advisory external Android demo
Group. Registering those three declarations automatically provisions all three
Task Calls. Server readiness does not depend on a Worker Host. The root
`run_local_runtime.py` defaults to this Profile, starts Server first and starts
the standalone JVM Host only after readiness. Its finite vertical Worker proof
is owned by
[`integrations/worker-correctness`](../integrations/worker-correctness/README.md).

The checked `agentforge` profile is a separate downstream deployment preset.
It starts exactly one `agentforge-websocket` Adapter at 18183, exposes Server
at 18182, uses `profile_agentforge`, and has an empty configured Group manifest.
AgentForge registers its own Groups through the public API; this Profile does
not start or embed AgentForge or Scenario capability code.

Start the Java Runtime API from the repository root. Server selects the
checked `DEFAULT` policy preset, constructs the one `KernelPacerRuntime`, and
its Spring adapter starts that Runtime before later lifecycle components:

```text
./gradlew :server_boot_jvm:bootRun
```

Start the checked local Scenario profile from the repository root:

```text
./gradlew :server_boot_jvm:bootRun \
  --args="--spring.profiles.active=scenario-workers"
```

This selects `SCENARIO_LAB` and starts Group/Task seeds, Pacer and Adapter, but
no JVM Worker. For the complete local Lab use the one-command process launcher.
Omitting `--profile` defaults to `scenario-workers`:

```text
python run_local_runtime.py
```

It builds and starts Server first, waits for readiness, then starts the
standalone Worker Simulator against `data/scenario-workers`. Existing
Worker files remain persistent local state. Stopping Host closes its network
resources without stopping Server or deleting Workers, WorkerGroups or managed
Task Calls.

The same source launcher can start the checked clean downstream Profile:

```text
python run_local_runtime.py --profile agentforge
```

That path builds and serves the same frontend, then starts Server, Pacer and the
single AgentForge WebSocket Adapter. It does not build or start the Scenario
Worker Host. Unknown Profiles are rejected.

The same root entry builds and starts the combined SMS/Messages Preview:

```powershell
python run_local_runtime.py --profile preview
```

It delegates to the [shared Preview launcher](../distribution/server/PREVIEW.md),
including its isolated scope, two-process lifecycle and optional inventory parameters.

For a repository-independent deployment, extract the
[`distribution/server`](../distribution/server/) Runtime ZIP and start its Boot
JAR directly from the Runtime root with Java 21, external Redis and explicit
Profile and frontend arguments. The schema-v5 manifest lists the supported
`scenario-workers`, `agentforge` and `preview` Profiles. The Runtime ZIP does not contain
the repository-local Worker Simulator; use `run_local_runtime.py` or the
module's Gradle task when that Lab is required. Source `bootRun` remains
available for repository development.

## Build and verification

```powershell
.\gradlew.bat :server_boot_jvm:bootJar
.\gradlew.bat :server_boot_jvm:test
.\gradlew.bat :server_boot_jvm:smsCompositionIntegrationTest :server_boot_jvm:scenarioCompositionIntegrationTest
```

The Boot JAR retains the external `xa-mass-server-jvm` name. [Runtime distribution](../distribution/server/README.md)
owns ZIP delivery, frontend builds, diagnostic dictionaries and archive proof.
This executable owns JVM composition proof: platform/preview resource isolation,
startup/close ordering, application-service calls with Task HTTP blocked, uncertain
submission and late observations. [Scenario Coexistence](../integrations/scenario-coexistence/README.md)
owns the real shared-Worker business witness. Scale is governed by [TESTING](../TESTING.md).

Deployment tests use these classpath profiles directly. AgentForge, Lab Group
seeds and the Loaded Recovery overlay are executable configuration contracts;
pure binding and invalid-input tests remain with Server. Platform/preview
composition tests never load a source YAML through an additional file location.
Server tests and OpenAPI export use their explicit test fixtures, with disabled
Pacers and unreachable Redis unless the real boundary proof supplies its own
connection, unique scope and endpoints. Both archive verifiers reject application
configuration in nested platform/Scenario libraries and reject test configuration.

Matching resources are explicit per Group. The scenario-workers profile enables
any/worker.any for both Lab Groups and preserves their 1000 managed watermarks.
Preview enables country/messaging and phone lookup; SMS managed supply is
country/{} /100. Groups without a task-rpc refill override save empty managed
supply and may use Identity without a Pool.
