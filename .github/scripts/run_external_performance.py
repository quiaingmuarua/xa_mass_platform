#!/usr/bin/env python3
"""Transport one verified Performance kit to its unprivileged consumer; never retry work.

The same file is the stdlib-only remote unit entry. Run IDs, not attempts, own units.
The published Performance runner remains the sole workload and measurement owner.
"""
from __future__ import annotations

import argparse
from contextlib import ExitStack, contextmanager
from datetime import date, datetime, time as daytime, timedelta, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import shlex
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import time
import zipfile


USER = "xa-mass-performance"
ENTRY = "acceptance/performance/run_worker_call_performance.py"
FILES = ("summary.json", "summary.md", "consumption.json", "orchestration.json")
UNIT_PREFIX = "xa-mass-performance-"
PREPARE_TTL_SECONDS = 300
SENSITIVE = re.compile(r"\bxak_[A-Za-z0-9_-]+\b|\bBasic\s+[A-Za-z0-9+/]{12,}={0,2}")
IDENTITY = re.compile(r"\b[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\b|\btask-[0-9a-f]{32}\b|\bperf-rpc-[0-9a-f]+\b")
PRIVATE_PATH = re.compile(r"/(?:run/user/[0-9]+|var/lib/xa-mass-performance|home/[^\s/\"']+)(?:/[^\s\"',;)}\]]+)*")


class Failure(RuntimeError):
    """Only fixed, operator-authored text is safe outside the private host."""


class LockBusy(Failure):
    pass


class ObservationSkipped(Failure):
    pass


def require(value, reason):
    if not value:
        raise Failure(reason)


def now():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def observation_now():
    return datetime.now(timezone.utc)


def observation_bounds(slot):
    local = timezone(timedelta(hours=8))
    return (datetime.combine(slot, daytime(5, 30), local).astimezone(timezone.utc),
            datetime.combine(slot + timedelta(days=1), daytime(), local).astimezone(timezone.utc))


def observation_reason(value, current=None):
    """The copied remote helper validates the same finite slot without another configuration file."""
    if value is None:
        return None
    try:
        matched = re.fullmatch(r"external-10k-([0-9]{8})", value["windowId"])
        if matched is None:
            return "invalid-observation"
        start = datetime.strptime(matched[1], "%Y%m%d").date()
        slot = date.fromisoformat(value["slotDate"])
        before, after = observation_bounds(slot)
        if not start <= slot < start + timedelta(days=7) or value["notBeforeUTC"] != before.isoformat() or value["notAfterUTC"] != after.isoformat():
            return "invalid-observation"
        current = current or observation_now()
        if current < before:
            return "before-observation-slot"
        if current >= after:
            return "observation-slot-expired"
        return None
    except (KeyError, TypeError, ValueError):
        return "invalid-observation"


def observation_slot(path, environment, workers, repetitions):
    plan = read_json(path)
    require(isinstance(plan, dict) and plan.get("schemaVersion") == 1 and plan.get("days") == 7
            and plan.get("localTime") == "05:30" and plan.get("utcOffset") == "+08:00"
            and plan.get("workersPerGroup") == workers == 5000 and plan.get("repetitions") == repetitions == 1,
            "Observation plan must declare the fixed seven-day 10k one-repetition window")
    start = date.fromisoformat(plan["startDate"])
    require(plan.get("windowId") == "external-10k-" + start.strftime("%Y%m%d"), "Observation window identity differs")
    value = {"windowId": plan["windowId"], "status": "missing", "reason": "not-an-observation-schedule"}
    schedule = environment.get("OBSERVATION_SCHEDULE", "")
    for offset in range(7):
        slot = start + timedelta(days=offset)
        before, after = observation_bounds(slot)
        if schedule == f"{before.minute} {before.hour} {before.day} {before.month} *":
            current = observation_now()
            value.update(slotDate=slot.isoformat(), notBeforeUTC=before.isoformat(), notAfterUTC=after.isoformat(),
                         observedAtUTC=current.isoformat(), actualDelaySeconds=int((current - before).total_seconds()))
            reason = observation_reason(value, current)
            if environment.get("GITHUB_EVENT_NAME") != "schedule" or environment.get("GITHUB_REF") != "refs/heads/main":
                reason = "not-original-main-schedule"
            if int(environment.get("GITHUB_RUN_ATTEMPT", "1")) != 1:
                reason = "rerun-attach-only"
            value.update(status="eligible" if reason is None else "missing", reason=reason)
            break
    return value


def observation_output(admitted):
    path = os.environ.get("GITHUB_OUTPUT")
    if path:
        with Path(path).open("a", encoding="utf-8") as target:
            target.write("observation_admitted=" + ("true" if admitted else "false") + "\n")


def missing_observation(identifier, value, reason, receipt=None):
    value = dict(value, status="missing", reason=reason)
    return {"summary.json": {"status": "missing", "caseCounts": {}, "observation": value},
            "summary.md": ("# External Performance observation\n\nStatus: **missing**; no performance pass is claimed.\n\n"
                           f"Window: `{value.get('windowId')}`. Planned local date: `{value.get('slotDate', 'unmatched')}`. "
                           f"Reason: `{reason}`. Actual delay seconds: `{value.get('actualDelaySeconds', 'unavailable')}`.\n"),
            "consumption.json": {"status": "not-run", "observation": value},
            "orchestration.json": {"runId": identifier, "state": "observation-skipped", "observation": value,
                                   "receipt": receipt, "cleanupRequired": bool(receipt and (
                                       receipt.get("privateInputsRemoved") is not True or receipt.get("finalizationFailures") != []))}}


def run_id(value):
    require(isinstance(value, str) and re.fullmatch(r"[0-9]{1,20}", value), "Invalid GitHub Run ID")
    return value


def write_json(path, value, *, exclusive=False):
    data = (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode()
    target = path if exclusive else path.with_name(path.name + f".{os.getpid()}.tmp")
    descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600)
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    if not exclusive:
        os.replace(target, path)


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            result.update(chunk)
    return result.hexdigest()


def call(command, *, data=None, env=None, timeout=60):
    result = subprocess.run(command, input=data, capture_output=True, env=env, timeout=timeout)
    require(result.returncode == 0, "Command or transport was not confirmed")
    return result.stdout


def paths(identifier):
    import pwd
    require(sys.platform == "linux" and os.getuid() != 0 and pwd.getpwuid(os.getuid()).pw_name == USER,
            "Remote actions require the dedicated unprivileged consumer")
    home = Path(pwd.getpwuid(os.getuid()).pw_dir).resolve()
    root = home / "runs" / run_id(identifier)
    runtime = Path(f"/run/user/{os.getuid()}")
    require(runtime.is_dir() and runtime.stat().st_uid == os.getuid(), "User runtime/linger is unavailable")
    return root, runtime / UNIT_PREFIX.rstrip("-") / identifier


