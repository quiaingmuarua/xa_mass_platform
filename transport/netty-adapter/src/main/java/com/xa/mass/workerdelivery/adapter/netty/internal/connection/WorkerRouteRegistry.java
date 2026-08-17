package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Process-local Worker route truth for one Adapter instance.
 *
 * <p>This owner performs only atomic route-state transitions. It never
 * writes to, closes, or otherwise operates on a physical Channel.
 */
public final class WorkerRouteRegistry {

    private static final AttributeKey<String> IDENTIFIED_WORKER_ID =
            AttributeKey.valueOf(
                    WorkerRouteRegistry.class,
                    "identifiedWorkerId"
            );

    private final ConcurrentMap<String, RouteState> routesByWorkerId =
            new ConcurrentHashMap<>();

    public IdentityAdmission admitIdentity(
            String workerId,
            Channel channel
    ) {
        String requiredWorkerId = requireWorkerId(workerId);
        Channel requiredChannel = Objects.requireNonNull(channel, "channel");
        while (true) {
            RouteState current = routesByWorkerId.get(requiredWorkerId);
            if (current == null) {
                VerifyingRoute verifying = new VerifyingRoute(
                        requiredChannel
                );
                if (routesByWorkerId.putIfAbsent(
                        requiredWorkerId,
                        verifying
                ) != null) {
                    continue;
                }
                identify(requiredChannel, requiredWorkerId);
                return new IdentityAdmission(
                        IdentityAdmissionKind.VERIFICATION_CLAIMED,
                        null
                );
            }
            if (current instanceof VerifyingRoute) {
                return new IdentityAdmission(
                        IdentityAdmissionKind.VERIFICATION_BUSY,
                        null
                );
            }
            if (current instanceof ConnectedRoute connected) {
                ConnectedRoute replacement = new ConnectedRoute(
                        requiredChannel
                );
                if (!routesByWorkerId.replace(
                        requiredWorkerId,
                        current,
                        replacement
                )) {
                    continue;
                }
                identify(requiredChannel, requiredWorkerId);
                return new IdentityAdmission(
                        IdentityAdmissionKind.VERIFIED_ACTIVATED,
                        distinctReplacement(
                                connected.channel(),
                                requiredChannel
                        )
                );
            }
            if (current == DisconnectedRoute.INSTANCE) {
                ConnectedRoute connected = new ConnectedRoute(
                        requiredChannel
                );
                if (!routesByWorkerId.replace(
                        requiredWorkerId,
                        current,
                        connected
                )) {
                    continue;
                }
                identify(requiredChannel, requiredWorkerId);
                return new IdentityAdmission(
                        IdentityAdmissionKind.VERIFIED_ACTIVATED,
                        null
                );
            }
        }
    }

