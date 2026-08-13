package com.xa.mass.workerdelivery.adapter.netty.internal.remote;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** Remote Binding verification used by one Adapter's connection mechanism. */
public final class WorkerRouteRemoteApi {

    private static final String OPERATION = "workerConnection.verifyRoute";

    private final WorkerDeliveryHttpClient httpClient;

    public WorkerRouteRemoteApi(WorkerDeliveryHttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    public CompletionStage<Void> verify(
            String adapterId,
            String workerId
    ) {
        CompletionStage<Void> request;
        try {
            request = httpClient.postEmptyAsync(
                    routePath(adapterId, workerId),
                    204
            );
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(classify(error));
        }
        return request.handle((ignored, failure) -> {
            if (failure != null) {
                throw classify(failure);
            }
            return null;
        });
    }

    private static String routePath(String adapterId, String workerId) {
        return "/api/v1/worker-delivery/endpoint-managers/"
                + WorkerDeliveryHttpClient.encodePathSegment(adapterId)
                + "/workers/"
                + WorkerDeliveryHttpClient.encodePathSegment(workerId)
                + ":verify-binding";
    }

    private static WorkerDeliveryAdapterException classify(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof WorkerDeliveryAdapterException classified) {
            return classified;
        }
        if (cause instanceof WorkerDeliveryHttpClient.UnexpectedStatus status) {
            WorkerDeliveryAdapterErrorCode errorCode;
            if (status.statusCode() >= 400 && status.statusCode() < 500) {
                errorCode = WorkerDeliveryAdapterErrorCode
                        .WORKER_ROUTE_REJECTED;
            } else if (status.statusCode() >= 500) {
                errorCode = WorkerDeliveryAdapterErrorCode
                        .REMOTE_API_UNAVAILABLE;
            } else {
                errorCode = WorkerDeliveryAdapterErrorCode
                        .REMOTE_API_PROTOCOL_ERROR;
            }
            return new WorkerDeliveryAdapterException(
                    errorCode,
                    OPERATION,
                    "Worker route verification failed with HTTP "
                            + status.statusCode(),
                    null
            );
        }
        return new WorkerDeliveryAdapterException(
                WorkerDeliveryAdapterErrorCode.REMOTE_API_UNAVAILABLE,
                OPERATION,
                "Worker route verification failed",
                cause
        );
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
