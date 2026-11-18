# XA Mass 4.4 release and upgrade contract

Status: version-scoped 4.4 contract; target 4.4.0. Publication requires complete
manual Proof at the exact merged candidate commit. This document does not record
publication, deployment or passing public acceptance. Published 4.3 artifacts
remain immutable.

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
`xa.mass.task-retention.archive-directory` configuration are not read or migrated.
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

This consumer delivery change adds no Worker protocol or existing Runtime Task API
change relative to 4.3.2. The new Storage APIs belong to the history change above.
Use the matching 4.4.0 kit/SDK for this Release's recorded acceptance; no existing
APK is replaced automatically and prior-version witnesses retain their versions.

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
retired archive-directory setting from launch inputs. Create a fresh database,
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

Normal GCP acceptance is small and specific to this Release:

- Verify readiness, public Console, unauthenticated API rejection and valid
  authenticated access. Confirm Batch Checks and Messages remain enabled with
  the preserved external configuration and new-scope Group/resource registrations.
- Start a small external Host from the published 4.4.0 kit and run its shipped
  finite round. Independently verify Item identities/states, MD5 Results, terminal
  Task and complete export. Use the normal standing acceptance identity and a
  temporary Worker credential under the host's existing private-file procedure.
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
