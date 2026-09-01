#!/usr/bin/env bash

set -euo pipefail

workspace=${GITHUB_WORKSPACE:?GITHUB_WORKSPACE must be set}
proof_root=${ANDROID_WORKER_PROOF_ROOT:-$workspace/build/android-emulator-proof}
apk=${ANDROID_WORKER_APK:-$proof_root/apk/xa-mass-android-worker-demo-debug.apk}
application_id=com.xa.mass.integration.androidworker
activity_component="$application_id/com.xa.mass.android.workerdemo.MainActivity"
endpoint_manager_id=scenario-websocket
server_base_url=http://127.0.0.1:18082
device_base_url=http://127.0.0.1:18084
maximum_wait_millis=${ANDROID_WORKER_MAXIMUM_WAIT_MILLIS:-60000}
scope_base=${XA_MASS_REDIS_SCOPE:-test_android_emulator}
correctness_scope="${scope_base}_correctness"
convergence_scope="${scope_base}_convergence"
evidence_root="$proof_root/evidence"
log_root="$proof_root/logs"
server_pid=
proof_pid=

if ! [[ $maximum_wait_millis =~ ^[1-9][0-9]*$ ]] \
        || (( maximum_wait_millis > 300000 )); then
    echo "ANDROID_WORKER_MAXIMUM_WAIT_MILLIS must be in 1..300000"
    exit 1
fi

mkdir -p "$proof_root/data" "$evidence_root" "$log_root"
test -f "$apk"

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

clear_scope() {
    local scope=$1
    python "$workspace/.github/scripts/cleanup_redis_test_scope.py" \
        --redis-url redis://127.0.0.1:6379/15 \
        --scope "$scope"
}

cleanup() {
    local status=$?
    trap - EXIT
    set +e
    stop_process "$proof_pid" 1
    collect_android_log
    stop_process "$server_pid" 15
    adb shell am force-stop "$application_id" >/dev/null 2>&1
    adb forward --remove tcp:18084 >/dev/null 2>&1
    adb reverse --remove tcp:18082 >/dev/null 2>&1
    adb reverse --remove tcp:18083 >/dev/null 2>&1
    clear_scope "$correctness_scope"
    clear_scope "$convergence_scope"
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

start_server() {
    local label=$1
    local scope=$2
    local server_jar
    server_jar=$(find "$workspace/server_jvm/build/libs" \
        -name 'xa-mass-server-jvm-*.jar' \
        ! -name '*-plain.jar' -printf '%T@ %p\n' \
        | sort -nr \
        | head -n 1 \
        | cut -d ' ' -f 2-)
    test -n "$server_jar"
    XA_MASS_REDIS_SCOPE="$scope" java -jar "$server_jar" \
        --spring.profiles.active=scenario-workers \
        > "$log_root/server-$label.log" 2>&1 &
    server_pid=$!
    if ! wait_for_url \
        "$server_base_url/actuator/health/readiness" 90; then
        cat "$log_root/server-$label.log"
        return 1
    fi
}

stop_server_gracefully() {
    local pid=$server_pid
    if [ -z "$pid" ] || ! kill -0 "$pid" 2>/dev/null; then
        echo "Runtime Server was not running"
        return 1
    fi
    kill "$pid"
    for _ in $(seq 1 20); do
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
    echo "Runtime Server did not stop within 20 seconds"
    return 1
}

start_application() {
    adb shell am start -W -n "$activity_component" >/dev/null
}

reset_application() {
    adb shell am force-stop "$application_id" >/dev/null 2>&1 || true
    adb shell pm clear "$application_id" >/dev/null
    start_application
}

run_proof_phase() {
    local task=$1
    local proof_id=$2
    local phase=$3
    local evidence_file=$4
    local baseline_file=${5:-}
    local arguments=(
        "--phase=$phase"
        "--proof-id=$proof_id"
        "--server-base-url=$server_base_url"
        "--device-base-url=$device_base_url"
        "--endpoint-manager-id=$endpoint_manager_id"
        "--evidence-file=$evidence_file"
        "--maximum-wait-millis=$maximum_wait_millis"
        "--request-timeout-millis=120000"
        "--android-api-level=33"
    )
    if [ -n "$baseline_file" ]; then
        arguments+=("--baseline-file=$baseline_file")
    fi
    "$workspace/gradlew" --no-daemon \
        ":integrations:android-worker-proof:$task" \
        "--args=${arguments[*]}"
}

await_process_stop_marker() {
    local output_file=$1
    local wait_seconds=$(( (maximum_wait_millis + 999) / 1000 + 10 ))
    local deadline=$((SECONDS + wait_seconds))
    while (( SECONDS < deadline )); do
        if grep -Fxq \
            ANDROID_WORKER_PROCESS_STOP_OBSERVED \
            "$output_file"; then
            return 0
        fi
        if ! kill -0 "$proof_pid" 2>/dev/null; then
            wait "$proof_pid"
            return 1
        fi
        sleep 0.1
    done
    return 1
}

adb install -r "$apk" >/dev/null
adb reverse tcp:18082 tcp:18082 >/dev/null
adb reverse tcp:18083 tcp:18083 >/dev/null
adb forward tcp:18084 tcp:18084 >/dev/null
adb logcat -c

correctness_id=ci-android-worker-correctness
correctness_initial="$evidence_root/correctness-initial.json"
correctness_restart="$evidence_root/correctness-process-restart.json"

start_server correctness "$correctness_scope"
reset_application
run_proof_phase \
    runAndroidWorkerCorrectness \
    "$correctness_id" \
    initial \
    "$correctness_initial"

adb shell am force-stop "$application_id"
process_output="$proof_root/correctness-process-restart.out"
run_proof_phase \
    runAndroidWorkerCorrectness \
    "$correctness_id" \
    process-restart \
    "$correctness_restart" \
    "$correctness_initial" > "$process_output" 2>&1 &
proof_pid=$!
if ! await_process_stop_marker "$process_output"; then
    cat "$process_output"
    echo "Correctness proof did not observe the stopped App route"
    exit 1
fi
start_application
wait "$proof_pid"
proof_pid=

adb shell am force-stop "$application_id"
stop_server_gracefully
clear_scope "$correctness_scope"

convergence_id=ci-android-worker-convergence-health
convergence_active="$evidence_root/convergence-active.json"
convergence_terminal="$evidence_root/convergence-terminal.json"
convergence_restart="$evidence_root/convergence-server-restart.json"

start_server convergence-initial "$convergence_scope"
reset_application
run_proof_phase \
    runAndroidWorkerConvergenceHealth \
    "$convergence_id" \
    active \
    "$convergence_active"

stop_server_gracefully
run_proof_phase \
    runAndroidWorkerConvergenceHealth \
    "$convergence_id" \
    terminal \
    "$convergence_terminal" \
    "$convergence_active"

start_server convergence-restart "$convergence_scope"
run_proof_phase \
    runAndroidWorkerConvergenceHealth \
    "$convergence_id" \
    server-restart \
    "$convergence_restart" \
    "$convergence_active"

adb shell am force-stop "$application_id"
stop_server_gracefully
clear_scope "$convergence_scope"
