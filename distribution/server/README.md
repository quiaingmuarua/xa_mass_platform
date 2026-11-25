# XA Mass Server Runtime distribution

This module owns Runtime and Scenario Preview ZIP delivery, frontend and
documentation-site builds, diagnostic dictionaries, Preview process assembly
and archive verification.

The current [4.4 release contract](RELEASE-4.4.md) retains version-specific
requirements, the 4.4.5 publication and accepted retained-scope upgrade, and fixed
links to earlier release records. Current mechanisms remain in their Owners.
A Release publishes three components: the Runtime, the Worker SDK and the Worker Simulator kit.
Use the [Release Preview guide](../../doc/guide/operations/deploy.md#release-preview)
for GCP's role, deployment cadence and environment handoff rather than inferring
a permanent compatibility obligation from one release's upgrade procedure.
That environment handoff is not a reading or validation prerequisite for ordinary
source changes; use local fixtures and the selected CI proofs for development.
Distribution consumes the sole Boot JAR produced by
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
60 Demo Workers and 20 Workers in each App Group. `consumer` starts Server/Adapter
with Batch Checks and Messages enabled, requiring external business and resource
configuration; it starts no Worker by default. `default` starts the plain platform.
SMS keeps the Preview assembly. Selecting a profile never installs Worker Handlers.

> **Data retention warning:** the default Preview command below uses a temporary
> Redis scope. Normal shutdown deletes its Tasks, Results, credit balances and
> transactions, other scoped Redis state, and this run's private Task archives.
> Configuration files and Worker inventory remain; they do not preserve that runtime data.
> For retained development data, supply `--redis-scope <stable-name>` and reuse
> the same `--work-dir` from the first launch. A work directory alone does not disable cleanup.

```powershell
python run_local_runtime.py --profile preview
```

For a local consumer demonstration, first copy
`xa-android/config/messages-runtime.yaml` or
`distribution/server/examples/mixed-runtime.yaml` to your input directory. These
samples omit the required `xa.mass.users.seed`. Set it explicitly in the copy;
the Worker launch examples below use `classpath:users/development-directory.json`
with the public local development keys. For example, after preparing the copies:

```powershell
python run_local_runtime.py --profile consumer --runtime-config .runtime/inputs/messages-runtime.yaml --groups xa-android/config/worker-groups.json --android-count 1
python run_local_runtime.py --profile consumer --runtime-config .runtime/inputs/mixed-runtime.yaml --groups distribution/server/examples/mixed-groups.json --java-config distribution/server/examples/mixed-java-workers.json --java-count local-java-workers=5 --android-count 3 --work-dir .runtime/mixed
```

The launcher's Worker connection check currently uses the public development
administrator key and has no private-key option. A directory containing only
private keys cannot pass that check when local Java or Android Workers are selected.
For private-user deployment, prepare an explicit `file:` users seed using the
[combined consumer example](src/main/dist/examples/consumer-business/README.md),
start the Runtime without local Worker options, and connect Workers independently.
API user keys, Worker gateway credentials and Worker identities have separate roles;
see the [Server access-key boundary](../../server_jvm/README.md#access-key-front-door).

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

The local CLI uses only quantity startup. Without a supplied `startupPlan`, each
configured Java Group needs an explicit `count` or `--java-count GROUP=N`; it does
not infer a count from retained inventory. A supplied plan may contain only
`workerCounts` and is passed
through without CLI count overrides. Coordinate and scheduled-stop plans belong
to the standalone [Simulator Host](../../worker_simulator_jvm/README.md#embedded-startup-plan)
and explicit proof fixtures. Quantity startup preserves retained inventory,
grows it through the Simulator when necessary, and selects a subset when counts
shrink; zero starts none. Counts are integers in `0..15000`, and `newEnvironment`
rebuilds must be performed separately. `--no-java-workers` selects no Java Host.
These controls do not add a Simulator business mode or change Group resources.

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
Both source and packaged Preview entries print the temporary-scope data warning
before building or starting processes; `--help` explains how to retain the scope.
Task retention still applies when the launcher preserves the scope.
The [Preview delivery](PREVIEW.md) and portable [Android consumer](../../xa-android/README.md)
retain their artifact-specific entrypoints and use these existing owner boundaries.

### Local HTTP client

The shared launcher/proof HTTP client reuses one connection per thread and
endpoint while requests remain less than five seconds apart. It closes an idle
connection before the next request; failed or uncertain mutations are surfaced
without automatic replay. Optional headers belong to each request and are not
retained on the connection. For `/api/` requests without explicit `Authorization`,
the client supplies the public development administrator Bearer key, authenticated
through the normal Server directory. The local Worker connection check uses this
default and has no private-key override; see the source-launch limitation above.

## External consumer workbench deployment

Consumer enables Batch Checks and Messages by default, without starting Workers.
The [combined example](src/main/dist/examples/consumer-business/README.md) supplies
both businesses, Group registration and a check seed from one packaged source.
Missing enabled-business configuration fails startup; no Preview fallback is used.
Existing raw consumer deployments must explicitly disable both businesses before
upgrading. Single-business deployments disable the other one. SMS remains Preview-only.

Messages-only deployments use external Runtime YAML and Group JSON and explicitly
disable Batch Checks.
The [Messages guide](src/main/dist/examples/messages/README.md) is packaged with
the canonical Android sample files under `examples/messages`. It uses the existing
consumer launch parameters and requires neither a Scenario store nor Simulator.
The archive verifier checks this example set without loading it as default config.

Batch Checks-only deployments explicitly disable Messages and use external host
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

The `consumer` profile supplies Server/Adapter coordinates but no host Project or
Group bindings. Enabled business modules contribute their Projects from external
configuration. For raw-only use, explicitly disable both businesses and supply
an explicit Project; `default` has no bound
Group IDs and uses the registered catalog, while other Projects declare their
bindings. The name need not equal the profile. The Console accepts any declared
Project, including an environment whose only Project is `agentforge`.

Deployment proceeds in order: declare the Project at startup, register its Group
descriptors through Runtime `group-config-json` or `/api/v1/worker-groups/{id}:register`, connect Workers
with the required installed Handlers, then prepare/call RPC Tasks or create/import/
approve finite Tasks in `/runtime/tasks?project=agentforge`. Registration alone
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
Release. Formal delivery separately follows the [current release contract](RELEASE-4.4.md):
publish immutable artifacts, update the consumer's exact version/commit/checksums,
resolve into a separate installation, and switch deliberately. Apply the target
release's configuration and data instructions; when it requires a fresh Redis scope,
register Groups and prepare required Tasks there. Keep the
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
$runtimeVersion = Read-Host "Candidate SemVer (major.minor.patch)"
.\gradlew.bat :distribution:server:distZip "-PxaMassVersion=$runtimeVersion"
```

The archive is written under `distribution/server/build/distributions`. After
extracting it on a machine with Java 21, run from the extracted Runtime root:

```powershell
$manifest = Get-Content -Raw .\manifest.json | ConvertFrom-Json
$serverJar = Join-Path (Get-Location) $manifest.serverJar
java -jar $serverJar `
  --spring.profiles.active=default `
  --spring.web.resources.static-locations=file:frontend/dist/ `
  --xa.mass.redis.url=redis://127.0.0.1:6379/15
```

Select `consumer` with the required external business configuration and Groups;
the [combined deployment commands](src/main/dist/examples/consumer-business/README.md)
use the manifest's JAR and the same three inputs as the source entry. For a raw-only
consumer, make the business disablement explicit in its Runtime YAML:

```yaml
xa:
  mass:
    scenarios:
      app-checks:
        enabled: false
      messages:
        enabled: false
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

## Trusted staging ingress

The opt-in [staging examples](src/main/dist/examples/staging-ingress/README.md)
provide two HTTPS/WSS Nginx entrypoints: an administrator host that forwards only
well-formed Runtime access keys, and a Worker host behind a shared Basic gate, with
positive route/method lists and bounded connection admission.
Ordinary HTTP requests can negotiate HTTP/2 over TLS with HTTP/1.1 fallback;
Worker WebSockets retain their HTTP/1.1 Upgrade contract. Upstream proxying stays HTTP/1.1.
They are delivered with the Runtime ZIP but are never auto-loaded. The renderer
is available from the checkout as `python -m distribution.server.staging_ingress`
and from the extracted example directory as `python -m staging_ingress`.
The archive admits only the three named configuration-renderer Python files in
that example; it still rejects Python runtime launchers, environments and proofs.
Rendering is an explicit preparation step, not a Runtime dependency or service.

Gateway credentials do not replace TaskCaller or establish Worker ownership.
Backend/Redis listeners and internal Adapter APIs remain private. The current
slice supports an external Java Host; Android SDK credential transport is tested,
but Android App provisioning is a separate slice. Secrets remain outside archives.

The existing Runtime Distribution Linux lane runs the actual Nginx/TLS contract
proof and a packaged Runtime with the same-build Worker Simulator kit as its external Java
Host; its finite rounds run the kit's shipped round command as a Project-scoped operator. Use
`--staging-ingress --simulator-home <extracted kit root>` on the existing proof runner;
HTTP/2-enabled Nginx, OpenSSL, htpasswd and JDK 21 must be installed. The same
gateway proof checks JDK stream limits, POST receipt across GOAWAY and HTTP/1.1 fallback;
these mock transport checks establish no Runtime throughput. The normal Windows Runtime proof
still checks its original boundaries; it does not pretend to prove Linux ingress.
The [Release Preview guide](../../doc/guide/operations/deploy.md#release-preview)
owns the distinction between CI evidence, a published Release and actual GCP
deployment acceptance. The gateway example does not decide when to deploy.

## Finite Task history

Runtime carries the SQLite JDBC implementation used by Server's finite history Owner.
The database, WAL and SHM files belong outside immutable installations and are never
packaged. `xa.mass.task-retention.database-path` selects the local database; the old
archive-directory property has no alias. API history queries are explicitly separate
from Runtime reads.

A fixed local environment preserves its database across restarts. Generated temporary
Preview scopes instead own a database under their run output and remove only that file
and its sidecars after the owned Server has stopped successfully. Explicit external
paths are not temporary cleanup targets. See
[Preview history](PREVIEW.md#task-result-history) and
[Server retention](../../server_jvm/README.md#finite-task-retention) for 1h/7d/30d policy,
backup, and independent historical expiry. Packaging adds no maintenance process.
