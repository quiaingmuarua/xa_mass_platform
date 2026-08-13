package com.xa.mass.workerdelivery.adapter.netty.internal.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer;
import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer.Response;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class WorkerDeliveryHttpClientTest {

    @Test
    void postsJsonAndRequiresTheExpectedStatus() {
        try (ScriptedHttpServer server = new ScriptedHttpServer(
                request -> new Response(202, "opaque-response")
        )) {
            WorkerDeliveryHttpClient client = client(server);

            assertThat(client.postJson(
                    "/owner/items",
                    "{\"value\":1}",
                    202
            )).isEqualTo("opaque-response");
            assertThat(server.requests()).singleElement().satisfies(request -> {
                assertThat(request.rawPath()).isEqualTo("/owner/items");
                assertThat(request.body()).isEqualTo("{\"value\":1}");
            });
        }
    }

    @Test
    void unexpectedStatusRemainsMechanicalEvidence() {
        try (ScriptedHttpServer server = new ScriptedHttpServer(
                request -> new Response(418, "opaque-response")
        )) {
            assertThatThrownBy(() -> client(server).postJson(
                    "/owner/items",
                    "{}",
                    202
            )).isInstanceOfSatisfying(
                    WorkerDeliveryHttpClient.UnexpectedStatus.class,
                    error -> assertThat(error.statusCode()).isEqualTo(418)
            );
        }
    }

    @Test
    void postsEmptyBodyAsynchronously() {
        try (ScriptedHttpServer server = new ScriptedHttpServer(
                request -> new Response(204, "")
        )) {
            client(server).postEmptyAsync("/owner/route", 204)
                    .toCompletableFuture()
                    .join();

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
            WorkerDeliveryHttpClient client = client(server);
            assertThatThrownBy(() -> client.postJson(
                    "relative", "{}", 204
            )).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> client.postJson(
                    "//authority/path", "{}", 204
            )).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> client.postJson(
                    "/path?query=1", "{}", 204
            )).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> client.postJson(
                    "/path#fragment", "{}", 204
            )).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void exposesNetworkFailureWithoutAddingOwnerSemantics() {
        ScriptedHttpServer server = new ScriptedHttpServer(
                request -> new Response(204, "")
        );
        WorkerDeliveryHttpClient client = client(server);
        server.close();

        assertThatThrownBy(() -> client.postJson(
                "/owner/items", "{}", 204
        )).isInstanceOf(WorkerDeliveryHttpClient.RequestFailure.class);
        assertThatThrownBy(() -> client.postEmptyAsync(
                "/owner/route", 204
        ).toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(
                        WorkerDeliveryHttpClient.RequestFailure.class
                );
    }

    @Test
    void appliesTheConfiguredTimeoutToSyncAndAsyncRequests() {
        try (ScriptedHttpServer server = new ScriptedHttpServer(request -> {
            Thread.sleep(500);
            return new Response(204, "");
        })) {
            WorkerDeliveryHttpClient client = new WorkerDeliveryHttpClient(
                    server.baseUri(),
                    Duration.ofMillis(50)
            );

            assertThatThrownBy(() -> client.postJson(
                    "/owner/items", "{}", 204
            )).isInstanceOf(WorkerDeliveryHttpClient.RequestFailure.class);
            assertThatThrownBy(() -> client.postEmptyAsync(
                    "/owner/route", 204
            ).toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(
                            WorkerDeliveryHttpClient.RequestFailure.class
                    );
        }
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
        try (ScriptedHttpServer server = new ScriptedHttpServer(
                request -> new Response(204, "")
        )) {
            assertThatThrownBy(() -> client(server).postJson(
                    "/owner/items", "{}", 99
            )).isInstanceOf(IllegalArgumentException.class);
            assertThat(server.requests()).isEmpty();
        }
    }

    private static WorkerDeliveryHttpClient client(
            ScriptedHttpServer server
    ) {
        return new WorkerDeliveryHttpClient(
                server.baseUri(),
                Duration.ofSeconds(2)
        );
    }
}
