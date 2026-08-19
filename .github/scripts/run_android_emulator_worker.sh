#!/usr/bin/env bash

set -euo pipefail

workspace=${GITHUB_WORKSPACE:?GITHUB_WORKSPACE must be set}
proof_root=${ANDROID_WORKER_PROOF_ROOT:-$workspace/build/android-emulator-proof}
apk=${ANDROID_WORKER_APK:-$proof_root/apk/xa-mass-android-worker-demo-debug.apk}
driver="$workspace/xa-android/worker-demo/host/android_worker_acceptance.py"
kernel_config="$workspace/xa-android/worker-demo/kernel-config.json"
application_id=com.xa.mass.integration.androidworker
activity_component="$application_id/com.xa.mass.android.workerdemo.MainActivity"
endpoint_manager_id=scenario-websocket
server_base_url=http://127.0.0.1:18082
device_base_url=http://127.0.0.1:18084
proof_id=ci-android-emulator-worker
maximum_wait_millis=${ANDROID_WORKER_MAXIMUM_WAIT_MILLIS:-30000}
process_marker_grace_millis=5000
evidence_root="$proof_root/evidence"
log_root="$proof_root/logs"
lab_root="$proof_root/data/scenario-workers"
task_root="$proof_root/data/rpc-task"
server_pid=
kernel_pid=
process_restart_pid=

if ! [[ $maximum_wait_millis =~ ^(0|[1-9][0-9]*)$ ]] \
        || (( maximum_wait_millis < 1 || maximum_wait_millis > 300000 )); then
    echo "ANDROID_WORKER_MAXIMUM_WAIT_MILLIS must be in 1..300000"
    exit 1
fi

process_marker_wait_seconds=$((
    (maximum_wait_millis + process_marker_grace_millis + 999) / 1000
))

mkdir -p "$proof_root/data" "$evidence_root" "$log_root"
test -f "$apk"
test -f "$driver"
test -f "$kernel_config"
test ! -e "$lab_root"

collect_android_log() {
    {
        adb logcat -d -v threadtime 2>/dev/null \
            | grep -E \
                'AndroidRuntime|com\.xa\.mass|AndroidWorker|WorkerRunController|AndroidOkHttp' \
            || true
    } > "$log_root/android-filtered.log"
}

stop_process() {
    local pid=${1:-}
    local attempts=${2:-5}
    if [ -z "$pid" ] || ! kill -0 "$pid" 2>/dev/null; then
        return 0
    fi
    kill "$pid" 2>/dev/null || true
    for _ in $(seq 1 "$attempts"); do
        if ! kill -0 "$pid" 2>/dev/null; then
            wait "$pid" 2>/dev/null || true
            return 0
        fi
        sleep 1
    done
    kill -9 "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
}

cleanup() {
    local status=$?
    trap - EXIT
    set +e
    stop_process "$process_restart_pid" 1
    collect_android_log
    stop_process "$server_pid" 15
    stop_process "$kernel_pid" 5
    adb forward --remove tcp:18084 >/dev/null 2>&1
    adb reverse --remove tcp:18082 >/dev/null 2>&1
    adb reverse --remove tcp:18083 >/dev/null 2>&1
    exit "$status"
}
trap cleanup EXIT

wait_for_url() {
    local url=$1
    local attempts=$2
    for _ in $(seq 1 "$attempts"); do
        if curl --fail --silent --show-error "$url" >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    return 1
}

start_kernel() {
    PYTHONUNBUFFERED=1 python -m kernel_design.runtime_server \
        --config "$kernel_config" \
        > "$log_root/kernel.log" 2>&1 &
    kernel_pid=$!
    if ! wait_for_url http://127.0.0.1:18080/health 60; then
        cat "$log_root/kernel.log"
        return 1
    fi
}

start_server() {
    local phase=$1
    local server_jar
    server_jar=$(find "$workspace/server_jvm/build/libs" \
        -name 'xa-mass-server-jvm-*.jar' \
        ! -name '*-plain.jar' -print -quit)
    test -n "$server_jar"
    java -jar "$server_jar" \
        --spring.profiles.active=scenario-workers \
        --xa.mass.worker-assembly.sandbox-root="$lab_root" \
        --xa.mass.task-batch.root="$task_root" \
        > "$log_root/server-$phase.log" 2>&1 &
    server_pid=$!
    if ! wait_for_url \
        "$server_base_url/actuator/health/readiness" 90; then
        cat "$log_root/server-$phase.log"
        return 1
    fi
}

stop_server_gracefully() {
    local pid=$server_pid
    if [ -z "$pid" ] || ! kill -0 "$pid" 2>/dev/null; then
        echo "Scenario Server was not running before the terminal phase"
        return 1
    fi
    kill "$pid"
    for _ in $(seq 1 15); do
        if ! kill -0 "$pid" 2>/dev/null; then
            wait "$pid" 2>/dev/null || true
            server_pid=
            return 0
        fi
        sleep 1
    done
    kill -9 "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
    server_pid=
    echo "Scenario Server did not stop within 15 seconds"
    return 1
}

run_phase() {
    local phase=$1
    local arguments=(
        "--phase=$phase"
        "--proof-id=$proof_id"
        "--server-base-url=$server_base_url"
        "--device-base-url=$device_base_url"
        "--endpoint-manager-id=$endpoint_manager_id"
        "--evidence-file=$evidence_root/$phase.json"
        "--maximum-wait-millis=$maximum_wait_millis"
        "--request-timeout-millis=120000"
        "--android-api-level=33"
    )
    if [ "$phase" != initial ]; then
        arguments+=("--baseline-file=$evidence_root/initial.json")
    fi
    python "$driver" "${arguments[@]}"
}

start_application() {
    adb shell am start -W -n "$activity_component" >/dev/null
}

start_kernel
start_server initial

adb install -r "$apk" >/dev/null
adb shell pm clear "$application_id" >/dev/null
adb reverse tcp:18082 tcp:18082 >/dev/null
adb reverse tcp:18083 tcp:18083 >/dev/null
adb forward tcp:18084 tcp:18084 >/dev/null
adb logcat -c
start_application

run_phase initial

stop_server_gracefully
run_phase terminal

start_server restart
run_phase server-restart

adb shell am force-stop "$application_id"
process_marker_file="$proof_root/process-restart.marker"
run_phase process-restart > "$process_marker_file" &
process_restart_pid=$!
process_stop_observed=false
process_marker_deadline=$((SECONDS + process_marker_wait_seconds))
while (( SECONDS < process_marker_deadline )); do
    if grep -Fxq android-worker-process-stop-observed \
        "$process_marker_file"; then
        process_stop_observed=true
        break
    fi
    if ! kill -0 "$process_restart_pid" 2>/dev/null; then
        wait "$process_restart_pid" 2>/dev/null || true
        break
    fi
    sleep 0.1
done
if grep -Fxq android-worker-process-stop-observed \
        "$process_marker_file"; then
    process_stop_observed=true
fi
if [ "$process_stop_observed" != true ]; then
    kill "$process_restart_pid" 2>/dev/null || true
    wait "$process_restart_pid" 2>/dev/null || true
    echo "Process-restart proof did not observe the stopped App route"
    exit 1
fi
start_application
wait "$process_restart_pid"
