# Worker SDK Distribution

Status: publishable XA Mass Worker SDK boundary.

This module aggregates the four owner-published Worker SDK artifacts into a
versioned Maven Repository ZIP. It owns packaging and verification only; the
source modules remain the contract and implementation owners.

```powershell
.\gradlew.bat "-PxaMassVersion=0.4.0" `
  :distribution:worker-sdk:workerSdkDistributionTest
```

The resulting archive is
`build/distributions/xa-mass-worker-sdk-0.4.0.zip`. Its `repository/` directory
can be used as an ordinary Maven repository after the archive and manifest
have been verified by the consuming release resolver.
