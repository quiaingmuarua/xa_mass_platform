package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

import io.netty.channel.Channel;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Process-local Worker route truth for one Adapter instance.
 *
 * <p>This owner performs only atomic route-state transitions. It never
 * writes to, closes, or otherwise operates on a physical Channel.
 */
public final class WorkerRouteRegistry {

    private final Object routeLock = new Object();
    private final Set<String> verifiedWorkerIds = new HashSet<>();
    private final Map<String, Channel> pendingVerifications = new HashMap<>();
    private final Map<String, Channel> activeChannels = new HashMap<>();
    private final Map<Channel, String> identifiedWorkerByChannel =
            new IdentityHashMap<>();

    public IdentityAdmission admitIdentity(
            String workerId,
            Channel channel
    ) {
        String requiredWorkerId = requireWorkerId(workerId);
        Channel requiredChannel = Objects.requireNonNull(channel, "channel");
        synchronized (routeLock) {
            if (verifiedWorkerIds.contains(requiredWorkerId)) {
                identifiedWorkerByChannel.put(
                        requiredChannel,
                        requiredWorkerId
                );
                Channel replaced = activeChannels.put(
                        requiredWorkerId,
                        requiredChannel
                );
                return new IdentityAdmission(
                        IdentityAdmissionKind.VERIFIED_ACTIVATED,
                        distinctReplacement(replaced, requiredChannel)
                );
            }
            if (pendingVerifications.containsKey(requiredWorkerId)) {
                return new IdentityAdmission(
                        IdentityAdmissionKind.VERIFICATION_BUSY,
                        null
                );
            }
            pendingVerifications.put(requiredWorkerId, requiredChannel);
            identifiedWorkerByChannel.put(
                    requiredChannel,
                    requiredWorkerId
            );
            return new IdentityAdmission(
                    IdentityAdmissionKind.VERIFICATION_CLAIMED,
                    null
            );
        }
    }

    public InboundInspection inspectInbound(Channel channel) {
        Channel requiredChannel = Objects.requireNonNull(channel, "channel");
        synchronized (routeLock) {
            String workerId = identifiedWorkerByChannel.get(requiredChannel);
            if (workerId == null) {
                return new InboundInspection(
                        InboundKind.IDENTITY_REQUIRED,
                        null
                );
            }
            if (pendingVerifications.get(workerId) == requiredChannel) {
                return new InboundInspection(
                        InboundKind.VERIFICATION_PENDING,
                        workerId
                );
            }
            if (verifiedWorkerIds.contains(workerId)) {
                return new InboundInspection(
                        InboundKind.VERIFIED,
                        workerId
                );
            }
            return new InboundInspection(InboundKind.INVALID, workerId);
        }
    }

    public ActivationResult completeVerificationAndActivate(
            String workerId,
            Channel expectedChannel
    ) {
        String requiredWorkerId = requireWorkerId(workerId);
        Channel requiredChannel = Objects.requireNonNull(
                expectedChannel,
                "expectedChannel"
        );
        synchronized (routeLock) {
            if (pendingVerifications.get(requiredWorkerId)
                    != requiredChannel
                    || !requiredWorkerId.equals(
                    identifiedWorkerByChannel.get(requiredChannel)
            )) {
                return ActivationResult.rejected();
            }
            pendingVerifications.remove(requiredWorkerId);
            verifiedWorkerIds.add(requiredWorkerId);
            Channel replaced = activeChannels.put(
                    requiredWorkerId,
                    requiredChannel
            );
            return ActivationResult.accepted(
                    distinctReplacement(replaced, requiredChannel)
            );
        }
    }

    public boolean cancelVerification(
            String workerId,
            Channel expectedChannel
    ) {
        String requiredWorkerId = requireWorkerId(workerId);
        Channel requiredChannel = Objects.requireNonNull(
                expectedChannel,
                "expectedChannel"
        );
        synchronized (routeLock) {
            if (pendingVerifications.get(requiredWorkerId)
                    != requiredChannel) {
                return false;
            }
            pendingVerifications.remove(requiredWorkerId);
            return true;
        }
    }

    public Channel activeChannel(String workerId) {
        String requiredWorkerId = requireWorkerId(workerId);
        synchronized (routeLock) {
            return activeChannels.get(requiredWorkerId);
        }
    }

    public boolean deactivate(String workerId, Channel expectedChannel) {
        String requiredWorkerId = requireWorkerId(workerId);
        Channel requiredChannel = Objects.requireNonNull(
                expectedChannel,
                "expectedChannel"
        );
        synchronized (routeLock) {
            if (activeChannels.get(requiredWorkerId) != requiredChannel) {
                return false;
            }
            activeChannels.remove(requiredWorkerId);
            return true;
        }
    }

    public void onChannelClosed(Channel channel) {
        Channel requiredChannel = Objects.requireNonNull(channel, "channel");
        synchronized (routeLock) {
            String workerId = identifiedWorkerByChannel.remove(
                    requiredChannel
            );
            if (workerId == null) {
                return;
            }
            if (pendingVerifications.get(workerId) == requiredChannel) {
                pendingVerifications.remove(workerId);
            }
            if (activeChannels.get(workerId) == requiredChannel) {
                activeChannels.remove(workerId);
            }
        }
    }

    public void clear() {
        synchronized (routeLock) {
            verifiedWorkerIds.clear();
            pendingVerifications.clear();
            activeChannels.clear();
            identifiedWorkerByChannel.clear();
        }
    }

    private static Channel distinctReplacement(
            Channel previous,
            Channel replacement
    ) {
        return previous == replacement ? null : previous;
    }

    private static String requireWorkerId(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must be non-blank");
        }
        return workerId;
    }

    public enum IdentityAdmissionKind {
        VERIFICATION_CLAIMED,
        VERIFICATION_BUSY,
        VERIFIED_ACTIVATED
    }

    public record IdentityAdmission(
            IdentityAdmissionKind kind,
            Channel replacedChannel
    ) {

        public IdentityAdmission {
            Objects.requireNonNull(kind, "kind");
        }
    }

    public enum InboundKind {
        IDENTITY_REQUIRED,
        VERIFICATION_PENDING,
        VERIFIED,
        INVALID
    }

    public record InboundInspection(InboundKind kind, String workerId) {

        public InboundInspection {
            Objects.requireNonNull(kind, "kind");
        }
    }

    public record ActivationResult(boolean accepted, Channel replacedChannel) {

        private static ActivationResult accepted(Channel replacedChannel) {
            return new ActivationResult(true, replacedChannel);
        }

        private static ActivationResult rejected() {
            return new ActivationResult(false, null);
        }
    }
}
