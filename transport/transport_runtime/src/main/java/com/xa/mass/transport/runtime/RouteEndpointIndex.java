package com.xa.mass.transport.runtime;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Narrow runtime helper for managing adapter-local route bindings.
 *
 * <p>This owns only route-to-endpoint and handle-to-route index mechanics. It
 * does not own protocol send, endpoint close, lifecycle events, or transport
 * result semantics.</p>
 */
public final class RouteEndpointIndex<H, E> {

    private final ConcurrentHashMap<String, Entry<H, E>> entryByRouteKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<H, Binding> bindingByHandle = new ConcurrentHashMap<>();

    public synchronized BindResult<H, E> bind(String routeKey,
                                              String workerId,
                                              H handle,
                                              E endpoint,
                                              Predicate<E> isActive) {
        Objects.requireNonNull(routeKey, "routeKey");
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(isActive, "isActive");

        Entry<H, E> existing = entryByRouteKey.get(routeKey);
        if (existing != null
                && Objects.equals(existing.handle(), handle)
                && isActive.test(existing.endpoint())) {
            return new BindResult<>(existing, null, true);
        }

        if (existing != null && !Objects.equals(existing.handle(), handle)) {
            bindingByHandle.remove(existing.handle());
        }

        Entry<H, E> updated = new Entry<>(routeKey, workerId, handle, endpoint);
        entryByRouteKey.put(routeKey, updated);
        bindingByHandle.put(handle, new Binding(routeKey, workerId));
        return new BindResult<>(updated, existing, false);
    }

    public synchronized RemoveResult<H, E> removeByHandle(H handle) {
        if (handle == null) {
            return new RemoveResult<>(null, null, false);
        }
        Binding binding = bindingByHandle.remove(handle);
        if (binding == null) {
            return new RemoveResult<>(null, null, false);
        }
        Entry<H, E> current = entryByRouteKey.get(binding.routeKey());
        boolean removedCurrent = current != null && Objects.equals(current.handle(), handle);
        if (removedCurrent) {
            entryByRouteKey.remove(binding.routeKey());
        }
        return new RemoveResult<>(binding, current, removedCurrent);
    }

    public E endpointForRoute(String routeKey) {
        Entry<H, E> entry = entryByRouteKey.get(routeKey);
        return entry != null ? entry.endpoint() : null;
    }

    public Binding bindingForHandle(H handle) {
        return bindingByHandle.get(handle);
    }

    public Entry<H, E> entryForRoute(String routeKey) {
        return entryByRouteKey.get(routeKey);
    }

    public int routeCount() {
        return entryByRouteKey.size();
    }

    public List<Entry<H, E>> entries() {
        return List.copyOf(entryByRouteKey.values());
    }

    public synchronized void clear() {
        entryByRouteKey.clear();
        bindingByHandle.clear();
    }

    public record Binding(String routeKey, String workerId) {
    }

    public record Entry<H, E>(String routeKey, String workerId, H handle, E endpoint) {
    }

    public record BindResult<H, E>(Entry<H, E> currentEntry,
                                   Entry<H, E> previousEntry,
                                   boolean unchanged) {
    }

    public record RemoveResult<H, E>(Binding binding,
                                     Entry<H, E> removedEntry,
                                     boolean removedCurrentRoute) {
    }
}
