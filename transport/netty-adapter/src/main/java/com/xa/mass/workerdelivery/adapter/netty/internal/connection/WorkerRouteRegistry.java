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

    private static final AttributeKey<String> IDENTIFIED_WORKER_ID =
            AttributeKey.valueOf(
                    WorkerRouteRegistry.class,
                    "identifiedWorkerId"
            );
    private static final AttributeKey<Boolean> ROUTE_VERIFIED =
            AttributeKey.valueOf(
                    WorkerRouteRegistry.class,
                    "routeVerified"
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
                        return RouteEntry.verifying(
                                requiredChannel,
                                null,
                                null
                        );
                    }
                    if (current.verifyingChannel() != null) {
                        admission.set(verificationBusy());
                        return current;
                    }
                    if (verificationFresh(current, now)) {
                        Channel previous = current.activeChannel();
                        admission.set(new IdentityAdmission(
                                IdentityAdmissionKind.VERIFIED_ACTIVATED,
                                distinctReplacement(previous, requiredChannel),
                                previous == null
                        ));
                        return RouteEntry.connected(
                                requiredChannel,
                                current.verifiedAtNanos()
                        );
                    }
                    admission.set(verificationClaimed());
                    return RouteEntry.verifying(
                            requiredChannel,
                            current.activeChannel(),
                            current.activeChannel() == null
                                    ? null
                                    : current.verifiedAtNanos()
                    );
                }
        );
        IdentityAdmission result = admission.get();
        if (result.kind() == IdentityAdmissionKind.VERIFICATION_CLAIMED) {
            identify(requiredChannel, requiredWorkerId, false);
        } else if (result.kind()
                == IdentityAdmissionKind.VERIFIED_ACTIVATED) {
            identify(requiredChannel, requiredWorkerId, true);
        }
        return result;
    }

    InboundInspection inspectInbound(Channel channel) {
        Channel requiredChannel = Objects.requireNonNull(channel, "channel");
        String workerId = identifiedWorkerId(requiredChannel);
        if (workerId == null) {
            return new InboundInspection(
                    InboundKind.IDENTITY_REQUIRED,
                    null
            );
        }
        RouteEntry route = quietRoute(workerId);
        if (route != null
                && route.verifyingChannel() == requiredChannel) {
            return new InboundInspection(
                    InboundKind.VERIFICATION_PENDING,
                    workerId
            );
        }
        if (isRouteVerified(requiredChannel)) {
            return new InboundInspection(InboundKind.VERIFIED, workerId);
        }
        return new InboundInspection(InboundKind.INVALID, workerId);
    }

    VerificationActivation completeVerificationAndActivate(
            String workerId,
            Channel expectedChannel
    ) {
        String requiredWorkerId = requireWorkerId(workerId);
        Channel requiredChannel = Objects.requireNonNull(
                expectedChannel,
                "expectedChannel"
        );
        AtomicReference<VerificationActivation> activation =
                new AtomicReference<>(VerificationActivation.notCompleted());
        routesByWorkerId.asMap().computeIfPresent(
                requiredWorkerId,
                (ignored, current) -> {
                    if (current.verifyingChannel() != requiredChannel
                            || !requiredWorkerId.equals(
                            identifiedWorkerId(requiredChannel)
                    )) {
                        return current;
                    }
                    Channel previous = current.activeChannel();
                    activation.set(new VerificationActivation(
                            true,
                            distinctReplacement(previous, requiredChannel),
                            previous == null
                    ));
                    return RouteEntry.connected(
                            requiredChannel,
                            ticker.read()
                    );
                }
        );
        VerificationActivation result = activation.get();
        if (result.completed()) {
            requiredChannel.attr(ROUTE_VERIFIED).set(true);
        }
        return result;
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
                    if (current.verifyingChannel() != requiredChannel) {
                        return current;
                    }
                    cancelled.set(true);
                    return withoutVerifying(current, ticker.read());
                }
        );
        return cancelled.get();
    }

    Channel activeChannel(String workerId) {
        RouteEntry route = quietRoute(requireWorkerId(workerId));
        return route == null ? null : route.activeChannel();
    }

    boolean isCurrentConnected(String workerId, Channel expectedChannel) {
        String requiredWorkerId = requireWorkerId(workerId);
        Channel requiredChannel = Objects.requireNonNull(
                expectedChannel,
                "expectedChannel"
        );
        RouteEntry route = quietRoute(requiredWorkerId);
        return route != null && route.activeChannel() == requiredChannel;
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
                        if (current.activeChannel() == null) {
                            return current;
                        }
                        removed.set(current.activeChannel());
                        return withoutActive(current, ticker.read());
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
                    if (current.activeChannel() != requiredChannel) {
                        return current;
                    }
                    deactivated.set(true);
                    return withoutActive(current, ticker.read());
                }
        );
        return deactivated.get();
    }

    String onChannelClosed(Channel channel) {
        Channel requiredChannel = Objects.requireNonNull(channel, "channel");
        String workerId = requiredChannel.attr(IDENTIFIED_WORKER_ID)
                .getAndSet(null);
        requiredChannel.attr(ROUTE_VERIFIED).set(null);
        if (workerId == null) {
            return null;
        }
        AtomicBoolean activeRemoved = new AtomicBoolean();
        routesByWorkerId.asMap().computeIfPresent(
                workerId,
                (ignored, current) -> {
                    RouteEntry updated = current;
                    if (updated.verifyingChannel() == requiredChannel) {
                        updated = withoutVerifying(updated, ticker.read());
                    }
                    if (updated != null
                            && updated.activeChannel() == requiredChannel) {
                        activeRemoved.set(true);
                        updated = withoutActive(updated, ticker.read());
                    }
                    return updated;
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

    private RouteEntry withoutActive(RouteEntry route, long now) {
        if (route.verifyingChannel() != null) {
            return new RouteEntry(
                    null,
                    route.verifyingChannel(),
                    route.verifiedAtNanos()
            );
        }
        return verificationFresh(route, now)
                ? RouteEntry.disconnected(route.verifiedAtNanos())
                : null;
    }

    private RouteEntry withoutVerifying(RouteEntry route, long now) {
        if (route.activeChannel() != null) {
            return new RouteEntry(
                    route.activeChannel(),
                    null,
                    route.verifiedAtNanos()
            );
        }
        return verificationFresh(route, now)
                ? RouteEntry.disconnected(route.verifiedAtNanos())
                : null;
    }

    private static WorkerConnectionState connectionState(RouteEntry route) {
        if (route == null) {
            return WorkerConnectionState.UNKNOWN;
        }
        if (route.activeChannel() != null
                && route.activeChannel().isActive()) {
            return WorkerConnectionState.CONNECTED;
        }
        if (route.verifyingChannel() != null) {
            return WorkerConnectionState.VERIFYING;
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

    private static void identify(
            Channel channel,
            String workerId,
            boolean verified
    ) {
        channel.attr(IDENTIFIED_WORKER_ID).set(workerId);
        channel.attr(ROUTE_VERIFIED).set(verified);
    }

    private static String identifiedWorkerId(Channel channel) {
        return channel.attr(IDENTIFIED_WORKER_ID).get();
    }

    private static boolean isRouteVerified(Channel channel) {
        return Boolean.TRUE.equals(channel.attr(ROUTE_VERIFIED).get());
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

    record VerificationActivation(
            boolean completed,
            Channel replacedChannel,
            boolean becameAvailable
    ) {

        private static VerificationActivation notCompleted() {
            return new VerificationActivation(false, null, false);
        }
    }

    enum InboundKind {
        IDENTITY_REQUIRED,
        VERIFICATION_PENDING,
        VERIFIED,
        INVALID
    }

    record InboundInspection(InboundKind kind, String workerId) {

        InboundInspection {
            Objects.requireNonNull(kind, "kind");
        }
    }

    private record RouteEntry(
            Channel activeChannel,
            Channel verifyingChannel,
            Long verifiedAtNanos
    ) {

        private RouteEntry {
            if (activeChannel == null
                    && verifyingChannel == null
                    && verifiedAtNanos == null) {
                throw new IllegalArgumentException(
                        "route entry must contain route evidence"
                );
            }
            if (activeChannel != null && activeChannel == verifyingChannel) {
                throw new IllegalArgumentException(
                        "active and verifying Channel must differ"
                );
            }
            if (activeChannel != null && verifiedAtNanos == null) {
                throw new IllegalArgumentException(
                        "active route must have verification evidence"
                );
            }
        }

        private static RouteEntry verifying(
                Channel verifyingChannel,
                Channel activeChannel,
                Long verifiedAtNanos
        ) {
            return new RouteEntry(
                    activeChannel,
                    Objects.requireNonNull(
                            verifyingChannel,
                            "verifyingChannel"
                    ),
                    verifiedAtNanos
            );
        }

        private static RouteEntry connected(
                Channel activeChannel,
                long verifiedAtNanos
        ) {
            return new RouteEntry(
                    Objects.requireNonNull(activeChannel, "activeChannel"),
                    null,
                    verifiedAtNanos
            );
        }

        private static RouteEntry disconnected(long verifiedAtNanos) {
            return new RouteEntry(null, null, verifiedAtNanos);
        }

        private boolean isDisconnectedCache() {
            return activeChannel == null && verifyingChannel == null;
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
