import dataclasses
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[5]
sys.path.insert(0, str(ROOT))
from distribution.server.staging_ingress import Deployment, render
from distribution.server.staging_ingress.renderer import ADMIN_OPERATIONS, ADMIN_PAGE_PATTERNS, operation_pattern


def configuration(**changes):
    original = Deployment(
        "/etc/xa-mass/tls/fullchain.pem", "/etc/xa-mass/tls/privkey.pem",
        "/etc/xa-mass/workers.htpasswd",
        "/var/lib/xa-mass/acme", "/var/lib/xa-mass/nginx", ("group-b", "group-a"))
    return dataclasses.replace(original, **changes)


class StagingIngressTest(unittest.TestCase):
    CAPACITY_DEFAULTS = {
        "worker_processes": 1,
        "worker_connections": 2048,
        "worker_connection_limit": 100,
        "worker_total_connection_limit": 200,
        "worker_requests_per_second": 10,
        "worker_request_burst": 30,
        "admin_requests_per_second": 30,
        "admin_request_burst": 60,
    }

    def test_omitted_capacity_fields_keep_existing_defaults(self):
        document = dataclasses.asdict(configuration())
        for name in self.CAPACITY_DEFAULTS:
            del document[name]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "deployment.json"
            path.write_text(json.dumps(document), encoding="utf-8")
            loaded = Deployment.load(path)
        self.assertEqual(self.CAPACITY_DEFAULTS,
                         {name: getattr(loaded, name) for name in self.CAPACITY_DEFAULTS})
        self.assertEqual(render(configuration()), render(loaded))
        for directive in ("worker_processes 1;", "worker_connections 2048;",
                          "limit_conn worker_credential 100;", "limit_conn worker_total 200;",
                          "zone=worker_rate:1m rate=10r/s;", "zone=admin_rate:1m rate=30r/s;",
                          "zone=worker_rate burst=30 nodelay;", "zone=admin_rate burst=60 nodelay;"):
            self.assertIn(directive, render(loaded))

    def test_custom_capacity_changes_only_the_declared_nginx_capacity_directives(self):
        custom = configuration(worker_processes=8, worker_connections=32768,
                               worker_connection_limit=20000, worker_total_connection_limit=30000,
                               worker_requests_per_second=200, worker_request_burst=1000,
                               admin_requests_per_second=300, admin_request_burst=600)
        expected = render(configuration())
        for original, replacement in (
            ("worker_processes 1;", "worker_processes 8;"),
            ("worker_connections 2048;", "worker_connections 32768;"),
            ("limit_conn worker_credential 100;", "limit_conn worker_credential 20000;"),
            ("limit_conn worker_total 200;", "limit_conn worker_total 30000;"),
            ("zone=worker_rate:1m rate=10r/s;", "zone=worker_rate:1m rate=200r/s;"),
            ("zone=worker_rate burst=30 nodelay;", "zone=worker_rate burst=1000 nodelay;"),
            ("zone=admin_rate:1m rate=30r/s;", "zone=admin_rate:1m rate=300r/s;"),
            ("zone=admin_rate burst=60 nodelay;", "zone=admin_rate burst=600 nodelay;"),
        ):
            expected = expected.replace(original, replacement)
        self.assertEqual(expected, render(custom))

    def test_capacity_is_strictly_positive_integer_and_per_credential_fits_total(self):
        for name in self.CAPACITY_DEFAULTS:
            for value in (0, -1, True, False, 1.0, "1", None):
                with self.subTest(name=name, value=value), self.assertRaisesRegex(ValueError, name):
                    configuration(**{name: value})
        with self.assertRaisesRegex(ValueError, "worker_connection_limit.*worker_total_connection_limit"):
            configuration(worker_connection_limit=201)
        self.assertEqual(1, configuration(worker_connection_limit=1,
                                         worker_total_connection_limit=1).worker_connection_limit)

    def test_vite_output_and_current_public_files_are_readable_but_not_writable(self):
        vite = (ROOT / "frontend/vite.config.ts").read_text(encoding="utf-8")
        outputs = re.findall(r'(?:chunkFileNames|entryFileNames|assetFileNames): "([^\"]+)"', vite)
        self.assertEqual(3, len(outputs))
        paths = ["/" + output.replace("[name]", "index").replace("[hash]", "abc123")
                 .replace("[extname]", ".css").replace("[ext]", "css") for output in outputs]
        paths.extend("/" + path.relative_to(ROOT / "frontend/public").as_posix()
                     for path in (ROOT / "frontend/public").rglob("*") if path.is_file())
        for path in paths:
            with self.subTest(path=path):
                self.assertTrue(any(re.fullmatch(pattern, "GET:" + path) for pattern in ADMIN_PAGE_PATTERNS))
                self.assertTrue(any(re.fullmatch(pattern, "HEAD:" + path) for pattern in ADMIN_PAGE_PATTERNS))
                self.assertFalse(any(re.fullmatch(pattern, "POST:" + path) for pattern in ADMIN_PAGE_PATTERNS))

    def test_existing_openapi_is_explicitly_partitioned(self):
        snapshot = json.loads((ROOT / "frontend/public/reference/openapi.json").read_text(encoding="utf-8"))
        actual = {(method.upper(), path) for path, spec in snapshot["paths"].items()
                  for method in spec if method in {"get", "post", "put", "patch", "delete"}}
        excluded = {
            ("POST", "/api/v1/worker-groups/{workerGroupId}/workers:prepare"),
            ("POST", "/api/v1/worker-groups/{workerGroupId}/workers:prepare-batch"),
            ("POST", "/api/v1/worker-delivery/endpoint-managers/{endpointManagerId}/commands:consume"),
            ("POST", "/api/v1/worker-delivery/endpoint-managers/{endpointManagerId}/results:append"),
            ("POST", "/api/v1/worker-delivery/endpoint-managers/{endpointManagerId}/workers/{workerId}/commands:poll"),
            ("POST", "/api/v1/worker-delivery/endpoint-managers/{endpointManagerId}/workers/{workerId}/results"),
        }
        self.assertEqual(actual - excluded, actual & set(ADMIN_OPERATIONS))
        self.assertTrue(excluded.isdisjoint(ADMIN_OPERATIONS))

    def test_admin_grants_do_not_cover_worker_or_internal_lookalikes(self):
        def allowed(method, path):
            return any(re.fullmatch(operation_pattern(m, p), method + ":" + path)
                       for m, p in ADMIN_OPERATIONS)
        self.assertTrue(allowed("POST", "/api/v1/worker-delivery/endpoint-managers/a/direct-calls"))
        self.assertTrue(allowed("POST", "/api/v1/tasks/a/items"))
        self.assertFalse(allowed("GET", "/api/v1/tasks/a/items"))
        self.assertFalse(allowed("POST", "/api/v1/tasks/a/items/extra"))
        self.assertFalse(allowed("POST", "/api/v1/worker-groups/a/workers:prepare"))
        self.assertFalse(allowed("POST", "/api/v1/worker-delivery/endpoint-managers/a/results:append"))

    def test_config_refuses_ambiguous_or_injected_values(self):
        bad = (
            {"admin_host": "localhost;return 200"}, {"worker_host": "staging.xamass.net"},
            {"listen_host": "8.8.8.8"}, {"runtime_port": 443}, {"https_port": True},
            {"certificate": "/tmp/x\ninclude evil;"}, {"state_directory": "/tmp/../etc"},
            {"worker_password_file": "relative/workers.htpasswd"},
            {"worker_groups": ()}, {"worker_groups": ("same", "same")},
            {"worker_groups": ("a/.+",)}, {"worker_groups": ("a$host",)},
        )
        for replacement in bad:
            with self.subTest(replacement=replacement), self.assertRaises(ValueError):
                configuration(**replacement)

    def test_render_is_deterministic_and_only_contains_configured_group_prepare_routes(self):
        text = render(configuration())
        self.assertEqual(text, render(configuration(worker_groups=("group-a", "group-b"))))
        self.assertIn('"POST:/api/v1/worker-groups/group-a/workers:prepare" 1;', text)
        self.assertNotIn("worker-groups/.*", text)
        self.assertIn('proxy_set_header Authorization "";', text)
        # The administrator host has no Basic realm; it forwards only a well-formed access key.
        self.assertEqual(1, text.count('auth_basic "'))
        self.assertIn('"~^Bearer xak_[A-Za-z0-9_-]{8,128}$" $http_authorization;', text)
        self.assertIn("proxy_set_header Authorization $admin_access_key;", text)
        admin = text[text.index("server_name localhost;") if "server_name localhost;" in text
                     else text.index("server_name staging.xamass.net;"):]
        admin = admin[:admin.index("server {")]
        self.assertNotIn("auth_basic", admin)
        self.assertIn("proxy_next_upstream off;", text)
        self.assertIn("log_format staging '$time_iso8601 $host $remote_user $request_method $uri $status';", text)

    def test_cli_rejects_unknown_duplicate_and_missing_fields(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "config.json"
            for document in ('{"x":1}', '{"admin_host":"a","admin_host":"b"}', '{}'):
                path.write_text(document, encoding="utf-8")
                with self.assertRaises(ValueError):
                    Deployment.load(path)

    def test_cli_writes_only_requested_config(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = root / "deployment.json"
            config.write_text(json.dumps(dataclasses.asdict(configuration())), encoding="utf-8")
            output = root / "nginx.conf"
            completed = subprocess.run([sys.executable, "-m", "distribution.server.staging_ingress", "render",
                "--config", str(config), "--output", str(output)], cwd=ROOT, capture_output=True, text=True)
            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertEqual(render(configuration()), output.read_text(encoding="utf-8"))
            self.assertEqual({"deployment.json", "nginx.conf"}, {path.name for path in root.iterdir()})


if __name__ == "__main__":
    unittest.main()
