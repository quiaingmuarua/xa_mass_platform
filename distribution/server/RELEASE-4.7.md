# XA Mass 4.7 release and upgrade contract

Status: current 4.7 release input. 4.7.0 is published and its retained-scope GCP
upgrade completed on 2026-11-26; temporary upgrade-material cleanup was confirmed
at 16:05:53 UTC. Published artifacts remain immutable. Actual host installation
state belongs to the host records reached through the
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

4.7.0 was published from merged commit
`adcb7761dd4968395054ce28a87e082ff54eefc2` through
[complete manual Proof 45851462980](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/45851462980),
attempt 1, with all 18 jobs successful, including SMS Reception Preview.
[Promotion 45854337696](https://github.com/quiaingmuarua/xa_mass_platform/actions/runs/45854337696)
promoted those exact Proof ZIPs to
[Release 4.7.0](https://github.com/quiaingmuarua/xa_mass_platform/releases/tag/v4.7.0)
(Release ID `459627729`).

| ZIP | SHA256 |
| --- | --- |
| `xa-mass-server-runtime-4.7.0.zip` | `b7841c247644cd64f2ffdfbb99a70a3ab12e670d6f49709c8f0c831a67c000e9` |
| `xa-mass-worker-sdk-4.7.0.zip` | `c6ededd0df8f0c8f74e8ebe99f875928fefeb037413571d39b8705e2bba9850f` |
| `xa-mass-worker-simulator-4.7.0.zip` | `b2e7d6392acf835a9dbb0dea2fa94043631a5e19b73329e0cee795af2f3a82fb` |

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

### Accepted deployment and closeout (2026-11-26)

The 2026-11-26 GCP Release Preview upgrade (`upgrade-4.7.0-20261126T160154Z`)
installed immutable Runtime 4.7.0 (`PID 1067612`) at the commit and SHA256
above, retaining `profile_long_preview_4_4`,
`/var/lib/xa-mass/task-history-4.4.sqlite` (schema 1, `PRAGMA quick_check=ok`),
Nginx staging ingress from 4.6.0, standing Host `4.4.2`, and Performance kit
`4.4.4`. Recomputed `configurationId` after adding
`xa.mass.control-plane.database-path: /var/lib/xa-mass/control-plane.sqlite` to
`/etc/xa-mass/runtime.yaml` and updating `start-runtime` is
`sha256:3d50c77086cd4c78d34d3ee43d0a1b8ed20c453b37943b28dd6ed67fe8240b5a`
(previous:
`sha256:821d6284a7768280b2b34388080520affa3b0e3c36a775c12098eff57ce14563`).

- Control-plane SQLite bootstrap succeeded on first startup:
  `UserDirectory` logged `Bootstrapped control-plane SQLite user directory from
  seed (7 users)` into `/var/lib/xa-mass/control-plane.sqlite` (`user_version=1`,
  `journal_mode=wal`, `PRAGMA quick_check=ok`, `cp_roles=2`, `cp_users=7`,
  `cp_user_projects=5`), without creating `/var/lib/xa-mass/users.json.lock`
  or modifying the retained `/var/lib/xa-mass/users.json` backup.
- Health, ingress and observability checks passed: loopback readiness `UP` (11s),
  loopback `/actuator/prometheus` exported 94 `xa_mass_*` lines, public
  `/actuator/prometheus` returned `404`, public `/runtime/workers`,
  `/storage/tasks`, `/runtime/tasks` and `/docs/` returned `200`, public
  `/task-workbench` returned `404`, unauthenticated `/api/v1/worker-groups`
  returned `401`, and `/docs/` served commit `adcb7761d`.
- Public authenticated API checks passed: `GET /api/v1/me` (`1000`, `admin`),
  `GET /api/v1/users` (7 users), `GET /api/v1/worker-groups` (10 registered
  groups, `unreadableCount=0`, all 10 reporting `editable=true` and
  `deletableByConfig=true`), `GET /api/v1/runtime-view/worker-groups/bindings`
  (4 Projects: `app-checks`, `default`, `messages`, `performance-consumer`), and
  dynamic `accept-470-smoke` Group completed `register -> update -> delete`.
- Standing `persistent-workers` observed `10/20` `activeHot` + `10/20`
  `recovery` (Ali Host untouched) and served both a fresh 3-Item RPC MD5 Task
  (`accept-470-rpc-71324d12`, 3/3 succeeded) and a fresh 3-Item finite MD5 Task
  (`task-00086ab02a8a4cbd964afbb72f3599a5`, 3/3 succeeded, JSONL export SHA256
  `4714e955fb46989b003592eeb6d6a5d14c04e24c57137faa7cdbf0f184aca421`) with
  independent UTF-8 MD5 verification passing.
- Old 4.6.0 binary, temporary stage directory and temporary restore point were
  confirmed removed at `2026-11-26 16:05:53 UTC`; GCP `release.json`,
  `deployment.json`, `OPERATIONS.txt`, `operations.log` and GitHub
  `release-preview-load` Server pins (`INSTALLED_RELEASE_TAG=v4.7.0`,
  `INSTALLED_RELEASE_COMMIT`, `PERFORMANCE_DEPLOYMENT_JSON.serverDeployment`)
  were updated and verified.

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
