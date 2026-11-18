from __future__ import annotations

import base64
from contextlib import redirect_stdout
import io
from pathlib import Path
import subprocess
import tempfile
import unittest

import check_credentials as checker


ACCESS = "xak_" + "A" * 43
GATEWAY = "B" * 43
PRIVATE = "-----BEGIN " + "OPENSSH PRIVATE KEY-----\n" + base64.b64encode(b"fixture" * 20).decode() + "\n-----END " + "OPENSSH PRIVATE KEY-----"


class CredentialCheckTest(unittest.TestCase):
    def test_project_literals_are_detected_without_returning_values(self):
        text = 'key = "' + ACCESS + '"\n{"secret":"' + GATEWAY + '"}\n' + PRIVATE
        self.assertEqual([checker.Finding("input.txt", 1, "production-access-key"),
                          checker.Finding("input.txt", 2, "gateway-secret"),
                          checker.Finding("input.txt", 3, "private-key-material")], checker.scan_text("input.txt", text))
        self.assertNotIn(ACCESS, repr(checker.scan_text("input.txt", text)))
        self.assertNotIn(GATEWAY, repr(checker.scan_text("input.txt", text)))

    def test_gateway_yaml_environment_and_multiline_json_literals(self):
        for prefix in ("secret: ", "WORKER_GATEWAY_SECRET=", '"secret":\n"', "'gateway-secret': '"):
            with self.subTest(prefix=prefix):
                self.assertEqual("gateway-secret", checker.scan_text("config", prefix + GATEWAY + '"')[0].kind)
        self.assertEqual([], checker.scan_text("config", 'secret: ${WORKER_GATEWAY_SECRET}\nsecret: example-only'))

    def test_partial_or_placeholder_material_and_longer_identifiers_are_not_keys(self):
        self.assertEqual([], checker.scan_text("doc.md", "-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----"))
        self.assertEqual([], checker.scan_text("doc.md", ACCESS + "a"))
        self.assertEqual([], checker.scan_text("doc.md", "xak_dev_" + "a" * 39))

    def test_complete_encrypted_pem_headers_do_not_hide_key_material(self):
        key = PRIVATE.replace("OPENSSH", "RSA").replace("-----\n", "-----\nProc-Type: 4,ENCRYPTED\nDEK-Info: AES-256-CBC,00\n\n", 1)
        self.assertEqual([checker.Finding("key.pem", 1, "private-key-material")], checker.scan_text("key.pem", key))

    def test_synthetic_exemption_is_local_and_requires_a_fixture_path(self):
        marked = '# credential-check: synthetic\nvalue="' + ACCESS + '"\n\nother="' + ACCESS + '"'
        self.assertEqual([checker.Finding("src/test/Fixture.java", 4, "production-access-key")],
                         checker.scan_text("src/test/Fixture.java", marked))
        self.assertEqual(2, len(checker.scan_text("src/main/Production.java", marked)))
        self.assertEqual(1, len(checker.scan_text("src/test/unmarked.json", '{"secret":"' + GATEWAY + '"}')))

    def test_current_tracked_tree_excludes_untracked_ignored_and_past_content(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                subprocess.run(["git", *args], cwd=root, check=True, capture_output=True)
            git("init", "--quiet")
            (root / ".gitignore").write_text("build/\n")
            tracked = root / "tracked.txt"
            tracked.write_text(ACCESS)
            git("add", ".")
            git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "--quiet", "-m", "fixture")
            tracked.write_text("current content\n")
            (root / "untracked.txt").write_text(ACCESS)
            (root / "build").mkdir()
            (root / "build/private.txt").write_text(ACCESS)
            self.assertEqual([], checker.scan(root))
            tracked.write_text(ACCESS)
            output = io.StringIO()
            with redirect_stdout(output):
                self.assertEqual(1, checker.main(["--root", str(root)]))
            self.assertIn("tracked.txt:1: production-access-key", output.getvalue())
            self.assertNotIn(ACCESS, output.getvalue())
            tracked.write_text(ACCESS, encoding="utf-16")
            self.assertEqual([checker.Finding("tracked.txt", 1, "production-access-key")], checker.scan(root))


if __name__ == "__main__":
    unittest.main()
