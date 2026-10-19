"""Owned child-process primitives for the Correctness and Convergence runners."""
from __future__ import annotations

import os
from pathlib import Path
import signal
import subprocess
import time
import urllib.error
import urllib.request


def start_process(
    command: list[str],
    log_path: Path,
    environment: dict[str, str],
    *,
    cwd: Path,
) -> subprocess.Popen[str]:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log = log_path.open("w", encoding="utf-8", newline="\n")
    try:
        process = subprocess.Popen(
            command,
            cwd=cwd,
            env=environment,
            stdout=log,
            stderr=subprocess.STDOUT,
            text=True,
            creationflags=(
                subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0
            ),
            start_new_session=os.name != "nt",
        )
    except BaseException:
        log.close()
        raise
    log.close()
    return process


def stop_process(process: subprocess.Popen[str], *, force: bool) -> None:
    if process.poll() is not None:
        return
    if os.name == "nt":
        command = ["taskkill", "/PID", str(process.pid), "/T"]
        if force:
            command.append("/F")
        subprocess.run(
            command,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
    elif force:
        os.killpg(process.pid, signal.SIGKILL)
    else:
        os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=15)
    except subprocess.TimeoutExpired:
        if os.name == "nt":
            subprocess.run(
                ["taskkill", "/PID", str(process.pid), "/T", "/F"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False,
            )
        else:
            os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=15)


def wait_http(
    url: str,
    process: subprocess.Popen[str],
    maximum_wait_millis: int,
    log_path: Path,
) -> None:
    deadline = time.monotonic() + maximum_wait_millis / 1000
    latest: BaseException | None = None
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(
                f"Process exited with {process.returncode}; log:\n{tail(log_path)}"
            )
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                if 200 <= response.status < 300:
                    return
        except (OSError, urllib.error.URLError) as error:
            latest = error
        time.sleep(0.2)
    raise RuntimeError(f"Timed out waiting for {url}: {latest}; log:\n{tail(log_path)}")


def tail(path: Path, maximum_lines: int = 80) -> str:
    if not path.exists():
        return "<missing>"
    return "\n".join(
        path.read_text(encoding="utf-8", errors="replace").splitlines()[
            -maximum_lines:
        ]
    )
