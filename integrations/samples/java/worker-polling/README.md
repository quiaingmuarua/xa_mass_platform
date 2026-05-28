# Java Polling Worker Sample

Status: current runnable external worker sample.

Build from repo root:

```bash
./mvnw -pl integrations/samples/java/worker-polling -am -DskipTests package
```

Run:

```bash
java -jar integrations/samples/java/worker-polling/target/worker-polling-java-sample.jar
```

Environment defaults:

```text
MASS_BASE_URL=http://127.0.0.1:8088
MASS_WORKER_ID=java-worker-api-001
MASS_WORKER_KEY=java-worker-key
MASS_PROJECT=crawlerApp
MASS_EVENT_CODE=crawler.fetch-page
```

This sample is included in the root reactor so its `xa-mass-java-sdk`
dependency is built from source. Runtime behavior still uses only the external
polling worker HTTP contract under `/worker-api`.

Implementation note:

- this Java sample uses `integrations/xa-mass-java-sdk`.
- WorkerGroup declaration is still explicit setup code.
- runtime worker lifecycle, heartbeat, polling, dispatch handling, result
  submit, and offline shutdown are handled through `PollingWorkerSession`.
- the sample lives under `integrations/samples` with the other external worker
  samples.
