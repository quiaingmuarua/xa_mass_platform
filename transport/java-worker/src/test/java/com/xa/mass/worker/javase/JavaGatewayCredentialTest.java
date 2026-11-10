package com.xa.mass.worker.javase;

import static org.junit.jupiter.api.Assertions.*;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.transport.client.TextMessageReconnectPolicy;
import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.worker.runtime.WorkerConnectionOptions;
import com.xa.mass.worker.runtime.WorkerGatewayCredential;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaGatewayCredentialTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final WorkerGatewayCredential CREDENTIAL = WorkerGatewayCredential.of("java-host", "private-test-secret");
    @TempDir Path temporary;

    @Test
    void prepareAndBatchSendOnlyTheHeaderAndRejectUnsafeReturnedEndpoints() throws Exception {
        try (TlsWorld world = new TlsWorld()) {
            JavaOkHttpWorkerControlClient client = world.control();
            world.server.enqueue(new MockResponse.Builder().body(world.prepared("one")).build());
            client.prepare("group", WorkerTransportType.WEBSOCKET, Map.of("clientWorkerKey", "one"), TIMEOUT);
            var single = world.server.takeRequest(3, TimeUnit.SECONDS);
            assertNotNull(single);
            assertEquals(CREDENTIAL.authorizationHeader(), single.getHeaders().get("Authorization"));
            assertFalse(single.getBody().utf8().contains("private-test-secret"));
            world.server.enqueue(new MockResponse.Builder().body("[" + world.prepared("two") + "]").build());
            client.prepareBatch("CLIENT_KEY", "group", WorkerTransportType.WEBSOCKET,
                    List.of(Map.of("clientWorkerKey", "two")), TIMEOUT);
            var batch = world.server.takeRequest(3, TimeUnit.SECONDS);
            assertNotNull(batch);
            assertTrue(batch.getTarget().endsWith("workers:prepare-batch"));
            assertEquals(CREDENTIAL.authorizationHeader(), batch.getHeaders().get("Authorization"));
            for (String endpoint : List.of("ws://localhost/socket", "wss://other.example/socket",
                    "wss://user@localhost/socket")) {
                world.server.enqueue(new MockResponse.Builder().body(prepared("bad", endpoint)).build());
                assertThrows(WorkerException.class, () -> client.prepare("group", WorkerTransportType.WEBSOCKET,
                        Map.of("clientWorkerKey", "one"), TIMEOUT));
            }
            int before = world.server.getRequestCount();
            assertThrows(IllegalArgumentException.class, () -> client.prepare("group", WorkerTransportType.SOCKET,
                    Map.of("clientWorkerKey", "one"), TIMEOUT));
            assertEquals(before, world.server.getRequestCount());
            client.close();
        }
    }

    @Test
    void authenticatedPrepareDoesNotFollowRedirectsOrLeakCredentials() throws Exception {
        try (TlsWorld world = new TlsWorld(); TlsWorld other = new TlsWorld(world.certificate)) {
            JavaOkHttpWorkerControlClient client = world.control();
            for (int code : List.of(302, 307, 308)) {
                other.server.enqueue(new MockResponse.Builder().body(other.prepared("unexpected")).build());
                world.server.enqueue(new MockResponse.Builder().code(code)
                        .addHeader("Location", other.server.url("/steal")).build());
                var error = assertThrows(WorkerException.class, () -> client.prepare("group", WorkerTransportType.WEBSOCKET,
                        Map.of("clientWorkerKey", "one"), TIMEOUT));
                assertFalse(error.toString().contains("private-test-secret"));
                assertEquals(0, other.server.getRequestCount());
            }
            client.close();
        }
    }

    @Test
    void everyPhysicalWebSocketReconnectSendsTheCredential() throws Exception {
        try (TlsWorld world = new TlsWorld()) {
            var scheduler = Executors.newSingleThreadScheduledExecutor();
            CountDownLatch opened = new CountDownLatch(2);
            world.server.enqueue(upgrade(true));
            world.server.enqueue(upgrade(false));
            JavaOkHttpTextWebSocketClient client = new JavaOkHttpTextWebSocketClient(world.http, scheduler,
                    world.endpoint(), TextMessageReconnectPolicy.of(3, Duration.ofMillis(20), Duration.ofSeconds(1)),
                    world.runtime(), CREDENTIAL);
            try {
                client.start(listener(opened, new CountDownLatch(1)));
                assertTrue(opened.await(5, TimeUnit.SECONDS));
                for (int index = 0; index < 2; index++) {
                    var request = world.server.takeRequest(3, TimeUnit.SECONDS);
                    assertNotNull(request);
                    assertEquals(CREDENTIAL.authorizationHeader(), request.getHeaders().get("Authorization"));
                }
            } finally {
                client.close();
                scheduler.shutdownNow();
            }
        }
    }

    @Test
    void webSocketRedirectAndForeignEndpointCannotReceiveTheCredential() throws Exception {
        try (TlsWorld world = new TlsWorld(); TlsWorld other = new TlsWorld(world.certificate)) {
            var scheduler = Executors.newSingleThreadScheduledExecutor();
            CountDownLatch ended = new CountDownLatch(1);
            other.server.enqueue(upgrade(false));
            world.server.enqueue(new MockResponse.Builder().code(307).addHeader("Location", other.server.url("/steal")).build());
            JavaOkHttpTextWebSocketClient client = new JavaOkHttpTextWebSocketClient(world.http, scheduler,
                    world.endpoint(), TextMessageReconnectPolicy.of(1, Duration.ofMillis(20), Duration.ofSeconds(1)),
                    world.runtime(), CREDENTIAL);
            try {
                assertThrows(IllegalArgumentException.class, () -> new JavaOkHttpTextWebSocketClient(world.http, scheduler,
                        URI.create("wss://other.example/socket"), TextMessageReconnectPolicy.defaults(), world.runtime(), CREDENTIAL));
                client.start(listener(new CountDownLatch(1), ended));
                assertTrue(ended.await(5, TimeUnit.SECONDS));
                assertEquals(0, other.server.getRequestCount());
            } finally {
                client.close();
                scheduler.shutdownNow();
            }
        }
    }

    @Test
    void managerBatchUsesItsConfiguredCredentialOverTls() throws Exception {
        try (TlsWorld world = new TlsWorld()) {
            Path trustStore = temporary.resolve("gateway-trust.p12");
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setCertificateEntry("gateway", world.certificate.certificate());
            try (var output = Files.newOutputStream(trustStore)) { store.store(output, "test-only".toCharArray()); }
            String previousPath = System.getProperty("javax.net.ssl.trustStore");
            String previousPassword = System.getProperty("javax.net.ssl.trustStorePassword");
            String previousType = System.getProperty("javax.net.ssl.trustStoreType");
            System.setProperty("javax.net.ssl.trustStore", trustStore.toString());
            System.setProperty("javax.net.ssl.trustStorePassword", "test-only");
            System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
            world.server.enqueue(new MockResponse.Builder().body("[" + world.prepared("worker") + "]").build());
            world.server.enqueue(upgrade(false));
            try (JavaWorkerManager manager = JavaWorkerManager.builder(world.runtime(), "group", WorkerTransportType.WEBSOCKET)
                    .options(WorkerConnectionOptions.defaults().withGatewayCredential(CREDENTIAL))
                    .batchWorkerKind("CLIENT_KEY").replica("one", () -> Map.of("clientWorkerKey", "one")).build()) {
                manager.start();
                var request = world.server.takeRequest(5, TimeUnit.SECONDS);
                assertNotNull(request);
                assertTrue(request.getTarget().endsWith("workers:prepare-batch"));
                assertEquals(CREDENTIAL.authorizationHeader(), request.getHeaders().get("Authorization"));
                var socket = world.server.takeRequest(5, TimeUnit.SECONDS);
                assertNotNull(socket);
                assertEquals(CREDENTIAL.authorizationHeader(), socket.getHeaders().get("Authorization"));
            } finally {
                restore("javax.net.ssl.trustStore", previousPath);
                restore("javax.net.ssl.trustStorePassword", previousPassword);
                restore("javax.net.ssl.trustStoreType", previousType);
            }
        }
    }

    @Test
    void invalidGatewayAssemblyFailsBeforeOpeningConnections() {
        var options = WorkerConnectionOptions.defaults().withGatewayCredential(CREDENTIAL);
        assertThrows(IllegalArgumentException.class, () -> JavaWorker.create(URI.create("http://localhost"), "group", "key",
                WorkerTransportType.WEBSOCKET, Map::of, List.of(), options));
        assertThrows(IllegalArgumentException.class, () -> JavaWorkerManager.builder(URI.create("https://localhost"),
                "group", WorkerTransportType.SOCKET).options(options).replica("one", Map::of).build());
    }

    private static void restore(String name, String value) {
        if (value == null) System.clearProperty(name); else System.setProperty(name, value);
    }

    private static String prepared(String id, String endpoint) {
        return "{\"workerId\":\"" + id + "\",\"transportType\":\"WEBSOCKET\",\"endpointUri\":\"" + endpoint + "\"}";
    }

    private static MockResponse upgrade(boolean reconnect) {
        return new MockResponse.Builder().webSocketUpgrade(new WebSocketListener() {
            @Override public void onOpen(WebSocket socket, Response response) {
                if (reconnect) socket.close(1001, "test reconnect");
            }
            @Override public void onClosing(WebSocket socket, int code, String reason) { socket.close(code, reason); }
        }).build();
    }

    private static TextMessageClient.Listener listener(CountDownLatch opened, CountDownLatch ended) {
        return new TextMessageClient.Listener() {
            @Override public void onOpen() { opened.countDown(); }
            @Override public void onMessage(String message) { }
            @Override public void onEndpointTerminated() { ended.countDown(); }
        };
    }

    private static final class TlsWorld implements AutoCloseable {
        final HeldCertificate certificate;
        final MockWebServer server = new MockWebServer();
        final OkHttpClient http;
        TlsWorld() throws Exception {
            this(new HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build());
        }
        TlsWorld(HeldCertificate certificate) throws Exception {
            this.certificate = certificate;
            var serverTls = new HandshakeCertificates.Builder().heldCertificate(certificate).build();
            var clientTls = new HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate()).build();
            server.useHttps(serverTls.sslSocketFactory());
            server.start();
            http = new OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager()).build();
        }
        URI runtime() { return URI.create(server.url("/").toString()); }
        URI endpoint() { return URI.create(server.url("/socket").toString().replaceFirst("^https", "wss")); }
        String prepared(String id) { return JavaGatewayCredentialTest.prepared(id, endpoint().toString()); }
        JavaOkHttpWorkerControlClient control() { return new JavaOkHttpWorkerControlClient(http, runtime(), CREDENTIAL); }
        @Override public void close() throws Exception {
            http.connectionPool().evictAll();
            http.dispatcher().executorService().shutdownNow();
            server.close();
        }
    }
}
