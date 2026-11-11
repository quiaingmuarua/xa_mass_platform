#!/usr/bin/env python3
"""Provision one stopped, debuggable Demo APK; credentials travel only through ADB stdin."""
from __future__ import annotations

import argparse
import ipaddress
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
from urllib.parse import urlsplit
import uuid

PACKAGES = ("com.xa.mass.integration.androidworker",) + tuple(
    f"com.xa.mass.integration.androidworker.lab{index}" for index in range(1, 4))
MAX_CREDENTIAL_BYTES = 65_536
DESTINATION = "no_backup/worker-access.json"
JAVA_WHITESPACE = frozenset((*range(0x09, 0x0e), *range(0x1c, 0x21), 0x1680,
                            *range(0x2000, 0x2007), *range(0x2008, 0x200b),
                            0x2028, 0x2029, 0x205f, 0x3000))


class ProvisionError(RuntimeError):
    """Only fixed, credential-free diagnostics may cross the CLI boundary."""


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate field")
        result[key] = value
    return result


def _valid_origin(value):
    if not value or any(ord(char) <= 32 or ord(char) >= 127 for char in value):
        return False
    try:
        origin = urlsplit(value)
        if (origin.scheme.lower() != "https" or not origin.hostname
                or origin.username is not None or origin.password is not None
                or origin.path not in ("", "/") or "?" in value or "#" in value
                or origin.port == 0 or "%" in origin.netloc or "\\" in value):
            return False
        host = origin.hostname
        if ":" in host:
            ipaddress.IPv6Address(host)
        elif re.fullmatch(r"[0-9.]+", host):
            ipaddress.IPv4Address(host)
        else:
            labels = host.rstrip(".").split(".")
            if not all(re.fullmatch(r"[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?", part)
                       for part in labels):
                return False
        return True
    except (ValueError, UnicodeError):
        return False


def read_credential(path: Path) -> bytes:
    try:
        with Path(path).open("rb") as source:
            raw = source.read(MAX_CREDENTIAL_BYTES + 1)
        if not raw or len(raw) > MAX_CREDENTIAL_BYTES:
            raise ValueError("Invalid size")
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=_unique_object,
                           parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Invalid number")))
        if not isinstance(value, dict) or set(value) != {"allowedOrigin", "keyId", "secret"}:
            raise ValueError("Invalid fields")
        if not all(isinstance(item, str) for item in value.values()):
            raise ValueError("Invalid field type")
        if not _valid_origin(value["allowedOrigin"]):
            raise ValueError("Invalid origin")
        key, secret = value["keyId"], value["secret"]
        if not 1 <= len(key) <= 128 or any(not 33 <= ord(char) <= 126 or char == ":" for char in key):
            raise ValueError("Invalid key")
        if (not secret or all(ord(char) in JAVA_WHITESPACE for char in secret)
                or len(secret.encode("utf-8")) > 4096
                or any(ord(char) < 32 or 127 <= ord(char) <= 159 for char in secret)):
            raise ValueError("Invalid secret")
        return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    except (OSError, ValueError, UnicodeError, TypeError, RecursionError):
        raise ProvisionError("Credential file must be bounded UTF-8 JSON with valid allowedOrigin, keyId and secret") from None


def find_adb():
    executable = shutil.which("adb")
    if not executable:
        sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        candidate = Path(sdk or "") / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
        if candidate.is_file():
            executable = str(candidate.resolve())
    if not executable:
        raise ProvisionError("ADB was not found on PATH or in the Android SDK")
    return executable


def _run(command, *, data=None, timeout=30):
    try:
        return subprocess.run(command, input=data, capture_output=True, timeout=timeout)
    except (OSError, subprocess.SubprocessError):
        raise ProvisionError("ADB operation did not complete; credential publication may be unconfirmed") from None


