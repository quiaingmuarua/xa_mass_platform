# xa-mass-worker-pack

## Role

- official builtin, sample, and dev worker capabilities
- sample worker clients, worker launchers, and worker-side command runtime
- sample-only bootstrap surfaces that support dev-shell acceptance flows

## Boundaries

- keep runtime composition SDK-first; worker-pack registers through normal platform APIs
- keep external process references under `samples/`
- do not let worker-pack redefine `xa-mass-server` as the product shell

## Start Here

- `src/main/java/com/xa/mass/workerpack/sample/starter/SampleWorkerProcessStarter.java`
- `src/main/java/com/xa/mass/workerpack/sample/client/SampleWorkerWebSocketClient.java`
- `src/main/java/com/xa/mass/workerpack/sample/command/runtime/SampleCommandRuntime.java`

