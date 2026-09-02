package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.benmanes.caffeine.cache.Ticker;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class WorkerRouteRegistryTest {

    private static final Duration RETENTION = Duration.ofMinutes(10);

    @Test
    void firstIdentityClaimIsPerWorkerAtomicAndBusyChannelIsUnclaimed()
            throws Exception {
        WorkerRouteRegistry registry = registry(100);
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<WorkerRouteRegistry.IdentityAdmission> firstResult =
                    executor.submit(() -> {
                        start.await();
                        return registry.admitIdentity("worker-1", first);
                    });
            Future<WorkerRouteRegistry.IdentityAdmission> secondResult =
                    executor.submit(() -> {
                        start.await();
                        return registry.admitIdentity("worker-1", second);
                    });

            start.countDown();
            assertThat(List.of(
                    firstResult.get(5, TimeUnit.SECONDS).kind(),
                    secondResult.get(5, TimeUnit.SECONDS).kind()
            )).containsExactlyInAnyOrder(
                    WorkerRouteRegistry.IdentityAdmissionKind
                            .VERIFICATION_CLAIMED,
                    WorkerRouteRegistry.IdentityAdmissionKind
                            .VERIFICATION_BUSY
            );
            Channel claimed = registry.claimedWorkerId(first) == null
                    ? second
                    : first;
            Channel busy = claimed == first ? second : first;
            assertThat(registry.claimedWorkerId(claimed))
                    .isEqualTo("worker-1");
            assertThat(registry.claimedWorkerId(busy)).isNull();
            assertThat(registry.hasVerificationEvidence("worker-1"))
                    .isFalse();
            assertThat(registry.connectionStates(List.of("worker-1")))
                    .containsExactly(Map.entry(
                            "worker-1",
                            WorkerConnectionState.UNKNOWN
                    ));
        } finally {
            executor.shutdownNow();
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }

    @Test
    void disconnectedReconnectUsesButDoesNotRenewVerificationEvidence() {
        MutableTicker ticker = new MutableTicker();
        WorkerRouteRegistry registry = registry(100, ticker);
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel reconnect = new EmbeddedChannel();
        try {
            verifyAndActivate(registry, "worker-1", first);
            ticker.advance(Duration.ofMinutes(4));
            assertThat(registry.onChannelClosed(first)).isEqualTo("worker-1");

            var admission = registry.admitIdentity("worker-1", reconnect);
            assertThat(admission.kind()).isEqualTo(
                    WorkerRouteRegistry.IdentityAdmissionKind
                            .VERIFIED_ACTIVATED
            );
            assertThat(admission.becameAvailable()).isTrue();
            ticker.advance(Duration.ofMinutes(7));
            assertThat(registry.onChannelClosed(reconnect))
                    .isEqualTo("worker-1");

            assertThat(registry.connectionStates(List.of("worker-1")))
                    .containsExactly(Map.entry(
                            "worker-1",
                            WorkerConnectionState.UNKNOWN
                    ));
        } finally {
            first.finishAndReleaseAll();
            reconnect.finishAndReleaseAll();
        }
    }

    @Test
    void connectedRouteReplacementNeverReverifiesOrRenewsEvidence() {
        MutableTicker ticker = new MutableTicker();
        WorkerRouteRegistry registry = registry(100, ticker);
        EmbeddedChannel current = new EmbeddedChannel();
        EmbeddedChannel replacement = new EmbeddedChannel();
        try {
            verifyAndActivate(registry, "worker-1", current);
            ticker.advance(RETENTION.plusNanos(1));

            var admission = registry.admitIdentity(
                    "worker-1",
                    replacement
            );
            assertThat(admission.kind()).isEqualTo(
                    WorkerRouteRegistry.IdentityAdmissionKind
                            .VERIFIED_ACTIVATED
            );
            assertThat(admission.replacedChannel()).isSameAs(current);
            assertThat(admission.becameAvailable()).isFalse();
            assertThat(registry.activeChannel("worker-1"))
                    .isSameAs(replacement);
            assertThat(registry.hasVerificationEvidence("worker-1"))
                    .isTrue();

            assertThat(registry.onChannelClosed(current)).isNull();
            assertThat(registry.onChannelClosed(replacement))
                    .isEqualTo("worker-1");
            assertThat(registry.connectionStates(List.of("worker-1")))
                    .containsExactly(Map.entry(
                            "worker-1",
                            WorkerConnectionState.UNKNOWN
                    ));
        } finally {
            current.finishAndReleaseAll();
            replacement.finishAndReleaseAll();
        }
    }

    @Test
    void cancelledPendingClaimKeepsCorrelationUntilChannelClose() {
        WorkerRouteRegistry registry = registry(100);
        EmbeddedChannel pending = new EmbeddedChannel();
        try {
            registry.admitIdentity("worker-1", pending);

            assertThat(registry.cancelVerification("worker-1", pending))
                    .isTrue();
            assertThat(registry.claimedWorkerId(pending))
                    .isEqualTo("worker-1");
            assertThat(registry.hasVerificationEvidence("worker-1"))
                    .isFalse();
            assertThat(registry.connectionStates(List.of("worker-1")))
                    .containsExactly(Map.entry(
                            "worker-1",
                            WorkerConnectionState.UNKNOWN
                    ));

            assertThat(registry.onChannelClosed(pending)).isNull();
            assertThat(registry.claimedWorkerId(pending)).isNull();
        } finally {
            pending.finishAndReleaseAll();
        }
    }

    @Test
    void connectedAndPendingRoutesAreNotCapacityOrTimeEvicted() {
        MutableTicker ticker = new MutableTicker();
        WorkerRouteRegistry registry = registry(1, ticker);
        EmbeddedChannel connected = new EmbeddedChannel();
        EmbeddedChannel pending = new EmbeddedChannel();
        try {
            verifyAndActivate(registry, "connected", connected);
            registry.admitIdentity("pending", pending);
            ticker.advance(Duration.ofDays(1));
            for (int index = 0; index < 20; index++) {
                EmbeddedChannel temporary = new EmbeddedChannel();
                verifyAndActivate(registry, "cached-" + index, temporary);
                registry.onChannelClosed(temporary);
                temporary.finishAndReleaseAll();
            }

            assertThat(registry.connectionStates(List.of(
                    "connected",
                    "pending"
            ))).containsExactly(
                    Map.entry(
                            "connected",
                            WorkerConnectionState.CONNECTED
                    ),
                    Map.entry("pending", WorkerConnectionState.UNKNOWN)
            );
            assertThat(registry.claimedWorkerId(pending))
                    .isEqualTo("pending");
        } finally {
            connected.finishAndReleaseAll();
            pending.finishAndReleaseAll();
        }
    }

    @Test
    void disconnectedRoutesAreBoundedAndExpire() {
        MutableTicker ticker = new MutableTicker();
        WorkerRouteRegistry registry = registry(1, ticker);
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        try {
            verifyAndActivate(registry, "worker-1", first);
            registry.onChannelClosed(first);
            verifyAndActivate(registry, "worker-2", second);
            registry.onChannelClosed(second);

            long retained = registry.connectionStates(List.of(
                    "worker-1",
                    "worker-2"
            )).values().stream()
                    .filter(state -> state
                            == WorkerConnectionState.DISCONNECTED)
                    .count();
            assertThat(retained).isLessThanOrEqualTo(1L);

            ticker.advance(RETENTION.plusNanos(1));
            assertThat(registry.connectionStates(List.of(
                    "worker-1",
                    "worker-2"
            )).values()).containsOnly(WorkerConnectionState.UNKNOWN);
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }

    @Test
    void staleChannelCallbacksCannotChangeReplacementRoute() {
        WorkerRouteRegistry registry = registry(100);
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel replacement = new EmbeddedChannel();
        try {
            verifyAndActivate(registry, "worker-1", first);
            registry.admitIdentity("worker-1", replacement);

            assertThat(registry.onChannelClosed(first)).isNull();
            assertThat(registry.deactivate("worker-1", first)).isFalse();
            assertThat(registry.activeChannel("worker-1"))
                    .isSameAs(replacement);
            assertThat(registry.claimedWorkerId(first)).isNull();
            assertThat(registry.claimedWorkerId(replacement))
                    .isEqualTo("worker-1");
            assertThat(registry.connectionStates(List.of(
                    "worker-1",
                    "unknown"
            ))).containsExactly(
                    Map.entry(
                            "worker-1",
                            WorkerConnectionState.CONNECTED
                    ),
                    Map.entry("unknown", WorkerConnectionState.UNKNOWN)
            );

            registry.clear();
            assertThat(registry.connectionStates(List.of("worker-1")))
                    .containsExactly(Map.entry(
                            "worker-1",
                            WorkerConnectionState.UNKNOWN
                    ));
        } finally {
            first.finishAndReleaseAll();
            replacement.finishAndReleaseAll();
        }
    }

    @Test
    void snapshotsRequireOneToOneHundredUniqueWorkerIds() {
        WorkerRouteRegistry registry = registry(100);
        List<String> tooMany = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            tooMany.add("worker-" + index);
        }

        assertThatThrownBy(() -> registry.connectionStates(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.connectionStates(List.of(
                "worker-1",
                "worker-1"
        ))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.connectionStates(tooMany))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static WorkerRouteRegistry registry(long capacity) {
        return registry(capacity, new MutableTicker());
    }

    private static WorkerRouteRegistry registry(
            long capacity,
            Ticker ticker
    ) {
        return new WorkerRouteRegistry(
                RETENTION,
                capacity,
                ticker
        );
    }

    private static void verifyAndActivate(
            WorkerRouteRegistry registry,
            String workerId,
            Channel channel
    ) {
        assertThat(registry.admitIdentity(workerId, channel).kind())
                .isEqualTo(
                        WorkerRouteRegistry.IdentityAdmissionKind
                                .VERIFICATION_CLAIMED
                );
        assertThat(registry.completeVerification(workerId, channel)).isTrue();
    }

    private static final class MutableTicker implements Ticker {

        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long read() {
            return nanos.get();
        }

        private void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }
}
