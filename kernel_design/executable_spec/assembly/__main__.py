from __future__ import annotations

import argparse
import json
import logging
import os
import sys
from dataclasses import replace
from pathlib import Path
from typing import BinaryIO, Protocol

from ..kernel import WorkerScoreCore
from .application import KernelApplication, KernelApplicationConfig


_LOGGER = logging.getLogger(__name__)
_MANAGED_REDIS_URL_ENV = "XA_MASS_KERNEL_PACER_REDIS_URL"
_MANAGED_REDIS_SCOPE_ENV = "XA_MASS_KERNEL_PACER_REDIS_SCOPE"


class _ApplicationFactory(Protocol):
    def __call__(
        self,
        config: KernelApplicationConfig,
        *,
        result_routing_enabled: bool,
        worker_serviceability_result_enabled: bool,
        worker_serviceability_dispatch_enabled: bool,
        hot_eligibility_floor_millis: int | None,
    ) -> KernelApplication: ...


def _default_application_factory(
    config: KernelApplicationConfig,
    *,
    result_routing_enabled: bool,
    worker_serviceability_result_enabled: bool,
    worker_serviceability_dispatch_enabled: bool,
    hot_eligibility_floor_millis: int | None,
) -> KernelApplication:
    return KernelApplication(
        config,
        _result_routing_enabled=result_routing_enabled,
        _worker_serviceability_result_enabled=(
            worker_serviceability_result_enabled
        ),
        _worker_serviceability_dispatch_enabled=(
            worker_serviceability_dispatch_enabled
        ),
        _hot_eligibility_floor_millis=hot_eligibility_floor_millis,
    )


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
    managed_redis_url: str | None = None,
    managed_redis_scope: str | None = None,
    without_result_routing: bool = False,
    without_worker_serviceability_result: bool = False,
    without_worker_serviceability_dispatch: bool = False,
    hot_eligibility_floor_millis: int | None = None,
    application_factory: _ApplicationFactory = _default_application_factory,
) -> None:
    if (instance_token is None) != (ready_file is None):
        raise ValueError(
            "instance_token and ready_file must be provided together"
        )
    if instance_token is not None and not instance_token:
        raise ValueError("instance_token must be non-empty")
    managed = instance_token is not None
    if without_result_routing and not managed:
        raise ValueError(
            "without_result_routing requires the managed parent protocol"
        )
    if without_worker_serviceability_result and not managed:
        raise ValueError(
            "without_worker_serviceability_result requires the managed "
            "parent protocol"
        )
    if without_worker_serviceability_dispatch and not managed:
        raise ValueError(
            "without_worker_serviceability_dispatch requires the managed "
            "parent protocol"
        )
    if hot_eligibility_floor_millis is not None and not managed:
        raise ValueError(
            "hot_eligibility_floor_millis requires the managed parent protocol"
        )
    if hot_eligibility_floor_millis is not None and (
        isinstance(hot_eligibility_floor_millis, bool)
        or not isinstance(hot_eligibility_floor_millis, int)
        or hot_eligibility_floor_millis <= 0
        or hot_eligibility_floor_millis > WorkerScoreCore.MAX_TIME_MILLIS
        or hot_eligibility_floor_millis % WorkerScoreCore.SLOT_MILLIS != 0
    ):
        raise ValueError(
            "hot_eligibility_floor_millis must be score-slot aligned"
        )
    has_managed_redis = (
        managed_redis_url is not None or managed_redis_scope is not None
    )
    if managed != has_managed_redis:
        raise ValueError(
            "managed Redis coordinates must accompany the parent protocol"
        )
    if has_managed_redis and (
        not managed_redis_url or not managed_redis_scope
    ):
        raise ValueError(
            "managed Redis URL and scope must both be non-empty"
        )

    config_json = (
        config_path.read_text(encoding="utf-8")
        if config_path is not None
        else None
    )
    config = KernelApplicationConfig.from_json(config_json)
    if managed:
        raw_config = json.loads(config_json or "{}")
        if "redis" in raw_config:
            raise ValueError(
                "managed Pacer config must not declare Redis coordinates"
            )
        config = replace(
            config,
            redis_url=managed_redis_url,
            redis_scope=managed_redis_scope,
        )
        if config.worker_serviceability is None:
            if hot_eligibility_floor_millis is not None:
                raise ValueError(
                    "HOT eligibility floor requires Worker Serviceability"
                )
        elif hot_eligibility_floor_millis is None:
            raise ValueError(
                "managed Worker Serviceability requires the parent HOT floor"
            )
    application = application_factory(
        config,
        result_routing_enabled=not without_result_routing,
        worker_serviceability_result_enabled=(
            not without_worker_serviceability_result
        ),
        worker_serviceability_dispatch_enabled=(
            not without_worker_serviceability_dispatch
        ),
        hot_eligibility_floor_millis=hot_eligibility_floor_millis,
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
    parser.add_argument(
        "--without-result-routing",
        action="store_true",
        help="managed migration mode: Java owns Result Routing",
    )
    parser.add_argument(
        "--without-worker-serviceability-result",
        action="store_true",
        help=(
            "managed migration mode: Java owns Worker Serviceability Result"
        ),
    )
    parser.add_argument(
        "--without-worker-serviceability-dispatch",
        action="store_true",
        help=(
            "managed migration mode: Java owns Worker Serviceability Dispatch"
        ),
    )
    parser.add_argument(
        "--hot-eligibility-floor-millis",
        type=int,
        help="parent-owned Worker Serviceability HOT eligibility floor",
    )
    args = parser.parse_args()

    if (args.instance_token is None) != (args.ready_file is None):
        parser.error("--instance-token and --ready-file must be used together")
    if args.without_result_routing and args.instance_token is None:
        parser.error(
            "--without-result-routing requires the managed parent protocol"
        )
    if (
        args.without_worker_serviceability_result
        and args.instance_token is None
    ):
        parser.error(
            "--without-worker-serviceability-result requires the managed "
            "parent protocol"
        )
    if (
        args.without_worker_serviceability_dispatch
        and args.instance_token is None
    ):
        parser.error(
            "--without-worker-serviceability-dispatch requires the managed "
            "parent protocol"
        )
    if (
        args.hot_eligibility_floor_millis is not None
        and args.instance_token is None
    ):
        parser.error(
            "--hot-eligibility-floor-millis requires the managed parent "
            "protocol"
        )

    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    _run_application(
        config_path=args.config,
        instance_token=args.instance_token,
        ready_file=args.ready_file,
        input_stream=sys.stdin.buffer,
        managed_redis_url=(
            os.environ.get(_MANAGED_REDIS_URL_ENV)
            if args.instance_token is not None
            else None
        ),
        managed_redis_scope=(
            os.environ.get(_MANAGED_REDIS_SCOPE_ENV)
            if args.instance_token is not None
            else None
        ),
        without_result_routing=args.without_result_routing,
        without_worker_serviceability_result=(
            args.without_worker_serviceability_result
        ),
        without_worker_serviceability_dispatch=(
            args.without_worker_serviceability_dispatch
        ),
        hot_eligibility_floor_millis=(
            args.hot_eligibility_floor_millis
        ),
    )


if __name__ == "__main__":
    main()
