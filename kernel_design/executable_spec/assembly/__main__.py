from __future__ import annotations

import argparse
import logging
from pathlib import Path
from threading import Event

from .application import KernelApplication, KernelApplicationConfig


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
    args = parser.parse_args()

    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    config_json = args.config.read_text(encoding="utf-8") if args.config else None
    config = KernelApplicationConfig.from_json(config_json)
    application = KernelApplication(config)
    application.start()
    logging.getLogger(__name__).info("kernel application started")
    try:
        Event().wait()
    except KeyboardInterrupt:
        pass
    finally:
        application.stop()


if __name__ == "__main__":
    main()
