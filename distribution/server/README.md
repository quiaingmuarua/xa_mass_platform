# XA Mass Server Runtime distribution

This module owns Runtime and Scenario Preview ZIP delivery, frontend builds,
diagnostic dictionaries, Preview process assembly and archive verification.

The [3.0 release and upgrade contract](RELEASE-3.0.md) owns public compatibility,
fresh-state cutover and exact-artifact release acceptance.
It consumes the sole Boot JAR produced by
[Server Boot composition](../../server_boot_jvm/README.md). It has no Java
entrypoint, Spring composition, application services or platform Owner operations.

The Runtime archive packages the Boot JAR, unified Console and current-build
diagnostic dictionary. It excludes the independent Worker Simulator, Python
runtime, wheels and virtual environments. Redis remains external; the JAR
embeds no separate business frontend.
The compiled frontend also carries the committed, Server-verified OpenAPI
snapshot at `frontend/dist/reference/openapi.json`; unlike the diagnostic
dictionary, that snapshot is generated explicitly and tracked in source.

Boot owns [profiles and Project/Group/Scenario composition](../../server_boot_jvm/README.md#platform-and-preview)
and [page forwards and production configuration](../../server_boot_jvm/README.md#pages-and-configuration).
Distribution consumes that composition without creating platform Owners or
another maintained YAML default.

## Source launch

From the checkout root, build and start the complete business Preview:

```text
python run_local_runtime.py
```

The default is `preview` and delegates to the sole Preview launcher. It supplies
three business Scenarios and the explicit `default` raw-task Project.
`--profile default` builds and starts only the plain Server; no Worker Host or
business storage is opened. The retired platform Lab profile has no launch alias.

`python run_local_runtime.py --profile consumer` builds the same frontend and
starts only Server with its configured Adapter; it does not build or start Host.
Consumer alone also accepts `--runtime-config <Runtime YAML>`, `--groups <Group JSON>`
and `--server-port <port>`. Files resolve against the invoking working directory
before the build; only Group JSON syntax/root-object checks happen in the launcher.
Server validates resources. HTTP port precedence is the explicit argument, then
`SERVER_PORT`, then 18182; command and readiness use that same value, overriding
`server.port` in the external YAML. No Project or resource defaults are inferred.
Unknown profiles are rejected. For business Preview, the root command delegates
in-process to the [sole Preview launcher](PREVIEW.md#source-launch):

```text
python -m pip install -r distribution/server/requirements-preview.txt
python run_local_runtime.py --profile preview
```

The [Simulator entry](../../worker_simulator_jvm/README.md#one-configuration-one-entry)
also supports standalone Host development; [Boot Run](../../server_boot_jvm/README.md#run)
starts only the Server composition.

## External consumer workbench deployment

The verified consumer may explicitly opt into Batch Checks using external host
configuration and a separate writable Scenario document. See the
[Android Pool example](../../xa-android/README.md#batch-checks-through-an-android-pool).
Use new candidate bytes containing this addition; do not modify released archives.

The [three-file deployment guide](src/main/dist/examples/batch-checks/README.md)
provides source and extracted-ZIP commands. Runtime ZIPs ship that guide and the
three canonical Android configuration inputs under `examples/batch-checks`.
They are opt-in examples, never automatically loaded profiles or writable state.
Copy them to a deployment directory; retain actual Scenario configuration and Task
archives outside the immutable installation. The Scenario's check configuration
derives its `app-checks` Project membership; host Pool bindings remain independent.
The unchanged Preview entry is the built-in multi-Scenario demonstration.


[XA Android](../../xa-android/README.md) supplies the portable dual-ZIP reference entry, explicit initialization and real RPC business example.

The `consumer` profile supplies Server/Adapter coordinates and no Project or
Group resources. The host supplies an explicit Project; `default` has no bound
Group IDs and uses the registered catalog, while other Projects declare their
bindings. The name need not equal the profile. The Console accepts any declared
Project, including an environment whose only Project is `agentforge`.

Deployment proceeds in order: declare the Project at startup, register its Group
descriptors through Runtime `group-config-json` or `/api/v1/worker-groups/{id}:register`, connect Workers
with the required installed Handlers, then prepare/call RPC Tasks or create/import/
approve finite Tasks in `/task-workbench?project=agentforge`. Registration alone
creates no Task. The capabilities page distinguishes an unregistered Group from
an empty Pool configuration; `workerId` accepts a JSON string and needs no Pool.
An old registration report is not evidence for a different Redis scope or process.

The Runtime Distribution proof checks a caller-supplied AgentForge Project with
four Group bindings, empty resources before registration, explicit registration
and RPC preparation, finite creation/close, and direct workbench page routes in a
fresh extracted archive. It retains the platform fixture and empty-resource
assertions; it does not prove Android capability behavior. Runtime Boundary owns
real Worker traversal; a desktop acceptance may additionally run a JVM test Worker
against source and extracted Runtime, identified explicitly as a test capability.

For an already running consumer, building this repository does not replace its
locked installation. A local candidate ZIP is acceptance output, not a published
Release. Formal delivery separately follows the [3.0 release contract](RELEASE-3.0.md):
publish immutable artifacts, update the consumer's exact version/commit/checksums,
resolve into a separate installation, and switch deliberately. The task-directory
storage change after 1.0.0 requires a fresh Redis scope; do not reuse old descriptors
or indexes. Register Groups and prepare required Tasks in the new scope. Keep the
old installation/scope for rollback, and do not overwrite a running consumer's
`.runtime` directory or restart its Server as part of local acceptance.

## Diagnostic dictionary

The projection is produced from an explicit three-owner allowlist rather than
a repository-wide scan:

```powershell
.\gradlew.bat :distribution:server:generatePlatformDiagnosticCodes
.\gradlew.bat :distribution:server:verifyPlatformDiagnosticCodes
```

The build reads compiled `ServerErrorCode`,
`WorkerDeliveryAdapterErrorCode`, and `WorkerErrorCode` enums through an
isolated ClassLoader. It writes only under
`distribution/server/build/generated/reference` and adds the JSON to
`frontend/dist/reference` during local or archive assembly. Scenario Workers,
Integrations, Android Capabilities, Frontend/Distribution-local exceptions and
downstream Extensions are deliberately outside this lookup. Kernel currently
has no unified numeric ErrorCode catalog and is not represented. The generated
file is not committed and is not a compatibility contract.

## Runtime archive

Build an explicit release version:

```powershell
.\gradlew.bat :distribution:server:distZip "-PxaMassVersion=3.0.0"
```

The archive is written under `distribution/server/build/distributions`. After
extracting it on a machine with Java 21, run from the extracted Runtime root:

```powershell
java -jar .\lib\xa-mass-server-jvm-3.0.0.jar `
  --spring.profiles.active=default `
  --spring.web.resources.static-locations=file:frontend/dist/ `
  --xa.mass.redis.url=redis://127.0.0.1:6379/15
```

Select the built-in clean `consumer` deployment preset explicitly:

```powershell
java -jar .\lib\xa-mass-server-jvm-3.0.0.jar `
  --spring.profiles.active=consumer `
  --spring.web.resources.static-locations=file:frontend/dist/
```

Only profiles listed in the schema-v6 Runtime manifest are supported. Redis
lifecycle remains with the caller. The archive contains no Pacer policy file or
per-field policy tuning; the checked Boot profile selects its fixed preset.
Use the [Preview archive](PREVIEW.md#archive-delivery) when delivery must include
Host and the finite two-process launcher; it requires no platform release version.

Endpoint overrides retain the [Server Endpoint contract](../../server_jvm/README.md#endpoint-defaults-and-polling-evidence).
Scenario composition needs no data rebuild. Deployments crossing incompatible
historical Worker Binding storage must follow the stopped, exact-scope
[Worker rebuild procedure](../../kernel_jvm/doc/runtime-redis/worker-runtime-redis-shape.md#scope-rebuild);
there are no compatibility aliases or reads.

## Archive verification

Distribution owns the single component verifier: `python -m distribution.archive_tools`
with `runtime`, `sdk` and `preview` subcommands. Candidate and release tools import
its verification functions directly. SDK validation is pure unless the caller
explicitly supplies `--consumer-project`; CI builds the real consumer in APK Assembly.


The archive verifier requires the diagnostic JSON and checks its version and
full Git commit against `manifest.json`, plus the exact Server, Adapter and
Worker Core owner order. It also requires the OpenAPI 3.1 snapshot, rejects a
request-derived `servers` field or non-`/api/v1/**` paths, and checks the stable
four-Tag navigation order.
It requires the three canonical Boot resources (base, Consumer and Preview),
excludes the external platform proof fixture, and rejects application configuration
in nested platform/Scenario libraries and all test configuration.
It also requires exactly the deployment README and three Batch Checks example files;
saved configuration, lock files and temporary state are rejected in the sample directory.
Preview adds [external-profile and packaged-launch checks](PREVIEW.md#archive-delivery).
The Runtime proof loads the external platform fixture, verifies that its Project
bindings create no Tasks, then explicitly prepares its named RPC Tasks and
point-reads their approved descriptors from the extracted archive.

## Finite Task archives

The Runtime consumes Boot's finite Task retention configuration and Server-owned
archive files. Production keeps the configured archive directory across restarts;
API readers of one scope share that storage. The Preview launcher instead supplies
one private root for each generated test scope and cleans it on normal shutdown.
See [Preview archives](PREVIEW.md#task-result-archives) and
[Server retention](../../server_jvm/README.md#finite-task-retention). Packaging adds
no background cleanup implementation or historical data migration.
