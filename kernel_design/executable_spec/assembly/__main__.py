from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path
from typing import BinaryIO, Protocol

from .application import KernelApplication, KernelApplicationConfig


_LOGGER = logging.getLogger(__name__)


class _ApplicationFactory(Protocol):
    def __call__(
        self,
        config: KernelApplicationConfig,
    ) -> KernelApplication: ...


def _run_application(
    *,
    config_path: Path | None,
    input_stream: BinaryIO,
    application_factory: _ApplicationFactory = KernelApplication,
) -> None:
    if config_path is None:
        raise ValueError("Oracle CLI requires an explicit --config file")
    config = KernelApplicationConfig.from_json(
        config_path.read_text(encoding="utf-8"),
    )
    if not config.redis_scope.startswith("test_"):
        raise ValueError("Oracle CLI requires an isolated test_* Redis scope")
    application = application_factory(config)
    started = False
    try:
        application.start()
        started = True
        _LOGGER.info("kernel Oracle application started")
        input_stream.read()
    except KeyboardInterrupt:
        pass
    finally:
        if started:
            application.stop()


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Start the complete executable Kernel Oracle.",
    )
    parser.add_argument(
        "--config",
        type=Path,
        required=True,
        help="UTF-8 KernelApplication JSON config with a test_* Redis scope",
    )
    parser.add_argument(
        "--log-level",
        choices=("DEBUG", "INFO", "WARNING", "ERROR"),
        default="INFO",
    )
    args = parser.parse_args()

    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    _run_application(
        config_path=args.config,
        input_stream=sys.stdin.buffer,
    )


if __name__ == "__main__":
    main()
