package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import com.xa.mass.workerdelivery.adapter.netty.NettyWorkerRouteCacheConfig;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
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

    private final Cache<String, RouteEntry> routesByWorkerId;
    private final long reconnectVerificationRetentionNanos;
    private final Ticker ticker;

    public WorkerRouteRegistry(NettyWorkerRouteCacheConfig config) {
        this(config, Ticker.systemTicker());
    }

    WorkerRouteRegistry(
            NettyWorkerRouteCacheConfig config,
            Ticker ticker
    ) {
        NettyWorkerRouteCacheConfig requiredConfig = Objects.requireNonNull(
                config,
                "config"
        );
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        reconnectVerificationRetentionNanos = requiredConfig
                .reconnectVerificationRetention()
                .toNanos();
        routesByWorkerId = Caffeine.newBuilder()
                .maximumWeight(requiredConfig.maximumDisconnectedWorkers())
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
                    if (current == null) {
                        admission.set(verificationClaimed());
                        return RouteEntry.pending(requiredChannel);
                    }
                    if (current.isPending()) {
                        admission.set(verificationBusy());
                        return current;
                    }
                    if (current.isConnected()) {
                        admission.set(new IdentityAdmission(
                                IdentityAdmissionKind.VERIFIED_ACTIVATED,
                                distinctReplacement(
                                        current.channel(),
                                        requiredChannel
                                ),
                                false
                        ));
                        return RouteEntry.connected(
                                requiredChannel,
                                current.verifiedAtNanos()
                        );
                    }
                    if (verificationFresh(current, now)) {
                        admission.set(new IdentityAdmission(
                                IdentityAdmissionKind.VERIFIED_ACTIVATED,
                                null,
                                true
                        ));
                        return RouteEntry.connected(
                                requiredChannel,
                                current.verifiedAtNanos()
                        );
                    }
                    admission.set(verificationClaimed());
                    return RouteEntry.pending(requiredChannel);
                }
        );
        IdentityAdmission result = admission.get();
        if (result.kind() != IdentityAdmissionKind.VERIFICATION_BUSY) {
            requiredChannel.attr(CLAIMED_WORKER_ID).set(requiredWorkerId);
        }
        return result;
    }

    String claimedWorkerId(Channel channel) {
        return Objects.requireNonNull(channel, "channel")
                .attr(CLAIMED_WORKER_ID)
                .get();
    }

    boolean hasVerificationEvidence(String workerId) {
        RouteEntry route = quietRoute(requireWorkerId(workerId));
        if (route == null || route.verifiedAtNanos() == null) {
            return false;
        }
        return route.isConnected()
                || verificationFresh(route, ticker.read());
    }

    boolean completeVerification(
            String workerId,
            Channel expectedChannel
    ) {
        String requiredWorkerId = requireWorkerId(workerId);
        Channel requiredChannel = Objects.requireNonNull(
                expectedChannel,
                "expectedChannel"
        );
        AtomicBoolean completed = new AtomicBoolean();
        routesByWorkerId.asMap().computeIfPresent(
                requiredWorkerId,
                (ignored, current) -> {
                    if (!current.isPending()
                            || current.channel() != requiredChannel
                            || !requiredWorkerId.equals(
                            claimedWorkerId(requiredChannel)
                    )) {
                        return current;
                    }
                    completed.set(true);
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
                    if (!current.isPending()
                            || current.channel() != requiredChannel) {
                        return current;
                    }
                    cancelled.set(true);
                    return null;
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
        if (workerId == null) {
            return null;
        }
        AtomicBoolean activeRemoved = new AtomicBoolean();
        routesByWorkerId.asMap().computeIfPresent(
                workerId,
                (ignored, current) -> {
                    if (current.channel() != requiredChannel) {
                        return current;
                    }
                    if (current.isPending()) {
                        return null;
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
        return verificationFresh(route, now)
                ? RouteEntry.disconnected(route.verifiedAtNanos())
                : null;
    }

    private static WorkerConnectionState connectionState(RouteEntry route) {
        if (route == null || route.isPending()) {
            return WorkerConnectionState.UNKNOWN;
        }
        if (route.isConnected() && route.channel().isActive()) {
            return WorkerConnectionState.CONNECTED;
        }
        return WorkerConnectionState.DISCONNECTED;
    }

    private static IdentityAdmission verificationClaimed() {
        return new IdentityAdmission(
                IdentityAdmissionKind.VERIFICATION_CLAIMED,
                null,
                false
        );
    }

    private static IdentityAdmission verificationBusy() {
        return new IdentityAdmission(
                IdentityAdmissionKind.VERIFICATION_BUSY,
                null,
                false
        );
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
        VERIFICATION_BUSY,
        VERIFIED_ACTIVATED
    }

    record IdentityAdmission(
            IdentityAdmissionKind kind,
            Channel replacedChannel,
            boolean becameAvailable
    ) {

        IdentityAdmission {
            Objects.requireNonNull(kind, "kind");
        }
    }

    private record RouteEntry(
            Channel channel,
            Long verifiedAtNanos
    ) {

        private RouteEntry {
            if (channel == null && verifiedAtNanos == null) {
                throw new IllegalArgumentException(
                        "route entry must contain a Channel or verification"
                );
            }
        }

        private static RouteEntry pending(Channel channel) {
            return new RouteEntry(
                    Objects.requireNonNull(channel, "channel"),
                    null
            );
        }

        private static RouteEntry connected(
                Channel channel,
                long verifiedAtNanos
        ) {
            return new RouteEntry(
                    Objects.requireNonNull(channel, "channel"),
                    verifiedAtNanos
            );
        }

        private static RouteEntry disconnected(long verifiedAtNanos) {
            return new RouteEntry(null, verifiedAtNanos);
        }

        private boolean isPending() {
            return channel != null && verifiedAtNanos == null;
        }

        private boolean isConnected() {
            return channel != null && verifiedAtNanos != null;
        }

        private boolean isDisconnectedCache() {
            return channel == null;
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