    public InboundInspection inspectInbound(Channel channel) {
        Channel requiredChannel = Objects.requireNonNull(channel, "channel");
        String workerId = identifiedWorkerId(requiredChannel);
        if (workerId == null) {
            return new InboundInspection(
                    InboundKind.IDENTITY_REQUIRED,
                    null
            );
        }
        RouteState route = routesByWorkerId.get(workerId);
        if (route instanceof VerifyingRoute verifying
                && verifying.channel() == requiredChannel) {
            return new InboundInspection(
                    InboundKind.VERIFICATION_PENDING,
                    workerId
            );
        }
        if (route instanceof ConnectedRoute
                || route == DisconnectedRoute.INSTANCE) {
            return new InboundInspection(
                    InboundKind.VERIFIED,
                    workerId
            );
        }
        return new InboundInspection(InboundKind.INVALID, workerId);
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
        while (true) {
            RouteState current = routesByWorkerId.get(requiredWorkerId);
            if (!(current instanceof VerifyingRoute verifying)
                    || verifying.channel() != requiredChannel
                    || !requiredWorkerId.equals(
                    identifiedWorkerId(requiredChannel)
            )) {
                return ActivationResult.failure();
            }
            if (!routesByWorkerId.replace(
                    requiredWorkerId,
                    current,
                    new ConnectedRoute(requiredChannel)
            )) {
                continue;
            }
            return ActivationResult.success();
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
        while (true) {
            RouteState current = routesByWorkerId.get(requiredWorkerId);
            if (!(current instanceof VerifyingRoute verifying)
                    || verifying.channel() != requiredChannel) {
                return false;
            }
            if (routesByWorkerId.remove(requiredWorkerId, current)) {
                return true;
            }
        }
    }

    public Channel activeChannel(String workerId) {
        String requiredWorkerId = requireWorkerId(workerId);
        RouteState route = routesByWorkerId.get(requiredWorkerId);
        return route instanceof ConnectedRoute connected
                ? connected.channel()
                : null;
    }

    public Map<String, WorkerConnectionState> connectionStates(
            List<String> workerIds
    ) {
        List<String> requiredWorkerIds = requireWorkerIds(workerIds);
        Map<String, WorkerConnectionState> states = new LinkedHashMap<>();
        for (String workerId : requiredWorkerIds) {
            states.put(
                    workerId,
                    connectionState(routesByWorkerId.get(workerId))
            );
        }
        return Collections.unmodifiableMap(states);
    }

    public Map<String, Channel> detachActiveChannels(
            List<String> workerIds
    ) {
        List<String> requiredWorkerIds = requireWorkerIds(workerIds);
        Map<String, Channel> detached = new LinkedHashMap<>();
        for (String workerId : requiredWorkerIds) {
            while (true) {
                RouteState route = routesByWorkerId.get(workerId);
                if (!(route instanceof ConnectedRoute connected)) {
                    break;
                }
                if (!routesByWorkerId.replace(
                        workerId,
                        route,
                        DisconnectedRoute.INSTANCE
                )) {
                    continue;
                }
                detached.put(workerId, connected.channel());
                break;
            }
        }
        return Collections.unmodifiableMap(detached);
    }

    public boolean deactivate(String workerId, Channel expectedChannel) {
        String requiredWorkerId = requireWorkerId(workerId);
        Channel requiredChannel = Objects.requireNonNull(
                expectedChannel,
                "expectedChannel"
        );
        while (true) {
            RouteState route = routesByWorkerId.get(requiredWorkerId);
            if (!(route instanceof ConnectedRoute connected)
                    || connected.channel() != requiredChannel) {
                return false;
            }
            if (!routesByWorkerId.replace(
                    requiredWorkerId,
                    route,
                    DisconnectedRoute.INSTANCE
            )) {
                continue;
            }
            return true;
        }
    }

    public void onChannelClosed(Channel channel) {
        Channel requiredChannel = Objects.requireNonNull(channel, "channel");
        String workerId = requiredChannel.attr(IDENTIFIED_WORKER_ID)
                .getAndSet(null);
        if (workerId == null) {
            return;
        }
        while (true) {
            RouteState route = routesByWorkerId.get(workerId);
            if (route instanceof VerifyingRoute verifying
                    && verifying.channel() == requiredChannel) {
                if (!routesByWorkerId.remove(workerId, route)) {
                    continue;
                }
                return;
            }
            if (route instanceof ConnectedRoute connected
                    && connected.channel() == requiredChannel) {
                if (!routesByWorkerId.replace(
                        workerId,
                        route,
                        DisconnectedRoute.INSTANCE
                )) {
                    continue;
                }
                return;
            }
            return;
        }
    }

    public void clear() {
        routesByWorkerId.clear();
    }

    private static WorkerConnectionState connectionState(RouteState route) {
        if (route == null) {
            return WorkerConnectionState.UNKNOWN;
        }
        if (route instanceof VerifyingRoute) {
            return WorkerConnectionState.VERIFYING;
        }
        if (route instanceof ConnectedRoute connected) {
            return connected.channel().isActive()
                    ? WorkerConnectionState.CONNECTED
                    : WorkerConnectionState.DISCONNECTED;
        }
        return WorkerConnectionState.DISCONNECTED;
    }

    private static Channel distinctReplacement(
            Channel previous,
            Channel replacement
    ) {
        return previous == replacement ? null : previous;
    }

    private static void identify(Channel channel, String workerId) {
        channel.attr(IDENTIFIED_WORKER_ID).set(workerId);
    }

    private static String identifiedWorkerId(Channel channel) {
        return channel.attr(IDENTIFIED_WORKER_ID).get();
    }

    private static String requireWorkerId(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must be non-blank");
        }
        return workerId;
    }

    private static List<String> requireWorkerIds(List<String> workerIds) {
        if (workerIds == null
                || workerIds.isEmpty()
                || workerIds.size() > 100) {
            throw new IllegalArgumentException(
                    "workerIds must contain between 1 and 100 entries"
            );
        }
        Set<String> unique = new HashSet<>();
        for (String workerId : workerIds) {
            String requiredWorkerId = requireWorkerId(workerId);
            if (!unique.add(requiredWorkerId)) {
                throw new IllegalArgumentException(
                        "workerIds must be unique"
                );
            }
        }
        return List.copyOf(workerIds);
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

    public record ActivationResult(boolean accepted) {

        private static ActivationResult success() {
            return new ActivationResult(true);
        }

        private static ActivationResult failure() {
            return new ActivationResult(false);
        }
    }

    private sealed interface RouteState permits
            VerifyingRoute,
            ConnectedRoute,
            DisconnectedRoute {
    }

    private static final class VerifyingRoute implements RouteState {

        private final Channel channel;

        private VerifyingRoute(Channel channel) {
            this.channel = Objects.requireNonNull(channel, "channel");
        }

        private Channel channel() {
            return channel;
        }
    }

    private static final class ConnectedRoute implements RouteState {

        private final Channel channel;

        private ConnectedRoute(Channel channel) {
            this.channel = Objects.requireNonNull(channel, "channel");
        }

        private Channel channel() {
            return channel;
        }
    }

    private enum DisconnectedRoute implements RouteState {
        INSTANCE
    }
}
