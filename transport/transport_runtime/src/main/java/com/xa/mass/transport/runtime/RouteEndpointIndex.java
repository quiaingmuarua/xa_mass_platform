package com.xa.mass.transport.runtime;

import java.util.List;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
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

    private final ConcurrentHashMap<String, LinkedHashMap<H, Entry<H, E>>> entriesByRouteKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LinkedHashMap<H, Entry<H, E>>> entriesByWorkerId = new ConcurrentHashMap<>();
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

        Binding existingBinding = bindingByHandle.get(handle);
        Entry<H, E> existing = existingBinding == null ? null : entryForHandle(handle, existingBinding.routeKey());
        if (existing != null
                && Objects.equals(existing.handle(), handle)
                && Objects.equals(existing.routeKey(), routeKey)
                && Objects.equals(existing.workerId(), workerId)
                && isActive.test(existing.endpoint())) {
            return new BindResult<>(existing, null, true);
        }

        if (existingBinding != null) {
            removeEntry(existingBinding.routeKey(), handle);
        }

        Entry<H, E> updated = new Entry<>(routeKey, workerId, handle, endpoint);
        entriesByRouteKey.compute(routeKey, (ignored, entries) -> {
            LinkedHashMap<H, Entry<H, E>> next = entries == null ? new LinkedHashMap<>() : entries;
            next.put(handle, updated);
            return next;
        });
        entriesByWorkerId.compute(workerId, (ignored, entries) -> {
            LinkedHashMap<H, Entry<H, E>> next = entries == null ? new LinkedHashMap<>() : entries;
            next.put(handle, updated);
            return next;
        });
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
        Entry<H, E> removed = removeEntry(binding.routeKey(), handle);
        return new RemoveResult<>(binding, removed, removed != null);
    }

    public E endpointForRoute(String routeKey) {
        Entry<H, E> entry = entryForRoute(routeKey);
        return entry != null ? entry.endpoint() : null;
    }

    public Binding bindingForHandle(H handle) {
        return bindingByHandle.get(handle);
    }

    public Entry<H, E> entryForRoute(String routeKey) {
        List<Entry<H, E>> entries = entriesForRoute(routeKey);
        return entries.isEmpty() ? null : entries.getFirst();
    }

    public List<Entry<H, E>> entriesForRoute(String routeKey) {
        if (routeKey == null) {
            return List.of();
        }
        LinkedHashMap<H, Entry<H, E>> entries = entriesByRouteKey.get(routeKey);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        return List.copyOf(entries.values());
    }

    public Entry<H, E> entryForWorker(String workerId) {
        List<Entry<H, E>> entries = entriesForWorker(workerId);
        return entries.isEmpty() ? null : entries.getFirst();
    }

    public List<Entry<H, E>> entriesForWorker(String workerId) {
        if (workerId == null) {
            return List.of();
        }
        LinkedHashMap<H, Entry<H, E>> entries = entriesByWorkerId.get(workerId);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        return List.copyOf(entries.values());
    }

    public int routeCount() {
        return entriesByRouteKey.size();
    }

    public List<Entry<H, E>> entries() {
        List<Entry<H, E>> entries = new ArrayList<>();
        entriesByRouteKey.values().stream()
                .map(LinkedHashMap::values)
                .flatMap(Collection::stream)
                .sorted(Comparator.comparing(Entry::routeKey))
                .forEach(entries::add);
        return List.copyOf(entries);
    }

    public synchronized void clear() {
        entriesByRouteKey.clear();
        entriesByWorkerId.clear();
        bindingByHandle.clear();
    }

    private Entry<H, E> entryForHandle(H handle, String routeKey) {
        LinkedHashMap<H, Entry<H, E>> entries = entriesByRouteKey.get(routeKey);
        return entries != null ? entries.get(handle) : null;
    }

    private Entry<H, E> removeEntry(String routeKey, H handle) {
        if (routeKey == null || handle == null) {
            return null;
        }
        LinkedHashMap<H, Entry<H, E>> entries = entriesByRouteKey.get(routeKey);
        if (entries == null) {
            return null;
        }
        Entry<H, E> removed = entries.remove(handle);
        if (entries.isEmpty()) {
            entriesByRouteKey.remove(routeKey, entries);
        }
        if (removed != null) {
            LinkedHashMap<H, Entry<H, E>> workerEntries = entriesByWorkerId.get(removed.workerId());
            if (workerEntries != null) {
                workerEntries.remove(handle);
                if (workerEntries.isEmpty()) {
                    entriesByWorkerId.remove(removed.workerId(), workerEntries);
                }
            }
        }
        return removed;
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
