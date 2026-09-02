#!/usr/bin/env bash

set -euo pipefail

workspace=${GITHUB_WORKSPACE:?GITHUB_WORKSPACE must be set}
proof_root=${ANDROID_WORKER_PROOF_ROOT:-$workspace/build/android-emulator-proof}
apk_root=${ANDROID_WORKER_APK_ROOT:-$proof_root/apk}
apk=${ANDROID_WORKER_APK:-$apk_root/xa-mass-android-worker-demo-debug.apk}
application_id=com.xa.mass.integration.androidworker
activity_component="$application_id/com.xa.mass.android.workerdemo.MainActivity"
lab_application_ids=(
    com.xa.mass.integration.androidworker.lab1
    com.xa.mass.integration.androidworker.lab2
    com.xa.mass.integration.androidworker.lab3
)
lab_apks=(
    "$apk_root/xa-mass-android-worker-demo-lab1.apk"
    "$apk_root/xa-mass-android-worker-demo-lab2.apk"
    "$apk_root/xa-mass-android-worker-demo-lab3.apk"
)
lab_ports=(18184 18185 18186)
lab_outage_index=1
endpoint_manager_id=scenario-websocket
server_base_url=http://127.0.0.1:18082
device_base_url=http://127.0.0.1:18084
maximum_wait_millis=${ANDROID_WORKER_MAXIMUM_WAIT_MILLIS:-120000}
scope_base=${XA_MASS_REDIS_SCOPE:-test_android_emulator}
correctness_scope="${scope_base}_correctness"
convergence_scope="${scope_base}_convergence"
triad_correctness_scope="${scope_base}_triad_correctness"
triad_convergence_scope="${scope_base}_triad_convergence"
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
for lab_apk in "${lab_apks[@]}"; do
    test -f "$lab_apk"
done

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
    if ! python3 "$workspace/.github/scripts/cleanup_redis_test_scope.py" \
            --redis-url redis://127.0.0.1:6379/15 \
            --scope "$scope" \
            --best-effort; then
        echo "Redis cleanup could not run for $scope" >&2
    fi
}

