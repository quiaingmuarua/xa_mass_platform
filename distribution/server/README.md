# XA Mass Server Runtime distribution

This module owns Runtime and Scenario Preview ZIP delivery, frontend and
documentation-site builds, diagnostic dictionaries, Preview process assembly
and archive verification.

The [3.1 release and upgrade contract](RELEASE-3.1.md) owns public compatibility,
fresh-state cutover and exact-artifact release acceptance.
It consumes the sole Boot JAR produced by
[Server Boot composition](../../server_boot_jvm/README.md). It has no Java
entrypoint, Spring composition, application services or platform Owner operations.

The Runtime archive packages the Boot JAR, unified Console, documentation site at
`/docs/` and current-build diagnostic dictionary. It excludes the independent Worker Simulator, Python
runtime, wheels and virtual environments. Redis remains external; the JAR
embeds no separate business frontend.
The compiled frontend also carries the committed, Server-verified OpenAPI
snapshot at `frontend/dist/reference/openapi.json`; unlike the diagnostic
dictionary, that snapshot is generated explicitly and tracked in source.

The [Frontend Owner](../../frontend/README.md#documentation-site) owns VitePress
and Mermaid integration using its existing pnpm lock. `pnpm build`,
`pnpm build:demo` and `pnpm build --outDir <path>` all include `docs/` inside
their selected output directory. Distribution uses that custom-output build
and packages the resulting tree; it does not copy or rewrite Owner Markdown
into a second documentation source. `pnpm --dir frontend docs:build` and
`pnpm --dir frontend docs:check` build and verify the site independently.

Boot owns [profiles and Project/Group/Scenario composition](../../server_boot_jvm/README.md#platform-and-preview)
and [page forwards and production configuration](../../server_boot_jvm/README.md#pages-and-configuration).
Distribution consumes that composition without creating platform Owners or
another maintained YAML default.

## Source launch

`run_local_runtime.py` owns one local Server/Adapter and its optional Java and
Android Workers. Install `distribution/server/requirements-preview.txt` once.
The default `preview` keeps the five Batch Checks capabilities, SMS and Messages,
60 Demo Workers and 20 Workers in each App Group. `consumer` starts the clean
Server/Adapter; `default` starts the plain platform. Neither implicitly selects
business resources or starts Workers.

```powershell
python run_local_runtime.py --profile preview
python run_local_runtime.py --profile consumer --runtime-config xa-android/config/messages-runtime.yaml --groups xa-android/config/worker-groups.json --android-count 1
python run_local_runtime.py --profile consumer --runtime-config distribution/server/examples/mixed-runtime.yaml --groups distribution/server/examples/mixed-groups.json --java-config distribution/server/examples/mixed-java-workers.json --java-count local-java-workers=5 --android-count 3 --work-dir .runtime/mixed
```

Java and Android are independent options on the same environment. Counts mean
Worker instances, not JVM threads or emulator counts. Android uses the existing
Lab1/2/3 APKs on one connected device; select `--android-device <serial>` if more
than one device is available. The source entry builds the checked Runtime/SDK ZIP
pair and independent consumer. An unchanged source tree/commit and matching ZIP
bytes reuse the candidate pair, avoiding timestamp-only Maven republication.
Launcher builds use Gradle's single-use mode so they leave no new build daemon.
Android runs use the verified Runtime ZIP itself; the existing consumer remains
the sole archive-format validator. It verifies cached APKs by build inputs and bytes, installs
only selected changed APKs and starts them. It creates no emulator, clears no App
data, changes no freezer setting and takes over no already running App.

Common parameters are `--server-port`, `--adapter-port`, `--redis-url`,
`--redis-scope`, `--work-dir`, `--runtime-config`, `--groups`, `--java-config`,
repeatable `--java-count GROUP=N`, `--no-java-workers`, `--android-count` and
`--android-device`. Default HTTP/Adapter pairs remain Preview 18500/18503 and
Consumer 18182/18183; the plain platform uses HTTP 18082 without a default Adapter.
Java Host details, properties/templates, events and seed stay in its JSON.
There are no business example selectors or additional profiles.

Precedence is explicit CLI, then the selected file, then profile defaults.
Unset CLI options do not overwrite file values. Local coordinates must be
literal values; the local YAML must not hide them behind imports or profile
switches. Runtime YAML still belongs to Spring for resource and Scenario
validation; the launcher reads only process coordinates. Files are captured
before launch, CLI paths become absolute, Java relative paths resolve against
the Java file, and external business storage paths/seed file URIs are absolute.
An explicit work directory supplies `data/scenario-workers`, default scenario
configuration and archives; explicitly configured business file paths remain
owned by that configuration. `--groups` supplies a complete registration object,
not a merge with demonstration resources.

The Server command, readiness, Java Runtime URL, APK Runtime URL and ADB mappings
use one resolved coordinate set. Resource initialization and dependency validation
remain inside Server before Adapter startup. Choosing a Worker never enables a
Pool or registers a capability. The startup gate proves Worker identity and
connection, not qualification, installed business Handlers or successful execution.

The local entry ignores inherited business variables, including `SERVER_PORT`,
`PREVIEW_*`, `XA_MASS_*`, Spring environment overrides and JVM injection options.
OS/toolchain discovery such as PATH, JAVA_HOME and Android SDK locations remains.
`XA_MASS_LOCAL_REDIS_URL` is an explicit parent-to-child secret transport set by
the launcher, never an input read from the shell. Frontend builds explicitly use
API mode and do not write `.env.local`; distribution output is staged separately
from developer `frontend/dist` and each running instance gets private artifacts.

Exit stops owned Workers before Server and restores touched ADB mappings. APKs,
configuration and inventory remain. Explicit scopes remain; only Preview's own
fresh `test_products_*` scope and private archives are removed. Cleanup failure
identifies remaining resources. There is no automatic restart or task submission.
The [Preview delivery](PREVIEW.md) and portable [Android consumer](../../xa-android/README.md)
retain their artifact-specific entrypoints and use these existing owner boundaries.

## External consumer workbench deployment

Messages can independently opt in with external Runtime YAML and Group JSON.
The [Messages guide](src/main/dist/examples/messages/README.md) is packaged with
the canonical Android sample files under `examples/messages`. It uses the existing
consumer launch parameters and requires neither a Scenario store nor Simulator.
The archive verifier checks this example set without loading it as default config.

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
Release. Formal delivery separately follows the [3.1 release contract](RELEASE-3.1.md):
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
.\gradlew.bat :distribution:server:distZip "-PxaMassVersion=3.1.0"
```

The archive is written under `distribution/server/build/distributions`. After
extracting it on a machine with Java 21, run from the extracted Runtime root:

```powershell
java -jar .\lib\xa-mass-server-jvm-3.1.0.jar `
  --spring.profiles.active=default `
  --spring.web.resources.static-locations=file:frontend/dist/ `
  --xa.mass.redis.url=redis://127.0.0.1:6379/15
```

Select the built-in clean `consumer` deployment preset explicitly:

```powershell
java -jar .\lib\xa-mass-server-jvm-3.1.0.jar `
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
