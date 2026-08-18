package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WorkerRouteRegistryTest {

    @Test
    void firstIdentityOwnsOneVerificationClaim() {
        WorkerRouteRegistry registry = new WorkerRouteRegistry();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        try {
            var claimed = registry.admitIdentity("worker-1", first);
            var busy = registry.admitIdentity("worker-1", second);

            assertThat(claimed.kind()).isEqualTo(
                    WorkerRouteRegistry.IdentityAdmissionKind
                            .VERIFICATION_CLAIMED
            );
            assertThat(claimed.becameAvailable()).isFalse();
            assertThat(busy.kind()).isEqualTo(
                    WorkerRouteRegistry.IdentityAdmissionKind
                            .VERIFICATION_BUSY
            );
            assertThat(registry.inspectInbound(first).kind()).isEqualTo(
                    WorkerRouteRegistry.InboundKind.VERIFICATION_PENDING
            );
            assertThat(registry.inspectInbound(second).kind()).isEqualTo(
                    WorkerRouteRegistry.InboundKind.IDENTITY_REQUIRED
            );
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }

    @Test
    void concurrentFirstIdentityStillHasOneClaim() throws Exception {
        WorkerRouteRegistry registry = new WorkerRouteRegistry();
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
        } finally {
            executor.shutdownNow();
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }

    @Test
    void firstVerificationInstallsTheCurrentWorkerRoute() {
        WorkerRouteRegistry registry = new WorkerRouteRegistry();
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            assertThat(registry.admitIdentity("worker-1", channel).kind())
                    .isEqualTo(
                            WorkerRouteRegistry.IdentityAdmissionKind
                                    .VERIFICATION_CLAIMED
                    );
            assertThat(registry.completeVerificationAndActivate(
                    "worker-1",
                    channel
            )).isTrue();

            assertThat(registry.activeChannel("worker-1")).isSameAs(channel);
            assertThat(registry.isCurrentConnected("worker-1", channel))
                    .isTrue();
            assertThat(registry.inspectInbound(channel).kind()).isEqualTo(
                    WorkerRouteRegistry.InboundKind.VERIFIED
            );
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void connectedReplacementDoesNotCreateAnAvailabilityTransition() {
        WorkerRouteRegistry registry = new WorkerRouteRegistry();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel replacement = new EmbeddedChannel();
        try {
            verifyAndActivate(registry, "worker-1", first);

            var admission = registry.admitIdentity(
                    "worker-1",
                    replacement
            );

            assertThat(admission.kind()).isEqualTo(
                    WorkerRouteRegistry.IdentityAdmissionKind
                            .VERIFIED_ACTIVATED
            );
            assertThat(admission.replacedChannel()).isSameAs(first);
            assertThat(admission.becameAvailable()).isFalse();
            assertThat(registry.onChannelClosed(first)).isNull();
            assertThat(registry.activeChannel("worker-1"))
                    .isSameAs(replacement);
            assertThat(registry.isCurrentConnected("worker-1", first))
                    .isFalse();
        } finally {
            first.finishAndReleaseAll();
            replacement.finishAndReleaseAll();
        }
    }

    @Test
    void disconnectAndReconnectExposeExactAvailabilityTransitions() {
        WorkerRouteRegistry registry = new WorkerRouteRegistry();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel reconnect = new EmbeddedChannel();
        try {
            verifyAndActivate(registry, "worker-1", first);

            assertThat(registry.onChannelClosed(first)).isEqualTo("worker-1");
            assertThat(registry.onChannelClosed(first)).isNull();
            assertThat(registry.connectionStates(List.of("worker-1")))
                    .containsExactly(Map.entry(
                            "worker-1",
                            WorkerConnectionState.DISCONNECTED
                    ));

            var admission = registry.admitIdentity("worker-1", reconnect);
            assertThat(admission.kind()).isEqualTo(
                    WorkerRouteRegistry.IdentityAdmissionKind
                            .VERIFIED_ACTIVATED
            );
            assertThat(admission.becameAvailable()).isTrue();
            assertThat(registry.activeChannel("worker-1"))
                    .isSameAs(reconnect);
        } finally {
            first.finishAndReleaseAll();
            reconnect.finishAndReleaseAll();
        }
    }

    @Test
    void detachAndDeactivateOnlyTransitionTheExactCurrentChannel() {
        WorkerRouteRegistry registry = new WorkerRouteRegistry();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        try {
            verifyAndActivate(registry, "worker-1", first);
            verifyAndActivate(registry, "worker-2", second);

            assertThat(registry.detachActiveChannels(List.of(
                    "worker-1",
                    "missing"
            ))).containsExactly(Map.entry("worker-1", first));
            assertThat(registry.deactivate("worker-2", first)).isFalse();
            assertThat(registry.deactivate("worker-2", second)).isTrue();
            assertThat(registry.deactivate("worker-2", second)).isFalse();
            assertThat(registry.connectionStates(List.of(
                    "worker-1",
                    "worker-2"
            ))).containsExactly(
                    Map.entry(
                            "worker-1",
                            WorkerConnectionState.DISCONNECTED
                    ),
                    Map.entry(
                            "worker-2",
                            WorkerConnectionState.DISCONNECTED
                    )
            );
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }

    @Test
    void connectionSnapshotDoesNotCreateRouteTruth() {
        WorkerRouteRegistry registry = new WorkerRouteRegistry();
        EmbeddedChannel active = new EmbeddedChannel();
        EmbeddedChannel verifying = new EmbeddedChannel();
        try {
            verifyAndActivate(registry, "active", active);
            registry.admitIdentity("verifying", verifying);

            assertThat(registry.connectionStates(List.of(
                    "active",
                    "verifying",
                    "unknown"
            ))).containsExactly(
                    Map.entry("active", WorkerConnectionState.CONNECTED),
                    Map.entry("verifying", WorkerConnectionState.VERIFYING),
                    Map.entry("unknown", WorkerConnectionState.UNKNOWN)
            );
            assertThat(registry.activeChannel("unknown")).isNull();
        } finally {
            active.finishAndReleaseAll();
            verifying.finishAndReleaseAll();
        }
    }

    @Test
    void snapshotsRequireOneToOneHundredUniqueWorkerIds() {
        WorkerRouteRegistry registry = new WorkerRouteRegistry();
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

    @Test
    void staleCompletionAndClearCannotRestoreAnOldRoute() {
        WorkerRouteRegistry registry = new WorkerRouteRegistry();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        try {
            registry.admitIdentity("worker-1", first);
            registry.onChannelClosed(first);
            assertThat(registry.completeVerificationAndActivate(
                    "worker-1",
                    first
            )).isFalse();

            verifyAndActivate(registry, "worker-1", second);
            registry.clear();
            assertThat(registry.activeChannel("worker-1")).isNull();
            assertThat(registry.isCurrentConnected("worker-1", second))
                    .isFalse();
            assertThat(registry.connectionStates(List.of("worker-1")))
                    .containsExactly(Map.entry(
                            "worker-1",
                            WorkerConnectionState.UNKNOWN
                    ));
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
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
        )).isTrue();
    }
}
