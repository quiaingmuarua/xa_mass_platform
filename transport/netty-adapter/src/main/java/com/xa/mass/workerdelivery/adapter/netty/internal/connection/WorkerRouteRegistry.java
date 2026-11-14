package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Process-local Worker route truth for one Adapter instance. */
public final class WorkerRouteRegistry {

    private static final AttributeKey<String> CLAIMED_WORKER_ID =
            AttributeKey.valueOf(
                    WorkerRouteRegistry.class,
                    "claimedWorkerId"
            );
    private static final AttributeKey<Boolean> VERIFIED_CHANNEL =
            AttributeKey.valueOf(WorkerRouteRegistry.class, "verifiedChannel");

    private final Cache<String, RouteEntry> routesByWorkerId;
    private final long reconnectVerificationRetentionNanos;
    private final Ticker ticker;

    public WorkerRouteRegistry(
            Duration reconnectVerificationRetention,
            long maximumDisconnectedWorkers
    ) {
        this(
                reconnectVerificationRetention,
                maximumDisconnectedWorkers,
                Ticker.systemTicker()
        );
    }

    WorkerRouteRegistry(
            Duration reconnectVerificationRetention,
            long maximumDisconnectedWorkers,
            Ticker ticker
    ) {
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        reconnectVerificationRetentionNanos = Objects.requireNonNull(
                reconnectVerificationRetention,
                "reconnectVerificationRetention"
        ).toNanos();
        routesByWorkerId = Caffeine.newBuilder()
                .maximumWeight(maximumDisconnectedWorkers)
                .weigher((String ignored, RouteEntry route) ->
                        route.isDisconnectedCache() ? 1 : 0)
                .expireAfter(new RouteExpiry(
                        reconnectVerificationRetentionNanos
                ))
                .ticker(this.ticker)
                .executor(Runnable::run)
                .build();
    }

    IdentityAdmission admitIdentity(String workerId, Channel channel) {
        String requiredWorkerId = requireWorkerId(workerId);
        Channel requiredChannel = Objects.requireNonNull(channel, "channel");
        AtomicReference<IdentityAdmission> admission = new AtomicReference<>();
        routesByWorkerId.asMap().compute(
                requiredWorkerId,
                (ignored, current) -> {
                    long now = ticker.read();
                    if (current != null && current.pendingChannel() != null) {
                        admission.set(verificationBusy());
                        return current;
                    }
                    admission.set(verificationClaimed());
                    requiredChannel.attr(CLAIMED_WORKER_ID).set(requiredWorkerId);
                    requiredChannel.attr(VERIFIED_CHANNEL).set(false);
                    if (current == null) {
                        return new RouteEntry(null, requiredChannel, null);
                    }
                    return new RouteEntry(current.channel(), requiredChannel,
                            current.isConnected() || verificationFresh(current, now)
                                    ? current.verifiedAtNanos() : null);
                }
        );
        return admission.get();
    }

    String claimedWorkerId(Channel channel) {
        return Objects.requireNonNull(channel, "channel")
                .attr(CLAIMED_WORKER_ID)
                .get();
    }

    boolean wasVerified(Channel channel) {
        return Boolean.TRUE.equals(channel.attr(VERIFIED_CHANNEL).get());
    }

    boolean hasVerificationEvidence(String workerId) {
        RouteEntry route = quietRoute(requireWorkerId(workerId));
        if (route == null || route.verifiedAtNanos() == null) {
            return false;
        }
        return route.isConnected()
                || verificationFresh(route, ticker.read());
    }

    Activation completeVerification(
            String workerId,
            Channel expectedChannel
    ) {
        String requiredWorkerId = requireWorkerId(workerId);
        Channel requiredChannel = Objects.requireNonNull(
                expectedChannel,
                "expectedChannel"
        );
        AtomicReference<Activation> completed = new AtomicReference<>();
        routesByWorkerId.asMap().computeIfPresent(
                requiredWorkerId,
                (ignored, current) -> {
                    if (current.pendingChannel() != requiredChannel
                            || !requiredWorkerId.equals(
                            claimedWorkerId(requiredChannel)
                    )) {
                        return current;
                    }
                    requiredChannel.attr(VERIFIED_CHANNEL).set(true);
                    completed.set(new Activation(current.channel(), !current.isConnected()));
                    return RouteEntry.connected(
                            requiredChannel,
                            ticker.read()
                    );
                }
        );
        return completed.get();
    }

