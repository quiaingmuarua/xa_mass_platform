# Worker SDK Distribution

Status: publishable XA Mass Worker SDK boundary.

This module aggregates the four owner-published Worker SDK artifacts into a
versioned Maven Repository ZIP. It owns packaging and verification only; the
source modules remain the contract and implementation owners.

```powershell
.\gradlew.bat "-PxaMassVersion=3.0.0" `
  :distribution:worker-sdk:workerSdkDistributionTest
```

The resulting archive is
`build/distributions/xa-mass-worker-sdk-3.0.0.zip`. Its `repository/` directory
can be used as an ordinary Maven repository after the archive and manifest
have been verified by the consuming release resolver.

The focused distribution task copies the real [Android consumer](../../xa-android/README.md)
outside the checkout and assembles all four APKs from the ZIP. SDK archive/POM
checks remain here; the independent build checks actual resolved JAR/AAR hashes.
In CI, Candidate Distribution Archives produces the ZIP once and APK Assembly
performs that consumption build, avoiding an additional empty Android project.
The HTTP producer's root task path is `:android-capability-http`; its source
location and published coordinate are independent of that Gradle mapping.

Archive validation is owned by `python -m distribution.archive_tools sdk`.
Without `--consumer-project` it checks only publication bytes and metadata.
The Gradle distribution test supplies the standalone consumer explicitly; CI
performs its real SDK compilation in Android APK Assembly without repeating it.
