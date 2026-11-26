# Worker Simulator Kit Distribution

Status: publishable XA Mass Worker Simulator kit boundary.

This module packages the existing [Worker Simulator](../../worker_simulator_jvm/README.md)
installation together with finite-round, external-capacity and deployed call-performance commands into one versioned ZIP. It
owns packaging and verification only: the Simulator module owns the Host,
`distribution/acceptance/finite_round.py` owns the small one-round contract, and
[Loaded Recovery](../../integrations/worker-loaded-recovery/README.md) owns the compiled
external-capacity workload and its bounded consumer orchestration.
[Worker Call Performance](../../integrations/worker-call-performance/README.md#deployed-runtime-consumer)
owns the deployed mode of its existing runner and Java Harness.

```powershell
$kitVersion = Read-Host "Candidate SemVer (major.minor.patch)"
.\gradlew.bat "-PxaMassVersion=$kitVersion" `
  :distribution:worker-simulator:workerSimulatorDistributionTest
```

The result is `build/distributions/xa-mass-worker-simulator-<version>.zip`, rooted at
`xa-mass-worker-simulator-<version>/`. A local build is a candidate, not a published
Release:

| Path | Source |
| --- | --- |
| `bin/`, `lib/`, `config/` | `:worker_simulator_jvm:installDist` |
| `acceptance/finite_round.py` | [finite_round.py](../acceptance/finite_round.py), standard-library Python |
| `acceptance/run_loaded.py`, `acceptance/support/` | Existing Integration consumer and shared owned-process/resource helpers |
| `acceptance/loaded/` | Compiled `:integrations:worker-loaded-recovery:installDist`; no source build at consumption |
| `acceptance/performance/run_worker_call_performance.py` | Existing Performance runner with `--deployment-config` |
| `acceptance/performance/reporting.py` | Performance-owned pure report interpretation shared with external collection |
| `acceptance/performance/harness/` | Compiled Performance Harness and its dependencies |
| `examples/remote-host.json` | A remote Host template with a gateway credential file |
| `examples/persistent-20-host.json`, `examples/xa-mass-worker-host.service` | Ordinary persistent Host and systemd templates, without a health task or timer |
| `examples/performance-deployment.json` | Private-file references and declared deployed Performance coordinates |
| `README.md` | The [user-facing kit guide](src/main/dist/README.md) shipped inside the ZIP |
| `manifest.json` | Schema 3: version, commit, Java version and Host, round, loaded and Performance entry paths |

The kit lets any machine act as an external user of a published Runtime without a checkout
or a build. It is a test and acceptance capability, not a production Worker.
The [current 4.7 contract](../server/RELEASE-4.7.md#published-worker-consumers)
records the supported Consumer/Server attribution; each immutable kit and target
Server is pinned independently for deployed Performance. Use the selected
Release's compatibility and configuration requirements; this makes no general
cross-version compatibility promise.

Current public-network CI uses only the existing
[External Performance entry](../../integrations/worker-call-performance/README.md#external-actions-execution).
The Loaded public Actions route was retired on 2026-11-19; the
[dated public closeout](../../doc/guide/capabilities.md#external-public-closeout)
retains its earlier results and unpassed full target. The packaged Loaded command
remains consumed by the local ZIP/TLS delivery fixture, with schema 3 unchanged.

`python -m distribution.archive_tools simulator` validates the archive: its root, manifest,
entries, non-empty Host and Harness libraries, and the absence of inventory, private or credential files.
In CI, Candidate Distribution Archives builds the ZIP once. Runtime And Worker SDK
Distribution extracts that exact ZIP as its external Java Host and runs the shipped round
command through the packaged gateway, as a non-administrator user, and checks the external
capacity entry with a small fixture. These local delivery checks are separate
from the current manual public Performance observation.

The [kit examples](src/main/dist/examples/README.md) separate a long-lived ordinary
20-Worker Host from an on-demand Performance Host. They may share a physical client,
but share its CPU, memory, source ports and network failure domain; the persistent
Host is not an independent external witness. Distribution supplies no scheduler,
low-rate Performance profile or health-judgment loop. Deployment configuration
selects only the existing open-loop rates and windows; it creates no proof lane.
