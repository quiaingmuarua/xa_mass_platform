package com.xa.mass.integration.androidworkerproof;

import java.util.Set;

final class AndroidWorkerProofConstants {

    static final String APPLICATION_ID =
            "com.xa.mass.integration.androidworker";
    static final String WORKER_GROUP_ID = "android-demo-workers";
    static final String DEFAULT_ENDPOINT_MANAGER_ID = "scenario-websocket";

    static final String HOST_SNAPSHOT_EVENT =
            "extension.worker.android-demo.host.snapshot";
    static final String HOST_START_EVENT =
            "extension.worker.android-demo.host.start";
    static final String HOST_STOP_EVENT =
            "extension.worker.android-demo.host.stop";
    static final String DELAY_EVENT = "extension.worker.lab.delay";
    static final String FAIL_EVENT = "extension.worker.lab.fail";
    static final String WORKER_PROBE_EVENT = "platform.worker.probe";
    static final String WORKER_PROPERTIES_EVENT =
            "platform.worker.properties.snapshot";
    static final String ADAPTER_PROPERTIES_EVENT =
            "platform.adapter.worker-properties.snapshot";
    static final String ADAPTER_CLOSE_CURRENT_EVENT =
            "platform.adapter.worker-connections.close-current";

    static final String PROCESS_STOP_OBSERVED_MARKER =
            "ANDROID_WORKER_PROCESS_STOP_OBSERVED";
    static final String PROCESS_LOSS_READY_MARKER =
            "ANDROID_WORKER_IN_FLIGHT_PROCESS_LOSS_READY";

    static final Set<String> DEVICE_EVENTS = Set.of(
            HOST_SNAPSHOT_EVENT,
            HOST_START_EVENT,
            HOST_STOP_EVENT,
            DELAY_EVENT,
            FAIL_EVENT,
            "extension.worker.android.state.read",
            "extension.worker.android.battery.read",
            "extension.worker.android.string.digest"
    );

    private AndroidWorkerProofConstants() {
    }
}
