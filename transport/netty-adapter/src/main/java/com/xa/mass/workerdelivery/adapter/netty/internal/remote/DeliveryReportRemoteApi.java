package com.xa.mass.workerdelivery.adapter.netty.internal.remote;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import java.util.List;
import java.util.Objects;

/** Remote Result ingress used by one Adapter's Report process. */
public final class DeliveryReportRemoteApi {

    private static final String OPERATION = "deliveryReport.submitRemote";

    private final WorkerDeliveryHttpClient httpClient;
    private final DeliveryReportHttpContract httpContract =
            new DeliveryReportHttpContract();

    public DeliveryReportRemoteApi(WorkerDeliveryHttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    public void append(
            String adapterId,
            List<String> encodedReports
    ) {
        String body;
        try {
            body = httpClient.postJson(
                    endpointPath(adapterId, "results:append"),
                    httpContract.encodeResultBatch(encodedReports),
                    202
            );
        } catch (WorkerDeliveryHttpClient.UnexpectedStatus error) {
            throw statusFailure(error.statusCode());
        } catch (WorkerDeliveryHttpClient.RequestFailure error) {
            throw unavailable("Worker result submission failed", error);
        }
        httpContract.requireCompleteResultResponse(
                body,
                encodedReports.size()
        );
    }

    private static String endpointPath(String adapterId, String action) {
        return "/api/v1/worker-delivery/endpoint-managers/"
                + WorkerDeliveryHttpClient.encodePathSegment(adapterId)
                + "/"
                + action;
    }

    private static WorkerDeliveryAdapterException statusFailure(
            int statusCode
    ) {
        WorkerDeliveryAdapterErrorCode errorCode = statusCode >= 500
                ? WorkerDeliveryAdapterErrorCode.REMOTE_API_UNAVAILABLE
                : WorkerDeliveryAdapterErrorCode.REMOTE_API_PROTOCOL_ERROR;
        return new WorkerDeliveryAdapterException(
                errorCode,
                OPERATION,
                "DeliveryReport append failed with HTTP " + statusCode,
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
