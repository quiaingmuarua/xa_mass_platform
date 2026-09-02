package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.netty.internal.remote.DeliveryReportRemoteApi;
import com.xa.mass.workerdelivery.adapter.netty.internal.remote.WorkerDeliveryHttpClient;
import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer;
import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer.Response;
import com.xa.mass.workerdelivery.json.Jsons;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BatchDispatcherHttpTest {

    @Test
    void stopInterruptsSynchronousHttpWithoutReprocessingTheBatch()
            throws Exception {
        try (BlockingReportPeer peer = new BlockingReportPeer()) {
            DeliveryReportProcess processor = new DeliveryReportProcess(
                    new DeliveryReportRemoteApi(
                            new WorkerDeliveryHttpClient(
                                    peer.server.baseUri(),
                                    Duration.ofSeconds(2)
                            )
                    ),
                    "adapter-1"
            );
            BatchDispatcher<String> dispatcher = BatchDispatcher.queued(
                    "adapter-1",
                    "delivery-report",
                    4,
                    100,
                    Duration.ofMillis(10),
                    processor
            );
            dispatcher.tryDispatch(List.of("in-flight"));
            dispatcher.start();
            assertThat(peer.requestStarted.await(
                    2,
                    TimeUnit.SECONDS
            )).isTrue();

            dispatcher.stopIngress();
            dispatcher.stop();
            dispatcher.thread().join(2_000);

            assertThat(dispatcher.isAlive()).isFalse();
            assertThat(peer.attempts).hasValue(1);
            Thread.sleep(100);
            assertThat(peer.attempts).hasValue(1);
        }
    }

    private static final class BlockingReportPeer implements AutoCloseable {

        private final CountDownLatch requestStarted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger attempts = new AtomicInteger();
        private final ScriptedHttpServer server = new ScriptedHttpServer(
                this::handle
        );

        private Response handle(ScriptedHttpServer.Request request)
                throws InterruptedException {
            attempts.incrementAndGet();
            requestStarted.countDown();
            release.await();
            return new Response(202, Jsons.toJson(Map.of(
                    "acceptedCount", 1,
                    "rejectedCount", 0
            )));
        }

        @Override
        public void close() {
            release.countDown();
            server.close();
        }
    }
}
