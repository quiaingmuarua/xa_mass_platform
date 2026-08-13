package com.xa.mass.workerdelivery.adapter.netty.internal.remote;

import static com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode.REMOTE_API_PROTOCOL_ERROR;
import static com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode.REMOTE_API_UNAVAILABLE;
import static com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode.WORKER_ROUTE_REJECTED;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer;
import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer.Response;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class WorkerRouteRemoteApiTest {

    @Test
    void ownsRoutePathAndSuccessfulVerificationContract() {
        try (ScriptedHttpServer server = new ScriptedHttpServer(
                request -> new Response(204, "")
        )) {
            remoteApi(server).verify("adapter/one", "worker two")
                    .toCompletableFuture()
                    .join();

            assertThat(server.requests()).singleElement().satisfies(request -> {
                assertThat(request.rawPath()).isEqualTo(
                        "/api/v1/worker-delivery/endpoint-managers/"
                                + "adapter%2Fone/workers/"
                                + "worker%20two:verify-binding"
                );
                assertThat(request.body()).isEmpty();
            });
        }
    }

    @Test
    void classifiesRouteStatusAtTheRouteOwner() {
        try (ScriptedHttpServer server = new ScriptedHttpServer(
                request -> new Response(404, "{}")
        )) {
            WorkerRouteRemoteApi remoteApi = remoteApi(server);
            assertFailure(remoteApi, WORKER_ROUTE_REJECTED);
            server.handler(request -> new Response(503, "{}"));
            assertFailure(remoteApi, REMOTE_API_UNAVAILABLE);
            server.handler(request -> new Response(302, "{}"));
            assertFailure(remoteApi, REMOTE_API_PROTOCOL_ERROR);
        }
    }

    private static void assertFailure(
            WorkerRouteRemoteApi remoteApi,
            WorkerDeliveryAdapterErrorCode expected
    ) {
        try {
            remoteApi.verify("adapter-1", "worker-1")
                    .toCompletableFuture()
                    .join();
            throw new AssertionError("route verification should fail");
        } catch (CompletionException error) {
            assertThat(error.getCause())
                    .isInstanceOfSatisfying(
                            WorkerDeliveryAdapterException.class,
                            failure -> assertThat(failure.errorCode())
                                    .isEqualTo(expected)
                    );
        }
    }

    private static WorkerRouteRemoteApi remoteApi(
            ScriptedHttpServer server
    ) {
        return new WorkerRouteRemoteApi(new WorkerDeliveryHttpClient(
                server.baseUri(),
                Duration.ofSeconds(2)
        ));
    }
}
