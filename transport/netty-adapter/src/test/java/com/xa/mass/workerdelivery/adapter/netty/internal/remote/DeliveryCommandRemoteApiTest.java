package com.xa.mass.workerdelivery.adapter.netty.internal.remote;

import static com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode.REMOTE_API_PROTOCOL_ERROR;
import static com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode.REMOTE_API_UNAVAILABLE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer;
import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer.Response;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeliveryCommandRemoteApiTest {

    private static final WorkerDeliveryCodec CODEC = new WorkerDeliveryCodec();

    @Test
    void ownsUnifiedCommandPathAndSuccessfulDecode() {
        DeliveryCommand command = DeliveryCommand.create(
                TASK,
                WORKER,
                "test.observe",
                1234,
                "{}",
                "forward-1"
        );
        String body = "{\"worker-2\":"
                + CODEC.encodeDeliveryCommand(command)
                + ",\"worker-1\":"
                + CODEC.encodeDeliveryCommand(command)
                + "}";
        try (ScriptedHttpServer server = new ScriptedHttpServer(
                request -> new Response(200, body)
        )) {
            DeliveryCommandRemoteApi remoteApi = remoteApi(server);

            Map<String, DeliveryCommand> commands =
                    remoteApi.consume("adapter/one", 3);
            assertThat(commands.keySet())
                    .containsExactly("worker-2", "worker-1");
            assertThat(commands).containsEntry("worker-1", command);
            assertThat(server.requests()).hasSize(1);
            assertThat(server.requests().get(0).rawPath()).isEqualTo(
                    "/api/v1/worker-delivery/endpoint-managers/"
                            + "adapter%2Fone/commands:consume"
            );
            assertThat(server.requests().get(0).body())
                    .isEqualTo("3");
        }
    }

    @Test
    void classifiesStatusAndMalformedResponseAtTheCommandOwner() {
        try (ScriptedHttpServer server = new ScriptedHttpServer(
                request -> new Response(503, "{}")
        )) {
            DeliveryCommandRemoteApi remoteApi = remoteApi(server);
            assertFailure(
                    () -> remoteApi.consume("adapter-1", 1),
                    REMOTE_API_UNAVAILABLE
            );
            server.handler(request -> new Response(409, "{}"));
            assertFailure(
                    () -> remoteApi.consume("adapter-1", 1),
                    REMOTE_API_PROTOCOL_ERROR
            );
            server.handler(request -> new Response(200, "{\"bad\":true}"));
            assertFailure(
                    () -> remoteApi.consume("adapter-1", 1),
                    REMOTE_API_PROTOCOL_ERROR
            );
        }
    }

    @Test
    void rejectsARemoteBatchThatExceedsTheRequestedLimit() {
        DeliveryCommand command = DeliveryCommand.create(
                TASK,
                WORKER,
                "test.observe",
                1234,
                "{}",
                "forward-1"
        );
        String body = "{\"worker-1\":"
                + CODEC.encodeDeliveryCommand(command)
                + ",\"worker-2\":"
                + CODEC.encodeDeliveryCommand(command)
                + "}";
        try (ScriptedHttpServer server = new ScriptedHttpServer(
                request -> new Response(200, body)
        )) {
            assertFailure(
                    () -> remoteApi(server).consume("adapter-1", 1),
                    REMOTE_API_PROTOCOL_ERROR
            );
        }
    }

    private static DeliveryCommandRemoteApi remoteApi(
            ScriptedHttpServer server
    ) {
        return new DeliveryCommandRemoteApi(
                new WorkerDeliveryHttpClient(
                        server.baseUri(),
                        Duration.ofSeconds(2)
                ),
                CODEC
        );
    }

    private static void assertFailure(
            Runnable action,
            WorkerDeliveryAdapterErrorCode expected
    ) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(
                WorkerDeliveryAdapterException.class,
                error -> assertThat(error.errorCode()).isEqualTo(expected)
        );
    }
}
