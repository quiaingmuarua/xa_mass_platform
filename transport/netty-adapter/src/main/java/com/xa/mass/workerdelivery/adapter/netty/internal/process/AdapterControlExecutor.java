package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.SYSTEM;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import java.util.Map;
import java.util.Objects;

/** Fixed, process-local execution of Adapter-targeted control commands. */
final class AdapterControlExecutor {

    static final String PROBE_EVENT = "adapter.probe";

    private final String adapterId;

    AdapterControlExecutor(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must be non-blank");
        }
        this.adapterId = adapterId;
    }

    DeliveryReport execute(DeliveryCommand command) {
        Objects.requireNonNull(command, "command");
        if (command.src() != SYSTEM || command.dst() != ADAPTER) {
            return result(
                    command,
                    WorkerDeliveryAdapterErrorCode.CONTROL_COMMAND_INVALID,
                    "null"
            );
        }
        if (!PROBE_EVENT.equals(command.messageType())) {
            return result(
                    command,
                    WorkerDeliveryAdapterErrorCode.CONTROL_EVENT_UNSUPPORTED,
                    "null"
            );
        }
        if (!"null".equals(command.payload())) {
            return result(
                    command,
                    WorkerDeliveryAdapterErrorCode.CONTROL_COMMAND_INVALID,
                    "null"
            );
        }
        return DeliveryReport.fromCommand(
                command,
                ADAPTER,
                adapterId,
                "200",
                Jsons.toJson(Map.of(
                        "adapterId", adapterId,
                        "reachable", true
                ))
        );
    }

    private DeliveryReport result(
            DeliveryCommand command,
            WorkerDeliveryAdapterErrorCode errorCode,
            String payload
    ) {
        return DeliveryReport.fromCommand(
                command,
                ADAPTER,
                adapterId,
                Integer.toString(errorCode.code()),
                payload
        );
    }
}