def user_environment():
    result = dict(os.environ)
    result["XDG_RUNTIME_DIR"] = f"/run/user/{os.getuid()}"
    result["DBUS_SESSION_BUS_ADDRESS"] = f"unix:path=/run/user/{os.getuid()}/bus"
    return result


def unit_name(identifier):
    return UNIT_PREFIX + run_id(identifier) + ".service"


def prepare_cleanup_name(identifier, suffix="timer"):
    return UNIT_PREFIX + run_id(identifier) + "-prepare-cleanup." + suffix


def current_boot_id():
    return Path("/proc/sys/kernel/random/boot_id").read_text().strip()


def prepare_timer_status(identifier):
    completed = subprocess.run(["systemctl", "--user", "show", prepare_cleanup_name(identifier), "--no-pager",
                                "--property=LoadState,ActiveState,Unit"], env=user_environment(), capture_output=True, timeout=30)
    value = dict(line.split("=", 1) for line in completed.stdout.decode().splitlines() if "=" in line)
    require("LoadState" in value and (completed.returncode == 0 or value["LoadState"] == "not-found"),
            "Preparation cleanup timer state is unavailable")
    return value


def arm_prepare_cleanup(identifier, root):
    """Called under control.lock, before the first private input; the lease never renews."""
    lease = {"runId": identifier, "requestedAt": now(), "ttlSeconds": PREPARE_TTL_SECONDS,
             "expiresAtMonotonic": time.monotonic() + PREPARE_TTL_SECONDS,
             "bootId": current_boot_id(), "armConfirmed": False}
    write_json(root / "prepare-lease.json", lease, exclusive=True)
    call(["systemd-run", "--user", "--collect", "--unit=" + prepare_cleanup_name(identifier, "service"),
          "--on-active=300s", "--timer-property=AccuracySec=1s", "--timer-property=RemainAfterElapse=no",
          "--property=Type=oneshot", "--property=TimeoutStartSec=120s", "--property=UMask=0077",
          sys.executable, str(root / "orchestrator.py"), "remote", "expire-preparation", "--run-id", identifier],
         env=user_environment())
    status = prepare_timer_status(identifier)
    require(status.get("LoadState") == "loaded" and status.get("ActiveState") == "active"
            and status.get("Unit") == prepare_cleanup_name(identifier, "service"), "Preparation cleanup timer was not confirmed")
    lease["armConfirmed"] = True
    write_json(root / "prepare-lease.json", lease)


def preparation_expired(root):
    path = root / "prepare-lease.json"
    if not path.exists():
        return False
    lease = read_json(path)
    require(lease.get("bootId") == current_boot_id(), "Original preparation belongs to another boot; outcome unknown, never restart")
    return time.monotonic() >= lease["expiresAtMonotonic"]


def update_prepare_cleanup(root, values):
    """The caller holds control.lock; only safe aggregate lifecycle fields are retained."""
    path = root / "prepare-cleanup.json"
    current = read_json(path) if path.exists() else {}
    current.update(values)
    write_json(path, current)
    return current


def stop_prepare_cleanup(identifier, root):
    # Never wait for systemd while holding control.lock: an already-fired helper may need it.
    if not (root / "prepare-lease.json").exists():
        return
    path = root / "prepare-cleanup.json"
    if path.exists() and read_json(path).get("timerStopped"):
        return
    try:
        status = prepare_timer_status(identifier)
        if status.get("LoadState") != "not-found" and status.get("ActiveState") not in {"inactive", "failed"}:
            call(["systemctl", "--user", "stop", prepare_cleanup_name(identifier)], env=user_environment(), timeout=30)
        values = {"timerStopped": True, "timerStoppedAt": now()}
    except (Failure, OSError, subprocess.SubprocessError) as error:
        values = {"timerStopped": False, "timerStopFailureType": type(error).__name__}
    try:
        with locked(root / "control.lock", wait_seconds=90):
            update_prepare_cleanup(root, values)
    except Exception as error:
        write_json(root / "prepare-cleanup-failure.json", {"outcome": "unconfirmed", "observedAt": now(),
                                                          "failureType": type(error).__name__})


def unit_status(identifier):
    completed = subprocess.run(["systemctl", "--user", "show", unit_name(identifier), "--no-pager",
                               "--property=LoadState,ActiveState,SubState,Result,ExecMainCode,ExecMainStatus"],
                               env=user_environment(), capture_output=True, timeout=30)
    value = dict(line.split("=", 1) for line in completed.stdout.decode().splitlines() if "=" in line)
    require("LoadState" in value and (completed.returncode == 0 or value["LoadState"] == "not-found"),
            "Original unit state is unavailable")
    return value


def remote_status(identifier):
    root, _ = paths(identifier)
    receipt = root / "receipt.json"
    if receipt.is_file():
        return {"state": "finished", "receipt": read_json(receipt)}
    if (root / "observation-skipped.json").is_file():
        return dict(read_json(root / "observation-skipped.json"), state="observation-skipped")
    if (root / "prepare-lease.json").exists() and read_json(root / "prepare-lease.json").get("bootId") != current_boot_id():
        return {"state": "unknown"}
    if not (root / "state.json").is_file():
        return {"state": "unknown" if (root / "orchestrator.py").exists() else "absent"}
    status = unit_status(identifier)
    if status.get("LoadState") == "not-found":
        state = "unknown" if (root / "start-request.json").exists() else "prepared"
    elif status.get("ActiveState") in {"active", "activating", "deactivating"}:
        state = "running"
    else:
        state = "unknown"
    return {"state": state, "unit": status}


@contextmanager
def locked(path, *, wait_seconds=0):
    import fcntl
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    with path.open("a") as stream:
        os.chmod(path, 0o600)
        deadline = time.monotonic() + wait_seconds
        while True:
            try:
                fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                if time.monotonic() >= deadline:
                    raise LockBusy("Another consumer or control operation owns the lock")
                time.sleep(.2)
        yield


def latest_prior_run(root):
    flags = ("state.json", "prepare-lease.json", "start-request.json", "receipt.json")
    candidates = []
    for candidate in root.parent.iterdir():
        if candidate == root or not candidate.is_dir() or candidate.is_symlink() or not re.fullmatch(r"[0-9]{1,20}", candidate.name):
            continue
        present = [candidate / name for name in flags if (candidate / name).exists()]
        if not present:
            continue
        stamp = None
        for filename, field in (("state.json", "preparedAt"), ("prepare-lease.json", "requestedAt"),
                                ("start-request.json", "requestedAt"), ("receipt.json", "finishedAt")):
            path = candidate / filename
            if path.is_file():
                try:
                    value = datetime.fromisoformat(read_json(path).get(field, "").replace("Z", "+00:00"))
                    if value.tzinfo is not None:
                        stamp = value.timestamp()
                        break
                except (OSError, TypeError, ValueError, AttributeError):
                    pass
        # Historical acceptance fixtures may use very large synthetic Run IDs.
        candidates.append((stamp if stamp is not None else max(path.stat().st_mtime for path in present), candidate.name, candidate))
    return max(candidates)[2] if candidates else None


