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

Messages is independently selected by `xa.mass.scenarios.messages.enabled`
(default false). Preview explicitly enables it; consumer supplies applications
and Matching enablement through external YAML and registers Groups separately.
Disabled composition contributes no Messages service, Project or qualified-country
definition. The strict properties object also validates the enable switch.
See [Messages deployment](../distribution/server/src/main/dist/examples/messages/README.md).

Batch Checks is independently selected by the strict Boolean
`xa.mass.scenarios.app-checks.enabled` (default false). Boot binds its properties
but imports the Scenario only when enabled. It requires explicit
`configuration.path` and `configuration.seed`. Preview enables it; consumer may
opt in through external YAML without SMS, Messages or Simulator Groups. No profile
is added. See the [Android Pool example](../xa-android/README.md#batch-checks-through-an-android-pool).

For deployment, use external Runtime YAML, Group registration JSON and the Scenario
configuration document. Source and ZIP instructions are owned by
[Distribution](../distribution/server/README.md#external-consumer-workbench-deployment).
Neither the launcher nor this profile adds Group/Pool bindings from check names.
Batch Checks contributes its Project from the effective check configuration; do not
repeat that Project in host YAML. Preview's built-in Groups remain demonstration data.


Ordinary platform profiles load no business Scenario Controllers, registrations or
jobs. The base platform and `consumer` remain independent of business modules. `preview` imports [SMS Reception](../scenarios/sms-reception-jvm/README.md),
[Message Campaigns](../scenarios/message-campaigns-jvm/README.md) and
[App Checks](../scenarios/app-checks-jvm/README.md). There is one
fixed preview assembly. Messages and Batch Checks also have explicit independent
enable switches, without adding per-business deployment profiles.

Preview's host configuration and enabled module declarations form this topology:

| Project | Groups | Declared events and Matching resources |
| --- | --- | --- |
| `default` | All currently registered Groups, read on demand | Explicit raw Task workspace; existing Group capabilities are reused |
| `sms` | `demo-sim` | String/SMS/Messages events; Partitioned SMS, Country and Messaging Pools; independent `worker.phone` query |
| `messages` | `demo-sim`, `app-a-sim`, `app-b-sim` | `extension.worker.message.send`; Group-isolated Messaging Pool and `worker.messaging.available` |
| `app-checks` | `app-a-sim`, `app-b-sim`, `demo-sim`, derived from checks | App A/B registration uses assignment-window; Demo carrier batch, uint32-prime and MD5 batch use Any. Queries are `worker.assignment.available` and `worker.any` |

Explicit RPC preparation for `demo-sim` retains the host-configured Partitioned SMS supply;
that Group also enables Any for Batch Checks. Raw finite Tasks may explicitly supply Country or Any.
The explicitly declared `default` Project reads currently registered Groups; its reads never provision resources. Messages contributes the existing
`QualifiedCountryDefinition`; Worker qualification remains `messaging.enabled="true"`
and the two-letter `country` field. Its Phone function declaration is retained by
that basic contract but `worker.messaging.phone` is not enabled in Preview.
`xa.mass.scenarios.messages.applications` explicitly binds ordered application IDs,
labels and Groups. One module-owned Project and its batched resource requirement
use those same Groups. There is no default application or old single Group alias.
The shared String Bean remains for SMS. New API creation requires an application;
only Messaging Pool selection is used. Simulator App Workers install both Messages
and App Checks, with explicit nonoverlapping phone ranges and country Properties.

The Preview seed binds each App Group to `assignment-window`. App Checks owns its JSON configuration file
and reads one immutable startup snapshot. That snapshot contributes the Project,
resource requirements and Catalog. Host bindings separately own window definitions and projections.
Boot passes `xa.mass.scenarios.app-checks.configuration.path` through
`XA_MASS_APP_CHECKS_CONFIG_PATH`, and `configuration.seed` through `XA_MASS_APP_CHECKS_SEED`
(default `classpath:app-checks/preview-configuration.json`). The seed contains App
A/B plus Demo checks and is used only for a missing configuration file. Host window bindings own the 60000 ms/10 limits.
Generic Matching Group binding still holds only enablement. Boot does not read or write the scenario file;
ordinary platform composition does not instantiate the scenario store.
Batch Checks derives and unions event/refill/query dependencies per Group from the JSON document keyed by checkId.
App A/B retain their registration checks. Demo exposes one original-carrier check and the uint32-prime/MD5-prefix demonstrations through explicitly enabled Any; these events do not enter the registration window observation. Other checks can also select Any without a window entry. Any remains available to
explicit callers, without a default Group-wide window gate. The old Group-level
`assignment-window-pool` path and `max-assignments` field have no aliases. The
scenario no longer reads window parameters from YAML. Saves require manual restart;
window length changes require a new Redis scope. Host resources are not auto-enabled.
Direct `workerId` remains available in every Group for general Runtime callers;
Messages API creation no longer exposes directed sending. Matching owns the
[query and index contracts](../worker_matching_jvm/README.md#identity-and-phone-query-functions).
Server initializes host Groups and validates module dependencies before Adapter
startup. Project bindings create no Tasks. SMS prepares one shared RPC Task during
its own initialization; Messages/App Checks create only finite business Tasks, and
the default workbench explicitly prepares caller-named RPC Tasks. Scenarios consume
the immutable Project directory; they do not register or amend Groups. A declaration is not evidence
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

Boot forwards `/sms`, `/sms/metrics`, `/messages`,
`/messages/tasks/{taskId}`, `/app-checks` and `/app-checks/tasks/{taskId}`
to the shared Console, including trailing slashes, in platform and preview
instances. Server's generic page controller also forwards `/task-workbench` and its trailing
slash to the same Console. This route enables no Project or API capabilities.
The root `/` serves development login and `/?view=users` serves admin user management.
Server loads its development user/role JSON into process memory; an installation may
set `xa.mass.users.seed` to another resource. Browser refresh retains user edits;
Server restart reloads that seed. This introduces no account database or session.
Catalog observation controls feature
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
| `consumer` | `application-consumer.yaml` over the base | Server 18182, `runtime-websocket` Adapter 18183, scope `profile_consumer`, DEFAULT and no Group seeds |
| `preview` | `application-preview.yaml` over the base | Server 18500, `products-websocket` Adapter 18503, required `XA_MASS_REDIS_SCOPE`, DEFAULT and all three business Scenarios |

The external [platform proof fixture](../integrations/worker_proof_support/platform-runtime.yaml)
retains the `scenario-workers` test Project, three Groups, original Adapter and
SCENARIO_LAB settings. Runners explicitly load that fixture before lane overlays;
it is not a production profile or packaged application resource.
External consumers register their own Groups through the public API; the profile does not
embed Consumer or Scenario capabilities. Groups without a task-rpc refill
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
  --args="--spring.profiles.active=consumer"
```

This starts the selected Server composition, without a Worker Host. Server
readiness does not depend on that Host. Use Distribution's
[source launcher](../distribution/server/README.md#source-launch) for the complete
local Preview, and its [Runtime archive instructions](../distribution/server/README.md#runtime-archive)
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

Deployment tests use these classpath profiles directly. Consumer, Lab Group
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

Preview explicitly declares `default` with `worker-group-ids: []`. Server resolves
its available Groups from current registration when capabilities are requested;
new HTTP registrations appear on refresh. Nonempty default bindings fail startup.
Other Projects retain explicit lists. Plain platform and Consumer do not receive
an implicit default Project. No Group, Pool or Worker is registered by this convention.


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
keeps default and SMS in the list and declares Messages and App Checks through their modules. Existing
external configurations must remove the `messages` and `app-checks` list entries and explicitly set
`xa.mass.scenarios.messages.applications`; neither the old single Group path nor
the shared String Bean is a fallback.
Changing the list does not disable an imported module. Module selection remains
explicit static Configuration assembly, with no new production profile or switch.

A proof or deployment changing Groups must update both its configuration-owned
Project list and enabled modules' Group bindings. Shared Group definitions remain
host-owned; module requirements only validate subsets and can use an already
registered Group without a host creation entry. Explicit preparation with the same
caller Task ID and complete descriptor reuses retained work. Changing Group
is a new binding, not an implicit data migration.
Default and Consumer have empty Project lists; downstream deployments provide
Groups and Projects before explicitly preparing RPC Tasks. An external
Group registration or Project declaration alone creates no Task. Projects have no mutation API.
The Console workbench consumes this directory and works without a Project named
`default`: use `/task-workbench?project=agentforge&tab=rpc` for a deployment that
declares `agentforge`. The profile name does not select or create a Project.
A null Group descriptor means registration is still required in the current scope;
it does not mean the Project binding was omitted. Register through the existing
WorkerGroup API, refresh capabilities, then explicitly prepare a Task. Groups with
only `workerId` use directed calls with empty supply; no Any Pool is inferred.

## Assignment batch ceiling

`xa.mass.kernel-pacer.assignment-batch-limit` binds
`XA_MASS_KERNEL_PACER_ASSIGNMENT_BATCH_LIMIT`, defaults to 100 and permits 1..1000.
Server passes it to the Pacer assembly for admission before resource creation.
It bounds each Task's Item check and each Group's demand-driven candidate read;
it is not a requested inventory size. No hot reload or per-Task override exists.
Other Owner budgets, preset intervals and HTTP limits remain independent.

Messages sending/receipt separation is verified by Scenario Composition using real
Redis, HTTP and Worker execution: text sends finish Tasks despite bounded optional
Reporter admission, and short Host windows release capacity without Worker restart.
The default ten-minute windows and explicit lab-json mode belong to the Simulator
Host configuration, not Boot YAML, Kernel Item retention or Messages input versions.

SMS's managed Task declares `sms-reception` targets for A/B/C × CN/US/GB, each
with watermark 100, and uses `worker.sms.available`. Country remains enabled for
explicit generic Task supply. The Scenario contributes its passive partitioned lease
resource and stored-Result projection; host configuration owns enabling both Pool and
function and admitting the `extension.worker.sms.number.lease` event.

## RPC Result and SMS cache configuration

RPC Results use `xa.mass.task-rpc.result-idle-ttl=7d`, a positive duration passed
to the Kernel provider. It is renewed only by actual RPC Result writes and does
not expire finite Task results. SMS Preview supplies
`xa.mass.sms.result-cache.maximum-size=10000` and
`xa.mass.sms.result-cache.expire-after-write=10m`; the SMS module validates and
owns this process-local Caffeine cache. Reads and unchanged revisions do not
renew the cache lifetime. Restart discards consumed SMS cache entries; unread
Redis Results retain their existing expiration. Use a fresh scope for this
cutover; no TTL backfill or old-result migration is performed.

## Finite Task retention configuration

All profiles explicitly provide `xa.mass.task-retention` defaults: 1h grace, 7d
retention, 1h maintenance interval, and `./data/task-archives`. Enablement defaults
to the resolved Pacer enablement and can be overridden with
`XA_MASS_TASK_RETENTION_ENABLED`; `XA_MASS_TASK_ARCHIVE_DIRECTORY` selects storage.
Only one instance per scope runs maintenance. The implementation belongs to Server,
not Boot; Kernel continues to own Task finality and mutation fences. The directory
must survive restarts for retained tasks and be shared with other API readers.
Preview assigns a private archive root per generated scope. No old scope is migrated.

### Named WorkerGroup window bindings

Preview declares the assignment-window Pool, query and nested registration-event feedback through
`xa.mass.worker-assembly.fixed-window`. App A/B each reference the Pool once; their raw Matching enablement retains only Any/Messaging.
Server expands the strict host startup input; Boot owns values and packaging, not qualification or projection execution.
Batch Checks JSON stores only common/checks and must not contain poolGroups. Default/Consumer without bindings create none.
See [Server bindings](../server_jvm/README.md#named-window-pool-and-feedback-bindings) for paired Pool/query/feedback binding,
Group overrides, startup errors and the read-only Group interface. A registered Group alone installs no Worker Handler or Pool stock.

`fixed-window.groups.<group>.pools.<pool>` enables the named window, query and required `pools.<pool>.feedback` together.
Project Group membership is independent Task admission and cannot enable a resource. Retired Project expansion and independent feedback
binding fields fail strict configuration binding. The Android consumer's `window-pool-runtime.yaml` demonstrates this without a Scenario;
its default workbench consumes the enabled Group Pool.
