from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import FastAPI

from kernel_design.executable_spec.assembly import (
    KernelApplication,
    KernelApplicationConfig,
)


def create_app(
    *,
    config: KernelApplicationConfig | None = None,
    application: KernelApplication | None = None,
) -> FastAPI:
    if config is not None and application is not None:
        raise ValueError(
            "config and injected application are mutually exclusive"
        )
    if application is None:
        kernel_application = KernelApplication(config)
    else:
        kernel_application = application

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        kernel_application.start()
        try:
            yield
        finally:
            kernel_application.stop()

    app = FastAPI(
        title="Python Kernel Pacer Host",
        lifespan=lifespan,
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    app.state.kernel_application = kernel_application

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok"}

    return app
