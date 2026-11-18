package com.xa.mass.integration.workercallperformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CallApiTransportDeadlineTest {
    @Test void alreadyCompletedResponseIsRetainedWhenTheCallerObtainsItAfterTheBudget() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://fixture.invalid/complete")).build();
        var response = new CompleteResponse(request);
        var client = new CompletedClient(response);
        assertThat(CallApi.sendWithDeadline(client, request, Duration.ofMillis(100))).isSameAs(response);
        assertThat(client.calls).hasValue(1);
        assertThat(client.response.isCancelled()).isFalse();
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("known-complete-body");
    }

    @Test void queuedClientWorkCannotStartARequestAfterItsExchangeDeadline() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes(); requests.incrementAndGet(); exchange.sendResponseHeaders(200, -1);
            }
        });
        server.start();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new Gate(executor);
            var client = HttpClient.newBuilder().executor(gate).version(HttpClient.Version.HTTP_1_1).build();
            try {
                assertThatThrownBy(() -> CallApi.sendWithDeadline(client, request(server), Duration.ofMillis(100)))
                        .isInstanceOfSatisfying(CallApi.ExchangeTimeout.class, error -> {
                            assertThat(error.phase).isEqualTo(CallApi.TimeoutPhase.BEFORE_HEADERS);
                            assertThat(error.exceptionType).isEqualTo(TimeoutException.class.getSimpleName());
                            assertThat(error.getCause()).isNull();
                        });
                assertThat(requests).hasValue(0);
                gate.release();
                assertThat(CallApi.sendWithDeadline(client, request(server), Duration.ofSeconds(2)).statusCode()).isEqualTo(200);
                assertThat(requests).hasValue(1); // Releasing the queue must not submit the cancelled original request.
            } finally {
                gate.release(); client.shutdownNow();
                assertThat(client.awaitTermination(Duration.ofSeconds(2))).isTrue();
            }
        } finally { server.stop(0); }
    }

    @Test void responseHeadersDoNotRemoveTheCompleteBodyDeadline() throws Exception {
        var requests = new AtomicInteger(); var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try (exchange) {
                    exchange.getRequestBody().readAllBytes(); requests.incrementAndGet();
                    exchange.sendResponseHeaders(200, 1); exchange.getResponseBody().flush();
                    try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                    exchange.getResponseBody().write('x');
                }
            });
            server.start();
            var client = HttpClient.newBuilder().executor(executor).version(HttpClient.Version.HTTP_1_1).build();
            try {
                assertThatThrownBy(() -> CallApi.sendWithDeadline(client, request(server), Duration.ofSeconds(1)))
                        .isInstanceOfSatisfying(CallApi.ExchangeTimeout.class, error -> {
                            assertThat(error.phase).isEqualTo(CallApi.TimeoutPhase.RESPONSE_BODY);
                            assertThat(error.exceptionType).isEqualTo(TimeoutException.class.getSimpleName());
                        });
                assertThat(requests).hasValue(1);
                client.shutdownNow();
                assertThat(client.awaitTermination(Duration.ofSeconds(2))).isTrue();
            } finally { release.countDown(); client.shutdownNow(); server.stop(0); }
        }
    }

    @Test void nativeConnectOrTlsTimeoutKeepsItsSafeSubtype() throws Exception {
        try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            listener.setSoTimeout(2_000);
            var release = new CountDownLatch(1);
            executor.execute(() -> {
                try (var socket = listener.accept()) { release.await(3, TimeUnit.SECONDS); }
                catch (Exception ignored) { /* The fixture closes the pending TLS connection in finally. */ }
            });
            var client = HttpClient.newBuilder().executor(executor).version(HttpClient.Version.HTTP_1_1).build();
            var request = HttpRequest.newBuilder(URI.create("https://localhost:" + listener.getLocalPort()))
                    .timeout(Duration.ofMillis(250)).POST(HttpRequest.BodyPublishers.noBody()).build();
            try {
                assertThatThrownBy(() -> CallApi.sendWithDeadline(client, request, Duration.ofSeconds(2)))
                        .isInstanceOfSatisfying(CallApi.ExchangeTimeout.class, error -> {
                            assertThat(error.phase).isEqualTo(CallApi.TimeoutPhase.CONNECT_TLS);
                            assertThat(error.exceptionType).isEqualTo(HttpConnectTimeoutException.class.getSimpleName());
                            assertThat(error.getCause()).isNull();
                        });
            } finally {
                release.countDown(); listener.close(); client.shutdownNow();
                assertThat(client.awaitTermination(Duration.ofSeconds(2))).isTrue();
            }
        }
    }

    @Test void interruptionCancelsQueuedWorkImmediatelyWithoutAReplyOrRetry() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) { requests.incrementAndGet(); exchange.sendResponseHeaders(200, -1); }
        });
        server.start();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new Gate(executor); var client = HttpClient.newBuilder().executor(gate).build();
            var failure = new CompletableFuture<Throwable>();
            Thread sender = Thread.ofVirtual().start(() -> {
                try { CallApi.sendWithDeadline(client, request(server), Duration.ofSeconds(5)); failure.complete(null); }
                catch (Throwable error) { failure.complete(error); }
            });
            try {
                assertThat(gate.submitted.await(2, TimeUnit.SECONDS)).isTrue();
                sender.interrupt();
                assertThat(failure.get(1, TimeUnit.SECONDS)).isInstanceOf(InterruptedException.class);
                gate.release(); sender.join(1000);
                assertThat(sender.isAlive()).isFalse();
                assertThat(CallApi.sendWithDeadline(client, request(server), Duration.ofSeconds(2)).statusCode()).isEqualTo(200);
                assertThat(requests).hasValue(1);
            } finally {
                gate.release(); sender.interrupt(); client.shutdownNow();
                assertThat(client.awaitTermination(Duration.ofSeconds(2))).isTrue();
            }
        } finally { server.stop(0); }
    }

    private static HttpRequest request(HttpServer server) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofString("fixture")).build();
    }

    private static final class Gate implements Executor {
        final CountDownLatch submitted = new CountDownLatch(1);
        private final Executor delegate;
        private final ArrayList<Runnable> queued = new ArrayList<>();
        private boolean released;
        Gate(Executor delegate) { this.delegate = delegate; }
        @Override public synchronized void execute(Runnable task) {
            if (released) delegate.execute(task); else queued.add(task);
            submitted.countDown();
        }
        synchronized void release() {
            released = true;
            queued.forEach(delegate::execute); queued.clear();
        }
    }

    private record CompleteResponse(HttpRequest request) implements java.net.http.HttpResponse<String> {
        @Override public int statusCode() { return 200; }
        @Override public java.util.Optional<java.net.http.HttpResponse<String>> previousResponse() { return java.util.Optional.empty(); }
        @Override public java.net.http.HttpHeaders headers() { return java.net.http.HttpHeaders.of(java.util.Map.of(), (name, value) -> true); }
        @Override public String body() { return "known-complete-body"; }
        @Override public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return java.util.Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }

    /** No network: the body is complete before sendAsync returns its reference to the delayed caller. */
    private static final class CompletedClient extends HttpClient {
        final AtomicInteger calls = new AtomicInteger();
        final CompletableFuture<java.net.http.HttpResponse<String>> response;
        CompletedClient(java.net.http.HttpResponse<String> response) { this.response = CompletableFuture.completedFuture(response); }
        @Override @SuppressWarnings("unchecked") public <T> CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                HttpRequest request, java.net.http.HttpResponse.BodyHandler<T> handler) {
            calls.incrementAndGet();
            try { Thread.sleep(150); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new java.util.concurrent.CompletionException(interrupted); }
            return (CompletableFuture<java.net.http.HttpResponse<T>>) (CompletableFuture<?>) response;
        }
        @Override public <T> CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(HttpRequest request,
                java.net.http.HttpResponse.BodyHandler<T> handler, java.net.http.HttpResponse.PushPromiseHandler<T> push) {
            throw new UnsupportedOperationException();
        }
        @Override public <T> java.net.http.HttpResponse<T> send(HttpRequest request, java.net.http.HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException();
        }
        @Override public java.util.Optional<java.net.CookieHandler> cookieHandler() { return java.util.Optional.empty(); }
        @Override public java.util.Optional<Duration> connectTimeout() { return java.util.Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public java.util.Optional<java.net.ProxySelector> proxy() { return java.util.Optional.empty(); }
        @Override public javax.net.ssl.SSLContext sslContext() { throw new UnsupportedOperationException(); }
        @Override public javax.net.ssl.SSLParameters sslParameters() { return new javax.net.ssl.SSLParameters(); }
        @Override public java.util.Optional<java.net.Authenticator> authenticator() { return java.util.Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public java.util.Optional<Executor> executor() { return java.util.Optional.empty(); }
    }
}
