package com.xa.mass.workerdelivery.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer;
import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer.Response;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class WorkerDeliveryHttpClientTest {

    @Test
    void postsJsonWithoutInterpretingStatusOrBody() {
        try (ScriptedHttpServer server = new ScriptedHttpServer(
                request -> new Response(418, "opaque-response")
        )) {
            WorkerDeliveryHttpClient.HttpCallResult result = server.client()
                    .postJson("/owner/items", "{\"value\":1}");

            assertThat(result.statusCode()).isEqualTo(418);
            assertThat(result.body()).isEqualTo("opaque-response");
            assertThat(server.requests()).singleElement().satisfies(request -> {
                assertThat(request.rawPath()).isEqualTo("/owner/items");
                assertThat(request.body()).isEqualTo("{\"value\":1}");
            });
        }
    }

    @Test
    void postsEmptyBodyAsynchronously() {
        try (ScriptedHttpServer server = new ScriptedHttpServer(
                request -> new Response(204, "")
        )) {
            WorkerDeliveryHttpClient.HttpCallResult result = server.client()
                    .postEmptyAsync("/owner/route")
                    .toCompletableFuture()
                    .join();

            assertThat(result.statusCode()).isEqualTo(204);
            assertThat(server.requests()).singleElement().satisfies(request -> {
                assertThat(request.rawPath()).isEqualTo("/owner/route");
                assertThat(request.body()).isEmpty();
            });
        }
    }

    @Test
    void encodesOnePathSegment() {
        assertThat(WorkerDeliveryHttpClient.encodePathSegment("adapter/one a"))
                .isEqualTo("adapter%2Fone%20a");
    }

    @Test
    void rejectsPathsThatEscapeTheConfiguredBase() {
        try (ScriptedHttpServer server = new ScriptedHttpServer(
                request -> new Response(204, "")
        )) {
            WorkerDeliveryHttpClient client = server.client();
            assertThatThrownBy(() -> client.postJson("relative", "{}"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> client.postJson("//authority/path", "{}"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> client.postJson("/path?query=1", "{}"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> client.postJson("/path#fragment", "{}"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void mapsNetworkFailureWithoutAddingOwnerSemantics() {
        ScriptedHttpServer server = new ScriptedHttpServer(
                request -> new Response(204, "")
        );
        WorkerDeliveryHttpClient client = server.client();
        server.close();

        assertThatThrownBy(() -> client.postJson("/owner/items", "{}"))
                .isInstanceOfSatisfying(
                        WorkerDeliveryAdapterException.class,
                        error -> {
                            assertThat(error.errorCode()).isEqualTo(
                                    WorkerDeliveryAdapterErrorCode
                                            .REMOTE_API_UNAVAILABLE
                            );
                            assertThat(error.operation())
                                    .isEqualTo("workerDeliveryHttp.post");
                        }
                );
        assertThatThrownBy(() -> client.postEmptyAsync("/owner/route")
                .toCompletableFuture()
                .join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(WorkerDeliveryAdapterException.class);
    }

    @Test
    void rejectsInvalidConstructionAndArguments() {
        assertThatThrownBy(() -> new WorkerDeliveryHttpClient(
                URI.create("/relative"),
                Duration.ofSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkerDeliveryHttpClient(
                URI.create("redis://127.0.0.1:6379"),
                Duration.ofSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkerDeliveryHttpClient(
                URI.create("http://127.0.0.1:18082?owner=other"),
                Duration.ofSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkerDeliveryHttpClient(
                URI.create("http://127.0.0.1:18082#fragment"),
                Duration.ofSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkerDeliveryHttpClient(
                URI.create("http://127.0.0.1:18082"),
                Duration.ZERO
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                WorkerDeliveryHttpClient.encodePathSegment(" ")
        ).isInstanceOf(IllegalArgumentException.class);
    }
}