    boolean cancelVerification(
            String workerId,
            Channel expectedChannel
    ) {
        String requiredWorkerId = requireWorkerId(workerId);
        Channel requiredChannel = Objects.requireNonNull(
                expectedChannel,
                "expectedChannel"
        );
        AtomicBoolean cancelled = new AtomicBoolean();
        routesByWorkerId.asMap().computeIfPresent(
                requiredWorkerId,
                (ignored, current) -> {
                    if (current.pendingChannel() != requiredChannel) {
                        return current;
                    }
                    cancelled.set(true);
                    return withoutPending(current, ticker.read());
                }
        );
        return cancelled.get();
    }

    Channel activeChannel(String workerId) {
        RouteEntry route = quietRoute(requireWorkerId(workerId));
        return route != null && route.isConnected()
                ? route.channel()
                : null;
    }

    boolean isCurrentConnected(String workerId, Channel expectedChannel) {
        String requiredWorkerId = requireWorkerId(workerId);
        Channel requiredChannel = Objects.requireNonNull(
                expectedChannel,
                "expectedChannel"
        );
        RouteEntry route = quietRoute(requiredWorkerId);
        return route != null
                && route.isConnected()
                && route.channel() == requiredChannel;
    }

    Map<String, WorkerConnectionState> connectionStates(
            List<String> workerIds
    ) {
        List<String> requiredWorkerIds = requireWorkerIds(workerIds);
        Map<String, WorkerConnectionState> states = new LinkedHashMap<>();
        for (String workerId : requiredWorkerIds) {
            states.put(workerId, connectionState(quietRoute(workerId)));
        }
        return Collections.unmodifiableMap(states);
    }

    Map<String, Channel> detachActiveChannels(List<String> workerIds) {
        List<String> requiredWorkerIds = requireWorkerIds(workerIds);
        Map<String, Channel> detached = new LinkedHashMap<>();
        for (String workerId : requiredWorkerIds) {
            AtomicReference<Channel> removed = new AtomicReference<>();
            routesByWorkerId.asMap().computeIfPresent(
                    workerId,
                    (ignored, current) -> {
                        if (!current.isConnected()) {
                            return current;
                        }
                        removed.set(current.channel());
                        return disconnectedOrAbsent(current, ticker.read());
                    }
            );
            if (removed.get() != null) {
                detached.put(workerId, removed.get());
            }
        }
        return Collections.unmodifiableMap(detached);
    }

    Revocation revoke(String workerId) {
        String requiredWorkerId = requireWorkerId(workerId);
        AtomicReference<Revocation> removed = new AtomicReference<>(new Revocation(null, null));
        routesByWorkerId.asMap().computeIfPresent(requiredWorkerId, (ignored, current) -> {
            removed.set(new Revocation(current.channel(), current.pendingChannel()));
            if (current.channel() != null) {
                current.channel().attr(VERIFIED_CHANNEL).set(false);
            }
            if (current.pendingChannel() != null) {
                current.pendingChannel().attr(VERIFIED_CHANNEL).set(false);
            }
            return null;
        });
        return removed.get();
    }

    boolean deactivate(String workerId, Channel expectedChannel) {
        String requiredWorkerId = requireWorkerId(workerId);
        Channel requiredChannel = Objects.requireNonNull(
                expectedChannel,
                "expectedChannel"
        );
        AtomicBoolean deactivated = new AtomicBoolean();
        routesByWorkerId.asMap().computeIfPresent(
                requiredWorkerId,
                (ignored, current) -> {
                    if (!current.isConnected()
                            || current.channel() != requiredChannel) {
                        return current;
                    }
                    deactivated.set(true);
                    return disconnectedOrAbsent(current, ticker.read());
                }
        );
        return deactivated.get();
    }

    String onChannelClosed(Channel channel) {
        Channel requiredChannel = Objects.requireNonNull(channel, "channel");
        String workerId = requiredChannel.attr(CLAIMED_WORKER_ID)
                .getAndSet(null);
        requiredChannel.attr(VERIFIED_CHANNEL).set(false);
        if (workerId == null) {
            return null;
        }
        AtomicBoolean activeRemoved = new AtomicBoolean();
        routesByWorkerId.asMap().computeIfPresent(
                workerId,
                (ignored, current) -> {
                    if (current.pendingChannel() == requiredChannel) {
                        return withoutPending(current, ticker.read());
                    }
                    if (current.channel() != requiredChannel) {
                        return current;
                    }
                    activeRemoved.set(true);
                    return disconnectedOrAbsent(current, ticker.read());
                }
        );
        return activeRemoved.get() ? workerId : null;
    }

