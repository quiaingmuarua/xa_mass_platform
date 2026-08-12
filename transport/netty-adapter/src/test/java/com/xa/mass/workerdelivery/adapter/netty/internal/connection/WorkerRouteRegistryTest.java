package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
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
    void ordinaryDisconnectPreservesVerificationForFastReconnect() {
        WorkerRouteRegistry registry = new WorkerRouteRegistry();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel reconnect = new EmbeddedChannel();
        try {
            verifyAndActivate(registry, "worker-1", first);
            registry.onChannelClosed(first);

            assertThat(registry.activeChannel("worker-1")).isNull();

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
    void registriesAreInstanceLocalAndClearRemovesEveryTruth() {
        WorkerRouteRegistry firstRegistry = new WorkerRouteRegistry();
        WorkerRouteRegistry secondRegistry = new WorkerRouteRegistry();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        try {
            verifyAndActivate(firstRegistry, "worker-1", first);
            verifyAndActivate(secondRegistry, "worker-1", second);
            firstRegistry.clear();

            assertThat(firstRegistry.activeChannel("worker-1")).isNull();
            assertThat(firstRegistry.inspectInbound(first).kind())
                    .isEqualTo(
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
