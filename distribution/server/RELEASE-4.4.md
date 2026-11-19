# XA Mass 4.4 release and upgrade contract

Status: current 4.4 release input. 4.4.5 is published and its retained-scope GCP
upgrade was accepted on 2026-11-19; temporary upgrade-material cleanup was
confirmed at 09:58:31 UTC. Published artifacts remain immutable. These are dated
records; actual installation state belongs to the host records reached through
the [Release Preview guide](../../doc/guide/operations/deploy.md#release-preview).

## Release preparation

`.github/scripts/prepare_release.py` reads this file for exact `v4.4.<patch>`
tags. Each minor requires its own contract; promotion has no major-version
fallback. A candidate uses its exact version and merged commit in complete
manual Proof, including SMS Reception Preview, then promotes those same three
Runtime/SDK/Simulator ZIPs and their three SHA256 sidecars. Manifest schemas are
6/1/3. Published candidates are not relabeled or rebuilt on GCP. Publication and
deployment are separate recorded facts.

## 4.4.5 Terminal RPC data retention patch

4.4.5 delivers the shared Performance sampler exit fix, consolidated CI gates
and staged terminal RPC Item retention. It was published from merged commit
`520b7ce314de24ad42ea079f97491919a24852b4` through
[complete manual Proof 44493074012](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/44493074012),
attempt 1, with all 18 jobs successful, including SMS Reception Preview.
[Promotion 44495903690](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/44495903690)
promoted those exact Proof ZIPs to
[Release 4.4.5](https://github.com/quiaingmuarua/xa_mass_platform/releases/tag/v4.4.5)
(Release ID `451116838`). All six GitHub digests and SHA256 sidecars were
verified; no new Runtime build was made on GCP.

| ZIP | SHA256 |
| --- | --- |
| `xa-mass-server-runtime-4.4.5.zip` | `72f08d6e8b7db998854145b30f95c454d813f19a05b918c14d5181e22081ed7f` |
| `xa-mass-worker-sdk-4.4.5.zip` | `de868f92b230c99f92a20a9c573bd9a7805fa88c01e1acb0d14118f5934d56cf` |
| `xa-mass-worker-simulator-4.4.5.zip` | `452be280a5b66819bd9298c200adf66cf53eb47fc61a3b23de5364a536c78665` |

### Retained-scope constraints

The accepted 4.4.0-to-4.4.5 Runtime upgrade reused
`profile_long_preview_4_4` and
`/var/lib/xa-mass/task-history-4.4.sqlite`. It retained external configuration,
user directory and grants, credit accounts, Task/request identities, business
configuration, gateway credentials and Nginx ingress budgets. It included no
schema migration, scope reset, manual Redis sweep, old-scope maintenance or
Worker Host/SDK upgrade. The initial 4.4.0 fresh-scope cutover does not apply
to this patch.

Published defaults keep RPC reclamation off. The selected Preview deployment
explicitly enabled it with the following external configuration, retaining
the existing finite-task and history timings and database path:

```yaml
xa:
  mass:
    task-retention:
      enabled: true
      rpc-item-retention-enabled: true
      rpc-input-retention: 1h
      rpc-terminal-retention: 24h
      interval: 1h
```

Only attributed raw RPC Tasks observed parked are candidates. Age comes from
the terminal Score's band-local time. The 1h stage removes matching input while
Score and Result remain; the 24h stage removes matching input, Result and Score
together and can discard unconsumed Results. Old calls outside the retention
window must not be automatically replayed; new business calls use new message
IDs. ACTIVE Items, finite retention, SMS, other scopes, Worker records and credit
ledgers are outside this cleanup. Disabling the flag stops future deletions and
does not restore removed data; the writer guards remain active.

The [Server RPC retention Owner](../../server_jvm/README.md#rpc-terminal-item-retention)
owns eligibility, fencing and the bounded maintenance lifecycle;
[Boot configuration](../../server_boot_jvm/README.md#rpc-terminal-item-retention-configuration)
owns defaults. A binary downgrade alone cannot recover deleted RPC identities.
The completed upgrade's temporary restore point has been removed; it is not
an available recovery path or a reason to replay uncertain work.

### Accepted deployment and closeout

The 2026-11-19 upgrade installed Runtime 4.4.5 at the commit/checksum above,
with deployed configuration fingerprint
`sha256:8bf0a7ba13db534c8a1ee5b846c0e011ee35b84644103094dcd447755ae7cb2d`.
Only installed-Server pins and the corresponding consumer `serverDeployment`
record changed: the Performance kit remained 4.4.4 and the standing Host 4.4.2.

The first RPC maintenance cycle completed in **23,813ms**, scanning and
examining 892,741 eligible observations across two Task candidates, with
892,741 matches. It deleted **892,741 inputs, 250,774 Item Scores and 69
Results**, with zero skipped Tasks, no failure and no deadline/scan/Task-budget
flag. Private point checks covered 21 identities from the 24h purge stage
and 39 from the input-only stage. These snapshots precede fresh RPC acceptance:

| Existing RPC families | Before | After first GC |
| --- | ---: | ---: |
| input fields | 892,741 | 0 |
| Result fields | 1,279 | 1,210 |
| Item Score members | 892,741 | 641,967 |

Redis `used_memory` fell from **545,717,368 to 148,777,632 bytes**,
a reduction of 396,939,736 bytes (378.55MiB). Remaining Scores and Results retained
their eligibility window. Protected current state was unchanged; the independent
old `profile_long_preview` scope was not cleaned, retained 347 keys, and its
checked protected-state fingerprints remained unchanged.

Both the external three-Item finite baseline using the 4.4.5 kit and three
fresh RPC calls passed independent UTF-8 MD5 checks; the fresh RPC Items also
reached terminal state. Runtime readiness, public API/Worker authentication
and packaged documentation commit were checked. SQLite remained schema 1 and
`quick_check` returned `ok`. Console source was unchanged, so no new browser
interaction acceptance was selected. No capacity or maximum-TPS run was made.

The standing 20 Workers **did not automatically resume** after maintenance:
the Host remained live with desired RUNNING and runtime STOPPED. One explicit
existing Lab start per original coordinate restored all same 20 identities.
The Host process, configuration and inventory were unchanged, and SDK retry
budgets were not changed. This records manual maintenance resume, not an
automatic-recovery proof.

Temporary acceptance credentials were cleared and owned upgrade/acceptance
units exited. At 2026-11-19 09:58:31 UTC, the old 4.4.0 binary, single temporary
restore point and temporary Runtime download were confirmed absent. GCP's
upgrade operation and Ali's independent resume operation were closed, canonical
records updated, and GitHub Server pins read back as 4.4.5 at the recorded
commit/configuration. Separate 4.4.4 Performance kit pins remained unchanged.
Retained old scopes were untouched.

The [complete 4.4.5 procedure and acceptance record](https://github.com/quiaingmuarua/xa_mass_platform/blob/9c30061d0188036f48b296c1be2cdd3872416b45/distribution/server/RELEASE-4.4.md#445-terminal-rpc-data-retention-patch)
preserves the original upgrade preparation, measurement and restore constraints
as completed, version-scoped history.

## Finite Task history

4.4 uses immutable SQLite finite Task history. Server publishes a complete frozen
snapshot before releasing Runtime Item/Result payloads. Runtime reads remain
Redis-only; the separate current-scope `/api/v1/storage/tasks` API reads saved
history with caller/Project checks and explicit cursor pagination. Batch Checks,
Messages and the Console consume that history without restoring execution.

The [Server retention and history Owner](../../server_jvm/README.md#finite-task-retention)
owns publication, expiry, access and failure semantics;
[Boot configuration](../../server_boot_jvm/README.md#finite-task-retention-configuration)
owns defaults. Preserve the database across Runtime restarts; backup requires a
stopped Runtime and the database together with any remaining WAL/SHM files.

The initial 4.4.0 deployment used a fresh scope and database. Old JSONL/manifest
archives and retired `xa.mass.task-retention.directory` /
`xa.mass.task-retention.archive-directory` inputs are not read or migrated.
The supported setting is `xa.mass.task-retention.database-path`. The
[completed 4.4.0 cutover](https://github.com/quiaingmuarua/xa_mass_platform/blob/9c30061d0188036f48b296c1be2cdd3872416b45/distribution/server/RELEASE-4.4.md#selected-gcp-cutover)
is historical and does not prescribe another scope reset for a patch upgrade.

## Published Worker consumers

The Release retains Runtime, Worker SDK and Worker Simulator kit ZIP components
and SHA256 sidecars. Runtime and SDK manifest schemas are 6 and 1; Simulator kit
schema 3 includes
`performanceEntry=acceptance/performance/run_worker_call_performance.py` and
compiled libraries under `acceptance/performance/harness/`.

Each immutable kit and target Runtime is pinned independently by exact version,
commit and checksum for deployed Performance. The two commits need not match;
neither coordinate uses `latest` or a fallback. This is not a general cross-version
compatibility promise. Existing APKs are not replaced automatically, and
prior-version witnesses retain their versions.

The [Worker Call Performance Owner](../../integrations/worker-call-performance/README.md#deployed-runtime-consumer)
owns deployed workload and measurement limits. The kit also ships an ordinary
persistent 20-Worker Host/systemd example, retaining inventory across restarts
without an operator key, Task timer or health-judgment loop. Persistent and
on-demand Hosts use distinct Group/inventory coordinates; on one physical
machine they still share resources and a network failure domain. Current
usage and configuration belong to the [kit guide](../worker-simulator/src/main/dist/README.md)
and [kit examples](../worker-simulator/src/main/dist/examples/README.md).

## Earlier release records

Completed upgrade procedures and patch investigations are retained at the
fixed source commit below. They are historical evidence, not current deployment
steps. The [dated capability evidence](../../doc/guide/capabilities.md#external-public-closeout)
keeps measured scope, case status, cleanup and proof limits.

| Version | Fixed historical record |
| --- | --- |
| 4.4.4 | [Administrator connection reuse, publication and nine-case 10k observation](https://github.com/quiaingmuarua/xa_mass_platform/blob/9c30061d0188036f48b296c1be2cdd3872416b45/distribution/server/RELEASE-4.4.md#444-administrator-connection-reuse-patch) |
| 4.4.3 | [HTTP/2 transport patch and nine invalid observation cases](https://github.com/quiaingmuarua/xa_mass_platform/blob/9c30061d0188036f48b296c1be2cdd3872416b45/distribution/server/RELEASE-4.4.md#443-performance-transport-patch) |
| 4.4.2 | [Consumer cleanup, publication and bounded idle/reconnect acceptance](https://github.com/quiaingmuarua/xa_mass_platform/blob/9c30061d0188036f48b296c1be2cdd3872416b45/distribution/server/RELEASE-4.4.md#442-consumer-closeout-patch) |
| 4.4.1 | [Performance bootstrap correction and consumer attribution](https://github.com/quiaingmuarua/xa_mass_platform/blob/9c30061d0188036f48b296c1be2cdd3872416b45/distribution/server/RELEASE-4.4.md#441-performance-consumer-patch) |
| 4.4.0 | [Initial cutover and acceptance contract](https://github.com/quiaingmuarua/xa_mass_platform/blob/9c30061d0188036f48b296c1be2cdd3872416b45/distribution/server/RELEASE-4.4.md#selected-gcp-cutover) |
| 1.0–4.3 | [Earlier minor release contracts](https://github.com/quiaingmuarua/xa_mass_platform/tree/9c30061d0188036f48b296c1be2cdd3872416b45/distribution/server) |
