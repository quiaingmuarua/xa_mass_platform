package com.xa.mass.workerdelivery.adapter.netty.internal.remote;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import java.util.Map;
import java.util.Objects;

/** Remote Command source used by one Adapter's Command process. */
public final class DeliveryCommandRemoteApi {

    private static final String OPERATION = "deliveryCommand.consumeRemote";

    private final WorkerDeliveryHttpClient httpClient;
    private final DeliveryCommandHttpContract httpContract;

    public DeliveryCommandRemoteApi(
            WorkerDeliveryHttpClient httpClient,
            WorkerDeliveryCodec codec
    ) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        httpContract = new DeliveryCommandHttpContract(
                Objects.requireNonNull(codec, "codec")
        );
    }

    public Map<String, DeliveryCommand> consume(
            String adapterId,
            int limit
    ) {
        String body;
        try {
            body = httpClient.postJson(
                    endpointPath(adapterId, "commands:consume"),
                    httpContract.encodeConsumeRequest(limit),
                    200
            );
        } catch (WorkerDeliveryHttpClient.UnexpectedStatus error) {
            throw statusFailure(
                    "Worker command consume",
                    error.statusCode()
            );
        } catch (WorkerDeliveryHttpClient.RequestFailure error) {
            throw unavailable("Worker command acquisition failed", error);
        }
        return httpContract.decodeConsumeResponse(body);
    }

    private static String endpointPath(String adapterId, String action) {
        return "/api/v1/worker-delivery/endpoint-managers/"
                + WorkerDeliveryHttpClient.encodePathSegment(adapterId)
                + "/"
                + action;
    }

    private static WorkerDeliveryAdapterException statusFailure(
            String action,
            int statusCode
    ) {
        WorkerDeliveryAdapterErrorCode errorCode = statusCode >= 500
                ? WorkerDeliveryAdapterErrorCode.REMOTE_API_UNAVAILABLE
                : WorkerDeliveryAdapterErrorCode.REMOTE_API_PROTOCOL_ERROR;
        return new WorkerDeliveryAdapterException(
                errorCode,
                OPERATION,
                action + " failed with HTTP " + statusCode,
                null
        );
    }

    private static WorkerDeliveryAdapterException unavailable(
            String message,
            Throwable cause
    ) {
        return new WorkerDeliveryAdapterException(
                WorkerDeliveryAdapterErrorCode.REMOTE_API_UNAVAILABLE,
                OPERATION,
                message,
                cause
        );
    }
}
