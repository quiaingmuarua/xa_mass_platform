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
their platform behavior. `preview` imports [SMS Reception](../scenarios/sms-reception-jvm/README.md),
[Message Campaigns](../scenarios/message-campaigns-jvm/README.md) and
[App Checks](../scenarios/app-checks-jvm/README.md). There is one
fixed preview assembly, without per-business deployment profiles or selection.

Preview's host configuration and enabled module declarations form this topology:

| Project | Groups | Declared events and Matching resources |
| --- | --- | --- |
| `sms` | `demo-sim` | String/SMS/Messages events; Country and Messaging Pools; independent `worker.phone` query |
| `messages` | `demo-sim`, `app-a-sim`, `app-b-sim` | `extension.worker.message.send`; Group-isolated Messaging Pool and `worker.messaging.available` |
| `app-checks` | `app-a → app-a-sim`, `app-b → app-b-sim` | `extension.worker.app.registration.check`; Any and assignment-window Pools, `worker.any` and `worker.assignment.available` |

The `demo-sim` Project managed Tasks retain `country / {} / 100` supply;
that Group does not enable Any Pool. Messages contributes the existing
`QualifiedCountryDefinition`; Worker qualification remains `messaging.enabled="true"`
and the two-letter `country` field. Its Phone function declaration is retained by
that basic contract but `worker.messaging.phone` is not enabled in Preview.
`xa.mass.scenarios.messages.applications` explicitly binds ordered application IDs,
labels and Groups. One module-owned Project and its batched resource requirement
use those same Groups. There is no default application or old single Group alias.
The shared String Bean remains for SMS. New API creation requires an application;
only Messaging Pool selection is used. Simulator App Workers install both Messages
and App Checks, with explicit nonoverlapping phone ranges and country Properties.

