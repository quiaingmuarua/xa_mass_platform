# Perf Load Model

## 1. Scope

This topic covers the engine hot-path load model used to expose storage and callback pressure.

The model is not a product benchmark.
It is a regression tool for:

- callback hot paths
- task progress recomputation
- resource release cost
- redispatch/refill pressure
- storage call count explosion

## 2. Core Code Path

- [TaskFlowLoadModelRunner.java](D:/code_project/geekrun/xa_mass_platform/xa-mass-testing/src/main/java/com/xa/mass/testing/perf/TaskFlowLoadModelRunner.java)

The runner focuses on:

- callback -> progress recompute -> resource release -> redispatch

It reports:

- wall-clock totals
- callback concurrency and callback cost
- release cost
- storage probe call counts and total time

## 3. Commands

Default:

```bash
./mvnw -pl xa-mass-testing -am -Dexec.classpathScope=compile -Dmaven.test.skip=true org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.perf.TaskFlowLoadModelRunner
```

Heavier example:

```bash
./mvnw -pl xa-mass-testing -am -Dexec.classpathScope=compile -Dmaven.test.skip=true -Dmass.load.messages=2048 -Dmass.load.workers=16 -Dmass.load.batchSize=8 -Dmass.load.callbackThreads=32 -Dmass.load.retryFailureEveryNth=7 org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.perf.TaskFlowLoadModelRunner
```

Artifact:

- JSON report under `xa-mass-testing/target/perf-reports/`

## 4. How To Read Results

Look at these first:

- `wallClock.totalMillis`
- `callbacks.maxConcurrentCallbacks`
- `release.attemptClosedMillis`
- `storageProbe.getLatestActiveTaskMessageAttempt`
- `storageProbe.getTaskMessageStats`
- `storageProbe.countPendingDispatchableMessages`

Use the runner to answer:

- did this change make a hot path asymptotically worse
- did a race fix accidentally add scan-heavy storage work
- did dispatch cycles or redispatch pressure increase unexpectedly

## 5. CI Placement

Recommended placement:

- smoke run: PR optional or non-blocking
- heavier run and trend comparison: nightly or release

Do not make machine-sensitive throughput thresholds a default PR-required gate unless the CI hardware is stable enough to trust them.
