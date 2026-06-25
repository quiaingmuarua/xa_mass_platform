package com.xa.mass.transport.socket.server;

import com.xa.mass.base.runtime.VirtualThreadRuntimeTaskExecutor;
import com.xa.mass.contract.worker.WorkerChannelFrame;
import com.xa.mass.contract.worker.WorkerChannelFrameJsonCodec;
import com.xa.mass.transport.channel.ResultIngressEntry;
import com.xa.mass.transport.runtime.lease.AdapterSessionEvidencePublisher;
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
                sessionManager(),
                new SocketTransportFrameCodec(),
                null,
                executor::submit
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
        SocketSessionManager sessionManager = sessionManager();
        SocketTransportServer server = new SocketTransportServer(
                "socket",
                "127.0.0.1",
                0,
                10,
                sessionManager,
                new SocketTransportFrameCodec(),
                null,
                executor::submit
        );

        try {
            server.start();
            int port = Integer.parseInt(System.getProperty(SocketTransportServer.BOUND_PORT_PROPERTY));
            try (Socket socket = new Socket("127.0.0.1", port);
                 BufferedWriter writer = new BufferedWriter(
                         new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                writer.write("{\"type\":\"hello\",\"workerId\":\"worker-1\",\"workerGroupId\":\"bucket-1\"}");
                writer.newLine();
                writer.flush();

                waitUntil(() -> sessionManager.hasActiveWorkerSession("worker-1"),
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
    void actionReplyResultIngressUsesWorkerChannelFrame() throws Exception {
        VirtualThreadRuntimeTaskExecutor executor = new VirtualThreadRuntimeTaskExecutor("socket-test-", 4);
        SocketSessionManager sessionManager = sessionManager();
        AtomicReference<ResultIngressEntry> capturedEntry = new AtomicReference<>();
        SocketTransportServer server = new SocketTransportServer(
                "socket",
                "127.0.0.1",
                0,
                10,
                sessionManager,
                new SocketTransportFrameCodec(),
                entry -> {
                    capturedEntry.set(entry);
                    return true;
                },
                executor::submit
        );

        try {
            server.start();
            int port = Integer.parseInt(System.getProperty(SocketTransportServer.BOUND_PORT_PROPERTY));
            try (Socket socket = new Socket("127.0.0.1", port);
                 BufferedWriter writer = new BufferedWriter(
                         new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                writer.write("{\"type\":\"hello\",\"workerId\":\"worker-1\",\"workerGroupId\":\"bucket-1\"}");
                writer.newLine();
                writer.write(new WorkerChannelFrameJsonCodec().encode(new WorkerChannelFrame(
                        "reply-corr-1",
                        WorkerChannelFrame.ACTION_REPLY,
                        "{\"replyRef\":\"corr-1\",\"success\":true,\"body\":\"{\\\"status\\\":\\\"SUCCESS\\\"}\"}"
                )));
                writer.newLine();
                writer.flush();

                waitUntil(() -> capturedEntry.get() != null,
                        "socket ACTION_REPLY result should be ingested");
                assertEquals("corr-1", capturedEntry.get().partitionKey());
                assertEquals("corr-1", capturedEntry.get().message().resultCorrelationRef());
                assertEquals("socket", capturedEntry.get().diagnostics().get("adapterId"));
                assertNull(capturedEntry.get().diagnostics().get("route" + "Key"));
                assertEquals("reply-corr-1", capturedEntry.get().diagnostics().get("traceId"));
                assertTrue(capturedEntry.get().message().payload().contains("\"replyRef\":\"corr-1\""));
                assertFalse(capturedEntry.get().message().payload().contains("\"taskId\""));
                assertFalse(capturedEntry.get().message().payload().contains("\"messageId\""));
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
                sessionManager(),
                new SocketTransportFrameCodec(),
                null,
                executor::submit
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

    private SocketSessionManager sessionManager() {
        return new SocketSessionManager(
                SocketAdapterConfig.DEFAULT_ADAPTER_ID,
                SocketAdapterConfig.DEFAULT_ADAPTER_ID,
                AdapterSessionEvidencePublisher.noop(
                        SocketAdapterConfig.DEFAULT_ADAPTER_ID,
                        SocketAdapterConfig.DEFAULT_ADAPTER_ID
                )
        );
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
