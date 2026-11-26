from __future__ import annotations

import copy
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from unittest.mock import patch

import prepare_release as release


class ReleasePromotionTest(unittest.TestCase):
    def setUp(self):
        self.commit = "a" * 40
        self.repository = "quiaingmuarua/xa_mass_platform"
        self.run = dict(head_sha=self.commit, event="workflow_dispatch", status="completed",
                        conclusion="success", path=".github/workflows/proof-ci.yml",
                        repository={"full_name": self.repository})
        self.jobs = [dict(name=name, status="completed", conclusion="success")
                     for name in sorted(release.REQUIRED_JOBS | {
                         "Worker Convergence Health (worker-fault)",
                         "Worker Convergence Health (task-fault)"})]

    def test_complete_manual_proof_is_accepted(self):
        release.validate_run(self.run, self.jobs, self.commit, self.repository)

    def test_reusable_workflow_job_name_is_accepted(self):
        jobs = copy.deepcopy(self.jobs)
        next(job for job in jobs if job["name"] == "Product Coexistence")["name"] += " / Shared products and recipient outcomes"
        next(job for job in jobs if job["name"] == "SMS Reception Preview")["name"] += " / product-preview"
        release.validate_run(self.run, jobs, self.commit, self.repository)

    def test_sms_proof_is_required_with_its_real_result(self):
        missing = [job for job in self.jobs if job["name"] != "SMS Reception Preview"]
        with self.assertRaisesRegex(ValueError, "missing required jobs"):
            release.validate_run(self.run, missing, self.commit, self.repository)
        for outcome in ("failure", "cancelled", "skipped", None):
            with self.subTest(outcome=outcome):
                jobs = copy.deepcopy(self.jobs)
                next(job for job in jobs if job["name"] == "SMS Reception Preview")["conclusion"] = outcome
                with self.assertRaisesRegex(ValueError, "failed, incomplete or skipped"):
                    release.validate_run(self.run, jobs, self.commit, self.repository)

    def test_wrong_sha_partial_failed_and_foreign_runs_are_rejected(self):
        changes = [dict(head_sha="b" * 40), dict(event="push"), dict(conclusion="failure"),
                   dict(status="in_progress"), dict(repository={"full_name": "other/repo"}),
                   dict(path=".github/workflows/other.yml")]
        for change in changes:
            with self.subTest(change=change), self.assertRaises(ValueError):
                release.validate_run(dict(self.run, **change), self.jobs, self.commit, self.repository)

    def test_missing_skipped_and_failed_jobs_are_rejected(self):
        for jobs in [self.jobs[:-1], [dict(job, conclusion="skipped") for job in self.jobs],
                     [dict(job, conclusion="failure") for job in self.jobs]]:
            with self.subTest(jobs=jobs), self.assertRaises(ValueError):
                release.validate_run(self.run, jobs, self.commit, self.repository)

    def test_archive_producer_and_real_consumer_are_release_requirements(self):
        for name in ("Candidate Distribution Archives", "Android APK Assembly"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                release.validate_run(self.run, [job for job in self.jobs if job["name"] != name],
                                     self.commit, self.repository)

    def test_artifacts_require_current_attempt_and_every_unexpired_component(self):
        artifacts = [dict(name=f"xa-mass-{component}-2", expired=False) for component in release.COMPONENTS]
        self.assertEqual(3, len(release.validate_artifacts(artifacts, 2)))
        for invalid, attempt in [(artifacts[:1], 2), (artifacts[:2], 2),
                                 ([dict(artifact, expired=True) for artifact in artifacts], 2),
                                 (artifacts + [artifacts[0]], 2), (artifacts, 1)]:
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                release.validate_artifacts(invalid, attempt)

    def test_candidate_attempt_supports_failed_job_rerun(self):
        jobs = copy.deepcopy(self.jobs)
        next(job for job in jobs if job["name"] == "Candidate Distribution Archives")["run_attempt"] = 1
        self.assertEqual(1, release.candidate_artifact_attempt(jobs, 2))
        self.assertEqual(2, release.candidate_artifact_attempt(self.jobs, 2))
        for bad_attempt in (0, 3, True, "1"):
            bad_jobs = copy.deepcopy(self.jobs)
            next(job for job in bad_jobs if job["name"] == "Candidate Distribution Archives")["run_attempt"] = bad_attempt
            with self.subTest(bad_attempt=bad_attempt), self.assertRaises(ValueError):
                release.candidate_artifact_attempt(bad_jobs, 2)

    def test_archive_version_sha_and_schema_are_verified(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "runtime.zip"
            for changes in [{}, {"version": "0.5.0"}, {"gitCommit": "b" * 40}, {"schemaVersion": 3}]:
                manifest = dict(version="1.0.0", gitCommit=self.commit, schemaVersion=6)
                manifest.update(changes)
                with zipfile.ZipFile(path, "w") as archive:
                    archive.writestr("xa-mass-server-runtime-1.0.0/manifest.json", json.dumps(manifest))
                if changes:
                    with self.assertRaises(ValueError):
                        release.archive_manifest(path, "server-runtime", "1.0.0", self.commit)
                else:
                    release.archive_manifest(path, "server-runtime", "1.0.0", self.commit)

    def test_checksum_uses_only_asset_basename(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "runtime.zip"
            archive.write_bytes(b"published bytes")
            sidecar = release.checksum(archive)
            self.assertEqual(release.digest(archive) + "  runtime.zip\n", sidecar.read_text())
            self.assertEqual(sidecar, release.checksum(archive))

    def test_simulator_requires_current_schema_without_changing_other_components(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "simulator.zip"
            for schema in (2, 3):
                with zipfile.ZipFile(path, "w") as archive:
                    archive.writestr("xa-mass-worker-simulator-4.4.0/manifest.json", json.dumps({
                        "version": "4.4.0", "gitCommit": self.commit, "schemaVersion": schema}))
                if schema == 3:
                    release.archive_manifest(path, "worker-simulator", "4.4.0", self.commit)
                else:
                    with self.assertRaisesRegex(ValueError, "schema"):
                        release.archive_manifest(path, "worker-simulator", "4.4.0", self.commit)

    def test_draft_resume_accepts_only_identical_assets(self):
        with tempfile.TemporaryDirectory() as directory:
            expected = Path(directory) / "runtime.zip"
            downloaded = Path(directory) / "download.zip"
            expected.write_bytes(b"exact")
            downloaded.write_bytes(b"exact")
            release.validate_draft(dict(tag_name="v1.0.0", draft=True, assets=[{"name": expected.name}]), "v1.0.0", [expected])
            release.verify_existing_asset(expected, downloaded)
            downloaded.write_bytes(b"different")
            with self.assertRaises(ValueError):
                release.verify_existing_asset(expected, downloaded)
            for changes in [dict(draft=False), dict(tag_name="v0.5.0"), dict(assets=[{"name": "unknown"}])]:
                with self.assertRaises(ValueError):
                    release.validate_draft({**dict(tag_name="v1.0.0", draft=True, assets=[]), **changes}, "v1.0.0", [expected])

    def test_minor_contracts_use_exact_versions_and_all_three_components(self):
        self.assertEqual(("server-runtime", "worker-sdk", "worker-simulator"), release.COMPONENTS)
        for version in ("1.0.0", "2.0.0", "3.0.0", "3.1.0", "3.1.1", "3.2.0",
                        "4.0.0", "4.1.0", "4.2.0", "4.3.0", "4.3.1", "4.3.2"):
            with self.subTest(version=version):
                self._assert_draft_creation(version)

    def test_current_four_four_contract_is_read_from_repository(self):
        contract = release.ROOT / "distribution/server/RELEASE-4.4.md"
        expected = contract.read_text(encoding="utf-8")
        self.assertTrue(expected.startswith("# XA Mass 4.4 release"))
        for version in ("4.4.0", "4.4.5"):
            with self.subTest(version=version):
                self.assertEqual(expected, release.release_contract(version))

    def test_current_four_five_contract_is_read_from_repository(self):
        contract = release.ROOT / "distribution/server/RELEASE-4.5.md"
        expected = contract.read_text(encoding="utf-8")
        self.assertTrue(expected.startswith("# XA Mass 4.5 release"))
        for version in ("4.5.0",):
            with self.subTest(version=version):
                self.assertEqual(expected, release.release_contract(version))

    def test_current_four_six_contract_is_read_from_repository(self):
        contract = release.ROOT / "distribution/server/RELEASE-4.6.md"
        expected = contract.read_text(encoding="utf-8")
        self.assertTrue(expected.startswith("# XA Mass 4.6 release"))
        for version in ("4.6.0",):
            with self.subTest(version=version):
                self.assertEqual(expected, release.release_contract(version))

    def test_current_four_seven_contract_is_read_from_repository(self):
        contract = release.ROOT / "distribution/server/RELEASE-4.7.md"
        expected = contract.read_text(encoding="utf-8")
        self.assertTrue(expected.startswith("# XA Mass 4.7 release"))
        for version in ("4.7.0",):
            with self.subTest(version=version):
                self.assertEqual(expected, release.release_contract(version))

    def test_missing_minor_contract_fails_before_github_without_major_fallback(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            documents = root / "distribution/server"
            documents.mkdir(parents=True)
            (documents / "RELEASE-3.0.md").write_text("synthetic major contract", encoding="utf-8")
            with patch.object(release, "ROOT", root), patch.object(release, "gh") as github:
                with self.assertRaisesRegex(ValueError, "RELEASE-3.1.md"):
                    release.prepare(self.repository, "v3.1.0", self.commit, root / "output")
                github.assert_not_called()

    def _assert_draft_creation(self, version):
        tag = "v" + version
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            major, minor, _ = version.split(".")
            documents = root / "distribution/server"
            documents.mkdir(parents=True)
            contract = f"# XA Mass {major}.{minor} release\n\nSynthetic minor contract.\n"
            (documents / f"RELEASE-{major}.{minor}.md").write_text(contract, encoding="utf-8")
            fixtures = {}
            for component in release.COMPONENTS:
                name = f"xa-mass-{component}-{version}.zip"
                path = root / name
                manifest = dict(version=version, gitCommit=self.commit,
                                schemaVersion={"server-runtime": 6, "worker-sdk": 1, "worker-simulator": 3}[component])
                with zipfile.ZipFile(path, "w") as archive:
                    archive.writestr(name[:-4] + "/manifest.json", json.dumps(manifest))
                fixtures[component] = path
            uploaded = []
            created = dict(id=42, tag_name=tag, draft=True, assets=[])
            def fake_gh(*arguments):
                if arguments[:2] == ("run", "download"):
                    component = next(component for component in release.COMPONENTS
                                     if f"xa-mass-{component}-1" in arguments)
                    target = Path(arguments[-1]) / fixtures[component].name
                    target.write_bytes(fixtures[component].read_bytes())
                    return ""
                if arguments[:3] == ("api", "--method", "POST"):
                    document = json.loads(Path(arguments[-1]).read_text())
                    self.assertTrue(document["draft"])
                    self.assertEqual(tag, document["tag_name"])
                    self.assertEqual(contract + f"\nVerified full Proof: {run['html_url']}\n\n"
                                     f"Commit: `{self.commit}`\n", document["body"])
                    return json.dumps(created)
                if arguments[:2] == ("release", "upload"):
                    uploaded.append(Path(arguments[3]).name)
                    return ""
                self.fail(f"Unexpected GitHub operation: {arguments[:3]}")
            def fake_api(endpoint):
                self.assertTrue(endpoint.endswith("/releases/42"))
                return {**created, "assets": [{"name": name} for name in uploaded]}
            run = dict(id=1, html_url="https://github.com/example/proof")
            with patch.object(release, "ROOT", root), \
                    patch.object(release, "verify_tag"), \
                    patch.object(release, "find_run",
                                 return_value=(run, [f"xa-mass-{c}-1" for c in release.COMPONENTS])), \
                    patch.object(release, "paged", return_value=[]) as listing, \
                    patch.object(release, "gh", side_effect=fake_gh), \
                    patch.object(release, "api", side_effect=fake_api), \
                    patch.object(release, "verify_runtime"), \
                    patch.object(release, "verify_sdk"), \
                    patch.object(release, "verify_simulator") as simulator:
                release.prepare(self.repository, tag, self.commit, root / "output")
            listing.assert_called_once()
            simulator.assert_called_once()
            self.assertEqual(2 * len(release.COMPONENTS), len(uploaded))
            expected_archives = {path.name for path in fixtures.values()}
            self.assertEqual(expected_archives | {name + ".sha256" for name in expected_archives},
                             set(uploaded))


if __name__ == "__main__":
    unittest.main()
