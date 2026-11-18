package com.xa.mass.worker.javase;

import static org.junit.jupiter.api.Assertions.*;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.transport.client.TextMessageReconnectPolicy;
import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.runtime.WorkerGatewayCredential;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.SocketFactory;
import jdk.net.ExtendedSocketOptions;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.Test;

class JavaWorkerTcpKeepAliveTest {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

    @Test
    void websocketClientsShareTheFactoryWithoutChangingControlHttpOrReadDeadlines() throws Exception {
        try (var platform = JavaWorkerPlatform.create(2, "test-keepalive", true)) {
            var first = platform.textClient(WorkerTransportType.WEBSOCKET, URI.create("ws://localhost/one"),
                    REQUEST_TIMEOUT, TextMessageReconnectPolicy.defaults());
            var second = platform.textClient(WorkerTransportType.WEBSOCKET, URI.create("ws://localhost/two"),
                    REQUEST_TIMEOUT, TextMessageReconnectPolicy.defaults());
            try {
                var base = (OkHttpClient) field(platform, "httpClient");
                var http = socketHttp(first);
                var factory = (SocketFactory) field(platform, "webSocketSocketFactory");
                assertSame(factory, http.socketFactory());
                assertSame(factory, socketHttp(second).socketFactory());
                assertNotSame(base.socketFactory(), factory);
                assertSame(base.socketFactory(), field(factory, "delegate"));
                assertEquals(0, http.pingIntervalMillis());
                assertEquals(0, http.readTimeoutMillis());
                assertEquals(0, http.callTimeoutMillis());
                assertEquals(REQUEST_TIMEOUT.toMillis(), http.connectTimeoutMillis());
                assertEquals(REQUEST_TIMEOUT.toMillis(), http.writeTimeoutMillis());
                assertEquals(0, base.pingIntervalMillis());
                assertEquals(10_000, base.readTimeoutMillis());
            } finally {
                first.close();
                second.close();
            }
        }
    }

