package com.xa.mass.transport.socket.server;

import com.xa.mass.base.runtime.VirtualThreadRuntimeTaskExecutor;
import com.xa.mass.transport.channel.TransportResultIngressChannel;
import com.xa.mass.transport.model.TransportResultIngressEnvelope;
import com.xa.mass.transport.socket.protocol.SocketTransportFrameCodec;
import com.xa.mass.transport.socket.runtime.SocketAdapterConfig;
import com.xa.mass.transport.socket.session.SocketSessionManager;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocketTransportServerTest {

    @Test
    void startAndClientHandlingUseRuntimeExecutor() throws Exception {
        VirtualThreadRuntimeTaskExecutor executor = new VirtualThreadRuntimeTaskExecutor("socket-test-", 4);
        SocketTransportServer server = new SocketTransportServer(
                "socket",
                "127.0.0.1",
                0,
                10,
                new SocketSessionManager(SocketAdapterConfig.DEFAULT_ADAPTER_ID, SocketAdapterConfig.DEFAULT_ADAPTER_ID),
                new SocketTransportFrameCodec(),
                null,
                executor
        );

        try {
            server.start();
            assertTrue(server.isRunning());

            int port = Integer.parseInt(System.getProperty(SocketTransportServer.BOUND_PORT_PROPERTY));
            try (Socket ignored = new Socket("127.0.0.1", port)) {
                waitUntil(() -> executor.getStatistics().getSubmittedTasks() >= 2,
                        "accept and client loops should be submitted to runtime executor");
            }
        } finally {
            server.stop();
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            System.clearProperty(SocketTransportServer.BOUND_PORT_PROPERTY);
        }

        assertFalse(server.isRunning());
        assertNull(System.getProperty(SocketTransportServer.BOUND_PORT_PROPERTY));
    }

    @Test
    void helloFrameRegistersSocketSession() throws Exception {
        VirtualThreadRuntimeTaskExecutor executor = new VirtualThreadRuntimeTaskExecutor("socket-test-", 4);
        SocketSessionManager sessionManager =
                new SocketSessionManager(SocketAdapterConfig.DEFAULT_ADAPTER_ID, SocketAdapterConfig.DEFAULT_ADAPTER_ID);
        SocketTransportServer server = new SocketTransportServer(
                "socket",
                "127.0.0.1",
                0,
                10,
                sessionManager,
                new SocketTransportFrameCodec(),
                null,
                executor
        );

        try {
            server.start();
            int port = Integer.parseInt(System.getProperty(SocketTransportServer.BOUND_PORT_PROPERTY));
            try (Socket socket = new Socket("127.0.0.1", port);
                 BufferedWriter writer = new BufferedWriter(
                         new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                writer.write("{\"type\":\"hello\",\"workerId\":\"worker-1\",\"workerGroupId\":\"bucket-1\",\"routeKey\":\"socket-route-9\"}");
                writer.newLine();
                writer.flush();

                waitUntil(() -> sessionManager.isAdapterRouteOnline("socket", "socket-route-9"),
                        "hello frame should register worker socket session");
            }
        } finally {
            server.stop();
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            System.clearProperty(SocketTransportServer.BOUND_PORT_PROPERTY);
        }
    }

    @Test
    void helloFrameRouteKeyOverridesWorkerIdAsSocketAddress() throws Exception {
        VirtualThreadRuntimeTaskExecutor executor = new VirtualThreadRuntimeTaskExecutor("socket-test-", 4);
        SocketSessionManager sessionManager =
                new SocketSessionManager(SocketAdapterConfig.DEFAULT_ADAPTER_ID, SocketAdapterConfig.DEFAULT_ADAPTER_ID);
        SocketTransportServer server = new SocketTransportServer(
                "socket",
                "127.0.0.1",
                0,
                10,
                sessionManager,
                new SocketTransportFrameCodec(),
                null,
                executor
        );

        try {
            server.start();
            int port = Integer.parseInt(System.getProperty(SocketTransportServer.BOUND_PORT_PROPERTY));
            try (Socket socket = new Socket("127.0.0.1", port);
                 BufferedWriter writer = new BufferedWriter(
                         new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                writer.write("{\"type\":\"hello\",\"workerId\":\"worker-1\",\"workerGroupId\":\"bucket-1\",\"routeKey\":\"socket-route-9\"}");
                writer.newLine();
                writer.flush();

                waitUntil(() -> sessionManager.isAdapterRouteOnline("socket", "socket-route-9"),
                        "hello frame should register socket routeKey independently");
                assertFalse(sessionManager.isAdapterRouteOnline("socket", "worker-1"));
            }
        } finally {
            server.stop();
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            System.clearProperty(SocketTransportServer.BOUND_PORT_PROPERTY);
        }
    }

    @Test
    void canonicalTaskResultIngressUsesBoundRouteKeyAndCorrelationTraceFallback() throws Exception {
        VirtualThreadRuntimeTaskExecutor executor = new VirtualThreadRuntimeTaskExecutor("socket-test-", 4);
        SocketSessionManager sessionManager =
                new SocketSessionManager(SocketAdapterConfig.DEFAULT_ADAPTER_ID, SocketAdapterConfig.DEFAULT_ADAPTER_ID);
        AtomicReference<TransportResultIngressEnvelope> capturedEnvelope = new AtomicReference<>();
        SocketTransportServer server = new SocketTransportServer(
                "socket",
                "127.0.0.1",
                0,
                10,
                sessionManager,
                new SocketTransportFrameCodec(),
                new TransportResultIngressChannel() {
                    @Override
                    public boolean ingest(TransportResultIngressEnvelope envelope) {
                        capturedEnvelope.set(envelope);
                        return true;
                    }
                },
                executor
        );

        try {
            server.start();
            int port = Integer.parseInt(System.getProperty(SocketTransportServer.BOUND_PORT_PROPERTY));
            try (Socket socket = new Socket("127.0.0.1", port);
                 BufferedWriter writer = new BufferedWriter(
                         new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                writer.write("{\"type\":\"hello\",\"workerId\":\"worker-1\",\"workerGroupId\":\"bucket-1\",\"routeKey\":\"socket-route-9\"}");
                writer.newLine();
                writer.write("""
                        {"resultCorrelationRef":"corr-1","success":true,"detail":"ok","output":{"status":"SUCCESS"}}
                        """.trim());
                writer.newLine();
                writer.flush();

                waitUntil(() -> capturedEnvelope.get() != null,
                        "canonical socket result should be ingested");
                assertEquals("socket", capturedEnvelope.get().diagnostic("adapterId"));
                assertEquals("socket-route-9", capturedEnvelope.get().diagnostic("routeKey"));
                assertEquals("corr-1", capturedEnvelope.get().diagnostic("traceId"));
                assertEquals("corr-1", capturedEnvelope.get().getPartitionKey());
                assertTrue(capturedEnvelope.get().getPayload().contains("\"resultCorrelationRef\":\"corr-1\""));
                assertFalse(capturedEnvelope.get().getPayload().contains("\"taskId\""));
                assertFalse(capturedEnvelope.get().getPayload().contains("\"messageId\""));
            }
        } finally {
            server.stop();
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            System.clearProperty(SocketTransportServer.BOUND_PORT_PROPERTY);
        }
    }

    @Test
    void startCleansUpWhenRuntimeExecutorRejectsAcceptLoop() throws Exception {
        VirtualThreadRuntimeTaskExecutor executor = new VirtualThreadRuntimeTaskExecutor("socket-test-", 1);
        executor.shutdown();
        SocketTransportServer server = new SocketTransportServer(
                "socket",
                "127.0.0.1",
                0,
                10,
                new SocketSessionManager(SocketAdapterConfig.DEFAULT_ADAPTER_ID, SocketAdapterConfig.DEFAULT_ADAPTER_ID),
                new SocketTransportFrameCodec(),
                null,
                executor
        );

        try {
            assertThrows(RejectedExecutionException.class, server::start);
            assertFalse(server.isRunning());
            assertNull(System.getProperty(SocketTransportServer.BOUND_PORT_PROPERTY));
        } finally {
            server.stop();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            System.clearProperty(SocketTransportServer.BOUND_PORT_PROPERTY);
        }
    }

    private void waitUntil(BooleanSupplier condition, String failureMessage) throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25L);
        }
        assertTrue(condition.getAsBoolean(), failureMessage);
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
