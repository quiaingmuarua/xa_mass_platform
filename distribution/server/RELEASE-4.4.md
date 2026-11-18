# XA Mass 4.4 release and upgrade contract

Status: version-scoped 4.4 contract. 4.4.0 Runtime and 4.4.1 Consumer kit have been
published; the next patch target is 4.4.2. Its complete manual Proof, publication
and consumer validation remain pending. Published artifacts remain immutable.
Actual installation and acceptance status belongs to host records and
[dated evidence](../../doc/guide/capabilities.md#external-public-closeout), not a live claim here.

## 4.4.2 Consumer closeout patch

The patch keeps the existing Runtime APIs, Worker protocol, workload and result
oracles. Deployed Performance connection cleanup uses a bounded complete scan
with eight concurrent pages of at most 100 original IDs, sharing the existing
30-second deadline. A missing page, read error or deadline remains unconfirmed;
safe counts and error types explain the observation without publishing identities.
It does not retry failed pages or replace unknown submissions. Sampling retains
the five-second interval after successful and failed observations. Prepared
credential copies receive a one-time five-minute cleanup fence before any secret
is written; a campaign must take ownership without extending or replaying work.

Java WSS sockets enable TCP keepalive; where all extended options are supported,
the private SocketFactory sets 60-second idle, 10-second interval and three probes.
The [Java Worker Owner](../../transport/java-worker/README.md) owns fallback and
SOCKS boundaries. This adds no application Ping or Handler execution deadline,
and is not yet a claim that every half-open path has been repaired.

Publish the normal Runtime, Worker SDK and Simulator kit set only after complete
manual Proof at the exact final merged candidate. Then update only the Ali
consumer installations: verify the 4.4.2 kit manifest and digest, stop the owned
persistent Host before switching its installed kit, preserve its external config,
gateway credential and inventory, and restart that service. Set the Performance
kit pins to the published 4.4.2 tag/commit independently. Keep GCP Runtime 4.4.0,
its `INSTALLED_RELEASE_*` pins, scope and database unchanged; this patch schedules
no GCP Runtime deployment or restart. A bounded consumer check must record actual
connections, automatic cleanup and the controlled reconnect behavior, retaining
unknown/failed outcomes. Publication and this validation are still pending.

The project-owned current-tree credential check runs inside existing Proof CI;
it adds no workflow or historical secret audit. Lab remains a trusted loopback
control surface, not a new authenticated public service.

## 4.4.1 Performance consumer patch

4.4.1 corrects the shared Performance Harness bootstrap readiness check. A Group
preview can contain Worker identities retained from earlier campaigns, so checking
that its returned identities all belong to the current Host inventory can falsely
reject a ready campaign. The Harness retains the existing Group preview API and
its 1000-entry ceiling, filters observations to known current Worker IDs and
accumulates nonempty Properties coverage. Groups of up to 1000 current Workers
require coverage of all of them; larger Groups use a stable 1000-Worker sample
from the current inventory. Historical identities and empty Properties do not
count. The whole current fleet must still be RUNNING, connected and HOT; the
240-second bootstrap budget, path cases, rates, warmup, windows and result
assertions are unchanged. This changes no Server, SDK or wire contract.

4.4.1 was published on 2026-11-18 from
`60752f8b51bdc9d291bcbde53de97d65592cd984` through the ordinary Release path;
the [published Release](https://github.com/quiaingmuarua/xa_mass_platform/releases/tag/v4.4.1)
retains the normal three ZIPs and SHA256 sidecars. Manifest schemas remain
Runtime 6, SDK 1 and Simulator kit 3. Do not replace files inside the published
4.4.0 kit or build a separate consumer-only Release format.

The recommended deployed-Performance combination is the published **4.4.1
Simulator kit** with the existing GCP **4.4.0 Runtime**, whose installed commit is
`b28a87ac918a34ffabb4feb8c63a29998a0610db`. Record and validate the kit's Release
tag, exact Release commit and ZIP SHA256 independently of that installed Server
version/commit. The two commits need not match; neither coordinate uses `latest`
or a fallback. This consumer correction requires no GCP Runtime replacement,
Server restart, new scope/database or replacement of the standing 20-Worker Host.
The selected 4.4.0 scope, SQLite database and retained old data below remain as
installed. The dated 2k and 10k Performance observations are linked above;
invalid/saturated cases, unknown requests and unconfirmed runner cleanup remain
explicit. Neither publication nor an operator postcheck makes all cases valid.

## Finite Task history

4.4 includes the immutable SQLite finite Task history introduced on main at
`6fb9de84cb63dbf522fb29cd64e7228ddd9c16a9`. Server publishes a complete frozen
snapshot before releasing Runtime Item/Result payloads. Runtime reads remain
Redis-only; the independent current-scope `/api/v1/storage/tasks` API reads saved
history with its existing caller/Project checks and explicit cursor pagination.
Batch Checks and Messages add their own pure interpretations of those saved rows.
The Console exposes separate history views; it does not silently replace Runtime
reads with database reads or restore historical execution.

The [Server retention and history Owner](../../server_jvm/README.md#finite-task-retention)
owns publication, expiry, access and failure semantics; [Boot configuration](../../server_boot_jvm/README.md#finite-task-retention-configuration)
owns defaults. Grace is 1h, Runtime retention 7d, history retention 30d and the
maintenance interval 1h. The first SQLite deployment has one Runtime writer.
Preserve the database across Runtime restarts; backup requires a stopped Runtime
and the database together with any remaining WAL/SHM files. A live WAL is not a
disposable log.

This is a fresh-scope/database cutover. Old JSONL/manifest archives and the retired
`xa.mass.task-retention.directory` configuration are not read or migrated.
Remove that legacy launch input and any `xa.mass.task-retention.archive-directory`
input; neither is a supported alias for the new database setting.
Do not point 4.4 at an old archive directory or convert it in place. No historical
identity, Task, credit or result migration is included.

## Published Worker consumers

The Release retains Runtime, Worker SDK and Worker Simulator kit ZIP components
and SHA256 sidecars. Runtime and SDK manifest schemas remain 6 and 1; the Simulator
kit becomes schema 3 and adds
`performanceEntry=acceptance/performance/run_worker_call_performance.py`, with
compiled libraries under `acceptance/performance/harness/`.

The same [Worker Call Performance runner and Harness](../../integrations/worker-call-performance/README.md#deployed-runtime-consumer)
now accept an explicit deployment configuration. The deployed mode retains the
original open rates `500,1000,2000`, path cases, warmup and windows, with attribution
and diagnostics off. It starts only its local Host/Harness and reuses stable
per-Group RPC Task IDs. Server coordinates and assignment ceiling are recorded
from the operator's configuration; the consumer does not change or discover them.
It supplies no Server JFR, Redis calibration, saturation, recovery or reference-host
proof and adds no CI lane or low-rate profile.

The kit also ships an ordinary persistent 20-Worker Host/systemd example. It uses
the existing Simulator and gateway credential, keeps inventory across restarts
and needs no operator key. It installs no Task timer or health-judgment loop. A
persistent Host and an on-demand Performance Host require distinct Group/inventory
coordinates; on one physical machine they still share resources and a network
failure domain. See the [kit examples](../worker-simulator/src/main/dist/examples/README.md).

**4.4.0 example erratum:** its immutable `persistent-20-host.json` used the parent
`/var/lib/xa-mass-worker/persistent-20` as `sandboxRoot`, which the Host rejects.
The external operator configuration was corrected to
`/var/lib/xa-mass-worker/persistent-20/data/scenario-workers`; the published ZIP
was not patched. The corrected example is included in 4.4.1 and later kits.

This consumer delivery change adds no Worker protocol or existing Runtime Task API
change relative to 4.3.2. The new Storage APIs belong to the history change above.
The 4.4.0 cutover acceptance uses its matching 4.4.0 kit/SDK; the 4.4.1 Performance
consumer combination is described above. No existing APK is replaced automatically
and prior-version witnesses retain their versions.

## Selected GCP cutover

Follow the existing [Release Preview procedure](../../doc/guide/operations/deploy.md#release-preview)
and its actual host records. The reviewed 4.4 deployment selects:

| Coordinate | 4.4 value |
| --- | --- |
| Redis scope | `profile_long_preview_4_4` |
| SQLite history database | `/var/lib/xa-mass/task-history-4.4.sqlite` |

Set `xa.mass.redis.scope` and `xa.mass.task-retention.database-path` accordingly
through the existing external configuration; `XA_MASS_REDIS_SCOPE` and
`XA_MASS_TASK_HISTORY_DATABASE` are the corresponding Boot inputs. Remove the
retired `directory` and any `archive-directory` settings from launch inputs;
the new YAML uses only `database-path` for this storage location. Create a fresh database,
not a copy of previous Task archives, and preserve Runtime ownership/permissions.

Keep the users file, role/Project grants, daily gateway credentials and external
Batch Checks/Messages configuration, including their enabled switches, applications
and host resource bindings. The new Redis scope starts without the old credits,
Workers, Tasks or business runtime state. Existing host Group declarations must
initialize their registrations in that new scope before Scenario dependency
validation; a Project declaration alone does not register resources. External
Hosts re-Prepare their retained coordinates and receive new Server-issued Worker
IDs in this scope. Do not compare those IDs as a retained-scope recovery witness.
Any account funding required for a tiny paid acceptance is a new explicit,
request-identified adjustment in the new scope, not a copied balance or replayed debit.

Keep the old scope and original archive data untouched. They are retained data,
not the upgrade's temporary backup. Take the existing single upgrade restore point
before stopping/switching the Runtime. Re-render the administrator ingress with
this Release's renderer so the new exact Storage routes are admitted; validate
before loading it and retain TLS, Worker restrictions and capacity settings.
Start one Runtime/Pacer in the new scope, verify the installed artifact coordinate,
readiness, database path and the declared Scenario resources. GCP still runs no
Worker Host, source build or full CI workload.

During the bounded upgrade window, a failed switch may restore the old immutable
binary, its saved configuration and old scope using that restore point. Stop 4.4
first; never run both Pacers against one scope. New-scope operations do not appear
in the old scope: preserve their receipts and database for diagnosis, without
merging histories or replaying uncertain business requests. After acceptance,
remove the previous Release directory and the single temporary restore point and
close the operations record. This does not delete the retained old scope or
original archive data. Later fixes use a new Release, not a permanent local
rollback archive.

Provision the reviewed external consumers once in the new scope: Group
`persistent-workers` serves the ordinary 20-Worker Host with the MD5 event;
`performance-a` and `performance-b` serve Project `performance-consumer` with
`extension.worker.string.md5` and the existing `extension.worker.lab.delay` event.
Declare `any` / `worker.any` enablement and an explicit per-Group `task-rpc` default
refill of `any`, empty target and count `1000` for all three Groups; built-in
`workerId` needs no registration. Add these Groups to the gateway's explicit
allowlist. Create operator user `perfcamp` with only the `performance-consumer`
Project grant, without API administrator access. Retain its standing access key
privately on GCP; copy it to the 43 consumer host only for a campaign and remove
that copy afterwards. Use separate Basic credential IDs `consumer-persistent` and
`consumer-performance`, retaining their permanent private gateway credential
files; the persistent Host's private copy must remain available for service
restarts, while campaign credential copies are removed after use. These gateway
credentials do not authenticate individual Worker identities. Leave capacity
settings unchanged; record the existing host/gateway limits and operator preflight
before a run rather than automatically tuning them. The new host receives no
Server SSH or API administrator credential.

## Acceptance and publication

Complete manual Proof at the exact candidate provides the selected Owner, Redis,
Runtime, Android, distribution and business regression evidence. Publish the
existing workflow's exact verified ZIP bytes; publication and deployment remain
separate recorded facts.

The 4.4.0 GCP cutover acceptance is small and specific to that Release:

Use the standing test identity `accept4`, currently an administrator without an
access key, for external acceptance. Before changing it, privately record its
original name, role and Project grants. Temporarily make it an operator with the
actual declared acceptance Projects (including `default`, business Projects and
`performance-consumer`), then issue one ephemeral access key for the external
acceptance client and browser. Perform administrator operations on GCP with user
`1000`; its private key stays on GCP throughout and is never given to the external
host or browser. Afterwards revoke the `accept4` key, remove its temporary copies
and restore that identity's original name, role and Project grants. Existing daily
users, grants and access keys remain unchanged; no extra acceptance user is needed.

- Verify readiness, public Console, unauthenticated API rejection and valid
  authenticated access. Confirm Batch Checks and Messages remain enabled with
  the preserved external configuration and new-scope Group/resource registrations.
- Start a small external Host from the published 4.4.0 kit and run its shipped
  finite round. Independently verify Item identities/states, MD5 Results, terminal
  Task and complete export. Use the ephemeral operator access above and a temporary
  Worker credential under the host's existing private-file procedure.
- In the actual Console, open the finite workbench history view and the Batch
  Checks/Messages history entries with an appropriately granted user. In the fresh
  scope/database, an accurate empty history view is expected before publication;
  verify API access and rendering without borrowing old-scope data or Mock rows.
  Verify the ordinary finite Task/detail/result view and a tiny affected business
  flow as needed. Record browser evidence separately from API checks.
- Leave the production retention timings unchanged. History publication/content,
  expiry and release semantics have their named repository proofs; an empty new
  database is not a claim that a historical snapshot was published on GCP. Record
  any unexercised on-host history-content path explicitly rather than waiting for
  a long-running health campaign or forcing early retention.
- Stop temporary acceptance processes, revoke their temporary credentials and
  remove private copies. Retain daily credentials, new database, new scope and
  selected production configuration. Record completed/pending checks accurately.

The new 20-Worker service and on-demand Performance command are consumer deployment
capabilities, not a requirement to run a 24-hour health loop, repeated faults or a
large public campaign during ordinary Release acceptance. The packaged local TLS
consumer fixture proves its shipped entry, not public throughput. Historical
capacity results keep their dated version/configuration/status; this Release does
not reopen a closed load investigation or assert the prior 10k business failure
has been repaired.
