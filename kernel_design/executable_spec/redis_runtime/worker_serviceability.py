from __future__ import annotations

from typing import Any, Mapping, Sequence

from ..kernel.worker_delivery import (
    DeliveryEndpoint,
    DeliveryReport,
    decode_delivery_report,
    encode_delivery_report,
)
from ..kernel.worker_runtime import EndpointManagerId
from ..kernel.worker_score import WorkerId
from ..kernel.worker_serviceability import (
    ProbeRequestOfferStatus,
    WorkerServiceabilityRuntime,
)
from .keyspace import RedisKeyspace


_OFFER_PROBE_REQUESTS_SCRIPT = """
-- worker_serviceability_offer_probe_requests
local capacity = tonumber(ARGV[1])
local size = redis.call('HLEN', KEYS[1])
local results = {}
for index = 2, #ARGV do
  local worker_id = ARGV[index]
  if redis.call('HEXISTS', KEYS[1], worker_id) == 1 then
    table.insert(results, 'ALREADY_REQUESTED')
  elseif size >= capacity then
    table.insert(results, 'CAPACITY')
  else
    redis.call('HSET', KEYS[1], worker_id, '1')
    size = size + 1
    table.insert(results, 'OFFERED')
  end
end
return results
"""


_CONSUME_PROBE_REQUESTS_SCRIPT = """
-- worker_serviceability_consume_probe_requests
local worker_ids = redis.call('HRANDFIELD', KEYS[1], tonumber(ARGV[1]))
if not worker_ids or #worker_ids == 0 then
  return {}
end
for _, worker_id in ipairs(worker_ids) do
  redis.call('HDEL', KEYS[1], worker_id)
end
return worker_ids
"""


_APPEND_ADAPTER_EVIDENCE_RESULTS_SCRIPT = """
-- worker_serviceability_append_adapter_evidence_results
local remaining = tonumber(ARGV[1]) - redis.call('LLEN', KEYS[1])
if remaining <= 0 then
  return 0
end
local accepted = math.min(remaining, #ARGV - 1)
for index = 1, accepted do
  redis.call('RPUSH', KEYS[1], ARGV[index + 1])
end
return accepted
"""


