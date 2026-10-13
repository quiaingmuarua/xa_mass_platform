from __future__ import annotations

import importlib.util
import json
import unittest
from pathlib import Path
from unittest.mock import call, patch


SCRIPT = Path(__file__).resolve().parent / "run_runtime_distribution_proof.py"
SPEC = importlib.util.spec_from_file_location(
    "run_runtime_distribution_proof",
    SCRIPT,
)
assert SPEC is not None and SPEC.loader is not None
proof = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(proof)


class RuntimeDistributionProofApiContractTest(unittest.TestCase):

    def test_prepares_rpc_task_then_point_reads_the_supplied_identity(self) -> None:
        task_id = "distribution-rpc-group-a"
        entry = {
            "taskId": task_id,
            "scoreBand": "running-initial",
            "task": {
                "taskId": task_id,
                "projectId": "scenario-workers",
                "workerGroupId": "group-a",
                "idleDisposition": "PARK_WHEN_IDLE",
                "refill": [],
            },
        }
        with patch.object(proof, "_request", side_effect=[
            (200, json.dumps({"taskId": task_id}).encode(), "application/json"),
            (200, json.dumps(entry).encode(), "application/json"),
        ]) as request:
            self.assertEqual(proof._prepare_rpc_task("http://runtime", "scenario-workers", "group-a"), task_id)
        self.assertEqual(request.call_args_list, [
            call("POST", f"http://runtime/api/v1/tasks/{task_id}/rpc:prepare",
                 {"projectId": "scenario-workers", "workerGroupId": "group-a"}),
            call("GET", f"http://runtime/api/v1/projects/scenario-workers/tasks/{task_id}"),
        ])

    def test_preparation_uses_explicit_non_default_project_for_request_and_read(self) -> None:
        task_id = "distribution-rpc-group-a"
        entry = {"taskId": task_id, "scoreBand": "running-initial", "task": {
            "taskId": task_id, "projectId": "agentforge", "workerGroupId": "group-a",
            "idleDisposition": "PARK_WHEN_IDLE", "refill": []}}
        with patch.object(proof, "_request", side_effect=[
            (200, json.dumps({"taskId": task_id}).encode(), "application/json"),
            (200, json.dumps(entry).encode(), "application/json"),
        ]) as request:
            self.assertEqual(proof._prepare_rpc_task("http://runtime", "agentforge", "group-a"), task_id)
        self.assertEqual(request.call_args_list[0].args[2]["projectId"], "agentforge")
        self.assertEqual(request.call_args_list[1].args[1], f"http://runtime/api/v1/projects/agentforge/tasks/{task_id}")

    def test_unconfirmed_preparation_does_not_retry_or_change_identity(self) -> None:
        with patch.object(proof, "_request", return_value=(503, b"{}", "application/json")) as request:
            with self.assertRaisesRegex(RuntimeError, "not confirmed"):
                proof._prepare_rpc_task("http://runtime", "scenario-workers", "group-a")
        self.assertEqual(request.call_count, 1)

    def test_preview_requests_use_direct_integer_bodies(self) -> None:
        responses = [
            (200, json.dumps({"workers": []}).encode(), "application/json"),
            (200, json.dumps({"entries": []}).encode(), "application/json"),
            (
                200,
                json.dumps({
                    "returnedCount": 0,
                    "workerGroups": [],
                }).encode(),
                "application/json",
            ),
        ]
        with patch.object(proof, "_request", side_effect=responses) as request:
            self.assertEqual(
                proof._preview_worker_count("http://runtime", "group-a"),
                0,
            )
            self.assertEqual(proof._preview_tasks("http://runtime"), [])
            self.assertEqual(
                proof._preview_worker_groups("http://runtime")["workerGroups"],
                [],
            )

        self.assertEqual(
            request.call_args_list,
            [
                call(
                    "POST",
                    "http://runtime/api/v1/runtime-view/worker-groups/"
                    "group-a/workers:preview",
                    100,
                ),
                call(
                    "POST",
                    "http://runtime/api/v1/runtime-view/tasks:preview",
                    100,
                ),
                call(
                    "POST",
                    "http://runtime/api/v1/runtime-view/worker-groups:preview",
                    100,
                ),
            ],
        )


if __name__ == "__main__":
    unittest.main()