Each App Group enables `assignment-window`. App Checks contributes its immutable
Pool definition and binds `xa.mass.worker-pools.assignment-window.groups.<group>`
with explicit `window-millis=60000` and `max-count=10`. Its projection uses the
same configuration object; generic Matching Group binding holds only enablement.
App Checks declares only that Pool's supply. Any remains available for other
explicit callers, without a default Group-wide window gate. The old Group-level
`assignment-window-pool` path and `max-assignments` field have no aliases and fail
strict configuration binding; window length stays fixed within an existing scope.
Direct `workerId` remains available in every Group for general Runtime callers;
Messages API creation no longer exposes directed sending. Matching owns the
[query and index contracts](../worker_matching_jvm/README.md#identity-and-phone-query-functions).
Server initializes host Groups, validates module dependencies and prepares Project
managed Tasks before Adapter startup. Scenarios consume the immutable Project
directory; they do not register or amend Groups. A declaration is not evidence
that the real Worker installed those handlers.

Boot assembles each scenario to start after the platform lifecycle is ready; a scenario
startup failure fails the context. The
[Server Scenario boundary](../server_jvm/README.md#worker-and-scenario-assembly)
owns permitted service calls, partial-initialization cleanup and closing business
admission/submission/observation before platform resources.

Boot configuration lives outside the platform's package scan. Server tests and
OpenAPI export use their own platform-only test bootstrap, without a dependency
on this executable or the scenarios.

## Pages and configuration

Boot forwards `/sms`, `/sms/listeners`, `/sms/metrics`, `/messages`,
`/messages/tasks/{taskId}`, `/app-checks` and `/app-checks/tasks/{taskId}`
to the shared Console, including trailing slashes, in platform and preview
instances. The root `/` remains the Runtime entry. Catalog observation controls feature
availability; static assets never enable business. Unknown API and asset paths
remain errors. The [Frontend Owner](../frontend/README.md) owns page data and
polling lifetimes. Platform OpenAPI snapshots exclude scenarios; preview's live
OpenAPI includes all three business namespaces.

All production `application*.yaml` files live in this module's `src/main/resources`.
The Server library supplies binding, validation and lifecycle implementation;
it contributes no application YAML to the classpath. Tests do not inject these
resources into the Server library. External configuration continues through Boot's
standard environment, command-line and `spring.config.additional-location` inputs.

| Profile | Configuration | Default deployment |
| --- | --- | --- |
| Default | `application.yaml` | Server 18082, Redis `redis://localhost:6379/15`, scope `profile_default`, DEFAULT Pacer, no Adapter or Group seeds |
| `scenario-workers` | `application-scenario-workers.yaml` over the base | Server 18082, Adapter 18083, scope `profile_scenario_workers`, SCENARIO_LAB and three advisory Groups |
| `agentforge` | `application-agentforge.yaml` over the base | Server 18182, `agentforge-websocket` Adapter 18183, scope `profile_agentforge`, DEFAULT and no Group seeds |
| `preview` | `application-preview.yaml` over the base | Server 18500, `products-websocket` Adapter 18503, required `XA_MASS_REDIS_SCOPE`, DEFAULT and all three business Scenarios |

The `scenario-workers` Project references its two JVM Lab Groups and advisory
Android demo Group, provisioning three independent managed Task Calls. Its two
Lab Groups enable `any/worker.any` with managed watermarks of 1000.
AgentForge registers its own Groups through the public API; the profile does not
embed AgentForge or Scenario capabilities. Groups without a task-rpc refill
override save empty managed supply and may use Identity without a Pool.

[Preview delivery](../distribution/server/PREVIEW.md#archive-delivery) copies the
preview profile from this source and verifies it against the packaged resource.
External overrides do not create a second maintained default configuration.

## Run

Start the Java Runtime API from the repository root. Server selects the
checked `DEFAULT` policy preset, constructs the one `KernelPacerRuntime`, and
its Spring adapter starts that Runtime before later lifecycle components:

```text
./gradlew :server_boot_jvm:bootRun
```

Select another checked profile explicitly, for example:

```text
./gradlew :server_boot_jvm:bootRun \
  --args="--spring.profiles.active=scenario-workers"
```

This starts the selected Server composition, without a Worker Host. Server
readiness does not depend on that Host. Use Distribution's
[source launcher](../distribution/server/README.md#source-launch) for the complete
local Lab or Preview, and its [Runtime archive instructions](../distribution/server/README.md#runtime-archive)
for repository-independent deployment.

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
connection, unique scope and endpoints. Distribution owns
[archive configuration checks](../distribution/server/PREVIEW.md#archive-delivery);
[Worker Correctness](../integrations/worker-correctness/README.md) owns the
finite vertical Worker proof.

## Project topology

Production profiles can declare `xa.mass.project-assembly.projects` as a list:

```yaml
xa:
  mass:
    project-assembly:
      projects:
        - project-id: sms
          worker-group-ids: [demo-sim]
```

List overlays replace configuration-owned Projects only. Module `ProjectDefinition`
Beans contribute additional Projects through the same immutable directory; duplicate
IDs across either source fail even when their Group lists match. Preview therefore
keeps SMS/App Checks in the list and declares Messages through its module. Existing
external configurations must remove the `messages` list entry and explicitly set
`xa.mass.scenarios.messages.applications`; neither the old single Group path nor
the shared String Bean is a fallback.
Changing the list does not disable an imported module. Module selection remains
explicit static Configuration assembly, with no new production profile or switch.

A proof or deployment changing Groups must update both its configuration-owned
Project list and enabled modules' Group bindings. Shared Group definitions remain
host-owned; module requirements only validate subsets and can use an already
registered Group without a host creation entry. Reusing the same Project/Group and
managed refill retains the existing derived Task ID and descriptor. Changing Group
is a new binding, not an implicit data migration.
Default and AgentForge have empty Project lists; downstream deployments provide
Groups and Projects before startup when managed Calls are required. An external
Group registration alone creates no Task. Projects have no mutation API.

## Assignment batch ceiling

`xa.mass.kernel-pacer.assignment-batch-limit` binds
`XA_MASS_KERNEL_PACER_ASSIGNMENT_BATCH_LIMIT`, defaults to 100 and permits 1..1000.
Server passes it to the Pacer assembly for admission before resource creation.
It bounds each Task's Item check and each Group's demand-driven candidate read;
it is not a requested inventory size. No hot reload or per-Task override exists.
Other Owner budgets, preset intervals and HTTP limits remain independent.