    void clear() {
        routesByWorkerId.invalidateAll();
        routesByWorkerId.cleanUp();
    }

    private RouteEntry quietRoute(String workerId) {
        return routesByWorkerId.policy().getIfPresentQuietly(workerId);
    }

    private boolean verificationFresh(RouteEntry route, long now) {
        Long verifiedAt = route.verifiedAtNanos();
        return verifiedAt != null
                && now - verifiedAt < reconnectVerificationRetentionNanos;
    }

    private RouteEntry disconnectedOrAbsent(RouteEntry route, long now) {
        if (route.pendingChannel() != null) {
            return new RouteEntry(null, route.pendingChannel(), route.verifiedAtNanos());
        }
        return verificationFresh(route, now)
                ? RouteEntry.disconnected(route.verifiedAtNanos())
                : null;
    }

    private RouteEntry withoutPending(RouteEntry route, long now) {
        if (route.isConnected()) {
            return RouteEntry.connected(route.channel(), route.verifiedAtNanos());
        }
        return verificationFresh(route, now)
                ? RouteEntry.disconnected(route.verifiedAtNanos()) : null;
    }

    private static WorkerConnectionState connectionState(RouteEntry route) {
        if (route == null || !route.isConnected() && route.verifiedAtNanos() == null) {
            return WorkerConnectionState.UNKNOWN;
        }
        if (route.isConnected() && route.channel().isActive()) {
            return WorkerConnectionState.CONNECTED;
        }
        return WorkerConnectionState.DISCONNECTED;
    }

    private static IdentityAdmission verificationClaimed() {
        return new IdentityAdmission(
                IdentityAdmissionKind.VERIFICATION_CLAIMED
        );
    }

    private static IdentityAdmission verificationBusy() {
        return new IdentityAdmission(
                IdentityAdmissionKind.VERIFICATION_BUSY
        );
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

    enum IdentityAdmissionKind {
        VERIFICATION_CLAIMED,
        VERIFICATION_BUSY
    }

    record IdentityAdmission(
            IdentityAdmissionKind kind
    ) {

        IdentityAdmission {
            Objects.requireNonNull(kind, "kind");
        }
    }

    record Activation(Channel replacedChannel, boolean becameAvailable) { }

    record Revocation(Channel activeChannel, Channel pendingChannel) { }

    private record RouteEntry(
            Channel channel,
            Channel pendingChannel,
            Long verifiedAtNanos
    ) {

        private RouteEntry {
            if (channel == null && pendingChannel == null && verifiedAtNanos == null) {
                throw new IllegalArgumentException(
                        "route entry must contain a Channel or verification"
                );
            }
        }

        private static RouteEntry connected(
                Channel channel,
                long verifiedAtNanos
        ) {
            return new RouteEntry(
                    Objects.requireNonNull(channel, "channel"),
                    null,
                    verifiedAtNanos
            );
        }

        private static RouteEntry disconnected(long verifiedAtNanos) {
            return new RouteEntry(null, null, verifiedAtNanos);
        }

        private boolean isConnected() {
            return channel != null && verifiedAtNanos != null;
        }

        private boolean isDisconnectedCache() {
            return channel == null && pendingChannel == null;
        }
    }

    private static final class RouteExpiry
            implements Expiry<String, RouteEntry> {

        private final long retentionNanos;

        private RouteExpiry(long retentionNanos) {
            this.retentionNanos = retentionNanos;
        }

        @Override
        public long expireAfterCreate(
                String key,
                RouteEntry route,
                long currentTime
        ) {
            return duration(route, currentTime);
        }

        @Override
        public long expireAfterUpdate(
                String key,
                RouteEntry route,
                long currentTime,
                long currentDuration
        ) {
            return duration(route, currentTime);
        }

        @Override
        public long expireAfterRead(
                String key,
                RouteEntry route,
                long currentTime,
                long currentDuration
        ) {
            return duration(route, currentTime);
        }

        private long duration(RouteEntry route, long currentTime) {
            if (!route.isDisconnectedCache()) {
                return Long.MAX_VALUE;
            }
            long age = currentTime - route.verifiedAtNanos();
            return age >= retentionNanos ? 0L : retentionNanos - age;
        }
    }
}
