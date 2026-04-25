package com.xa.mass.starter.transport;

import com.xa.mass.starter.worker.PollingWorkerAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * Default embedded-runtime transport assembly: always include polling/pull and
 * merge any additional adapter contribution assembled by runtime composition.
 */
public class DefaultWorkerTransportRuntimeFactory implements WorkerTransportRuntimeFactory {

    @Override
    public TransportRuntimeRegistry create(WorkerTransportRuntimeFactoryContext context) {
        List<TransportBinding> bindings = new ArrayList<>();

        PollingWorkerAdapter pollingAdapter = new PollingWorkerAdapter(context.getSystemEventChannel());
        bindings.add(TransportBinding.builder(pollingAdapter)
                .taskPullChannel(pollingAdapter)
                .build());
        bindings.addAll(context.getAdapterBindings());

        return new TransportRuntimeRegistry(
                context.getWorkerManager(),
                context.getTaskResultIngestChannel(),
                context.getSystemEventChannel(),
                bindings
        );
    }
}
