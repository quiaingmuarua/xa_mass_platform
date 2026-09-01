package com.xa.mass.android.workerdemo;

import com.xa.mass.android.capabilities.AndroidDemoCapabilities;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.worker.runtime.WorkerLifecycle;
import com.xa.mass.workerdelivery.json.Jsons;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Local-only Worker Host events exposed through the demo's loopback HTTP. */
final class AndroidWorkerHostEvents {

    static final String SNAPSHOT_EVENT =
            "extension.worker.android-demo.host.snapshot";
    static final String START_EVENT =
            "extension.worker.android-demo.host.start";
    static final String STOP_EVENT =
            "extension.worker.android-demo.host.stop";

    private static final String SNAPSHOT_CAPABILITY =
            "android-demo.host.snapshot";
    private static final String START_CAPABILITY =
            "android-demo.host.start";
    private static final String STOP_CAPABILITY =
            "android-demo.host.stop";

    private AndroidWorkerHostEvents() {
    }

    static Collection<? extends WorkerEventDefinition<?>> assemble(
            Collection<? extends WorkerEventDefinition<?>>
                    businessDefinitions,
            WorkerLifecycle worker,
            AndroidDemoCapabilities capabilities,
            AndroidWorkerLabEvents labEvents
    ) {
        Objects.requireNonNull(businessDefinitions, "businessDefinitions");
        WorkerLifecycle resolvedWorker = Objects.requireNonNull(
                worker,
                "worker"
        );
        AndroidDemoCapabilities resolvedCapabilities =
                Objects.requireNonNull(capabilities, "capabilities");
        AndroidWorkerLabEvents resolvedLabEvents = Objects.requireNonNull(
                labEvents,
                "labEvents"
        );

        List<WorkerEventDefinition<?>> definitions = new ArrayList<>(
                businessDefinitions.size() + 3
        );
        for (WorkerEventDefinition<?> definition : businessDefinitions) {
            definitions.add(Objects.requireNonNull(
                    definition,
                    "businessDefinition"
            ));
        }
        definitions.add(WorkerEventDefinition.extension(
                SNAPSHOT_CAPABILITY,
                WorkerEventParameterResolvers.jsonMap(),
                ignored -> snapshot(
                        resolvedWorker,
                        resolvedCapabilities,
                        resolvedLabEvents
                )
        ));
        definitions.add(WorkerEventDefinition.extension(
                START_CAPABILITY,
                WorkerEventParameterResolvers.jsonMap(),
                ignored -> requestStart(resolvedWorker)
        ));
        definitions.add(WorkerEventDefinition.extension(
                STOP_CAPABILITY,
                WorkerEventParameterResolvers.jsonMap(),
                ignored -> requestStop(resolvedWorker)
        ));
        return Collections.unmodifiableList(definitions);
    }

    private static String snapshot(
            WorkerLifecycle worker,
            AndroidDemoCapabilities capabilities,
            AndroidWorkerLabEvents labEvents
    ) {
        WorkerLifecycle.Snapshot workerSnapshot = worker.snapshot();
        AndroidDemoCapabilities.Snapshot capabilitySnapshot =
                capabilities.snapshot();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", workerSnapshot.state().name());
        result.put("workerId", workerSnapshot.workerId());
        URI endpointUri = workerSnapshot.endpointUri();
        result.put(
                "endpointUri",
                endpointUri == null ? null : endpointUri.toString()
        );
        result.put(
                "diagnosticMessage",
                workerSnapshot.diagnosticMessage()
        );
        result.put(
                "processedCommands",
                capabilitySnapshot.processedCommands()
        );
        result.put("lastEvent", capabilitySnapshot.lastEvent());
        result.put("activeDelayCount", labEvents.activeDelayCount());
        return Jsons.toJson(result);
    }

    private static String requestStart(WorkerLifecycle worker) {
        worker.start();
        return accepted(WorkerLifecycle.State.RUNNING);
    }

    private static String requestStop(WorkerLifecycle worker) {
        worker.stop();
        return accepted(WorkerLifecycle.State.STOPPED);
    }

    private static String accepted(WorkerLifecycle.State requestedState) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", true);
        result.put("requestedState", requestedState.name());
        return Jsons.toJson(result);
    }
}
