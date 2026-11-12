# Worker Simulator Kit Distribution

Status: publishable XA Mass Worker Simulator kit boundary.

This module packages the existing [Worker Simulator](../../worker_simulator_jvm/README.md)
installation together with the finite-round acceptance command into one versioned ZIP. It
owns packaging and verification only: the Simulator module owns the Host, and
`distribution/acceptance/finite_round.py` owns the one-round contract.

```powershell
.\gradlew.bat "-PxaMassVersion=4.1.0" `
  :distribution:worker-simulator:workerSimulatorDistributionTest
```

The result is `build/distributions/xa-mass-worker-simulator-4.1.0.zip`, rooted at
`xa-mass-worker-simulator-4.1.0/`:

| Path | Source |
| --- | --- |
| `bin/`, `lib/`, `config/` | `:worker_simulator_jvm:installDist` |
| `acceptance/finite_round.py` | [finite_round.py](../acceptance/finite_round.py), standard-library Python |
| `examples/remote-host.json` | A remote Host template with a gateway credential file |
| `README.md` | The [user-facing kit guide](src/main/dist/README.md) shipped inside the ZIP |
| `manifest.json` | Version, commit, Java version and both entry paths |

The kit lets any machine act as an external user of a published Runtime without a checkout
or a build. It is a test and acceptance capability, not a production Worker. It ships from the
same Release as the Runtime and makes no cross-version promise.

`python -m distribution.archive_tools simulator` validates the archive: its root, manifest,
both entries, non-empty `lib/`, and the absence of inventory, private or credential files.
In CI, Candidate Distribution Archives builds the ZIP once. Runtime And Worker SDK
Distribution extracts that exact ZIP as its external Java Host and runs the shipped round
command through the packaged gateway, as a non-administrator user.
