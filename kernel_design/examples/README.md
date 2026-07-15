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

Or provide the optional JSON shared by `KernelApplication` and
`ResourcesCommandClient`:

```text
python -m kernel_design.examples.fastapi_server --config kernel.json
```

`--host`, `--port`, and `--log-level` configure only the HTTP host. The example
imports only the two assembly command boundaries. FastAPI lifespan starts only
`KernelApplication`; WorkerGroup and Worker registration use the independent
`ResourcesCommandClient`. The host does not receive score cores, runtime
implementations, Redis keys, matcher, or pacers.

Dynamic attribute mutation is intentionally absent until the assembly installs
a real dynamic-attribute handler owner.

Planned executable-closure examples:

- [Local Function Adapter](local_function_adapter/README.md): consume
  DeliverSeeds, execute process-local Workers through shared event handlers,
  and append opaque SeedResults to the kernel SeedResult runtime.

This is not a production server. It intentionally omits authentication, Task
query/list, result-routing, transport submit, and API compatibility policy.
