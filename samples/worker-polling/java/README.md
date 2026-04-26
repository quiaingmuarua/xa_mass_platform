# Java Polling Worker Sample

Build from repo root:

```bash
./mvnw -f samples/worker-polling/java/pom.xml -DskipTests package
```

Run:

```bash
java -jar samples/worker-polling/java/target/worker-polling-java-sample.jar
```

Environment defaults:

```text
MASS_BASE_URL=http://127.0.0.1:8088
MASS_WORKER_ID=java-worker-api-001
MASS_WORKER_KEY=java-worker-key
MASS_PROJECT=crawlerApp
MASS_EVENT_CODE=crawler.fetch-page
```

This sample stays outside the main reactor and uses only the external polling
worker HTTP contract under `/worker-api`.
