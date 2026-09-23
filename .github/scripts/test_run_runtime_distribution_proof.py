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
