from __future__ import annotations

import argparse
import logging
import os
import sys
from collections.abc import Callable
from pathlib import Path
from typing import BinaryIO

from .application import KernelApplication, KernelApplicationConfig


_LOGGER = logging.getLogger(__name__)


def _write_ready_file(path: Path, instance_token: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{instance_token}.tmp")
    temporary.write_text(instance_token, encoding="utf-8")
    os.replace(temporary, path)


def _remove_ready_file(path: Path, instance_token: str) -> None:
    try:
        if path.read_text(encoding="utf-8") == instance_token:
            path.unlink(missing_ok=True)
    except FileNotFoundError:
        return


def _run_application(
    *,
    config_path: Path | None,
    instance_token: str | None,
    ready_file: Path | None,
    input_stream: BinaryIO,
    application_factory: Callable[[KernelApplicationConfig], KernelApplication] = (
        KernelApplication
    ),
) -> None:
    if (instance_token is None) != (ready_file is None):
        raise ValueError(
            "instance_token and ready_file must be provided together"
        )
    if instance_token is not None and not instance_token:
        raise ValueError("instance_token must be non-empty")

    config_json = (
        config_path.read_text(encoding="utf-8")
        if config_path is not None
        else None
    )
    application = application_factory(
        KernelApplicationConfig.from_json(config_json)
    )
    started = False
    try:
        application.start()
        started = True
        if ready_file is not None and instance_token is not None:
            _write_ready_file(ready_file, instance_token)
        _LOGGER.info("kernel application started")
        input_stream.read()
    except KeyboardInterrupt:
        pass
    finally:
        try:
            if started:
                application.stop()
        finally:
            if ready_file is not None and instance_token is not None:
                _remove_ready_file(ready_file, instance_token)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Start the executable kernel with built-in defaults.",
    )
    parser.add_argument(
        "--config",
        type=Path,
        help="optional UTF-8 KernelApplication JSON config file",
    )
    parser.add_argument(
        "--log-level",
        choices=("DEBUG", "INFO", "WARNING", "ERROR"),
        default="INFO",
    )
    parser.add_argument(
        "--instance-token",
        help="parent-owned process instance token",
    )
    parser.add_argument(
        "--ready-file",
        type=Path,
        help="write the instance token here after all Pacers start",
    )
    args = parser.parse_args()

    if (args.instance_token is None) != (args.ready_file is None):
        parser.error("--instance-token and --ready-file must be used together")

    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    _run_application(
        config_path=args.config,
        instance_token=args.instance_token,
        ready_file=args.ready_file,
        input_stream=sys.stdin.buffer,
    )


if __name__ == "__main__":
    main()
