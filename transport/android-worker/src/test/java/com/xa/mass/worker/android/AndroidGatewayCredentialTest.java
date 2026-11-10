package com.xa.mass.worker.android;

import static org.junit.Assert.*;

import android.os.HandlerThread;
import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.transport.client.TextMessageReconnectPolicy;
import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.worker.runtime.WorkerGatewayCredential;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowSystemClock;

@RunWith(RobolectricTestRunner.class)
public class AndroidGatewayCredentialTest {
    private static final WorkerGatewayCredential CREDENTIAL = WorkerGatewayCredential.of("android-host", "private-test-secret");
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    @Test
    public void prepareAuthenticatesOverTlsAndRejectsUnapprovedEndpoints() throws Exception {
        try (TlsWorld world = new TlsWorld()) {
            AndroidOkHttpWorkerControlClient client = new AndroidOkHttpWorkerControlClient(world.http, world.runtime(), CREDENTIAL);
            world.server.enqueue(new MockResponse.Builder().body(prepared(world.endpoint().toString())).build());
            client.prepare("group", WorkerTransportType.WEBSOCKET, Map.of("clientWorkerKey", "one"), TIMEOUT);
            var request = world.server.takeRequest(3, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals(CREDENTIAL.authorizationHeader(), request.getHeaders().get("Authorization"));
            assertFalse(request.getBody().utf8().contains("private-test-secret"));
            for (String endpoint : List.of("ws://localhost/socket", "wss://other.example/socket",
                    "wss://user@localhost/socket")) {
                world.server.enqueue(new MockResponse.Builder().body(prepared(endpoint)).build());
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
    public void prepareRedirectsNeverSendCredentialsToAnotherOrigin() throws Exception {
        try (TlsWorld world = new TlsWorld(); TlsWorld other = new TlsWorld(world.certificate)) {
            AndroidOkHttpWorkerControlClient client = new AndroidOkHttpWorkerControlClient(world.http, world.runtime(), CREDENTIAL);
            for (int code : List.of(302, 307, 308)) {
                other.server.enqueue(new MockResponse.Builder().body(prepared(other.endpoint().toString())).build());
                world.server.enqueue(new MockResponse.Builder().code(code).addHeader("Location", other.server.url("/steal")).build());
                var error = assertThrows(WorkerException.class, () -> client.prepare("group", WorkerTransportType.WEBSOCKET,
                        Map.of("clientWorkerKey", "one"), TIMEOUT));
                assertFalse(error.toString().contains("private-test-secret"));
                assertEquals(0, other.server.getRequestCount());
            }
            client.close();
        }
    }

    @Test
    public void eachPhysicalWssConnectionIncludesTheCredential() throws Exception {
        try (TlsWorld world = new TlsWorld()) {
            HandlerThread network = new HandlerThread("gateway-test");
            network.start();
            world.server.enqueue(upgrade(true));
            world.server.enqueue(upgrade(false));
            CountDownLatch opened = new CountDownLatch(2);
            AndroidOkHttpTextWebSocketClient client = new AndroidOkHttpTextWebSocketClient(world.http, network.getLooper(),
                    world.endpoint(), TextMessageReconnectPolicy.of(3, Duration.ofMillis(20), Duration.ofSeconds(1)),
                    world.runtime(), CREDENTIAL);
            try {
                client.start(listener(opened, new CountDownLatch(1)));
                await(opened);
                for (int index = 0; index < 2; index++) {
                    var request = world.server.takeRequest(3, TimeUnit.SECONDS);
                    assertNotNull(request);
                    assertEquals(CREDENTIAL.authorizationHeader(), request.getHeaders().get("Authorization"));
                }
            } finally {
                client.close();
                network.quitSafely();
                network.join(3000);
            }
        }
    }

    @Test
    public void wssRejectsForeignOriginsAndDoesNotFollowRedirects() throws Exception {
        try (TlsWorld world = new TlsWorld(); TlsWorld other = new TlsWorld(world.certificate)) {
            HandlerThread network = new HandlerThread("gateway-test");
            network.start();
            CountDownLatch ended = new CountDownLatch(1);
            other.server.enqueue(upgrade(false));
            world.server.enqueue(new MockResponse.Builder().code(307).addHeader("Location", other.server.url("/steal")).build());
            AndroidOkHttpTextWebSocketClient client = new AndroidOkHttpTextWebSocketClient(world.http, network.getLooper(),
                    world.endpoint(), TextMessageReconnectPolicy.of(1, Duration.ofMillis(20), Duration.ofSeconds(1)),
                    world.runtime(), CREDENTIAL);
            try {
                assertThrows(IllegalArgumentException.class, () -> new AndroidOkHttpTextWebSocketClient(world.http, network.getLooper(),
                        URI.create("wss://other.example/socket"), TextMessageReconnectPolicy.defaults(), world.runtime(), CREDENTIAL));
                client.start(listener(new CountDownLatch(1), ended));
                await(ended);
                assertEquals(0, other.server.getRequestCount());
            } finally {
                client.close();
                network.quitSafely();
                network.join(3000);
            }
        }
    }

    private static void await(CountDownLatch latch) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (latch.getCount() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
            ShadowSystemClock.advanceBy(Duration.ofMillis(20));
        }
        assertEquals(0, latch.getCount());
    }

    private static String prepared(String endpoint) {
        return "{\"workerId\":\"worker\",\"transportType\":\"WEBSOCKET\",\"endpointUri\":\"" + endpoint + "\"}";
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
        @Override public void close() throws Exception {
            http.connectionPool().evictAll();
            http.dispatcher().executorService().shutdownNow();
            server.close();
        }
    }
}
