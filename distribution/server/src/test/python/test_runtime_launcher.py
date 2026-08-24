from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


LAUNCHER = (
    Path(__file__).resolve().parents[2]
    / "main"
    / "dist"
    / "bin"
    / "run-server.py"
)
SPEC = importlib.util.spec_from_file_location("xa_mass_runtime_launcher", LAUNCHER)
assert SPEC is not None and SPEC.loader is not None
launcher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(launcher)
PYTHON_REQUIRES = ">=3.11.3,<3.14"


class RuntimeLauncherTest(unittest.TestCase):

    class FakeVenvBuilder:
        def __init__(self) -> None:
            self.created: list[Path] = []

        def create(self, root: Path) -> None:
            self.created.append(root)
            (root / "bin").mkdir(parents=True)
            (root / "bin/python").write_bytes(b"python")
            (root / "Scripts").mkdir()
            (root / "Scripts/python.exe").write_bytes(b"python")

    def test_profile_is_fixed_and_other_spring_arguments_are_forwarded(self) -> None:
        self.assertEqual(
            ["--server.port=19082"],
            launcher._forwarded_arguments(["--", "--server.port=19082"]),
        )
        for arguments in (
            ["--", "--spring.profiles.active=default"],
            ["--", "--spring.profiles.active", "default"],
        ):
            with self.subTest(arguments=arguments):
                with self.assertRaises(launcher.LauncherError):
                    launcher._forwarded_arguments(arguments)

    def test_spring_arguments_require_the_separator(self) -> None:
        with self.assertRaises(launcher.LauncherError):
            launcher._forwarded_arguments(["--server.port=19082"])

    def test_python_requirement_has_explicit_supported_bounds(self) -> None:
        self.assertEqual(
            ((3, 11, 3), (3, 14, 0)),
            launcher._python_requirement_bounds(PYTHON_REQUIRES),
        )
        for requirement in ("3.13", ">=3.11", ">=3.14.0,<3.14"):
            with self.subTest(requirement=requirement):
                with self.assertRaises(launcher.LauncherError):
                    launcher._python_requirement_bounds(requirement)

    def test_distribution_owned_runtime_paths_cannot_be_overridden(self) -> None:
        for argument in launcher._OWNED_SPRING_ARGUMENTS:
            with self.subTest(argument=argument):
                with self.assertRaises(launcher.LauncherError):
                    launcher._forwarded_arguments(
                        ["--", f"{argument}=caller-value"]
                    )

    def test_manifest_coordinates_cannot_escape_the_runtime(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaises(launcher.LauncherError):
                launcher._safe_member(root, "../server.jar", name="serverJar")

    def test_wheel_fingerprint_tracks_version_and_every_wheel(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            wheelhouse = Path(temporary)
            (wheelhouse / "b.whl").write_bytes(b"b")
            (wheelhouse / "a.whl").write_bytes(b"a")
            marker = launcher._wheel_fingerprint(wheelhouse, "0.1.0")
            self.assertEqual("0.1.0", marker["runtimeVersion"])
            self.assertEqual(["a.whl", "b.whl"], list(marker["wheels"]))

    def test_java_command_uses_distribution_owned_runtime_paths(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "lib").mkdir()
            (root / "lib/server.jar").write_bytes(b"jar")
            (root / "frontend/dist").mkdir(parents=True)
            (root / "config").mkdir()
            (root / "config/pacer-default.json").write_text(
                "{}", encoding="utf-8"
            )
            manifest = {
                "serverJar": "lib/server.jar",
            }
            command = launcher._java_command(
                root,
                manifest,
                root / ".runtime/python-venv/bin/python",
                ["--server.port=19082"],
            )
            self.assertIn("--spring.profiles.active=scenario-workers", command)
            self.assertIn("--server.port=19082", command)
            self.assertTrue(
                any(
                    argument.startswith(
                        "--spring.web.resources.static-locations=file:"
                    )
                    for argument in command
                )
            )

    def test_marker_reader_rejects_non_object_json(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / launcher._MARKER_NAME).write_text("[]", encoding="utf-8")
            self.assertIsNone(launcher._read_marker(root))

    def test_offline_venv_is_created_once_and_reused(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            wheelhouse = root / "kernel/wheelhouse"
            wheelhouse.mkdir(parents=True)
            (wheelhouse / "runtime.whl").write_bytes(b"wheel")
            builder = self.FakeVenvBuilder()
            manifest = {
                "pythonRequires": PYTHON_REQUIRES,
                "version": "0.1.0",
            }
            with patch.object(
                launcher.venv,
                "EnvBuilder",
                return_value=builder,
            ), patch.object(launcher.subprocess, "run") as install:
                first = launcher._ensure_venv(root, manifest, wheelhouse)
                second = launcher._ensure_venv(root, manifest, wheelhouse)
            self.assertEqual(first, second)
            self.assertEqual(1, len(builder.created))
            self.assertEqual(1, install.call_count)

    def test_changed_runtime_version_rebuilds_only_owned_venv(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            wheelhouse = root / "kernel/wheelhouse"
            wheelhouse.mkdir(parents=True)
            (wheelhouse / "runtime.whl").write_bytes(b"wheel")
            builder = self.FakeVenvBuilder()
            with patch.object(
                launcher.venv,
                "EnvBuilder",
                return_value=builder,
            ), patch.object(launcher.subprocess, "run"):
                launcher._ensure_venv(
                    root,
                    {"pythonRequires": PYTHON_REQUIRES, "version": "0.1.0"},
                    wheelhouse,
                )
                launcher._ensure_venv(
                    root,
                    {"pythonRequires": PYTHON_REQUIRES, "version": "0.1.1"},
                    wheelhouse,
                )
            self.assertEqual(2, len(builder.created))
            marker = launcher._read_marker(root / ".runtime/python-venv")
            self.assertEqual("0.1.1", marker["runtimeVersion"])

    def test_failed_offline_install_preserves_previous_venv(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            wheelhouse = root / "kernel/wheelhouse"
            wheelhouse.mkdir(parents=True)
            wheel = wheelhouse / "runtime.whl"
            wheel.write_bytes(b"wheel")
            builder = self.FakeVenvBuilder()
            with patch.object(
                launcher.venv,
                "EnvBuilder",
                return_value=builder,
            ), patch.object(launcher.subprocess, "run"):
                launcher._ensure_venv(
                    root,
                    {"pythonRequires": PYTHON_REQUIRES, "version": "0.1.0"},
                    wheelhouse,
                )
            old_marker = launcher._read_marker(root / ".runtime/python-venv")
            wheel.write_bytes(b"changed")
            with patch.object(
                launcher.venv,
                "EnvBuilder",
                return_value=self.FakeVenvBuilder(),
            ), patch.object(
                launcher.subprocess,
                "run",
                side_effect=launcher.subprocess.CalledProcessError(1, "pip"),
            ):
                with self.assertRaises(launcher.LauncherError):
                    launcher._ensure_venv(
                        root,
                        {
                            "pythonRequires": PYTHON_REQUIRES,
                            "version": "0.1.0",
                        },
                        wheelhouse,
                    )
            self.assertEqual(
                old_marker,
                launcher._read_marker(root / ".runtime/python-venv"),
            )


if __name__ == "__main__":
    unittest.main()
