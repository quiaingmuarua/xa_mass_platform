"""python -m distribution.server.staging_ingress render --config ... --output ..."""

import argparse
from pathlib import Path
from .renderer import Deployment, render


def main() -> None:
    parser = argparse.ArgumentParser(description="Render the fixed XA Mass trusted staging ingress")
    subcommands = parser.add_subparsers(dest="command", required=True)
    command = subcommands.add_parser("render")
    command.add_argument("--config", type=Path, required=True)
    command.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        content = render(Deployment.load(args.config))
    except (ValueError, OSError) as failure:
        parser.error(str(failure))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(content, encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
