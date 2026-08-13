package com.xa.mass.workerdelivery.adapter.netty.internal.remote;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.json.Jsons;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Remote Result ingress used by one Adapter's Report process. */
public final class DeliveryReportRemoteApi {

    private static final String OPERATION = "deliveryReport.submitRemote";
    private static final String DECODE_OPERATION =
            "deliveryReport.decodeRemoteResponse";
    private static final Set<String> APPEND_FIELDS = Set.of(
            "acceptedCount",
            "rejectedCount"
    );

    private final WorkerDeliveryHttpClient httpClient;

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
                    encodeResultBatch(encodedReports),
                    202
            );
        } catch (WorkerDeliveryHttpClient.UnexpectedStatus error) {
            throw statusFailure(error.statusCode());
        } catch (WorkerDeliveryHttpClient.RequestFailure error) {
            throw unavailable("Worker result submission failed", error);
        }
        requireCompleteResultResponse(
                body,
                encodedReports.size()
        );
    }

    private String encodeResultBatch(List<String> encodedDeliveryReports) {
        if (encodedDeliveryReports == null || encodedDeliveryReports.isEmpty()) {
            throw new IllegalArgumentException("results must not be empty");
        }
        for (String encodedDeliveryReport : encodedDeliveryReports) {
            if (encodedDeliveryReport == null
                    || encodedDeliveryReport.isEmpty()) {
                throw new IllegalArgumentException(
                        "encoded DeliveryReport must be non-empty"
                );
            }
        }
        return Jsons.toJson(Map.of("results", encodedDeliveryReports));
    }

    private void requireCompleteResultResponse(
            String value,
            int expectedCount
    ) {
        try {
            Map<String, Object> payload = Jsons.parseObject(value);
            if (!payload.keySet().equals(APPEND_FIELDS)
                    || !(payload.get("acceptedCount") instanceof Long accepted)
                    || !(payload.get("rejectedCount") instanceof Long rejected)
                    || accepted < 0
                    || rejected < 0
                    || accepted > Integer.MAX_VALUE
                    || rejected > Integer.MAX_VALUE
                    || accepted + rejected != expectedCount) {
                throw malformed("DeliveryReport append response");
            }
        } catch (WorkerDeliveryAdapterException error) {
            throw error;
        } catch (IllegalArgumentException | ArithmeticException error) {
            throw new WorkerDeliveryAdapterException(
                    WorkerDeliveryAdapterErrorCode.REMOTE_API_PROTOCOL_ERROR,
                    DECODE_OPERATION,
                    "DeliveryReport append response is malformed",
                    error
            );
        }
    }

    private static WorkerDeliveryAdapterException malformed(String type) {
        return new WorkerDeliveryAdapterException(
                WorkerDeliveryAdapterErrorCode.REMOTE_API_PROTOCOL_ERROR,
                DECODE_OPERATION,
                type + " is malformed",
                null
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
