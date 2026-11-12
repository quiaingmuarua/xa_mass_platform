# Worker Simulator Kit Distribution

Status: publishable XA Mass Worker Simulator kit boundary.

This module packages the existing [Worker Simulator](../../worker_simulator_jvm/README.md)
installation together with finite-round and external-capacity commands into one versioned ZIP. It
owns packaging and verification only: the Simulator module owns the Host,
`distribution/acceptance/finite_round.py` owns the small one-round contract, and
[Loaded Recovery](../../integrations/worker-loaded-recovery/README.md) owns the compiled
external-capacity workload and its bounded consumer orchestration.

```powershell
.\gradlew.bat "-PxaMassVersion=4.2.0" `
  :distribution:worker-simulator:workerSimulatorDistributionTest
```

The result is `build/distributions/xa-mass-worker-simulator-4.2.0.zip`, rooted at
`xa-mass-worker-simulator-4.2.0/`:

| Path | Source |
| --- | --- |
| `bin/`, `lib/`, `config/` | `:worker_simulator_jvm:installDist` |
| `acceptance/finite_round.py` | [finite_round.py](../acceptance/finite_round.py), standard-library Python |
| `acceptance/run_loaded.py`, `acceptance/support/` | Existing Integration consumer and shared owned-process/resource helpers |
| `acceptance/loaded/` | Compiled `:integrations:worker-loaded-recovery:installDist`; no source build at consumption |
| `examples/remote-host.json` | A remote Host template with a gateway credential file |
| `README.md` | The [user-facing kit guide](src/main/dist/README.md) shipped inside the ZIP |
| `manifest.json` | Schema 2: version, commit, Java version and all three entry paths |

The kit lets any machine act as an external user of a published Runtime without a checkout
or a build. It is a test and acceptance capability, not a production Worker. It ships from the
same Release as the Runtime and makes no cross-version promise.

`python -m distribution.archive_tools simulator` validates the archive: its root, manifest,
both entries, non-empty `lib/`, and the absence of inventory, private or credential files.
In CI, Candidate Distribution Archives builds the ZIP once. Runtime And Worker SDK
Distribution extracts that exact ZIP as its external Java Host and runs the shipped round
command through the packaged gateway, as a non-administrator user, and checks the external
capacity entry with a small fixture. The explicit manual public campaign is separate.
