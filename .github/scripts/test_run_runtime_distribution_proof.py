from __future__ import annotations

import importlib.util
import json
import tempfile
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
from distribution import docs_http_proof as docs


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
                 {"projectId": "scenario-workers", "workerGroupId": "group-a"}, headers=proof.TASK_CALLER_HEADERS),
            call("GET", f"http://runtime/api/v1/projects/scenario-workers/tasks/{task_id}", headers=proof.TASK_CALLER_HEADERS),
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
                 headers=proof.TASK_CALLER_HEADERS),
                call(
                    "POST",
                    "http://runtime/api/v1/runtime-view/worker-groups:preview",
                    100,
                ),
            ],
        )


class DocumentationHttpProofTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.frontend = Path(self.temporary.name)
        self.commit = "a" * 40
        self.responses = {}
        page = (f'<meta name="xa-mass-docs-commit" content="{self.commit}">'
                '<link rel="stylesheet" href="/docs/assets/main-one.css">'
                '<script src="/docs/assets/main-one.js"></script>').encode()
        for name, content in {
                "docs/index.html": page,
                "docs/doc/guide/first-task.html": page + b"<h1>First task</h1>",
                "docs/assets/main-one.js": b"export {};",
                "docs/assets/main-one.css": b"body{}"}.items():
            path = self.frontend / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
            content_type = "text/html" if name.endswith(".html") else "text/css" if name.endswith(".css") else "text/javascript"
            self.responses["/" + name] = (200, content, content_type)
        self.responses["/docs"] = self.responses["/docs/"] = self.responses["/docs/index.html"]

    def request(self, base, path):
        self.assertEqual("http://runtime", base)
        return self.responses.get(path, (404, b"", "text/plain"))

    def test_observes_supplied_pages_actual_assets_and_missing_resource_status(self):
        with patch.object(docs, "_request", side_effect=self.request) as request:
            self.assertEqual({"pages": 4, "assets": 2, "gitCommit": self.commit},
                             docs.verify("http://runtime", self.frontend, self.commit))
        self.assertIn(call("http://runtime", "/docs/doc/guide/first-task.html"), request.call_args_list)
        self.assertIn(call("http://runtime", "/docs/assets/missing.js"), request.call_args_list)

    def test_rejects_console_fallback_for_deep_page_asset_and_unknown_route(self):
        for path in ("/docs/doc/guide/first-task.html", "/docs/assets/main-one.js", "/docs/missing-page.html"):
            original = self.responses.get(path)
            self.responses[path] = (200, b'<div id="app"></div>', "text/html")
            try:
                with self.subTest(path=path), patch.object(docs, "_request", side_effect=self.request), \
                        self.assertRaises(AssertionError):
                    docs.verify("http://runtime", self.frontend, self.commit)
            finally:
                if original is None:
                    self.responses.pop(path)
                else:
                    self.responses[path] = original

    def test_accepts_an_empty_generated_stylesheet_served_exactly(self):
        path = "docs/assets/main-one.css"
        (self.frontend / path).write_bytes(b"")
        self.responses["/" + path] = (200, b"", "text/css")
        with patch.object(docs, "_request", side_effect=self.request):
            self.assertEqual(2, docs.verify("http://runtime", self.frontend, self.commit)["assets"])


if __name__ == "__main__":
    unittest.main()
