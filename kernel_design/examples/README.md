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

Or provide the same optional JSON accepted by `KernelApplication`:

```text
python -m kernel_design.examples.fastapi_server --config kernel.json
```

`--host`, `--port`, and `--log-level` configure only the HTTP host. The example
imports the assembly application boundary and does not receive score cores,
runtime implementations, Redis keys, matcher, or pacers.

This is not a production server. It intentionally omits authentication, Task
query/list, result-routing, transport submit, and API compatibility policy.
