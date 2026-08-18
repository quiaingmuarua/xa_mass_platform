package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.benmanes.caffeine.cache.Ticker;
import com.xa.mass.workerdelivery.adapter.netty.NettyWorkerRouteCacheConfig;
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
    void firstIdentityClaimIsPerWorkerAtomic() throws Exception {
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
            Channel verifying = registry.inspectInbound(first).kind()
                    == WorkerRouteRegistry.InboundKind.VERIFICATION_PENDING
                    ? first
                    : second;
            Channel busy = verifying == first ? second : first;
            assertThat(registry.inspectInbound(busy).kind()).isEqualTo(
                    WorkerRouteRegistry.InboundKind.IDENTITY_REQUIRED
            );
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
    void expiredEvidenceReverifiesWithoutRemovingCurrentActiveRoute() {
        MutableTicker ticker = new MutableTicker();
        WorkerRouteRegistry registry = registry(100, ticker);
        EmbeddedChannel active = new EmbeddedChannel();
        EmbeddedChannel verifying = new EmbeddedChannel();
        try {
            verifyAndActivate(registry, "worker-1", active);
            ticker.advance(RETENTION.plusNanos(1));

            var admission = registry.admitIdentity("worker-1", verifying);
            assertThat(admission.kind()).isEqualTo(
                    WorkerRouteRegistry.IdentityAdmissionKind
                            .VERIFICATION_CLAIMED
            );
            assertThat(registry.activeChannel("worker-1")).isSameAs(active);
            assertThat(registry.connectionStates(List.of("worker-1")))
                    .containsExactly(Map.entry(
                            "worker-1",
                            WorkerConnectionState.CONNECTED
                    ));

            var activation = registry.completeVerificationAndActivate(
                    "worker-1",
                    verifying
            );
            assertThat(activation.completed()).isTrue();
            assertThat(activation.replacedChannel()).isSameAs(active);
            assertThat(activation.becameAvailable()).isFalse();
            assertThat(registry.onChannelClosed(active)).isNull();
            assertThat(registry.activeChannel("worker-1"))
                    .isSameAs(verifying);
        } finally {
            active.finishAndReleaseAll();
            verifying.finishAndReleaseAll();
        }
    }

    @Test
    void failedReverificationLeavesOldActiveRouteUsable() {
        MutableTicker ticker = new MutableTicker();
        WorkerRouteRegistry registry = registry(100, ticker);
        EmbeddedChannel active = new EmbeddedChannel();
        EmbeddedChannel verifying = new EmbeddedChannel();
        try {
            verifyAndActivate(registry, "worker-1", active);
            ticker.advance(RETENTION.plusNanos(1));
            registry.admitIdentity("worker-1", verifying);

            assertThat(registry.cancelVerification("worker-1", verifying))
                    .isTrue();
            assertThat(registry.activeChannel("worker-1")).isSameAs(active);
            assertThat(registry.inspectInbound(verifying).kind()).isEqualTo(
                    WorkerRouteRegistry.InboundKind.INVALID
            );
        } finally {
            active.finishAndReleaseAll();
            verifying.finishAndReleaseAll();
        }
    }

    @Test
    void activeAndVerifyingRoutesAreNotCapacityOrTimeEvicted() {
        MutableTicker ticker = new MutableTicker();
        WorkerRouteRegistry registry = registry(1, ticker);
        EmbeddedChannel active = new EmbeddedChannel();
        EmbeddedChannel verifying = new EmbeddedChannel();
        try {
            verifyAndActivate(registry, "active", active);
            registry.admitIdentity("verifying", verifying);
            ticker.advance(Duration.ofDays(1));
            for (int index = 0; index < 20; index++) {
                EmbeddedChannel temporary = new EmbeddedChannel();
                verifyAndActivate(registry, "cached-" + index, temporary);
                registry.onChannelClosed(temporary);
                temporary.finishAndReleaseAll();
            }

            assertThat(registry.connectionStates(List.of(
                    "active",
                    "verifying"
            ))).containsExactly(
                    Map.entry("active", WorkerConnectionState.CONNECTED),
                    Map.entry("verifying", WorkerConnectionState.VERIFYING)
            );
        } finally {
            active.finishAndReleaseAll();
            verifying.finishAndReleaseAll();
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
                    .filter(state -> state == WorkerConnectionState.DISCONNECTED)
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
    void exactRemovalSnapshotAndClearPreserveRouteSafety() {
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
            assertThat(registry.connectionStates(List.of(
                    "worker-1",
                    "unknown"
            ))).containsExactly(
                    Map.entry("worker-1", WorkerConnectionState.CONNECTED),
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
                new NettyWorkerRouteCacheConfig(RETENTION, capacity),
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
        assertThat(registry.completeVerificationAndActivate(
                workerId,
                channel
        ).completed()).isTrue();
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
