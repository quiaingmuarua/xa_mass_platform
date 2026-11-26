# XA Mass 4.6 release and upgrade contract

Status: current 4.6 release input. Publication and GCP retained-scope upgrade
records are appended after verified completion. Published artifacts remain
immutable. Actual host installation state belongs to the host records reached
through the [Release Preview guide](../../doc/guide/operations/deploy.md#release-preview).

## Release preparation

`.github/scripts/prepare_release.py` reads this file for exact `v4.6.<patch>`
tags. Each minor requires its own contract; promotion has no major-version
fallback. A candidate uses its exact version and merged commit in complete
manual Proof, including SMS Reception Preview, then promotes those same three
Runtime/SDK/Simulator ZIPs and their three SHA256 sidecars. Manifest schemas are
6/1/3. Published candidates are not relabeled or rebuilt on GCP. Publication and
deployment are separate recorded facts.

## 4.6.0 WorkerGroup lifecycle management, offline Worker cleanup and Storage workspace

4.6.0 delivers:

- **WorkerGroup & offline Worker resource management**: paginated admin
  directory (`GET /api/v1/worker-groups`), metadata updates
  (`PUT /api/v1/worker-groups/{workerGroupId}`), zero-active-worker safe Group
  removal (`DELETE /api/v1/worker-groups/{workerGroupId}`), and single offline
  Worker Runtime cleanup
  (`DELETE /api/v1/worker-groups/{workerGroupId}/workers/{workerId}`), backed by
  O(log N) Kernel Score state summaries, atomic inactive-only Score removal,
  JSON-free conditional Binding cleanup, non-terminal Task keyset scanning, and
  Matching state/pool purges (`#143`).
- **Read-only Project resource topology & 3-tab `/runtime/workers` Console**:
  extended `GET /api/v1/runtime-view/worker-groups/bindings` with `projects`
  topology (`exclusiveInputOwner`) and upgraded `/runtime/workers` to **Worker
  观察**, **Group 管理**, and **资源绑定**, plus Staging Ingress allowlist rules
  for the new admin WorkerGroup routes (`#143`).
- **Independent Storage Task history workspace**: exposed `/storage/tasks` as a
  dedicated finite Task history workspace (`#142`).
- **Runtime Tasks & workbench polish**: active RPC channel board,
  retention-window statistics, App Checks approval guard, and global tab polish
  (`#140`, `#141`).

### Retained-scope upgrade constraints from 4.5.0

Upgrading an existing 4.5.0 Release Preview Runtime to 4.6.0 reuses the existing
Redis scope (`profile_long_preview_4_4`), SQLite finite Task history database
(`/var/lib/xa-mass/task-history-4.4.sqlite`, schema 1), external `users.json`,
credit accounts, Task/request identities, business configuration, gateway
credentials and standing Worker Host (`4.4.2`) / Performance kit (`4.4.4`). It
requires no Redis scope reset, SQLite schema migration, `users.json` change or
Worker Host/SDK replacement.

Apply these 4.6.0-specific host updates during the stopped-Runtime maintenance
window:

1. **Staging Ingress alignment**: regenerate and validate Nginx configuration
   from the 4.6.0 Runtime ZIP's `staging-ingress` renderer to allowlist the new
   admin WorkerGroup management routes (`GET /api/v1/worker-groups`,
   `PUT /api/v1/worker-groups/{workerGroupId}`,
   `DELETE /api/v1/worker-groups/{workerGroupId}`,
   `DELETE /api/v1/worker-groups/{workerGroupId}/workers/{workerId}`). Keep
   `/actuator/prometheus` bound to loopback on the Runtime host only; do not
   expose it through public Nginx ingress.
2. **Post-restart standing Worker check**: verify the standing 20 Workers after
   Runtime restart; if the Host remains live with desired `RUNNING` and runtime
   `STOPPED`, resume the original 20 identities explicitly via the existing Ali
   Lab start procedure and record manual resume separately from automatic
   reconnection.
3. **Runtime, management API & Console acceptance**: verify
   `/actuator/health/readiness`, loopback `/actuator/prometheus` (`xa_mass_*`
   series), authenticated `GET /api/v1/worker-groups`,
   `GET /api/v1/runtime-view/worker-groups/bindings` (including `projects`), a
   temporary dynamic Group register/update/delete round-trip through public
   ingress, authenticated `/runtime/workers` (`Worker 观察` / `Group 管理` /
   `资源绑定`) and `/storage/tasks`, external finite + RPC MD5 checks, and
   SQLite `PRAGMA quick_check`.

## Finite Task history and RPC retention

4.6 preserves immutable SQLite finite Task history (schema 1) and opt-in
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

Completed 4.5, 4.4 and earlier historical records remain immutable:

| Version | Record |
| --- | --- |
| 4.5.x | [4.5 release and upgrade contract](RELEASE-4.5.md) |
| 4.4.x | [4.4 release and upgrade contract](RELEASE-4.4.md) |
| Capability evidence | [Dated external public closeout](../../doc/guide/capabilities.md#external-public-closeout) |
