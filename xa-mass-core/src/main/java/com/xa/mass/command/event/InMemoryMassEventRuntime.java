package com.xa.mass.command.event;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local runtime for control-plane event handlers.
 */
public class InMemoryMassEventRuntime implements MassEventRuntime {

    private final Map<String, RegisteredHandler> handlers = new ConcurrentHashMap<>();

    @Override
    public void register(CoreEventDescriptor descriptor, MassEventHandler handler) {
        CoreEventDescriptor normalizedDescriptor = Objects.requireNonNull(descriptor, "descriptor");
        MassEventHandler normalizedHandler = Objects.requireNonNull(handler, "handler");
        RegisteredHandler previous = handlers.putIfAbsent(
                normalizedDescriptor.getEvent(),
                new RegisteredHandler(normalizedDescriptor, normalizedHandler)
        );
        if (previous != null) {
            throw new IllegalStateException("duplicate event register: " + normalizedDescriptor.getEvent());
        }
    }

    @Override
    public CoreEventResponse dispatch(CoreEventRequest request, CoreEventPrincipal principal) {
        CoreEventRequest normalizedRequest = Objects.requireNonNull(request, "request");
        RegisteredHandler registeredHandler = handlers.get(normalizedRequest.getEvent());
        if (registeredHandler == null) {
            return CoreEventResponse.failure("UNKNOWN_EVENT",
                    "unknown event: " + normalizedRequest.getEvent(),
                    normalizedRequest.getRequestId());
        }
        if (!registeredHandler.descriptor().isEnabled()) {
            return CoreEventResponse.failure("EVENT_DISABLED",
                    "event disabled: " + normalizedRequest.getEvent(),
                    normalizedRequest.getRequestId());
        }
        return registeredHandler.handler().handle(
                normalizedRequest,
                principal == null ? new CoreEventPrincipal(null, null) : principal
        );
    }

    @Override
    public CoreEventDescriptor getDescriptor(String event) {
        RegisteredHandler registeredHandler = handlers.get(event);
        return registeredHandler == null ? null : registeredHandler.descriptor();
    }

    @Override
    public List<CoreEventDescriptor> listDescriptors() {
        return handlers.values().stream()
                .map(RegisteredHandler::descriptor)
                .sorted(Comparator.comparing(CoreEventDescriptor::getEvent, String::compareToIgnoreCase))
                .toList();
    }

    @Override
    public boolean contains(String event) {
        return handlers.containsKey(event);
    }

    private record RegisteredHandler(CoreEventDescriptor descriptor, MassEventHandler handler) {
    }
}
