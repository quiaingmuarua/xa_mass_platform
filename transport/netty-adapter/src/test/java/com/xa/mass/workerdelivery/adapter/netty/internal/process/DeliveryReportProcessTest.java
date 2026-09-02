package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import static com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryReportProcess.ReportIngressStatus.ACCEPTED;
import static com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryReportProcess.ReportIngressStatus.CLOSED;
import static com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryReportProcess.ReportIngressStatus.FULL;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.netty.internal.remote.DeliveryReportRemoteApi;
import com.xa.mass.workerdelivery.adapter.netty.internal.remote.WorkerDeliveryHttpClient;
import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer;
import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer.Response;
import com.xa.mass.workerdelivery.json.Jsons;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class DeliveryReportProcessTest {

    @Test
    void emptyQueueBlocksWithoutRemoteCallsAndIngressWakesImmediately()
            throws Exception {
        try (ReportPeer peer = new ReportPeer()) {
            DeliveryReportProcess process = process(
                    peer,
                    10,
                    Duration.ofSeconds(1)
            );
            Thread loop = start(process);
            try {
                Thread.sleep(100);
                assertThat(peer.attempts).isEmpty();

                assertThat(process.ingress(List.of("report-1")))
                        .isEqualTo(ACCEPTED);
                awaitAttempts(peer, 1);
                assertThat(peer.attempts)
                        .containsExactly(List.of("report-1"));
            } finally {
                stop(process, loop);
            }
        }
    }

    @Test
    void residentLoopContinuouslyDrainsBeyondThePreviousBatchCap()
            throws Exception {
        try (ReportPeer peer = new ReportPeer()) {
            DeliveryReportProcess process = process(
                    peer,
                    1_000,
                    Duration.ofSeconds(1)
            );
            assertThat(process.ingress(reports(999))).isEqualTo(ACCEPTED);
            assertThat(process.ingress(reports(101))).isEqualTo(ACCEPTED);

            Thread loop = start(process);
            try {
                awaitAttempts(peer, 11);
                assertThat(peer.attempts)
                        .extracting(List::size)
                        .containsExactly(
                                100, 100, 100, 100, 100,
                                100, 100, 100, 100, 100,
                                100
                        );
            } finally {
                stop(process, loop);
            }
        }
    }

    @Test
    void remoteBatchLimitDoesNotShrinkWithSoftQueueCapacity()
            throws Exception {
        try (ReportPeer peer = new ReportPeer()) {
            DeliveryReportProcess process = process(
                    peer,
                    2,
                    Duration.ofSeconds(1)
            );
            assertThat(process.ingress(List.of("one"))).isEqualTo(ACCEPTED);
            assertThat(process.ingress(List.of("two", "three")))
                    .isEqualTo(ACCEPTED);

            Thread loop = start(process);
            try {
                awaitAttempts(peer, 1);
                assertThat(peer.attempts).containsExactly(
                        List.of("one", "two", "three")
                );
            } finally {
                stop(process, loop);
            }
        }
    }

    @Test
    void unavailableBatchBacksOffAndRetriesBeforeLaterReports()
            throws Exception {
        try (ReportPeer peer = new ReportPeer()) {
            peer.responses.add(new Response(503, "{}"));
            DeliveryReportProcess process = process(
                    peer,
                    4,
                    Duration.ofMillis(300)
            );
            Thread loop = start(process);
            try {
                process.ingress(List.of("report-1"));
                awaitAttempts(peer, 1);
                process.ingress(List.of("report-2"));

                Thread.sleep(100);
                assertThat(peer.attempts).hasSize(1);

                awaitAttempts(peer, 3);
                assertThat(peer.attempts).containsExactly(
                        List.of("report-1"),
                        List.of("report-1"),
                        List.of("report-2")
                );
            } finally {
                stop(process, loop);
            }
        }
    }

    @Test
    void protocolFailureDropsOnlyItsBatchAndContinues()
            throws Exception {
        try (ReportPeer peer = new ReportPeer()) {
            peer.responses.add(new Response(400, "{}"));
            DeliveryReportProcess process = process(
                    peer,
                    4,
                    Duration.ofMillis(300)
            );
            Thread loop = start(process);
            try {
                process.ingress(List.of("bad-report"));
                awaitAttempts(peer, 1);
                process.ingress(List.of("next-report"));

                Thread.sleep(100);
                assertThat(peer.attempts).hasSize(1);
                awaitAttempts(peer, 2);

                assertThat(peer.attempts).containsExactly(
                        List.of("bad-report"),
                        List.of("next-report")
                );
            } finally {
                stop(process, loop);
            }
        }
    }

    @Test
    void malformedAcceptedResponseDropsOnlyItsBatch()
            throws Exception {
        try (ReportPeer peer = new ReportPeer()) {
            peer.responses.add(new Response(
                    202,
                    "{\"acceptedCount\":0,\"rejectedCount\":0}"
            ));
            DeliveryReportProcess process = process(
                    peer,
                    4,
                    Duration.ofSeconds(1)
            );
            Thread loop = start(process);
            try {
                process.ingress(List.of("bad-accounting"));
                awaitAttempts(peer, 1);
                process.ingress(List.of("next-report"));
                awaitAttempts(peer, 2);

                assertThat(peer.attempts).containsExactly(
                        List.of("bad-accounting"),
                        List.of("next-report")
                );
            } finally {
                stop(process, loop);
            }
        }
    }

    @Test
    void ingressHidesQueueStorageAndAppliesSoftCapacity() {
        try (ReportPeer peer = new ReportPeer()) {
            DeliveryReportProcess process = process(
                    peer,
                    2,
                    Duration.ofSeconds(1)
            );

            assertThat(process.ingress(List.of("one", "two")))
                    .isEqualTo(ACCEPTED);
            assertThat(process.ingress(List.of("three"))).isEqualTo(FULL);
        }
    }

    @Test
    void interruptedHttpDropsPendingWithoutShutdownRetry()
            throws Exception {
        try (BlockingReportPeer peer = new BlockingReportPeer()) {
            DeliveryReportProcess process = new DeliveryReportProcess(
                    new DeliveryReportRemoteApi(new WorkerDeliveryHttpClient(
                            peer.server.baseUri(),
                            Duration.ofSeconds(2)
                    )),
                    "adapter-1",
                    4,
                    Duration.ofSeconds(1)
            );
            process.ingress(List.of("in-flight"));
            Thread loop = start(process);
            assertThat(peer.firstStarted.await(
                    2,
                    TimeUnit.SECONDS
            )).isTrue();

            process.stop();
            loop.interrupt();
            loop.join(2_000);
            assertThat(loop.isAlive()).isFalse();

            peer.releaseFirst.countDown();
            Thread.sleep(100);
            assertThat(peer.attempts).containsExactly(List.of("in-flight"));
            assertThat(process.ingress(List.of("late"))).isEqualTo(CLOSED);
        }
    }

    @Test
    void stopRejectsLateReportsAndDropsQueuedReports() throws Exception {
        try (ReportPeer peer = new ReportPeer()) {
            DeliveryReportProcess process = process(
                    peer,
                    2,
                    Duration.ofSeconds(1)
            );
            process.ingress(List.of("report-1", "system-report"));

            process.stop();
            assertThat(process.ingress(List.of("late")))
                    .isEqualTo(CLOSED);
            Thread loop = start(process);
            loop.join(2_000);
            assertThat(loop.isAlive()).isFalse();
            assertThat(peer.attempts).isEmpty();
        }
    }

    private static DeliveryReportProcess process(
            ReportPeer peer,
            int capacity,
            Duration retryBackoff
    ) {
        return new DeliveryReportProcess(
                new DeliveryReportRemoteApi(new WorkerDeliveryHttpClient(
                        peer.server.baseUri(),
                        Duration.ofSeconds(2)
                )),
                "adapter-1",
                capacity,
                retryBackoff
        );
    }

    private static Thread start(DeliveryReportProcess process) {
        Thread thread = new Thread(process::runLoop, "report-loop-test");
        thread.start();
        return thread;
    }

    private static void stop(
            DeliveryReportProcess process,
            Thread loop
    ) throws InterruptedException {
        process.stop();
        loop.interrupt();
        loop.join(2_000);
        assertThat(loop.isAlive()).isFalse();
    }

    private static void awaitAttempts(ReportPeer peer, int expected) {
        awaitAttempts(peer.attempts, expected);
    }

    private static void awaitAttempts(
            List<List<String>> attempts,
            int expected
    ) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (attempts.size() >= expected) {
                assertThat(attempts).hasSize(expected);
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError(
                "Expected " + expected + " report attempts but saw "
                        + attempts.size()
        );
    }

    private static List<String> reports(int count) {
        List<String> reports = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            reports.add("report-" + index);
        }
        return List.copyOf(reports);
    }

    private static final class ReportPeer implements AutoCloseable {

        private final ArrayDeque<Response> responses = new ArrayDeque<>();
        private final List<List<String>> attempts =
                new CopyOnWriteArrayList<>();
        private final ScriptedHttpServer server = new ScriptedHttpServer(
                this::handle
        );

        private synchronized Response handle(
                ScriptedHttpServer.Request request
        ) {
            assertThat(request.rawPath()).endsWith("/results:append");
            List<String> results = decode(request.body());
            attempts.add(results);
            Response scripted = responses.pollFirst();
            if (scripted != null) {
                return scripted;
            }
            return accepted(results.size());
        }

        @Override
        public void close() {
            server.close();
        }
    }

    private static final class BlockingReportPeer implements AutoCloseable {

        private final CountDownLatch firstStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private final AtomicBoolean first = new AtomicBoolean(true);
        private final List<List<String>> attempts =
                new CopyOnWriteArrayList<>();
        private final ScriptedHttpServer server = new ScriptedHttpServer(
                this::handle
        );

        private Response handle(ScriptedHttpServer.Request request)
                throws InterruptedException {
            List<String> results = decode(request.body());
            attempts.add(results);
            if (first.compareAndSet(true, false)) {
                firstStarted.countDown();
                releaseFirst.await();
            }
            return accepted(results.size());
        }

        @Override
        public void close() {
            releaseFirst.countDown();
            server.close();
        }
    }

    private static List<String> decode(String body) {
        return Jsons.parseArray(body)
                .stream()
                .map(String.class::cast)
                .toList();
    }

    private static Response accepted(int count) {
        return new Response(202, Jsons.toJson(Map.of(
                "acceptedCount", count,
                "rejectedCount", 0
        )));
    }
}
