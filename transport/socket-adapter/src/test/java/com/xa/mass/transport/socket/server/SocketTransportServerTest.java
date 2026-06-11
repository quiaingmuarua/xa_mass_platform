package com.xa.mass.transport.socket.server;

import com.xa.mass.base.runtime.VirtualThreadRuntimeTaskExecutor;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.model.TaskResultReport;
import com.xa.mass.transport.model.TransportResultEnvelope;
import com.xa.mass.transport.socket.protocol.SocketTransportFrameCodec;
import com.xa.mass.transport.socket.session.SocketSessionManager;
import com.xa.mass.transport.socket.worker.SocketRealtimeWorkerAdapter;
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
                new SocketSessionManager(SocketRealtimeWorkerAdapter.DEFAULT_ADAPTER_ID),
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
                new SocketSessionManager(SocketRealtimeWorkerAdapter.DEFAULT_ADAPTER_ID);
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
                writer.write("{\"type\":\"hello\",\"workerId\":\"worker-1\",\"routeKey\":\"socket-route-9\"}");
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
                new SocketSessionManager(SocketRealtimeWorkerAdapter.DEFAULT_ADAPTER_ID);
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
                writer.write("{\"type\":\"hello\",\"workerId\":\"worker-1\",\"routeKey\":\"socket-route-9\"}");
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
    void canonicalTaskResultIngressUsesBoundRouteKeyAndMessageIdTraceFallback() throws Exception {
        VirtualThreadRuntimeTaskExecutor executor = new VirtualThreadRuntimeTaskExecutor("socket-test-", 4);
        SocketSessionManager sessionManager =
                new SocketSessionManager(SocketRealtimeWorkerAdapter.DEFAULT_ADAPTER_ID);
        AtomicReference<TransportResultEnvelope> capturedEnvelope = new AtomicReference<>();
        SocketTransportServer server = new SocketTransportServer(
                "socket",
                "127.0.0.1",
                0,
                10,
                sessionManager,
                new SocketTransportFrameCodec(),
                new TaskResultIngestChannel() {
                    @Override
                    public boolean ingest(TaskResultReport report) {
                        return true;
                    }

                    @Override
                    public boolean ingest(TransportResultEnvelope envelope) {
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
                writer.write("{\"type\":\"hello\",\"workerId\":\"worker-1\",\"routeKey\":\"socket-route-9\"}");
                writer.newLine();
                writer.write("""
                        {"messageId":"msg-1","taskId":"task-1","success":true,"detail":"ok","output":{"status":"SUCCESS"}}
                        """.trim());
                writer.newLine();
                writer.flush();

                waitUntil(() -> capturedEnvelope.get() != null,
                        "canonical socket result should be ingested");
                assertEquals("socket", capturedEnvelope.get().getAdapterId());
                assertEquals("socket-route-9", capturedEnvelope.get().getRouteKey());
                assertEquals("msg-1", capturedEnvelope.get().getTraceId());
                assertEquals("task-1", capturedEnvelope.get().getTaskId());
                assertEquals("msg-1", capturedEnvelope.get().getMessageId());
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
                new SocketSessionManager(SocketRealtimeWorkerAdapter.DEFAULT_ADAPTER_ID),
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