def prior_admission_reason(root, *, scheduled):
    try:
        prior = latest_prior_run(root)
    except (OSError, ValueError, TypeError):
        return "prior-cleanup-unconfirmed"
    if prior is None:
        return None
    if not (prior / "receipt.json").is_file():
        return "prior-reservation-pending"
    try:
        receipt = read_json(prior / "receipt.json")
        status = unit_status(prior.name)
        active = status.get("ActiveState")
        if active in {"activating", "deactivating"} or (active == "active" and status.get("SubState") != "exited"):
            return "prior-unit-running"
        if status.get("LoadState") not in {"loaded", "not-found"} or not (active in {"inactive", "failed"}
                or (active == "active" and status.get("SubState") == "exited")):
            return "prior-unit-state-unconfirmed"
        if not scheduled:
            return None
        if receipt.get("privateInputsRemoved") is not True or receipt.get("finalizationFailures") != []:
            return "prior-cleanup-unconfirmed"
        if not any((prior / name).exists() for name in ("start-request.json", "execution.json", "result")):
            return None  # Clean finalization before any start cannot have admitted load.
        cleanup = read_json(prior / "result/evidence/lane-summary.json").get("cleanup", {})
        if (cleanup.get("ownedProcessesStopped") is not True or cleanup.get("privateCredentialCopiesRemoved") is not True
                or cleanup.get("workerConnections", {}).get("status") != "confirmed" or cleanup.get("errors") != []):
            return "prior-cleanup-unconfirmed"
        return None
    except (OSError, ValueError, TypeError, AttributeError, Failure, subprocess.SubprocessError):
        return "prior-cleanup-unconfirmed"


def claim_observation(root, identifier, value):
    reason = observation_reason(value)
    if reason:
        raise ObservationSkipped(reason)
    directory = root.parent / "observation-windows" / value["windowId"]
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    claim = directory / (value["slotDate"] + ".json")
    try:
        claimed = observation_now()
        write_json(claim, {"runId": identifier, "windowId": value["windowId"], "slotDate": value["slotDate"],
                           "claimedAtUTC": claimed.isoformat()}, exclusive=True)
        value.update(claimOutcome="claimed", claimedAtUTC=claimed.isoformat())
    except FileExistsError:
        raise ObservationSkipped("observation-slot-already-claimed") from None


def skipped_preparation(root, value, reason):
    record = {"state": "observation-skipped", "reason": reason, "observation": dict(value, status="missing", reason=reason),
              "cleanupConfirmed": True}
    path = root / "observation-skipped.json"
    if not path.exists():
        write_json(path, record, exclusive=True)
    return record


def reject_late_observation_locked(identifier, root, private, value, reason):
    if not (root / "stop-request.json").exists():
        write_json(root / "stop-request.json", {"requestedAt": now(), "reason": reason}, exclusive=True)
    record = skipped_preparation(root, value, reason)
    receipt = finalize_locked(identifier, root, private)
    return dict(record, receipt=receipt, cleanupConfirmed=(receipt.get("privateInputsRemoved") is True
                                                          and receipt.get("finalizationFailures") == []))


