package com.xa.mass.starter.transport;

import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.starter.GatewayTaskResultHandler;
import com.xa.mass.starter.worker.PollingWorkerAdapter;
import com.xa.mass.starter.worker.WebSocketWorkerAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * Default embedded-runtime transport assembly: pull/polling plus the current
 * WebSocket adapter when the gateway is enabled.
 *
 * <p>The WebSocket adapter still contributes the legacy {@code TASK/step}
 * protocol-frame route for task data-plane compatibility. New control or
 * business capabilities must be added as global SDK events instead of new
 * tuple branches here.
 */
public class DefaultWorkerTransportRuntimeFactory implements WorkerTransportRuntimeFactory {

    @Override
    public TransportRuntimeRegistry create(WorkerTransportRuntimeFactoryContext context) {
        List<TransportBinding> bindings = new ArrayList<>();

        PollingWorkerAdapter pollingAdapter = new PollingWorkerAdapter(
                context.getTaskManager(),
                context.getSystemEventChannel()
        );
        bindings.add(TransportBinding.builder(pollingAdapter)
                .taskPullChannel(pollingAdapter)
                .taskResultIngestChannel(pollingAdapter)
                .build());

        if (context.isGatewayEnabled()) {
            WebSocketWorkerAdapter webSocketAdapter = new WebSocketWorkerAdapter(
                    context.getDispatchRuntimeContext()
            );
            GatewayTaskResultHandler taskResultHandler = new GatewayTaskResultHandler(
                    context.getTaskManager()
            );
            bindings.add(TransportBinding.builder(webSocketAdapter)
                    .taskResultIngestChannel(taskResultHandler)
                    .inboundRoutes(List.of(
                            new TransportInboundRoute(MessageType.TASK, "step", taskResultHandler)
                    ))
                    .build());
        }

        return new TransportRuntimeRegistry(
                context.getWorkerManager(),
                context.getSystemEventChannel(),
                bindings
        );
    }
}
