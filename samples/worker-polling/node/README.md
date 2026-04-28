# Node Polling Worker Sample

Status: current runnable external worker sample.

Run from repo root:

```bash
node samples/worker-polling/node/worker.mjs
```

Environment defaults:

```text
MASS_BASE_URL=http://127.0.0.1:8088
MASS_WORKER_ID=node-worker-api-001
MASS_WORKER_KEY=node-worker-key
MASS_PROJECT=crawlerApp
MASS_EVENT_CODE=crawler.fetch-page
```

This sample uses only the external polling worker HTTP contract under `/worker-api`.