def validate_config(config, server_tag, server_commit, workers):
    require(re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", server_tag) and re.fullmatch(r"[0-9a-f]{40}", server_commit), "Invalid Server Release identity")
    require(config.get("consumerId") == "public43-performance", "Stable consumerId differs")
    deployment = config.get("serverDeployment", {})
    require(deployment.get("version") == server_tag[1:] and deployment.get("gitCommit") == server_commit, "Deployment and Server Release differ")
    require(isinstance(config.get("groups"), list) and len(config["groups"]) == 2
            and len(set(config["groups"])) == 2 and all(isinstance(g, str) for g in config["groups"]), "Two existing Groups are required")
    require(type(workers) is int and 1 <= workers <= 15000, "Worker quantity is outside the existing Host bound")
    require(isinstance(config.get("project"), str) and config["project"], "Existing Project is required")
    return config


def heap_bytes(value):
    match = re.fullmatch(r"([1-9][0-9]*)([mMgG])", value)
    require(match is not None, "Host heap must use an explicit JVM m/g quantity")
    return int(match[1]) * (1024 ** (2 if match[2].lower() == "m" else 3))


def preflight(config, workers, budget):
    import resource
    require(set(budget) == {"perCredential", "total", "reserved"}
            and all(type(v) is int and v >= 0 for v in budget.values()), "Connection budget is incomplete")
    connections = 2 * workers
    ports = [int(v) for v in Path("/proc/sys/net/ipv4/ip_local_port_range").read_text().split()]
    require(len(ports) == 2, "Ephemeral port evidence is unavailable")
    reserved = set()
    for field in Path("/proc/sys/net/ipv4/ip_local_reserved_ports").read_text().strip().split(","):
        if field:
            bounds = [int(v) for v in field.split("-")]
            reserved.update(range(bounds[0], bounds[-1] + 1))
    available_ports = ports[1] - ports[0] + 1 - sum(ports[0] <= p <= ports[1] for p in reserved)
    soft, hard = resource.getrlimit(resource.RLIMIT_NOFILE)
    fd_budget = min(config.get("hostFdLimit", 32768), 65536, hard if hard >= 0 else 65536)
    require(connections <= budget["perCredential"] and connections <= budget["total"] - budget["reserved"], "Configured gateway connection budget is insufficient")
    require(connections < fd_budget and connections <= available_ports, "Host FD or local port budget is insufficient")
    memory = dict(re.findall(r"^(MemAvailable|MemTotal):\s+(\d+) kB$", Path("/proc/meminfo").read_text(), re.M))
    available = int(memory.get("MemAvailable", "0")) * 1024
    heaps = heap_bytes(config.get("hostMaxHeap", "3g")) + 1024**3
    require(available >= heaps, "Available RAM is below the declared Host and Harness heap budgets")
    disk = shutil.disk_usage(Path.home()).free
    require(disk >= 1024**3, "Consumer disk has less than 1 GiB free")
    call(["systemctl", "--user", "list-units", "--no-pager", "--no-legend"], env=user_environment())
    return {"availableMemoryBytes": available, "declaredHeapBytes": heaps, "diskFreeBytes": disk,
            "nofile": [soft, hard], "effectiveFdBudget": fd_budget, "ephemeralPortRange": ports,
            "availableLocalPorts": available_ports, "connections": connections, "connectionBudget": budget,
            "limit": "Preparation budgets do not establish public network capacity or peak physical memory"}


def extract_kit(archive, root, tag, commit, expected_sha):
    require(digest(archive) == expected_sha and archive.stat().st_size <= 256 * 1024**2, "Transferred ZIP differs from the verified asset")
    prefix = "xa-mass-worker-simulator-" + tag[1:]
    with zipfile.ZipFile(archive) as source:
        seen, total = set(), 0
        for entry in source.infolist():
            name = PurePosixPath(entry.filename)
            require(entry.filename not in seen and "\\" not in entry.filename and not name.is_absolute()
                    and ".." not in name.parts and name.parts[0] == prefix
                    and not stat.S_ISLNK(entry.external_attr >> 16), "Unsafe or ambiguous kit archive member")
            total += entry.file_size
            require(total <= 512 * 1024**2, "Expanded kit exceeds its transfer bound")
            seen.add(entry.filename)
        manifest = json.loads(source.read(prefix + "/manifest.json"))
        require(manifest.get("schemaVersion") == 3 and manifest.get("performanceEntry") == ENTRY
                and manifest.get("version") == tag[1:] and manifest.get("gitCommit") == commit, "Kit manifest differs from the verified deployment")
        require(prefix + "/" + ENTRY in seen, "Published Performance entry is missing")
        source.extractall(root / "kit")
    return root / "kit" / prefix, manifest


def remote_prepare(identifier, envelope):
    root, private = paths(identifier)
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    require(not root.is_symlink() and root.stat().st_uid == os.getuid(), "Run directory ownership differs")
    os.chmod(root, 0o700)
    observation = envelope.get("observation")
    entered_control = False
    try:
        with locked(root / "control.lock"):
            entered_control = True
            if any((root / name).exists() for name in ("state.json", "start-request.json", "stop-request.json", "receipt.json", "prepare-lease.json")):
                return remote_status(identifier)
            helpers = envelope.get("helpers")
            require(isinstance(helpers, dict) and isinstance(helpers.get("controllerCommit"), str)
                    and re.fullmatch(r"[0-9a-f]{40}", helpers["controllerCommit"])
                    and helpers["controllerCommit"] == envelope.get("github", {}).get("controllerCommit"),
                    "Controller helper identity differs")
            for name in ("orchestrator.py", "reporting.py"):
                require(helpers.get(name) == digest(root / name), "Controller helper digest differs")
            if observation is not None:
                require(envelope["workers"] == 5000 and envelope["repetitions"] == 1, "Observation scale differs")
                claim_observation(root, identifier, observation)
                write_json(root / "observation.json", observation, exclusive=True)
            config = validate_config(envelope["deployment"], envelope["serverTag"], envelope["serverCommit"], envelope["workers"])
            # The entire preparation publishes one reservation before releasing this nonblocking lease.
            with locked(root.parent / "performance.lock"):
                reason = prior_admission_reason(root, scheduled=observation is not None)
                if reason:
                    if observation is not None:
                        raise ObservationSkipped(reason)
                    raise Failure("A prior consumer reservation remains unresolved")
                if observation is not None and observation_reason(observation):
                    raise ObservationSkipped(observation_reason(observation))
                budget = preflight(config, envelope["workers"], envelope["budget"])
                key, gateway = envelope["accessKey"].strip(), envelope["gateway"]
                require(re.fullmatch(r"xak_[A-Za-z0-9_-]{8,128}", key), "Invalid private operator input")
                require(set(gateway) == {"keyId", "secret"} and all(isinstance(v, str) and v for v in gateway.values())
                        and not any(ord(c) < 32 for v in gateway.values() for c in v), "Invalid private gateway input")
                kit, manifest = extract_kit(root / "kit.zip", root, envelope["tag"], envelope["commit"], envelope["kitSha256"])
                if observation is not None and observation_reason(observation):
                    raise ObservationSkipped(observation_reason(observation))
                arm_prepare_cleanup(identifier, root)
                require(not preparation_expired(root), "Preparation cleanup deadline expired before private input")
                if observation is not None and observation_reason(observation):
                    raise ObservationSkipped(observation_reason(observation))
                private.mkdir(parents=True, exist_ok=False, mode=0o700)
                write_json(private / "gateway.json", gateway, exclusive=True)
                descriptor = os.open(private / "access-key.txt", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                with os.fdopen(descriptor, "w") as target:
                    target.write(key + "\n")
                config = dict(config, workersPerGroup=envelope["workers"], accessKeyFile=str(private / "access-key.txt"), gatewayCredentialFile=str(private / "gateway.json"))
                write_json(root / "deployment.json", config, exclusive=True)
                state = {"runId": identifier, "firstAttempt": envelope["attempt"], "tag": envelope["tag"],
                         "commit": envelope["commit"], "workersPerGroup": envelope["workers"], "repetitions": envelope["repetitions"],
                         "kitRoot": str(kit), "manifest": manifest, "consumption": envelope["consumption"],
                         "preflight": budget, "preparedAt": now(), "github": envelope.get("github", {}),
                         "helpers": helpers,
                         "serverTag": envelope["serverTag"], "serverCommit": envelope["serverCommit"]}
                if observation is not None:
                    state["observation"] = observation
                write_json(root / "state.json", state, exclusive=True)
    except (ObservationSkipped, LockBusy) as error:
        if observation is None or not entered_control:
            raise
        reason = "consumer-busy" if isinstance(error, LockBusy) else str(error)
        return skipped_preparation(root, observation, reason)
    return {"state": "prepared", "preflight": budget}


def start_command(identifier, root, repetitions):
    helper = root / "orchestrator.py"
    finalize = shlex.join([sys.executable, str(helper), "remote", "finalize", "--run-id", identifier])
    return ["systemd-run", "--user", "--no-block", "--unit=" + unit_name(identifier), "--quiet",
            "--property=Type=oneshot", "--property=RemainAfterExit=yes", "--property=Restart=no",
            "--property=TimeoutStartSec=" + ("45min" if repetitions == 1 else "100min"),
            "--property=TimeoutStopSec=120s", "--property=KillMode=mixed", "--property=LimitNOFILE=65536",
            "--property=UMask=0077", "--property=ExecStopPost=" + finalize,
            "--working-directory=" + str(root), sys.executable, str(helper), "remote", "execute", "--run-id", identifier]


def remote_start(identifier):
    root, private = paths(identifier)
    disarm = False
    with locked(root / "control.lock"):
        if any((root / name).exists() for name in ("start-request.json", "stop-request.json", "receipt.json")):
            value = remote_status(identifier)
            disarm = value["state"] == "finished"
        elif preparation_expired(root):
            expire_preparation_locked(identifier, root, private)
            value, disarm = remote_status(identifier), True
        else:
            state = read_json(root / "state.json")
            reason = observation_reason(state.get("observation"))
            if reason:
                value = reject_late_observation_locked(identifier, root, private, state["observation"], reason)
                disarm = True
            else:
                require(not (root / "result").exists(), "An original result already exists; never restart it")
                write_json(root / "start-request.json", {"requestedAt": now(), "runId": identifier}, exclusive=True)
                # No retry after this point, including a lost systemd-run/SSH acknowledgement.
                call(start_command(identifier, root, state["repetitions"]), env=user_environment())
                value = remote_status(identifier)
                disarm = value["state"] in {"running", "finished"}
    if disarm:
        stop_prepare_cleanup(identifier, root)
    return value


def finalize_locked(identifier, root, private):
    if (root / "receipt.json").exists():
        return read_json(root / "receipt.json")
    result_file = root / "result/evidence/lane-summary.json"
    removed, failures = [], []
    for path in (private / "access-key.txt", private / "gateway.json", root / "result/private/access-key.txt", root / "result/private/worker-gateway.json"):
        try:
            if path.exists() or path.is_symlink():
                require(not path.is_symlink() and path.is_file(), "Unexpected private input path")
                path.unlink()
        except (OSError, Failure) as error:
            failures.append(type(error).__name__)
        removed.append(not path.exists() and not path.is_symlink())
    execution, result = {}, {}
    for path, target in ((root / "execution.json", execution), (result_file, result)):
        try:
            if path.exists():
                target.update(read_json(path))
        except (OSError, ValueError, TypeError) as error:
            failures.append(type(error).__name__)
    status = "passed" if execution.get("exitCode") == 0 and result.get("status") == "passed" else "failed" if execution.get("exitCode") is not None else "unknown"
    if failures or not all(removed):
        status = "unknown"
    if (root / "stop-request.json").exists() and status != "passed":
        status = "cancelled"
    receipt = {"runId": identifier, "status": status, "runnerStatus": result.get("status", "unknown"),
               "runnerExitCode": execution.get("exitCode"), "finishedAt": now(), "privateInputsRemoved": all(removed),
               "serviceResult": os.environ.get("SERVICE_RESULT", "not-reported"), "summaryPresent": result_file.exists(),
               "finalizationFailures": failures}
    if (root / "prepare-expired.json").exists():
        receipt["stopReason"] = "prepare-expired"
    if (root / "observation.json").exists():
        receipt["observation"] = read_json(root / "observation.json")
    write_json(root / "receipt.json", receipt, exclusive=True)
    return receipt


def remote_finalize(identifier):
    root, private = paths(identifier)
    with locked(root / "control.lock", wait_seconds=180):
        receipt = finalize_locked(identifier, root, private)
    stop_prepare_cleanup(identifier, root)
    return receipt


def expire_preparation_locked(identifier, root, private):
    if (root / "receipt.json").exists():
        return update_prepare_cleanup(root, {"outcome": "already-finished", "observedAt": now()})
    if (root / "prepare-lease.json").exists():
        require(read_json(root / "prepare-lease.json").get("bootId") == current_boot_id(),
                "Original preparation belongs to another boot; outcome unknown, never restart")
    status = unit_status(identifier)
    active = status.get("ActiveState")
    if status.get("LoadState") != "not-found" and (active in {"activating", "deactivating"}
            or (active == "active" and status.get("SubState") != "exited")):
        return update_prepare_cleanup(root, {"outcome": "handed-off", "observedAt": now()})
    if not (root / "prepare-expired.json").exists():
        write_json(root / "prepare-expired.json", {"observedAt": now(), "reason": "prepare-expired"}, exclusive=True)
    if not (root / "stop-request.json").exists():
        write_json(root / "stop-request.json", {"requestedAt": now(), "reason": "prepare-expired"}, exclusive=True)
    receipt = finalize_locked(identifier, root, private)
    value = {"outcome": "expired" if receipt["privateInputsRemoved"] else "unconfirmed", "observedAt": now(),
             "privateInputsRemoved": receipt["privateInputsRemoved"]}
    if not receipt["privateInputsRemoved"]:
        value["failureType"] = "PrivateInputRemoval"
    return update_prepare_cleanup(root, value)


def remote_expire_preparation(identifier):
    root, private = paths(identifier)
    try:
        with locked(root / "control.lock", wait_seconds=90):
            value = expire_preparation_locked(identifier, root, private)
    except Exception as error:
        # No successful-cleanup receipt is manufactured if the lock or removal could not complete.
        write_json(root / "prepare-cleanup-failure.json", {"outcome": "unconfirmed", "observedAt": now(),
                                                          "failureType": type(error).__name__})
        return {"outcome": "unconfirmed", "failureType": type(error).__name__}
    stop_prepare_cleanup(identifier, root)
    return value


def remote_execute(identifier):
    root, _ = paths(identifier)
    state = read_json(root / "state.json")
    try:
        # The global lease remains nonblocking. Preparation's opposite lock order
        # never waits on that lease, so this short control wait cannot form a cycle.
        with locked(root.parent / "performance.lock"), ExitStack() as resources:
            with locked(root / "control.lock", wait_seconds=90):
                require((root / "start-request.json").exists() and not (root / "receipt.json").exists()
                        and not (root / "stop-request.json").exists() and not (root / "result").exists(), "Original work cannot be restarted")
                reason = observation_reason(state.get("observation"))
                if reason:
                    _, private = paths(identifier)
                    reject_late_observation_locked(identifier, root, private, state["observation"], reason)
                    raise ObservationSkipped(reason)
                command = [sys.executable, "-u", str(Path(state["kitRoot"]) / ENTRY), "--deployment-config", str(root / "deployment.json"),
                           "--lane-rates", "500,1000,2000", "--lane-modes", "open", "--repetitions", str(state["repetitions"]),
                           "--lane-attribution", "off", "--skip-build", "--output-root", str(root / "result")]
                stopping = []
                signal.signal(signal.SIGTERM, lambda *_: stopping.append(True))
                signal.signal(signal.SIGINT, lambda *_: stopping.append(True))
                log = resources.enter_context((root / "runner.log").open("wb"))
                if state.get("observation") is not None:
                    started = observation_now()
                    observation = dict(state["observation"], status="started", startedAtUTC=started.isoformat(),
                                       actualDelaySeconds=int((started - datetime.fromisoformat(state["observation"]["notBeforeUTC"])).total_seconds()))
                    write_json(root / "observation.json", observation)
                # Serialize admission and spawn with expiry's unit snapshot/fence.
                reason = observation_reason(state.get("observation"))
                if reason:
                    _, private = paths(identifier)
                    reject_late_observation_locked(identifier, root, private, state["observation"], reason)
                    raise ObservationSkipped(reason)
                process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
            forwarded = False
            while process.poll() is None:
                if stopping and not forwarded:
                    process.terminate()
                    forwarded = True
                time.sleep(.25)
            write_json(root / "execution.json", {"exitCode": process.returncode, "finishedAt": now()}, exclusive=True)
    except ObservationSkipped:
        pass
    except Exception as error:
        if not (root / "execution.json").exists():
            write_json(root / "execution.json", {"exitCode": 1, "failureType": type(error).__name__, "finishedAt": now()}, exclusive=True)
    finally:
        remote_finalize(identifier)
    return 0 if read_json(root / "receipt.json")["status"] == "passed" else 1


def remote_stop(identifier):
    root, _ = paths(identifier)
    # Serialize the cancellation fence with prepare, then release before waiting for
    # systemd; ExecStopPost needs the same lock to finalize without a deadlock.
    with locked(root / "control.lock", wait_seconds=180):
        completed = (root / "receipt.json").exists()
        if not completed and not (root / "stop-request.json").exists():
            write_json(root / "stop-request.json", {"requestedAt": now()}, exclusive=True)
        status = unit_status(identifier)
    if status.get("LoadState") != "not-found" and status.get("ActiveState") in {"active", "activating", "deactivating"}:
        call(["systemctl", "--user", "stop", unit_name(identifier)], env=user_environment(), timeout=150)
    if not (root / "receipt.json").exists():
        remote_finalize(identifier)
    else:
        stop_prepare_cleanup(identifier, root)
    return remote_status(identifier)


def scrub(value):
    if isinstance(value, dict):
        forbidden = {"workerids", "taskids", "rpctaskids", "managedtaskid", "identities", "statesbyworkerid", "bootstrap",
                     "accesskey", "accesskeyfile", "gatewaycredentialfile", "secret", "password", "authorization", "private"}
        return {k: scrub(v) for k, v in value.items() if k.lower() not in forbidden
                and not re.search(r"(?i)unresolved.*ids", k)
                and not IDENTITY.search(k)
                and not (k.lower() in {"samples", "workersamples"} and isinstance(v, (list, dict)))}
    if isinstance(value, list):
        return [scrub(v) for v in value]
    if isinstance(value, str):
        return PRIVATE_PATH.sub("[private-path]", IDENTITY.sub("[redacted-id]", SENSITIVE.sub("[redacted-secret]", value)))
    return value


def load_reporter(root, state):
    """Only collection needs reporting; lifecycle commands remain independently usable."""
    path = root / "reporting.py"
    source = path.read_bytes()
    expected = state.get("helpers", {}).get("reporting.py")
    if expected is not None:
        require(hashlib.sha256(source).hexdigest() == expected, "Pinned reporting helper digest differs")
    spec = importlib.util.spec_from_file_location("performance_report_for_run", path)
    module = importlib.util.module_from_spec(spec)
    # Execute exactly the verified source bytes, without a mutable read or cached bytecode.
    exec(compile(source, str(path), "exec"), module.__dict__)
    return module


def remote_collect(identifier):
    root, _ = paths(identifier)
    state = read_json(root / "state.json") if (root / "state.json").exists() else {}
    result = root / "result"
    source = result / "evidence/lane-summary.json"
    observation_path = root / "observation.json"
    observation = read_json(observation_path) if observation_path.exists() else state.get("observation")
    skipped = root / "observation-skipped.json"
    if skipped.exists() and not source.exists():
        value = read_json(skipped)
        status = remote_status(identifier)
        receipt = status.get("receipt")
        collection = missing_observation(identifier, value["observation"], value["reason"], receipt)
        collection["orchestration.json"]["cleanupConfirmed"] = (
            receipt.get("privateInputsRemoved") is True and receipt.get("finalizationFailures") == []
            if receipt is not None else value.get("cleanupConfirmed") is True)
        collection["consumption.json"].update(scrub(state.get("consumption", {})))
        return collection
    status = remote_status(identifier)
    try:
        summary, content = load_reporter(root, state).collect_measurement(result)
    except Exception as error:
        summary = {"status": "unknown", "reportCollection": {"status": "unavailable", "failureType": type(error).__name__}}
        content = "# External Performance\n\nMeasurement report unavailable; no measurement conclusion is claimed.\n"
    summary["orchestrationReceipt"] = status.get("receipt", {"status": "unknown"})
    summary["run"] = {key: state[key] for key in ("runId", "firstAttempt", "tag", "commit", "workersPerGroup", "repetitions", "preparedAt") if key in state}
    summary["preflight"] = state.get("preflight", {})
    summary["github"] = state.get("github", {})
    summary["helpers"] = state.get("helpers", {})
    preparation = {}
    for filename, key in (("prepare-lease.json", "lease"), ("prepare-cleanup.json", "cleanup"),
                          ("prepare-cleanup-failure.json", "failure")):
        if (root / filename).is_file():
            value = read_json(root / filename)
            preparation[key] = {name: value[name] for name in ("ttlSeconds", "armConfirmed", "outcome", "observedAt",
                                "timerStopped", "timerStoppedAt", "timerStopFailureType", "privateInputsRemoved", "failureType") if name in value}
    if preparation:
        summary["preparation"] = preparation
    content = scrub(content)
    if observation is not None:
        summary["observation"] = observation
        content += ("\n\nObservation window: `" + observation["windowId"] + "`; planned local date: `" + observation["slotDate"]
                    + "`; actual delay seconds: `" + str(observation.get("actualDelaySeconds", "unavailable")) + "`.\n")
    return {"summary.json": scrub(summary), "summary.md": content, "consumption.json": scrub(state.get("consumption", {})),
            "orchestration.json": {"runId": identifier, "state": status["state"], "receipt": status.get("receipt"), "unit": status.get("unit"),
                                   "helpers": state.get("helpers", {}), "reportCollection": summary.get("reportCollection"),
                                   **({"observation": observation} if observation is not None else {})}}


def remote_main(arguments):
    root, _ = paths(arguments.run_id)
    action = arguments.remote_action
    if action == "prepare":
        value = remote_prepare(arguments.run_id, json.load(sys.stdin))
    elif action == "start":
        value = remote_start(arguments.run_id)
    elif action == "status":
        value = remote_status(arguments.run_id)
    elif action == "stop":
        value = remote_stop(arguments.run_id)
    elif action == "finalize":
        value = remote_finalize(arguments.run_id)
    elif action == "expire-preparation":
        value = remote_expire_preparation(arguments.run_id)
    elif action == "execute":
        return remote_execute(arguments.run_id)
    elif action == "preflight":
        call(["systemctl", "--user", "list-units", "--no-pager", "--no-legend"], env=user_environment())
        value = {"user": USER, "uid": os.getuid(), "runRoot": str(root), "userManager": "reachable"}
    else:
        value = remote_collect(arguments.run_id)
    print(json.dumps(value, ensure_ascii=False))
    return 1 if action == "expire-preparation" and value.get("outcome") == "unconfirmed" else 0


BOOTSTRAP = """import json,os,pwd,sys;from pathlib import Path
assert os.getuid()!=0 and pwd.getpwuid(os.getuid()).pw_name=='xa-mass-performance'
p=Path.home()/'runs'/sys.argv[1]
if sys.argv[2]=='create':
 p.mkdir(parents=True,exist_ok=True,mode=0o700);assert not p.is_symlink() and p.stat().st_uid==os.getuid();p.chmod(0o700)
print(json.dumps({'root':str(p),'helper':(p/'orchestrator.py').is_file()}))
"""


class Transport:
    def __init__(self, directory, environment):
        host, user = environment["SSH_HOST"], environment["SSH_USER"]
        require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.-]*", host) and user == USER, "Unexpected SSH destination")
        self.destination = user + "@" + host
        key, known = directory / "ssh-key", directory / "known_hosts"
        for path, name in ((key, "SSH_PRIVATE_KEY"), (known, "SSH_KNOWN_HOSTS")):
            path.write_text(environment[name].strip() + "\n", encoding="utf-8")
            path.chmod(0o600)
        self.options = ["-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", "-o", "IdentitiesOnly=yes",
                        "-o", "ConnectTimeout=15", "-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=3",
                        "-o", "UserKnownHostsFile=" + str(known), "-i", str(key)]

    def ssh(self, command, data=None, timeout=60):
        raw = call(["ssh", *self.options, self.destination, shlex.join([str(v) for v in command])], data=data, timeout=timeout)
        return json.loads(raw)

    def copy(self, source, destination):
        call(["scp", "-q", *self.options, str(source), self.destination + ":" + destination], timeout=180)


def env_required(environment, name):
    value = environment.get(name)
    require(isinstance(value, str) and value.strip(), "Missing required controller input: " + name)
    return value


def remote_call(transport, helper, action, identifier, data=None, timeout=60):
    return transport.ssh(["python3", helper, "remote", action, "--run-id", identifier], data=data, timeout=timeout)


def write_collection(directory, collection):
    require(set(collection) == set(FILES), "Remote collection is not the four-file allowlist")
    directory.mkdir(parents=True, exist_ok=True)
    for filename in FILES:
        value = collection[filename]
        if filename.endswith(".md"):
            (directory / filename).write_text(value, encoding="utf-8")
        else:
            write_json(directory / filename, value)


def publish_missing(directory, identifier, value, reason, receipt=None, *, cleanup_confirmed=True, orchestration_updates=None):
    collection = missing_observation(identifier, value, reason, receipt)
    if not cleanup_confirmed:
        collection["consumption.json"]["status"] = "unconfirmed"
        collection["orchestration.json"].update(state="unconfirmed", cleanupRequired=True)
    previous_summary = None
    try:
        if (directory / "summary.json").is_file():
            previous_summary = read_json(directory / "summary.json")
    except (OSError, ValueError):
        pass
    if isinstance(previous_summary, dict) and previous_summary.get("status") != "missing":
        # A later stop/collect failure must never replace already collected case evidence.
        path = directory / "orchestration.json"
        try:
            record = read_json(path) if path.exists() else {}
        except (OSError, ValueError):
            record = {}
        if not isinstance(record, dict):
            record = {}
        record.update(controllerStatus="unconfirmed", controllerFailureReason=reason, cleanupRequired=not cleanup_confirmed)
        if not cleanup_confirmed:
            record["state"] = "unconfirmed"
        record.update(orchestration_updates or {})
        write_json(path, scrub(record))
    else:
        collection["orchestration.json"].update(orchestration_updates or {})
        write_collection(directory, collection)
    observation_output(not cleanup_confirmed)
    return 0 if cleanup_confirmed else 1


def controller(arguments):
    environment = os.environ
    identifier = run_id(env_required(environment, "GITHUB_RUN_ID"))
    attempt = int(environment.get("GITHUB_RUN_ATTEMPT", "1"))
    output = arguments.output_dir.resolve()
    os.umask(0o077)
    interrupted = []
    previous = {sig: signal.signal(sig, lambda *_: interrupted.append(True)) for sig in (signal.SIGTERM, signal.SIGINT)}
    observation, admission_possible = None, False
    try:
        observation_path = getattr(arguments, "observation_plan", None)
        observation = (observation_slot(observation_path, environment, arguments.workers_per_group, arguments.repetitions)
                       if observation_path is not None else None)
        if observation is not None:
            observation_output(False)
            if arguments.action == "run" and attempt == 1 and observation["status"] != "eligible":
                return publish_missing(output, identifier, observation, observation["reason"])
            if attempt != 1 or arguments.action != "run":
                # A failed query cannot prove that the original Run has no live campaign.
                admission_possible = True
                observation_output(True)
        with tempfile.TemporaryDirectory(prefix="xa-performance-controller-") as temporary:
            private = Path(temporary)
            transport = Transport(private, environment)
            bootstrap = transport.ssh(["python3", "-c", BOOTSTRAP, identifier,
                                       "create" if arguments.action == "run" and attempt == 1 else "query"])
            root = bootstrap["root"]
            require(re.fullmatch(r"/[A-Za-z0-9_./-]+", root) and root.endswith("/runs/" + identifier), "Unexpected remote run coordinate")
            helper = root + "/orchestrator.py"
            attach = bootstrap["helper"]
            start_acknowledged = True
            if observation is not None and attach:
                admission_possible = True
                observation_output(True)  # Attach/stop must still finalize the original Run, never prepare a second one.
            if not attach:
                if observation is not None and (arguments.action != "run" or attempt != 1):
                    return publish_missing(output, identifier, observation, "original-observation-unavailable", cleanup_confirmed=False)
                require(arguments.action == "run" and attempt == 1, "Original helper is missing; outcome unknown, never restart")
                tag, commit = env_required(environment, "RELEASE_TAG"), env_required(environment, "RELEASE_COMMIT")
                server_tag, server_commit = env_required(environment, "SERVER_RELEASE_TAG"), env_required(environment, "SERVER_RELEASE_COMMIT")
                config = json.loads(env_required(environment, "DEPLOYMENT_JSON"))
                validate_config(config, server_tag, server_commit, arguments.workers_per_group)
                controller_commit = env_required(environment, "GITHUB_SHA")
                require(re.fullmatch(r"[0-9a-f]{40}", controller_commit), "Controller commit must be a full immutable SHA")
                require(not interrupted, "Controller cancelled before preparation")
                # Hash the captured bytes that are copied, from this single controller checkout.
                # Publish orchestrator last: its presence is the immutable same-Run attach fence.
                helper_sources = {"reporting.py": Path(__file__).resolve().parents[2] / "integrations/worker-call-performance/reporting.py",
                                  "orchestrator.py": Path(__file__).resolve()}
                helpers = {"controllerCommit": controller_commit}
                for name, source_path in helper_sources.items():
                    snapshot = private / name
                    snapshot.write_bytes(source_path.read_bytes())
                    helpers[name] = digest(snapshot)
                for name in helper_sources:
                    transport.copy(private / name, root + "/" + name)
                spec = importlib.util.spec_from_file_location("external_release_input", Path(__file__).with_name("prepare_external_release_load.py"))
                downloader = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(downloader)
                consumed = downloader.prepare(arguments.repository, tag, commit, private / "kit")
                consumption = {k: consumed[k] for k in ("release", "releaseId", "tag", "gitCommit", "kitSha256", "manifest")}
                archive = private / "kit" / f"xa-mass-worker-simulator-{tag[1:]}.zip"
                transport.copy(archive, root + "/kit.zip")
                envelope = {"deployment": config, "tag": tag, "commit": commit, "serverTag": server_tag,
                            "serverCommit": server_commit, "workers": arguments.workers_per_group,
                            "repetitions": arguments.repetitions, "attempt": attempt, "consumption": consumption,
                            "helpers": helpers,
                            "kitSha256": consumed["kitSha256"], "budget": json.loads(env_required(environment, "CONNECTION_BUDGET_JSON")),
                            "accessKey": env_required(environment, "RUNTIME_ACCESS_KEY"),
                            "gateway": {"keyId": env_required(environment, "WORKER_GATEWAY_KEY_ID"), "secret": env_required(environment, "WORKER_GATEWAY_SECRET")}}
                envelope["github"] = {"repository": arguments.repository, "runUrl": f"https://github.com/{arguments.repository}/actions/runs/{identifier}",
                                      "controllerCommit": environment.get("GITHUB_SHA"), "actor": environment.get("GITHUB_ACTOR")}
                if observation is not None:
                    reason = observation_reason(observation)
                    if reason:
                        return publish_missing(output, identifier, observation, reason)
                    envelope["observation"] = observation
                require(not interrupted, "Controller cancelled before private preparation")
                if observation is not None:
                    admission_possible = True
                    observation_output(True)  # An unknown SSH reply may follow credential writes; finalization must run.
                prepared = remote_call(transport, helper, "prepare", identifier, json.dumps(envelope).encode(), timeout=180)
                if prepared.get("state") == "observation-skipped":
                    return publish_missing(output, identifier, prepared.get("observation", observation), prepared["reason"],
                                           prepared.get("receipt"), cleanup_confirmed=prepared.get("cleanupConfirmed") is True)
                # Exactly one start request. Its remote marker is written before systemd-run.
                if interrupted:
                    remote_call(transport, helper, "stop", identifier, timeout=240)
                elif prepared.get("state") == "prepared":
                    try:
                        started = remote_call(transport, helper, "start", identifier)
                        if started.get("state") == "observation-skipped":
                            return publish_missing(output, identifier, started.get("observation", observation), started["reason"],
                                                   started.get("receipt"), cleanup_confirmed=started.get("cleanupConfirmed") is True)
                    except (Failure, subprocess.SubprocessError, ValueError):
                        start_acknowledged = False  # Reconnect only to status; never resend start.
            if arguments.action == "stop":
                remote_call(transport, helper, "stop", identifier, timeout=360)
            elif arguments.action == "run":
                deadline = time.monotonic() + (47 if arguments.repetitions == 1 else 102) * 60
                while True:
                    if interrupted:
                        remote_call(transport, helper, "stop", identifier, timeout=360)
                        break
                    try:
                        status = remote_call(transport, helper, "status", identifier)
                    except (Failure, subprocess.SubprocessError, ValueError):
                        require(time.monotonic() < deadline, "Original unit could not be observed; no restart was sent")
                        time.sleep(10)
                        continue
                    if status["state"] in {"finished", "unknown", "absent", "prepared", "observation-skipped"}:
                        break
                    require(time.monotonic() < deadline, "Controller observation deadline exceeded")
                    time.sleep(15)
            collection = remote_call(transport, helper, "collect", identifier, timeout=180)
            collection["orchestration.json"].update(controllerAttempt=attempt, attached=attach, controllerCancelled=bool(interrupted),
                                                   startAcknowledged=start_acknowledged)
            if observation is not None:
                observed = collection["orchestration.json"].get("observation", observation)
                collection["orchestration.json"]["observation"] = observed
                collection["summary.json"].setdefault("observation", observed)
                if "Observation window:" not in collection["summary.md"]:
                    collection["summary.md"] += ("\nObservation window: `" + observed["windowId"] + "`; planned local date: `"
                                                 + str(observed.get("slotDate", "unmatched")) + "`; actual delay seconds: `"
                                                 + str(observed.get("actualDelaySeconds", "unavailable")) + "`.\n")
            write_collection(output, collection)
            receipt = collection["orchestration.json"].get("receipt") or {}
            if observation is not None and collection["summary.json"].get("status") == "missing":
                clean = (receipt.get("privateInputsRemoved") is True and receipt.get("finalizationFailures") == []
                         if receipt else collection["orchestration.json"].get("cleanupConfirmed") is True)
                observation_output(not clean)
                return 0 if clean else 1
            report = collection["summary.json"].get("reportCollection", {})
            return 0 if receipt.get("status") == "passed" and report.get("status") != "unavailable" and not interrupted else 1
    except Exception as error:
        output.mkdir(parents=True, exist_ok=True)
        if observation is not None:
            reason = "controller-outcome-unconfirmed" if admission_possible else "controller-before-admission"
            publish_missing(output, identifier, observation, reason, cleanup_confirmed=not admission_possible,
                    orchestration_updates={"controllerAttempt": attempt, "controllerStatus": "unconfirmed",
                        "controllerFailureType": type(error).__name__, "controllerCancelled": bool(interrupted),
                        "cleanupRequired": admission_possible})
            raise
        path = output / "orchestration.json"
        previous_record = read_json(path) if path.exists() else {}
        previous_record.update(runId=identifier, controllerAttempt=attempt, controllerStatus="unconfirmed",
                               controllerFailureType=type(error).__name__, controllerCancelled=bool(interrupted))
        write_json(path, scrub(previous_record))
        raise
    finally:
        for sig, handler in previous.items():
            signal.signal(sig, handler)


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if argv and argv[0] == "remote":
        parser = argparse.ArgumentParser(description=__doc__)
        parser.add_argument("remote")
        parser.add_argument("remote_action", choices=("prepare", "start", "status", "stop", "collect", "finalize", "expire-preparation", "execute", "preflight"))
        parser.add_argument("--run-id", required=True, type=run_id)
        return remote_main(parser.parse_args(argv))
    if argv and argv[0] == "controller":
        argv.pop(0)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--workers-per-group", type=int, required=True)
    parser.add_argument("--repetitions", type=int, choices=(1, 3), required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--action", choices=("run", "stop", "collect"), default="run")
    parser.add_argument("--observation-plan", type=Path, help="Optional finite scheduled-observation plan; manual execution is unchanged")
    arguments = parser.parse_args(argv)
    require(1 <= arguments.workers_per_group <= 15000, "Worker quantity is outside the existing Host bound")
    return controller(arguments)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(json.dumps({"status": "failed", "failureType": type(error).__name__,
                          "reason": str(error) if isinstance(error, Failure) else "Private orchestration failure; inspect the original Run"}), file=sys.stderr)
        raise SystemExit(1)