def _stopped(adb, device, package):
    response = _run([adb, "-s", device, "shell", "pidof", package])
    if response.returncode == 0 or response.stdout.strip():
        raise ProvisionError("Selected App must be stopped before provisioning; it was not stopped by this tool")
    if response.returncode != 1:
        raise ProvisionError("Unable to confirm that the selected App is stopped")


def publication_script(package, temporary, byte_count):
    # Both strings come from fixed/locally generated identities, never credential JSON.
    assert package in PACKAGES and re.fullmatch(r"no_backup/\.worker-access-[0-9a-f]{32}\.tmp", temporary)
    assert type(byte_count) is int and 0 < byte_count <= MAX_CREDENTIAL_BYTES
    return f"""set -eu
umask 077
mkdir -p no_backup
test -d no_backup && test ! -L no_backup
test ! -L {DESTINATION}
if test -e {DESTINATION}; then test -f {DESTINATION}; fi
temporary={temporary}
trap 'rm -f "$temporary"' EXIT HUP INT TERM
check_stopped() {{
  if pidof {package} >/dev/null; then exit 73; else test "$?" -eq 1; fi
}}
check_stopped
(set -C; head -c {byte_count} > "$temporary")
test "$(wc -c < "$temporary")" -eq {byte_count}
chmod 600 "$temporary"
check_stopped
mv -f "$temporary" {DESTINATION}
trap - EXIT HUP INT TERM
printf 'worker-access-ready\\n'
"""


def provision(device: str, package: str, credential_file: Path, *, adb=None):
    if not device or any(ord(char) < 32 for char in device):
        raise ProvisionError("An explicit connected device serial is required")
    if package not in PACKAGES:
        raise ProvisionError("Only the fixed Debug and Lab APK packages may be provisioned")
    credential = read_credential(credential_file)
    executable = adb or find_adb()
    response = _run([executable, "devices"])
    if response.returncode:
        raise ProvisionError("Unable to inspect connected Android devices")
    devices = [parts[0] for line in response.stdout.decode("utf-8", errors="replace").splitlines()
               if len(parts := line.split()) == 2 and parts[1] == "device"]
    if device not in devices:
        raise ProvisionError("Selected Android device is unavailable or unauthorized")
    installed = _run([executable, "-s", device, "shell", "pm", "path", package])
    if installed.returncode or not any(line.startswith(b"package:") for line in installed.stdout.splitlines()):
        raise ProvisionError("Selected APK is not installed")
    debuggable = _run([executable, "-s", device, "shell", "run-as", package, "id", "-u"])
    if debuggable.returncode or not re.fullmatch(rb"[0-9]+", debuggable.stdout.strip()):
        raise ProvisionError("Selected APK must allow debuggable run-as access")
    _stopped(executable, device, package)
    temporary = "no_backup/.worker-access-" + uuid.uuid4().hex + ".tmp"
    script = publication_script(package, temporary, len(credential))
    # shell -T uses the bidirectional shell protocol, preserving stdin and the remote exit status.
    # exec-out is output-only and must never be used for credential provisioning.
    command = [executable, "-s", device, "shell", "-T", "run-as", package, "sh", "-c", shlex.quote(script)]
    try:
        result = _run(command, data=credential)
        if result.returncode or result.stdout.strip() != b"worker-access-ready":
            raise ProvisionError("Credential publication was not confirmed; the App was not started")
    except ProvisionError:
        cleanup = f"rm -f {temporary}"
        try:
            _run([executable, "-s", device, "shell", "-T", "run-as", package, "sh", "-c", shlex.quote(cleanup)])
        except ProvisionError:
            pass
        raise


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True)
    parser.add_argument("--package", required=True, choices=PACKAGES)
    parser.add_argument("--credential-file", required=True, type=Path)
    options = parser.parse_args(arguments)
    try:
        provision(options.device, options.package, options.credential_file)
    except ProvisionError as error:
        print(f"Provisioning failed: {error}", file=sys.stderr)
        return 1
    print(f"Private Worker access provisioned for {options.package}; App remains stopped")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
