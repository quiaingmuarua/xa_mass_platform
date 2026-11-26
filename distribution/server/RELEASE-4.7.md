# XA Mass 4.7 release and upgrade contract

Status: current 4.7 release input. 4.7.0 publication and GCP Release Preview
upgrade are pending complete manual Proof, Release promotion and deployment
closeout. Published artifacts remain immutable. Actual host installation state
belongs to the host records reached through the
[Release Preview guide](../../doc/guide/operations/deploy.md#release-preview).

## Release preparation

`.github/scripts/prepare_release.py` reads this file for exact `v4.7.<patch>`
tags. Each minor requires its own contract; promotion has no major-version
fallback. A candidate uses its exact version and merged commit in complete
manual Proof, including SMS Reception Preview, then promotes those same three
Runtime/SDK/Simulator ZIPs and their three SHA256 sidecars. Manifest schemas are
6/1/3. Published candidates are not relabeled or rebuilt on GCP. Publication and
deployment are separate recorded facts.

## 4.7.0 Control-plane SQLite user directory and WorkerGroup runtime-truth management

4.7.0 delivers:

- **Control-plane SQLite foundation & `UserDirectory` persistence**: optional
  Server-owned WAL SQLite database (`xa.mass.control-plane.database-path` /
  `XA_MASS_CONTROL_PLANE_DATABASE`, `PRAGMA user_version=1`, physically
  separate from `task-history.sqlite`) storing `UserDirectory` tables
  (`cp_roles`, `cp_users`, `cp_user_projects`). An empty database bootstraps
  once atomically from `xa.mass.users.seed` without locking or mutating
  `users.json`; subsequent restarts load directly from SQLite while serving all
  authentication (`AccessKeyFilter`) and directory reads from an immutable
  in-memory snapshot with zero SQLite I/O on request hot paths (`#147`, `#150`).
- **Hot/cold storage boundary & architecture guards**: codified the single
  [Storage Split (Hot vs Cold)](../../server_jvm/README.md#storage-split-hot-vs-cold)
  decision table (keeping declarative host `ProjectDirectory` in YAML and all
  scheduling/Matching/Worker/Task/credit hot state in Redis) and enforced
  explicit caller/JDBC allowlists in `ServerArchitectureBoundaryTest` and
  `ScenarioArchitectureTest` (`#147`, `#148`, `#150`).
- **WorkerGroup runtime-truth management & UI polish**: relaxed
  `ServerWorkerGroupInitializer` to skip already-registered Runtime descriptors
  on restart so Console metadata edits persist, unlocked Group edit/remove in
  `WorkerGroupManagementService` and `WorkerGroupManagementView.vue` (retaining
  hard blockers only for active Workers and non-terminal/parked Tasks while
  showing startup config references as informational warnings), and fixed
  `Event Codes` pill truncation and search bar sizing (`#146`).
- **Server Owner documentation convergence**: converged
  [server_jvm/README.md](../../server_jvm/README.md) in place while preserving
  all cross-repository anchors and load-bearing invariants (`#149`).

### Retained-scope upgrade constraints from 4.6.0

Upgrading an existing 4.6.0 Release Preview Runtime to 4.7.0 reuses the existing
Redis scope (`profile_long_preview_4_4`), SQLite finite Task history database
(`/var/lib/xa-mass/task-history-4.4.sqlite`, schema 1), credit accounts,
Task/request identities, business configuration, Nginx staging ingress rules
(unchanged from 4.6.0), gateway credentials and standing Worker Host (`4.4.2`) /
Performance kit (`4.4.4`). It requires no Redis scope reset, Task history schema
migration or Worker Host/SDK replacement.

Apply these 4.7.0-specific host updates during the stopped-Runtime maintenance
window:

1. **Enable `control-plane.sqlite` and bootstrap `users.json`**: configure
   `xa.mass.control-plane.database-path: /var/lib/xa-mass/control-plane.sqlite`
   (or `XA_MASS_CONTROL_PLANE_DATABASE=/var/lib/xa-mass/control-plane.sqlite`
   in `start-runtime` / `runtime.yaml`) while keeping `xa.mass.users.seed`
   pointing to the existing `/var/lib/xa-mass/users.json` for first-start
   bootstrap. On first startup, `UserDirectory` atomically imports all roles,
   users, Project grants and SHA-256 access-key hashes into
   `/var/lib/xa-mass/control-plane.sqlite` (`PRAGMA user_version=1`, WAL mode)
   without locking or modifying `users.json`. Retain `users.json` as an
   unmodified backup until post-upgrade acceptance confirms SQLite contents and
   `PRAGMA quick_check=ok`.
2. **Post-restart standing Worker check**: verify the standing 20 Workers after
   Runtime restart; if the Host remains live with desired `RUNNING` and runtime
   `STOPPED`, resume the original 20 identities explicitly via the existing Ali
   Lab start procedure and record manual resume separately from automatic
   reconnection.
3. **Runtime, SQLite & Console acceptance**: verify
   `/actuator/health/readiness`, loopback `/actuator/prometheus` (`xa_mass_*`
   series), `/var/lib/xa-mass/control-plane.sqlite` (`PRAGMA user_version=1`,
   `PRAGMA journal_mode=wal`, `PRAGMA quick_check=ok`, expected user count, no
   `users.json.lock` created), `/var/lib/xa-mass/task-history-4.4.sqlite`
   (`PRAGMA quick_check=ok`), authenticated `GET /api/v1/me`, `/users`,
   `/worker-groups` (confirming `editable=true` and `deletableByConfig=true`
   for all groups), a temporary dynamic Group register/update/delete round-trip
   through public ingress, external finite + RPC MD5 checks, and 0
   `xa-mass.service` warnings/errors.

## Finite Task history and RPC retention

4.7 preserves immutable SQLite finite Task history (schema 1) and opt-in
terminal RPC Item retention without schema changes. See
[Server retention and history Owner](../../server_jvm/README.md#finite-task-retention),
[Server RPC retention Owner](../../server_jvm/README.md#rpc-terminal-item-retention)
and [Boot configuration](../../server_boot_jvm/README.md#finite-task-retention-configuration).

## Published Worker consumers

The Release retains Runtime, Worker SDK and Worker Simulator kit ZIP components
and SHA256 sidecars. Runtime and SDK manifest schemas are 6 and 1; Simulator kit
schema 3 includes
`performanceEntry=acceptance/performance/run_worker_call_performance.py` and
compiled libraries under `acceptance/performance/harness/`.

Each immutable kit and target Runtime is pinned independently by exact version,
commit and checksum for deployed Performance. The two commits need not match;
neither coordinate uses `latest` or a fallback. This is not a general
cross-version compatibility promise. Existing APKs are not replaced
automatically, and prior-version witnesses retain their versions.

The [Worker Call Performance Owner](../../integrations/worker-call-performance/README.md#deployed-runtime-consumer)
owns deployed workload and measurement limits. Current kit usage and
configuration belong to the
[kit guide](../worker-simulator/src/main/dist/README.md) and
[kit examples](../worker-simulator/src/main/dist/examples/README.md).

## Earlier release records

Completed 4.6, 4.5, 4.4 and earlier historical records remain immutable:

| Version | Record |
| --- | --- |
| 4.6.x | [4.6 release and upgrade contract](RELEASE-4.6.md) |
| 4.5.x | [4.5 release and upgrade contract](RELEASE-4.5.md) |
| 4.4.x | [4.4 release and upgrade contract](RELEASE-4.4.md) |
| Capability evidence | [Dated external public closeout](../../doc/guide/capabilities.md#external-public-closeout) |
