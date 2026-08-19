from __future__ import annotations

import argparse
import logging
from pathlib import Path

from kernel_design.executable_spec.assembly import KernelApplicationConfig

from .app import create_app


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Start the Python Kernel Task Control API."
    )
    parser.add_argument("--config", type=Path, help="optional kernel JSON config")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18080)
    parser.add_argument(
        "--log-level",
        choices=("debug", "info", "warning", "error"),
        default="info",
    )
    args = parser.parse_args()
    if args.port <= 0:
        parser.error("--port must be positive")

    config_json = args.config.read_text(encoding="utf-8") if args.config else None
    config = KernelApplicationConfig.from_json(config_json)
    logging.basicConfig(level=args.log_level.upper())
    try:
        import uvicorn
    except ImportError as error:
        raise RuntimeError(
            "uvicorn is required for the Python Kernel Task Control API"
        ) from error
    uvicorn.run(
        create_app(config=config),
        host=args.host,
        port=args.port,
        log_level=args.log_level,
    )


if __name__ == "__main__":
    main()
