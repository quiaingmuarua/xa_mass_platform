from __future__ import annotations

import json
from collections.abc import Mapping
from dataclasses import dataclass

from .task_runtime import MessageId
from .task_score_band import TaskId
from .worker_runtime import WorkerGroupId
from .worker_score import Score as WorkerScore
from .worker_score import WorkerId


@dataclass(frozen=True, slots=True)
class ResultContext:
    task_id: TaskId
    message_id: MessageId
    worker_id: WorkerId
    worker_group_id: WorkerGroupId
    worker_lease_score: WorkerScore


def encode_result_context(context: ResultContext) -> str:
    """Encode the opaque handoff shared by dispatch and result-routing."""
    return json.dumps(
        {
            "taskId": context.task_id,
            "messageId": context.message_id,
            "workerId": context.worker_id,
            "workerGroupId": context.worker_group_id,
            "workerLeaseScore": context.worker_lease_score,
        },
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def decode_result_context(value: str) -> ResultContext | None:
    """Decode and validate one opaque result context inside result-routing."""
    try:
        payload = json.loads(value)
        if not isinstance(payload, Mapping):
            return None
        task_id = payload["taskId"]
        message_id = payload["messageId"]
        worker_id = payload["workerId"]
        worker_group_id = payload["workerGroupId"]
        worker_lease_score = payload["workerLeaseScore"]
    except (KeyError, TypeError, ValueError):
        return None

    if any(
        not isinstance(identifier, str) or not identifier
        for identifier in (task_id, message_id, worker_id, worker_group_id)
    ):
        return None
    if isinstance(worker_lease_score, bool) or not isinstance(
        worker_lease_score, int
    ):
        return None
    if worker_lease_score <= 0:
        return None
    return ResultContext(
        task_id=task_id,
        message_id=message_id,
        worker_id=worker_id,
        worker_group_id=worker_group_id,
        worker_lease_score=worker_lease_score,
    )