cleanup() {
    local status=$?
    trap - EXIT
    set +e
    stop_process "$proof_pid" 1
    collect_android_log
    stop_process "$server_pid" 15
    adb shell am force-stop "$application_id" >/dev/null 2>&1
    for lab_application_id in "${lab_application_ids[@]}"; do
        adb shell am force-stop "$lab_application_id" >/dev/null 2>&1
    done
    adb forward --remove tcp:18084 >/dev/null 2>&1
    for lab_port in "${lab_ports[@]}"; do
        adb forward --remove "tcp:$lab_port" >/dev/null 2>&1
    done
    adb reverse --remove tcp:18082 >/dev/null 2>&1
    adb reverse --remove tcp:18083 >/dev/null 2>&1
    clear_scope "$correctness_scope"
    clear_scope "$convergence_scope"
    clear_scope "$triad_correctness_scope"
    clear_scope "$triad_convergence_scope"
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

wait_for_android_boot() {
    adb wait-for-device
    for _ in $(seq 1 180); do
        if [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; then
            adb shell input keyevent 82 >/dev/null 2>&1 || true
            return 0
        fi
        sleep 1
    done
    echo "Android Emulator did not complete reboot"
    return 1
}

disable_cached_app_freezer() {
    local runtime_setting
    runtime_setting=$(adb shell dumpsys activity settings \
        | sed -n 's/^[[:space:]]*use_freezer=//p' \
        | tr -d '\r' \
        | tail -n 1)
    if [ "$runtime_setting" != "false" ]; then
        adb shell device_config put \
            activity_manager_native_boot use_freezer false
        adb shell settings put global cached_apps_freezer disabled
        adb reboot
        wait_for_android_boot
        runtime_setting=$(adb shell dumpsys activity settings \
            | sed -n 's/^[[:space:]]*use_freezer=//p' \
            | tr -d '\r' \
            | tail -n 1)
    fi
    if [ "$runtime_setting" != "false" ]; then
        echo "Android cached-app freezer remained enabled"
        return 1
    fi
}

start_server() {
    local label=$1
    local scope=$2
    local candidate
    local server_jar
    server_jar=
    for candidate in \
            "$workspace"/server_jvm/build/libs/xa-mass-server-jvm-*.jar; do
        if [ ! -f "$candidate" ] || [[ $candidate == *-plain.jar ]]; then
            continue
        fi
        if [ -z "$server_jar" ] || [ "$candidate" -nt "$server_jar" ]; then
            server_jar=$candidate
        fi
    done
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
    start_application_id "$application_id"
}

start_application_id() {
    local requested_application_id=$1
    adb shell am start -W -n \
        "$requested_application_id/com.xa.mass.android.workerdemo.MainActivity" \
        >/dev/null
}

reset_application() {
    adb shell am force-stop "$application_id" >/dev/null 2>&1 || true
    adb shell pm clear "$application_id" >/dev/null
    start_application
}

reset_triad_applications() {
    for lab_application_id in "${lab_application_ids[@]}"; do
        adb shell am force-stop "$lab_application_id" >/dev/null 2>&1 || true
        adb shell pm clear "$lab_application_id" >/dev/null
        start_application_id "$lab_application_id"
    done
}

stop_triad_applications() {
    for lab_application_id in "${lab_application_ids[@]}"; do
        adb shell am force-stop "$lab_application_id" >/dev/null 2>&1 || true
    done
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
        "--request-timeout-millis=5000"
        "--android-api-level=33"
    )
    if [ -n "$baseline_file" ]; then
        arguments+=("--baseline-file=$baseline_file")
    fi
    "$workspace/gradlew" --daemon \
        -Dorg.gradle.daemon.idletimeout=300000 \
        ":integrations:android-worker-proof:$task" \
        "--args=${arguments[*]}"
}

await_proof_marker() {
    local output_file=$1
    local marker=$2
    local wait_seconds=$(( (maximum_wait_millis + 999) / 1000 + 10 ))
    local deadline=$((SECONDS + wait_seconds))
    while (( SECONDS < deadline )); do
        if grep -Fxq "$marker" "$output_file"; then
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

disable_cached_app_freezer

adb install -r "$apk" >/dev/null
for index in "${!lab_apks[@]}"; do
    adb install -r "${lab_apks[$index]}" >/dev/null
    adb shell pm path "${lab_application_ids[$index]}" >/dev/null
done
adb reverse tcp:18082 tcp:18082 >/dev/null
adb reverse tcp:18083 tcp:18083 >/dev/null
adb forward tcp:18084 tcp:18084 >/dev/null
for lab_port in "${lab_ports[@]}"; do
    adb forward "tcp:$lab_port" "tcp:$lab_port" >/dev/null
done
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
if ! await_proof_marker \
    "$process_output" \
    ANDROID_WORKER_PROCESS_STOP_OBSERVED; then
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
convergence_process_loss="$evidence_root/convergence-process-loss.json"
convergence_process_loss_recovery="$evidence_root/convergence-process-loss-recovery.json"
convergence_terminal="$evidence_root/convergence-terminal.json"
convergence_restart="$evidence_root/convergence-server-restart.json"

start_server convergence-initial "$convergence_scope"
reset_application
run_proof_phase \
    runAndroidWorkerConvergenceHealth \
    "$convergence_id" \
    active \
    "$convergence_active"

process_loss_output="$proof_root/convergence-process-loss.out"
run_proof_phase \
    runAndroidWorkerConvergenceHealth \
    "$convergence_id" \
    process-loss \
    "$convergence_process_loss" \
    "$convergence_active" > "$process_loss_output" 2>&1 &
proof_pid=$!
if ! await_proof_marker \
    "$process_loss_output" \
    ANDROID_WORKER_IN_FLIGHT_PROCESS_LOSS_READY; then
    cat "$process_loss_output"
    echo "Convergence proof did not establish the in-flight DELAY"
    exit 1
fi
adb shell am force-stop "$application_id"
wait "$proof_pid"
proof_pid=

start_application
run_proof_phase \
    runAndroidWorkerConvergenceHealth \
    "$convergence_id" \
    process-loss-recovery \
    "$convergence_process_loss_recovery" \
    "$convergence_process_loss"

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

triad_correctness_id=ci-android-worker-triad-correctness
triad_correctness_evidence="$evidence_root/triad-correctness.json"

start_server triad-correctness "$triad_correctness_scope"
reset_triad_applications
run_proof_phase \
    runAndroidWorkerTriadCorrectness \
    "$triad_correctness_id" \
    initial \
    "$triad_correctness_evidence"

stop_triad_applications
stop_server_gracefully
clear_scope "$triad_correctness_scope"

triad_convergence_id=ci-android-worker-triad-convergence-health
triad_baseline="$evidence_root/triad-convergence-baseline.json"
triad_outage="$evidence_root/triad-convergence-outage.json"
triad_recovery="$evidence_root/triad-convergence-recovery.json"

start_server triad-convergence "$triad_convergence_scope"
reset_triad_applications
run_proof_phase \
    runAndroidWorkerTriadConvergenceHealth \
    "$triad_convergence_id" \
    baseline \
    "$triad_baseline"

adb shell am force-stop \
    "${lab_application_ids[$lab_outage_index]}" >/dev/null
run_proof_phase \
    runAndroidWorkerTriadConvergenceHealth \
    "$triad_convergence_id" \
    outage \
    "$triad_outage" \
    "$triad_baseline"

start_application_id "${lab_application_ids[$lab_outage_index]}"
run_proof_phase \
    runAndroidWorkerTriadConvergenceHealth \
    "$triad_convergence_id" \
    recovery \
    "$triad_recovery" \
    "$triad_baseline"

stop_triad_applications
stop_server_gracefully
clear_scope "$triad_convergence_scope"