class RedisWorkerServiceabilityRuntime(WorkerServiceabilityRuntime):
    """Redis request-set and result-list implementation."""

    def __init__(
        self,
        redis_client: Any,
        *,
        keyspace: RedisKeyspace,
        request_capacity_per_adapter: int = 10_000,
        result_capacity: int = 10_000,
    ) -> None:
        if not isinstance(keyspace, RedisKeyspace):
            raise TypeError("keyspace must be RedisKeyspace")
        if request_capacity_per_adapter <= 0 or result_capacity <= 0:
            raise ValueError("serviceability capacities must be positive")
        self.redis = redis_client
        self.keyspace = keyspace
        self.request_capacity_per_adapter = request_capacity_per_adapter
        self.result_capacity = result_capacity

    def offer_probe_requests(
        self,
        *,
        adapter_id: EndpointManagerId,
        worker_ids: Sequence[WorkerId],
    ) -> Mapping[WorkerId, ProbeRequestOfferStatus]:
        self._require_id(adapter_id, "adapterId")
        bounded = self._bounded_worker_ids(worker_ids, allow_empty=True)
        if not bounded:
            return {}
        raw_results = self.redis.eval(
            _OFFER_PROBE_REQUESTS_SCRIPT,
            1,
            self._requests_key(adapter_id),
            self.request_capacity_per_adapter,
            *bounded,
        )
        values = self._decode_text_values(raw_results)
        if len(values) != len(bounded):
            raise RuntimeError("Redis probe offer returned an invalid response")
        try:
            statuses = tuple(ProbeRequestOfferStatus(value) for value in values)
        except ValueError as error:
            raise RuntimeError(
                "Redis probe offer returned an invalid response"
            ) from error
        return dict(zip(bounded, statuses, strict=True))

    def consume_probe_requests(
        self,
        *,
        adapter_id: EndpointManagerId,
        limit: int,
    ) -> tuple[WorkerId, ...]:
        self._require_id(adapter_id, "adapterId")
        self._require_limit(limit)
        raw_results = self.redis.eval(
            _CONSUME_PROBE_REQUESTS_SCRIPT,
            1,
            self._requests_key(adapter_id),
            limit,
        )
        worker_ids = self._decode_text_values(raw_results)
        if len(worker_ids) > limit or len(set(worker_ids)) != len(worker_ids):
            raise RuntimeError("Redis probe consume returned an invalid response")
        if any(not worker_id for worker_id in worker_ids):
            raise RuntimeError("Redis probe consume returned an invalid response")
        return worker_ids

    def append_adapter_evidence_results(
        self,
        *,
        reports: Sequence[DeliveryReport],
    ) -> int:
        bounded = tuple(reports)
        if not bounded:
            return 0
        if len(bounded) > self.MAX_BATCH_SIZE:
            raise ValueError("Adapter evidence append exceeds 100 Reports")
        encoded: list[str] = []
        for report in bounded:
            if (
                not isinstance(report, DeliveryReport)
                or report.src is not DeliveryEndpoint.ADAPTER
                or report.dst is not DeliveryEndpoint.KERNEL
            ):
                raise ValueError(
                    "Adapter evidence source or destination is invalid"
                )
            encoded.append(encode_delivery_report(report))
        return int(self.redis.eval(
            _APPEND_ADAPTER_EVIDENCE_RESULTS_SCRIPT,
            1,
            self._results_key(),
            self.result_capacity,
            *encoded,
        ))

    def consume_adapter_evidence_results(
        self,
        *,
        limit: int,
    ) -> tuple[DeliveryReport, ...]:
        self._require_limit(limit)
        with self.redis.pipeline(transaction=True) as pipeline:
            for _ in range(limit):
                pipeline.lpop(self._results_key())
            raw_results = pipeline.execute()
        reports: list[DeliveryReport] = []
        for raw_result in raw_results:
            if raw_result is None:
                continue
            report = decode_delivery_report(raw_result)
            if (
                report is not None
                and report.src is DeliveryEndpoint.ADAPTER
                and report.dst is DeliveryEndpoint.KERNEL
            ):
                reports.append(report)
        return tuple(reports)

    def _requests_key(self, adapter_id: EndpointManagerId) -> str:
        return (
            f"{self.keyspace.base}:worker:serviceability:adapter:"
            f"{adapter_id}:probe_requests"
        )

    def _results_key(self) -> str:
        return f"{self.keyspace.base}:worker:serviceability:evidence_results"

    @classmethod
    def _bounded_worker_ids(
        cls,
        worker_ids: Sequence[WorkerId],
        *,
        allow_empty: bool,
    ) -> tuple[WorkerId, ...]:
        if isinstance(worker_ids, (str, bytes)) or not isinstance(
            worker_ids,
            Sequence,
        ):
            raise TypeError("workerIds must be a sequence")
        bounded = tuple(worker_ids)
        if not bounded and allow_empty:
            return ()
        if not bounded or len(bounded) > cls.MAX_BATCH_SIZE:
            raise ValueError("Probe request must contain 1..100 Workers")
        if len(set(bounded)) != len(bounded):
            raise ValueError("Probe request Worker ids must be unique")
        for worker_id in bounded:
            cls._require_id(worker_id, "workerId")
        return bounded

    @classmethod
    def _require_limit(cls, limit: int) -> None:
        if (
            isinstance(limit, bool)
            or not isinstance(limit, int)
            or not 1 <= limit <= cls.MAX_BATCH_SIZE
        ):
            raise ValueError("limit must be between 1 and 100")

    @staticmethod
    def _require_id(value: object, name: str) -> None:
        if not isinstance(value, str) or not value:
            raise ValueError(f"{name} must be non-empty")

    @staticmethod
    def _decode_text_values(raw_values: Any) -> tuple[str, ...]:
        if raw_values is None:
            return ()
        if isinstance(raw_values, (str, bytes)):
            raw_values = (raw_values,)
        values = tuple(
            value.decode("utf-8") if isinstance(value, bytes) else value
            for value in raw_values
        )
        if any(not isinstance(value, str) for value in values):
            raise RuntimeError("Redis serviceability script returned invalid text")
        return values