    @Test
    void wsAndAuthenticatedWssReconnectThroughTheConfiguredRawSocketFactory() throws Exception {
        for (boolean tls : List.of(false, true)) {
            try (var platform = JavaWorkerPlatform.create(1, "test-keepalive-wire", true);
                 var server = new MockWebServer()) {
                var certificate = new HeldCertificate.Builder().commonName("localhost")
                        .addSubjectAlternativeName("localhost").build();
                if (tls) {
                    var serverTls = new HandshakeCertificates.Builder().heldCertificate(certificate).build();
                    server.useHttps(serverTls.sslSocketFactory());
                }
                server.start();
                server.enqueue(upgrade(true));
                server.enqueue(upgrade(false));
                URI runtime = URI.create(server.url("/").toString());
                URI endpoint = URI.create(server.url("/socket").toString().replaceFirst("^http", "ws"));
                var credential = tls ? WorkerGatewayCredential.of("test-host", "test-only-secret") : null;
                var policy = TextMessageReconnectPolicy.of(3, Duration.ofMillis(20), Duration.ofSeconds(1));
                var original = platform.textClient(WorkerTransportType.WEBSOCKET, endpoint,
                        REQUEST_TIMEOUT, policy, tls ? runtime : null, credential);
                var configured = socketHttp(original);
                original.close();
                var observed = new ObservingSocketFactory(configured.socketFactory());
                var builder = configured.newBuilder().socketFactory(observed);
                if (tls) {
                    var clientTls = new HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate()).build();
                    builder.sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager());
                }
                var client = new JavaOkHttpTextWebSocketClient(builder.build(),
                        (ScheduledExecutorService) field(platform, "networkScheduler"), endpoint, policy,
                        tls ? runtime : null, credential);
                var opened = new CountDownLatch(2);
                var openCount = new AtomicInteger();
                var terminated = new AtomicInteger();
                try {
                    client.start(new TextMessageClient.Listener() {
                        @Override public void onOpen() { openCount.incrementAndGet(); opened.countDown(); }
                        @Override public void onMessage(String message) { }
                        @Override public void onEndpointTerminated() { terminated.incrementAndGet(); }
                    });
                    assertTrue(opened.await(5, TimeUnit.SECONDS));
                    assertEquals(2, openCount.get());
                    assertEquals(0, terminated.get());
                    // Route attempts (including Happy Eyeballs) are not successful upgrades.
                    assertTrue(observed.sockets.size() >= 2);
                    assertTrue(observed.keepAlive.stream().allMatch(Boolean::booleanValue));
                    for (int index = 0; index < 2; index++) {
                        var request = server.takeRequest(3, TimeUnit.SECONDS);
                        assertNotNull(request);
                        assertEquals(tls ? credential.authorizationHeader() : null,
                                request.getHeaders().get("Authorization"));
                    }
                    assertEquals(2, server.getRequestCount());
                    assertTrue(client.send("still-open"));
                } finally {
                    client.close();
                }
            }
        }
    }

    @Test
    void busyReaderSurvivesTheKeepaliveIdleWindowAndCloseDoesNotWaitForItsCallback() throws Exception {
        try (var platform = JavaWorkerPlatform.create(1, "test-keepalive-handler", true);
             var server = new MockWebServer();
             var closing = Executors.newSingleThreadExecutor()) {
            var peerClosed = new CountDownLatch(1);
            server.enqueue(new MockResponse.Builder().webSocketUpgrade(new WebSocketListener() {
                @Override public void onOpen(WebSocket socket, Response response) { socket.send("block-reader"); }
                @Override public void onFailure(WebSocket socket, Throwable error, Response response) { peerClosed.countDown(); }
                @Override public void onClosed(WebSocket socket, int code, String reason) { peerClosed.countDown(); }
                @Override public void onClosing(WebSocket socket, int code, String reason) { socket.close(code, reason); }
            }).build());
            server.start();
            var client = platform.textClient(WorkerTransportType.WEBSOCKET,
                    URI.create(server.url("/socket").toString().replaceFirst("^http", "ws")),
                    REQUEST_TIMEOUT, TextMessageReconnectPolicy.defaults());
            var handling = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var finished = new CountDownLatch(1);
            var terminated = new AtomicInteger();
            try {
                client.start(new TextMessageClient.Listener() {
                    @Override public void onOpen() { }
                    @Override public void onMessage(String message) {
                        handling.countDown();
                        try { release.await(); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                        finally { finished.countDown(); }
                    }
                    @Override public void onEndpointTerminated() { terminated.incrementAndGet(); }
                });
                assertTrue(handling.await(5, TimeUnit.SECONDS));
                assertFalse(peerClosed.await(65, TimeUnit.SECONDS));
                assertEquals(1, server.getRequestCount());
                assertEquals(0, terminated.get());
                closing.submit(client::close).get(1, TimeUnit.SECONDS);
                assertEquals(1, finished.getCount());
            } finally {
                release.countDown();
                client.close();
            }
        }
    }

    private static MockResponse upgrade(boolean reconnect) {
        return new MockResponse.Builder().webSocketUpgrade(new WebSocketListener() {
            @Override public void onOpen(WebSocket socket, Response response) {
                if (reconnect) socket.close(1001, "test reconnect");
            }
            @Override public void onClosing(WebSocket socket, int code, String reason) { socket.close(code, reason); }
        }).build();
    }

    private static OkHttpClient socketHttp(TextMessageClient client) throws Exception {
        Object connector = field(client, "connector");
        for (Field captured : connector.getClass().getDeclaredFields()) {
            if (captured.getType() == OkHttpClient.class) {
                captured.setAccessible(true);
                return (OkHttpClient) captured.get(connector);
            }
        }
        throw new AssertionError("WebSocket connector has no OkHttp client");
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static final class ObservingSocketFactory extends SocketFactory {
        private final SocketFactory delegate;
        final List<Socket> sockets = new CopyOnWriteArrayList<>();
        final List<Boolean> keepAlive = new CopyOnWriteArrayList<>();
        ObservingSocketFactory(SocketFactory delegate) { this.delegate = delegate; }
        private Socket record(Socket socket) throws IOException {
            sockets.add(socket);
            keepAlive.add(socket.getKeepAlive());
            Set<?> options = socket.supportedOptions();
            if (options.containsAll(Set.of(ExtendedSocketOptions.TCP_KEEPIDLE,
                    ExtendedSocketOptions.TCP_KEEPINTERVAL, ExtendedSocketOptions.TCP_KEEPCOUNT))) {
                assertEquals(60, socket.getOption(ExtendedSocketOptions.TCP_KEEPIDLE));
                assertEquals(10, socket.getOption(ExtendedSocketOptions.TCP_KEEPINTERVAL));
                assertEquals(3, socket.getOption(ExtendedSocketOptions.TCP_KEEPCOUNT));
            }
            return socket;
        }
        @Override public Socket createSocket() throws IOException { return record(delegate.createSocket()); }
        @Override public Socket createSocket(String host, int port) throws IOException { return record(delegate.createSocket(host, port)); }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
            return record(delegate.createSocket(host, port, local, localPort));
        }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException { return record(delegate.createSocket(host, port)); }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException {
            return record(delegate.createSocket(host, port, local, localPort));
        }
    }
}
