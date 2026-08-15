package com.xa.mass.workerdelivery.adapter.netty.internal.remote;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Remote Command source used by one Adapter's Command process. */
public final class DeliveryCommandRemoteApi {

    private static final String OPERATION = "deliveryCommand.consumeRemote";
    private static final String DECODE_OPERATION =
            "deliveryCommand.decodeRemoteResponse";
    private static final String RESPONSE_FIELD = "commands";

    private final WorkerDeliveryHttpClient httpClient;
    private final WorkerDeliveryCodec codec;

    public DeliveryCommandRemoteApi(
            WorkerDeliveryHttpClient httpClient,
            WorkerDeliveryCodec codec
    ) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public Map<String, DeliveryCommand> consume(
            String adapterId,
            int limit
    ) {
        String body;
        try {
            body = httpClient.postJson(
                    endpointPath(adapterId),
                    encodeConsumeRequest(limit),
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
        return decodeConsumeResponse(body);
    }

    private String encodeConsumeRequest(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException(
                    "consume limit must be positive"
            );
        }
        return Jsons.toJson(Map.of("limit", limit));
    }

    private Map<String, DeliveryCommand> decodeConsumeResponse(
            String value
    ) {
        try {
            Map<String, Object> payload = Jsons.parseObject(value);
            if (!payload.keySet().equals(java.util.Set.of(RESPONSE_FIELD))
                    || !(payload.get(RESPONSE_FIELD)
                    instanceof Map<?, ?> commands)) {
                throw malformed("Worker command consume response");
            }
            Map<String, DeliveryCommand> decoded = new LinkedHashMap<>();
            commands.forEach((workerId, encoded) -> {
                if (!(workerId instanceof String id) || id.isBlank()
                        || !(encoded instanceof Map<?, ?>)) {
                    throw malformed("Worker command workerId");
                }
                DeliveryCommand command = codec.decodeDeliveryCommand(
                        Jsons.toJson(encoded)
                );
                if (command == null) {
                    throw malformed("Worker command envelope");
                }
                decoded.put(id, command);
            });
            return Collections.unmodifiableMap(decoded);
        } catch (WorkerDeliveryAdapterException error) {
            throw error;
        } catch (IllegalArgumentException error) {
            throw new WorkerDeliveryAdapterException(
                    WorkerDeliveryAdapterErrorCode.REMOTE_API_PROTOCOL_ERROR,
                    DECODE_OPERATION,
                    "Worker command consume response is malformed",
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

    private static String endpointPath(String adapterId) {
        return "/api/v1/worker-delivery/endpoint-managers/"
                + WorkerDeliveryHttpClient.encodePathSegment(adapterId)
                + "/commands:consume";
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
