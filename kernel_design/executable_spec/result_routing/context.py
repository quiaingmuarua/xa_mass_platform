from __future__ import annotations

import json
from collections.abc import Mapping
from dataclasses import dataclass

from ..kernel.task_runtime import MessageId
from ..kernel.task_score_band import Score, TaskId, TimeMillis
from ..kernel.worker_score import Score as WorkerScore
from ..kernel.worker_score import WorkerId


@dataclass(frozen=True, slots=True)
class ResultContext:
    task_id: TaskId
    message_id: MessageId
    worker_id: WorkerId
    claim_score: Score
    worker_lease_score: WorkerScore
    task_item_claim_until_millis: TimeMillis


def encode_result_context(
    *,
    task_id: TaskId,
    message_id: MessageId,
    worker_id: WorkerId,
    claim_score: Score,
    worker_lease_score: WorkerScore,
    task_item_claim_until_millis: TimeMillis,
) -> str:
    """Encode the opaque handoff shared by dispatch and result-routing."""
    return json.dumps(
        {
            "taskId": task_id,
            "messageId": message_id,
            "workerId": worker_id,
            "claimScore": claim_score,
            "workerLeaseScore": worker_lease_score,
            "taskItemClaimUntilMillis": task_item_claim_until_millis,
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
        claim_score = payload["claimScore"]
        worker_lease_score = payload["workerLeaseScore"]
        task_item_claim_until_millis = payload["taskItemClaimUntilMillis"]
    except (KeyError, TypeError, ValueError):
        return None

    if any(
        not isinstance(identifier, str) or not identifier
        for identifier in (task_id, message_id, worker_id)
    ):
        return None
    if any(
        isinstance(score, bool) or not isinstance(score, int)
        for score in (claim_score, worker_lease_score)
    ):
        return None
    if claim_score <= 0 or worker_lease_score <= 0:
        return None
    if (
        isinstance(task_item_claim_until_millis, bool)
        or not isinstance(task_item_claim_until_millis, int)
        or task_item_claim_until_millis <= 0
    ):
        return None
    return ResultContext(
        task_id=task_id,
        message_id=message_id,
        worker_id=worker_id,
        claim_score=claim_score,
        worker_lease_score=worker_lease_score,
        task_item_claim_until_millis=task_item_claim_until_millis,
    )
