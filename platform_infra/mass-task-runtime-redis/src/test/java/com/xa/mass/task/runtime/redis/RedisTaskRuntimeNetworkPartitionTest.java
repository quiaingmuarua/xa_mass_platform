package com.xa.mass.task.runtime.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.task.runtime.AppendAdmissionPolicy;
import com.xa.mass.task.runtime.AppendBatchCommand;
import com.xa.mass.task.runtime.AppendBatchStatus;
import com.xa.mass.task.runtime.AppendItemInput;
import com.xa.mass.task.runtime.ClaimLeasePolicy;
import com.xa.mass.task.runtime.ClaimReadyCommand;
import com.xa.mass.task.runtime.FinalResultReadRequest;
import com.xa.mass.task.runtime.MessageFinalityStatus;
import com.xa.mass.task.runtime.PollActiveLeaseRepairCommand;
import com.xa.mass.task.runtime.ResultApplyCommand;
import com.xa.mass.task.runtime.ResultApplySource;
import com.xa.mass.task.runtime.ResultFinalityPolicySnapshot;
import com.xa.mass.task.runtime.RetryMode;
import com.xa.mass.task.runtime.RetryPolicySnapshot;
import com.xa.mass.task.runtime.RuntimeEpoch;
import com.xa.mass.task.runtime.RuntimeGate;
import com.xa.mass.task.runtime.SchedulerDiscoveryCommand;
import com.xa.mass.task.runtime.SchedulerEligibilityPolicy;
import com.xa.mass.task.runtime.UpdateSchedulerEligibilityCommand;
import com.xa.mass.task.runtime.WorkerReservationEvidence;
import io.lettuce.core.RedisClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RedisTaskRuntimeNetworkPartitionTest {

    private RedisClient redisClient;
    private String redisUri;
    private String namespace;
    private RedisTaskRuntime runtime;
    private RedisTcpProxy proxy;

    @AfterEach
    void cleanup() {
        closeRuntimeAndClient();
        closeProxy();
        if (namespace != null) {
            RedisTaskRuntimeTestSupport.cleanupNamespace(redisUri, namespace);
            namespace = null;
        }
    }

    @Test
    void activeLeaseCanBeRepairedAfterRedisNetworkPartition() throws Exception {
        redisUri = RedisTaskRuntimeTestSupport.redisUri();
        namespace = RedisTaskRuntimeTestSupport.namespace("network-partition");
        RedisTaskRuntimeTestSupport.createClientOrSkip("task runtime redis network partition test").shutdown();
        var redisAddress = RedisAddress.from(redisUri);
        proxy = RedisTcpProxy.start(redisAddress.host(), redisAddress.port(), 0);
        var proxiedRedisUri = redisAddress.withPort(proxy.port());
        var clock = new AtomicLong(0L);
        redisClient = RedisClient.create(proxiedRedisUri);
        runtime = new RedisTaskRuntime(redisClient, namespace, clock::get);
        var epoch = RuntimeEpoch.of("task-network-partition", 1L);

        runtime.updateTaskEligibility(new UpdateSchedulerEligibilityCommand(
                "task-network-partition",
                new SchedulerEligibilityPolicy(RuntimeGate.OPEN, "default", 0L, 0L, 0L, 0L),
                epoch));
        var appended = runtime.appendBatch(new AppendBatchCommand(
                "task-network-partition",
                List.of(new AppendItemInput("message-1", "demo.dispatch", Map.of("value", 1), null)),
                new AppendAdmissionPolicy(10, AppendAdmissionPolicy.UNLIMITED_READY_BACKLOG),
                epoch));
        assertThat(appended.status()).isEqualTo(AppendBatchStatus.ALL_ACCEPTED);

        var firstClaim = runtime.claimReady(new ClaimReadyCommand(
                "task-network-partition",
                List.of(new WorkerReservationEvidence("worker-before-partition", "group-1", "reservation-1", "target-1")),
                new ClaimLeasePolicy(1, 1_000L, 1L, epoch)));
        assertThat(firstClaim.claimedItems()).hasSize(1);
        assertThat(proxy.acceptedConnections()).isPositive();
        var firstItem = firstClaim.claimedItems().getFirst();
        assertThat(firstItem.attemptNo()).isEqualTo(1);

        int proxyPort = proxy.port();
        closeProxy();
        closeRuntimeAndClient();
        proxy = RedisTcpProxy.start(redisAddress.host(), redisAddress.port(), proxyPort);
        redisClient = RedisClient.create(proxiedRedisUri);
        runtime = new RedisTaskRuntime(redisClient, namespace, clock::get);
        clock.set(1_001L);

        var expired = runtime.pollExpiredActiveLeases(new PollActiveLeaseRepairCommand(10, clock.get()));
        assertThat(expired.candidates())
                .extracting(candidate -> candidate.messageId())
                .containsExactly("message-1");
        var repaired = expired.candidates().getFirst();
        var timeout = runtime.applyResult(new ResultApplyCommand(
                repaired.taskId(),
                repaired.messageId(),
                repaired.leaseToken(),
                repaired.workerId(),
                repaired.attemptNo(),
                ResultApplySource.LEASE_TIMEOUT,
                false,
                Map.of("reason", "network partition timeout"),
                "expired after Redis network partition",
                new RetryPolicySnapshot(RetryMode.FAST_READY, 1, 0L, 1L),
                new ResultFinalityPolicySnapshot(true, true, 86_400_000L),
                epoch,
                clock.get()));
        assertThat(timeout.status()).isEqualTo(MessageFinalityStatus.RETRY_SCHEDULED);

        assertThat(runtime.discoverEligibleTasks(new SchedulerDiscoveryCommand(10, clock.get())).candidates())
                .extracting(candidate -> candidate.taskId())
                .containsExactly("task-network-partition");
        var secondClaim = runtime.claimReady(new ClaimReadyCommand(
                "task-network-partition",
                List.of(new WorkerReservationEvidence("worker-after-partition", "group-1", "reservation-2", "target-2")),
                new ClaimLeasePolicy(1, 1_000L, 1L, epoch)));
        assertThat(secondClaim.claimedItems()).hasSize(1);
        var secondItem = secondClaim.claimedItems().getFirst();
        assertThat(secondItem.attemptNo()).isEqualTo(2);

        var finality = runtime.applyResult(new ResultApplyCommand(
                secondItem.taskId(),
                secondItem.messageId(),
                secondItem.leaseToken(),
                secondItem.workerId(),
                secondItem.attemptNo(),
                ResultApplySource.WORKER_RESULT,
                true,
                Map.of("ok", true),
                "",
                new RetryPolicySnapshot(RetryMode.FAST_READY, 1, 0L, 1L),
                new ResultFinalityPolicySnapshot(true, true, 86_400_000L),
                epoch,
                clock.get() + 1L));
        assertThat(finality.status()).isEqualTo(MessageFinalityStatus.LOGICAL_FINAL);

        var finalRows = runtime.readFinalResults(new FinalResultReadRequest("task-network-partition", 0, 10)).rows();
        assertThat(finalRows).hasSize(1);
        assertThat(finalRows.getFirst().workerId()).isEqualTo("worker-after-partition");
        assertThat(finalRows.getFirst().attemptNo()).isEqualTo(2);
    }

    private void closeRuntimeAndClient() {
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
        if (redisClient != null) {
            redisClient.shutdown();
            redisClient = null;
        }
    }

    private void closeProxy() {
        if (proxy != null) {
            proxy.close();
            proxy = null;
        }
    }

    private record RedisAddress(String sourceUri, String host, int port, String rawUserInfo, String rawPath) {

        private static RedisAddress from(String redisUri) {
            var uri = URI.create(redisUri);
            var port = uri.getPort() > 0 ? uri.getPort() : 6379;
            var path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/0" : uri.getRawPath();
            return new RedisAddress(redisUri, uri.getHost(), port, uri.getRawUserInfo(), path);
        }

        private String withPort(int proxyPort) {
            var userInfo = rawUserInfo == null || rawUserInfo.isBlank() ? "" : rawUserInfo + "@";
            return "redis://" + userInfo + "127.0.0.1:" + proxyPort + rawPath;
        }
    }

    private static final class RedisTcpProxy implements AutoCloseable {

        private final String upstreamHost;
        private final int upstreamPort;
        private final ServerSocket serverSocket;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicInteger acceptedConnections = new AtomicInteger();
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();
        private final ExecutorService executor;

        private RedisTcpProxy(String upstreamHost, int upstreamPort, ServerSocket serverSocket) {
            this.upstreamHost = upstreamHost;
            this.upstreamPort = upstreamPort;
            this.serverSocket = serverSocket;
            this.executor = Executors.newCachedThreadPool(r -> {
                var thread = new Thread(r, "redis-task-runtime-test-proxy-" + UUID.randomUUID());
                thread.setDaemon(true);
                return thread;
            });
            executor.execute(this::acceptLoop);
        }

        private static RedisTcpProxy start(String upstreamHost, int upstreamPort, int listenPort) throws IOException {
            var server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("127.0.0.1", listenPort));
            return new RedisTcpProxy(upstreamHost, upstreamPort, server);
        }

        private int port() {
            return serverSocket.getLocalPort();
        }

        private int acceptedConnections() {
            return acceptedConnections.get();
        }

        private void acceptLoop() {
            while (running.get()) {
                try {
                    var downstream = serverSocket.accept();
                    var upstream = new Socket();
                    upstream.connect(new InetSocketAddress(upstreamHost, upstreamPort), 1_000);
                    sockets.add(downstream);
                    sockets.add(upstream);
                    acceptedConnections.incrementAndGet();
                    executor.execute(() -> pipe(downstream, upstream));
                    executor.execute(() -> pipe(upstream, downstream));
                } catch (SocketException exception) {
                    if (running.get()) {
                        throw new IllegalStateException("Redis test proxy socket failed", exception);
                    }
                    return;
                } catch (IOException exception) {
                    if (running.get()) {
                        throw new IllegalStateException("Redis test proxy accept failed", exception);
                    }
                    return;
                }
            }
        }

        private void pipe(Socket source, Socket target) {
            try (InputStream input = source.getInputStream(); OutputStream output = target.getOutputStream()) {
                var buffer = new byte[8_192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    output.write(buffer, 0, read);
                    output.flush();
                }
            } catch (IOException ignored) {
                // The test closes both sides deliberately to simulate a network partition.
            } finally {
                closeSocket(source);
                closeSocket(target);
            }
        }

        @Override
        public void close() {
            if (!running.compareAndSet(true, false)) {
                return;
            }
            closeSocket(serverSocket);
            for (var socket : sockets) {
                closeSocket(socket);
            }
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }

        private static void closeSocket(ServerSocket socket) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }

        private static void closeSocket(Socket socket) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
