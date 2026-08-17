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
    void identityAdmissionOwnsFirstVerificationAsOneAtomicOperation() {
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
    void concurrentFirstIdentityHasOneClaimAndOneBusyChannel()
            throws Exception {
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
            var firstAdmission = firstResult.get(5, TimeUnit.SECONDS);
            var secondAdmission = secondResult.get(5, TimeUnit.SECONDS);

            assertThat(List.of(
                    firstAdmission.kind(),
                    secondAdmission.kind()
            )).containsExactlyInAnyOrder(
                    WorkerRouteRegistry.IdentityAdmissionKind
                            .VERIFICATION_CLAIMED,
                    WorkerRouteRegistry.IdentityAdmissionKind
                            .VERIFICATION_BUSY
            );

            EmbeddedChannel claimed = firstAdmission.kind()
                    == WorkerRouteRegistry.IdentityAdmissionKind
                    .VERIFICATION_CLAIMED
                    ? first
                    : second;
            EmbeddedChannel busy = claimed == first ? second : first;
            assertThat(registry.inspectInbound(claimed).kind()).isEqualTo(
                    WorkerRouteRegistry.InboundKind.VERIFICATION_PENDING
            );
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
    void verifiedReconnectReplacesActiveWithoutLosingOldCorrelation() {
        WorkerRouteRegistry registry = new WorkerRouteRegistry();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel replacement = new EmbeddedChannel();
        try {
            verifyAndActivate(registry, "worker-1", first);

            var reconnect = registry.admitIdentity(
                    "worker-1",
                    replacement
            );

            assertThat(reconnect.kind()).isEqualTo(
                    WorkerRouteRegistry.IdentityAdmissionKind
                            .VERIFIED_ACTIVATED
            );
            assertThat(reconnect.replacedChannel()).isSameAs(first);
            assertThat(registry.activeChannel("worker-1"))
                    .isSameAs(replacement);
            assertThat(registry.inspectInbound(first).kind()).isEqualTo(
                    WorkerRouteRegistry.InboundKind.VERIFIED
            );

            registry.onChannelClosed(first);

            assertThat(registry.activeChannel("worker-1"))
                    .isSameAs(replacement);
        } finally {
            first.finishAndReleaseAll();
            replacement.finishAndReleaseAll();
        }
    }

    @Test
    void reconnectAndOldInactiveConvergeOnReplacement() throws Exception {
        WorkerRouteRegistry registry = new WorkerRouteRegistry();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel replacement = new EmbeddedChannel();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            verifyAndActivate(registry, "worker-1", first);
            Future<WorkerRouteRegistry.IdentityAdmission> reconnect =
                    executor.submit(() -> {
                        start.await();
                        return registry.admitIdentity(
                                "worker-1",
                                replacement
                        );
                    });
            Future<?> inactive = executor.submit(() -> {
                start.await();
                registry.onChannelClosed(first);
                return null;
            });

            start.countDown();
            assertThat(reconnect.get(5, TimeUnit.SECONDS).kind()).isEqualTo(
                    WorkerRouteRegistry.IdentityAdmissionKind
                            .VERIFIED_ACTIVATED
            );
            inactive.get(5, TimeUnit.SECONDS);

            assertThat(registry.activeChannel("worker-1"))
                    .isSameAs(replacement);
            assertThat(registry.connectionStates(List.of("worker-1")))
                    .containsExactly(Map.entry(
                            "worker-1",
                            WorkerConnectionState.CONNECTED
                    ));
        } finally {
            executor.shutdownNow();
            first.finishAndReleaseAll();
            replacement.finishAndReleaseAll();
        }
    }

    @Test
    void ordinaryDisconnectPreservesVerificationForFastReconnect() {
        WorkerRouteRegistry registry = new WorkerRouteRegistry();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel reconnect = new EmbeddedChannel();
        try {
            verifyAndActivate(registry, "worker-1", first);
            registry.onChannelClosed(first);

            assertThat(registry.activeChannel("worker-1")).isNull();
            assertThat(registry.inspectInbound(first).kind()).isEqualTo(
                    WorkerRouteRegistry.InboundKind.IDENTITY_REQUIRED
            );
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
            assertThat(registry.activeChannel("worker-1"))
                    .isSameAs(reconnect);
        } finally {
            first.finishAndReleaseAll();
            reconnect.finishAndReleaseAll();
        }
    }

    @Test
    void connectionSnapshotDerivesAllStatesWithoutCreatingRoutes() {
        WorkerRouteRegistry registry = new WorkerRouteRegistry();
        EmbeddedChannel active = new EmbeddedChannel();
        EmbeddedChannel verifying = new EmbeddedChannel();
        EmbeddedChannel inactive = new EmbeddedChannel();
        EmbeddedChannel unknown = new EmbeddedChannel();
        try {
            verifyAndActivate(registry, "active", active);
            registry.admitIdentity("verifying", verifying);
            verifyAndActivate(registry, "inactive", inactive);
            inactive.close();

            assertThat(registry.connectionStates(List.of(
                    "active",
                    "verifying",
                    "inactive",
                    "unknown"
            ))).containsExactly(
                    Map.entry("active", WorkerConnectionState.CONNECTED),
                    Map.entry("verifying", WorkerConnectionState.VERIFYING),
                    Map.entry("inactive", WorkerConnectionState.DISCONNECTED),
                    Map.entry("unknown", WorkerConnectionState.UNKNOWN)
            );

            assertThat(registry.admitIdentity("unknown", unknown).kind())
                    .isEqualTo(
                            WorkerRouteRegistry.IdentityAdmissionKind
                                    .VERIFICATION_CLAIMED
                    );
        } finally {
            active.finishAndReleaseAll();
            verifying.finishAndReleaseAll();
            inactive.finishAndReleaseAll();
            unknown.finishAndReleaseAll();
        }
    }

    @Test
    void connectionSnapshotRequiresOneToOneHundredUniqueWorkerIds() {
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
    void cancellationInvalidatesThatChannelAndAllowsAnotherClaim() {
        WorkerRouteRegistry registry = new WorkerRouteRegistry();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel retry = new EmbeddedChannel();
        try {
            registry.admitIdentity("worker-1", first);
            assertThat(registry.cancelVerification("worker-1", first))
                    .isTrue();
            assertThat(registry.inspectInbound(first).kind()).isEqualTo(
                    WorkerRouteRegistry.InboundKind.INVALID
            );
            registry.onChannelClosed(first);
            assertThat(registry.inspectInbound(first).kind()).isEqualTo(
                    WorkerRouteRegistry.InboundKind.IDENTITY_REQUIRED
            );

            assertThat(registry.admitIdentity("worker-1", retry).kind())
                    .isEqualTo(
                            WorkerRouteRegistry.IdentityAdmissionKind
                                    .VERIFICATION_CLAIMED
                    );
        } finally {
            first.finishAndReleaseAll();
            retry.finishAndReleaseAll();
        }
    }

    @Test
    void lateCompletionAndOldDeactivationCannotChangeCurrentRoute() {
        WorkerRouteRegistry registry = new WorkerRouteRegistry();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        try {
            registry.admitIdentity("worker-1", first);
            registry.onChannelClosed(first);
            assertThat(registry.completeVerificationAndActivate(
                    "worker-1",
                    first
            ).accepted()).isFalse();

            verifyAndActivate(registry, "worker-1", second);
            assertThat(registry.deactivate("worker-1", first)).isFalse();
            assertThat(registry.activeChannel("worker-1")).isSameAs(second);
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }

    @Test
    void registriesAreInstanceLocalAndClearRemovesRouteTruth() {
        WorkerRouteRegistry firstRegistry = new WorkerRouteRegistry();
        WorkerRouteRegistry secondRegistry = new WorkerRouteRegistry();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        try {
            verifyAndActivate(firstRegistry, "worker-1", first);
            verifyAndActivate(secondRegistry, "worker-1", second);
            firstRegistry.clear();

            assertThat(firstRegistry.activeChannel("worker-1")).isNull();
            assertThat(firstRegistry.connectionStates(List.of("worker-1")))
                    .containsExactly(Map.entry(
                            "worker-1",
                            WorkerConnectionState.UNKNOWN
                    ));
            assertThat(firstRegistry.inspectInbound(first).kind())
                    .isEqualTo(
                            WorkerRouteRegistry.InboundKind.INVALID
                    );
            firstRegistry.onChannelClosed(first);
            assertThat(firstRegistry.inspectInbound(first).kind()).isEqualTo(
                    WorkerRouteRegistry.InboundKind.IDENTITY_REQUIRED
            );
            assertThat(firstRegistry.admitIdentity("worker-1", first).kind())
                    .isEqualTo(
                            WorkerRouteRegistry.IdentityAdmissionKind
                                    .VERIFICATION_CLAIMED
                    );
            assertThat(secondRegistry.activeChannel("worker-1"))
                    .isSameAs(second);
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
        ).accepted()).isTrue();
    }
}
