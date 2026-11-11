from __future__ import annotations

import os
import importlib.util
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import textwrap
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


class ConsumerProofGateTest(unittest.TestCase):
    def run_gate(self, *, manual=False, **overrides):
        workflow = (ROOT / ".github/workflows/proof-ci.yml").read_text(encoding="utf-8")
        gate = workflow.split("      - name: Require every selected proof lane", 1)[1]
        script = textwrap.dedent(gate.split("        run: |\n", 1)[1])
        environment = dict(os.environ)
        for name in set(re.findall(r"\$([A-Z_]+)", script)):
            if name.endswith("_SELECTED"):
                environment[name] = "false"
            elif name.endswith("_RESULT"):
                environment[name] = "success" if manual else "skipped"
        environment.update(EVENT_NAME="workflow_dispatch" if manual else "push",
                           CHANGES_RESULT="success", DOCS_RESULT="success")
        environment.update(overrides)
        bash = Path("C:/Program Files/Git/bin/bash.exe") if os.name == "nt" else None
        executable = str(bash) if bash and bash.is_file() else shutil.which("bash")
        self.assertIsNotNone(executable, "Proof Gate contract requires Bash")
        with tempfile.TemporaryDirectory() as directory:
            environment["GITHUB_STEP_SUMMARY"] = (Path(directory) / "summary").as_posix()
            return subprocess.run([executable, "-c", script], env=environment,
                                  capture_output=True, text=True).returncode

    def test_runtime_archive_lane_requires_the_real_consumer_and_single_producer(self):
        selected = dict(CANDIDATE_DISTRIBUTIONS_SELECTED="true", CANDIDATE_DISTRIBUTIONS_RESULT="success",
                        ANDROID_APK_SELECTED="true", ANDROID_APK_RESULT="success",
                        RUNTIME_DISTRIBUTION_SELECTED="true", RUNTIME_DISTRIBUTION_RESULT="success")
        self.assertEqual(0, self.run_gate(**selected))
        for key in ("CANDIDATE_DISTRIBUTIONS_RESULT", "ANDROID_APK_RESULT", "RUNTIME_DISTRIBUTION_RESULT"):
            for outcome in ("skipped", "failure", ""):
                with self.subTest(key=key, outcome=outcome):
                    self.assertNotEqual(0, self.run_gate(**dict(selected, **{key: outcome})))

    def test_pure_owner_checks_need_no_archive_chain(self):
        self.assertEqual(0, self.run_gate(ANDROID_HOST_SELECTED="true", ANDROID_HOST_RESULT="success"))

    def test_complete_manual_proof_rejects_missing_candidate_and_emulator(self):
        self.assertEqual(0, self.run_gate(manual=True))
        self.assertNotEqual(0, self.run_gate(manual=True, CANDIDATE_DISTRIBUTIONS_RESULT="skipped"))
        self.assertNotEqual(0, self.run_gate(manual=True, ANDROID_EMULATOR_RESULT="failure"))


class AndroidConsumerCredentialBuildTest(unittest.TestCase):
    def test_build_property_and_provenance_match_each_explicit_mode(self):
        spec = importlib.util.spec_from_file_location("credential_build_consumer", ROOT / ".github/scripts/build_android_consumer.py")
        builder = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(builder)
        for required in (False, True):
            with self.subTest(required=required), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / "apks"
                commands = []
                def copy(project):
                    project.mkdir()
                    (project / "run_consumer.py").write_text(
                        "def prepare_options(options, workspace):\n    return {'sdkRoot': str(workspace / 'sdk')}\n", encoding="utf-8")
                def build(command, cwd):
                    commands.append(command)
                    project = Path(cwd)
                    (project / "build").mkdir()
                    (project / "build/sdk-consumption.json").write_text('{"fixture":true}', encoding="utf-8")
                    for variant in ("debug", "lab1", "lab2", "lab3"):
                        apk = project / f"worker-demo/build/outputs/apk/{variant}/xa-mass-android-worker-demo-{variant}.apk"
                        apk.parent.mkdir(parents=True)
                        apk.write_bytes(variant.encode())
                    return subprocess.CompletedProcess(command, 0)
                arguments = ["--runtime-archive", "runtime.zip", "--sdk-archive", "sdk.zip", "--version", "3.2.0",
                             "--commit", "a" * 40, "--runtime-sha256", "b" * 64, "--sdk-sha256", "c" * 64,
                             "--runtime-api-base-url", "https://workers.example", "--output-dir", str(output)]
                if required: arguments.append("--gateway-credential-required")
                with patch.object(builder.consumer, "copy_consumer", side_effect=copy), \
                        patch.object(builder.subprocess, "run", side_effect=build), \
                        patch.object(builder.subprocess, "check_output", return_value="d" * 40):
                    self.assertEqual(0, builder.main(arguments))
                self.assertEqual(1, len(commands))
                self.assertIn(f"-PxaMassGatewayCredentialRequired={str(required).lower()}", commands[0])
                provenance = json.loads((output / "apk-provenance.json").read_text())
                self.assertIs(provenance["gatewayCredentialRequired"], required)
                self.assertEqual("https://workers.example", provenance["runtimeApiBaseUrl"])
                self.assertEqual("d" * 40, provenance["consumerSourceCommit"])


if __name__ == "__main__":
    unittest.main()
