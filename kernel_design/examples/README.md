# FastAPI Kernel Example

Status: external protocol example for the isolated executable spec.

Install the example-only dependencies:

```text
python -m pip install -r kernel_design/examples/requirements.txt
```

Start with built-in kernel defaults:

```text
python -m kernel_design.examples.fastapi_server
```

Or provide the optional JSON shared by all assembly clients:

```text
python -m kernel_design.examples.fastapi_server --config kernel.json
```

`--host`, `--port`, and `--log-level` configure only the HTTP host. The example
imports only assembly boundaries. FastAPI lifespan starts only
`KernelApplication`; WorkerGroup and Worker registration use the independent
`ResourcesCommandClient`, while DeliverSeed consumption uses
`DeliverSeedConsumerClient`. The host does not receive score cores, runtime
implementations, Redis keys, matcher, or pacers.

DeliverSeed consumption is Worker-addressed: `POST /deliver-seeds:consume`
accepts a bounded `workerIds` list. It is not partitioned by endpoint manager.

Task lifecycle routes include explicit approve and close commands. Close is
available for both Task types and does not expose a terminal score.

Dynamic attribute mutation is intentionally absent until the assembly installs
a real dynamic-attribute handler owner.

Executable-closure example:

- [Local Function Adapter](local_function_adapter/README.md): consume
  DeliverSeeds, execute process-local Workers through shared event handlers,
  and append opaque SeedResults to the kernel SeedResult runtime.

This is not a production server. It intentionally omits authentication, Task
query/list, result projection, production transport, and API compatibility
policy.
