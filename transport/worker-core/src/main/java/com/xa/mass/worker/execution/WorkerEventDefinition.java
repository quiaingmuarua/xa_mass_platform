package com.xa.mass.worker.execution;

import java.util.Objects;
import java.util.regex.Pattern;

public final class WorkerEventDefinition<P> {

    private static final String EXTENSION_WORKER_PREFIX =
            "extension.worker.";
    private static final String PLATFORM_WORKER_PREFIX =
            "platform.worker.";
    private static final Pattern CAPABILITY_NAME_PATTERN = Pattern.compile(
            "[a-z][a-z0-9-]*(?:\\.[a-z][a-z0-9-]*)*"
    );

    private final String eventName;
    private final WorkerEventParameterResolver<P> resolver;
    private final WorkerEventHandler<P> handler;

    private WorkerEventDefinition(
            String eventName,
            WorkerEventParameterResolver<P> resolver,
            WorkerEventHandler<P> handler
    ) {
        this.eventName = requireNonBlank(eventName, "eventName");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    public static <P> WorkerEventDefinition<P> extension(
            String capabilityName,
            WorkerEventParameterResolver<P> resolver,
            WorkerEventHandler<P> handler
    ) {
        return create(
                EXTENSION_WORKER_PREFIX,
                capabilityName,
                resolver,
                handler
        );
    }

    static <P> WorkerEventDefinition<P> platform(
            String capabilityName,
            WorkerEventParameterResolver<P> resolver,
            WorkerEventHandler<P> handler
    ) {
        return create(
                PLATFORM_WORKER_PREFIX,
                capabilityName,
                resolver,
                handler
        );
    }

    public String eventName() {
        return eventName;
    }

    public WorkerEventParameterResolver<P> parameterResolver() {
        return resolver;
    }

    public WorkerEventHandler<P> handler() {
        return handler;
    }

    private static <P> WorkerEventDefinition<P> create(
            String prefix,
            String capabilityName,
            WorkerEventParameterResolver<P> resolver,
            WorkerEventHandler<P> handler
    ) {
        String requiredCapabilityName = requireCapabilityName(
                capabilityName
        );
        return new WorkerEventDefinition<>(
                prefix + requiredCapabilityName,
                resolver,
                handler
        );
    }

    private static String requireCapabilityName(String value) {
        String required = requireNonBlank(value, "capabilityName");
        if (required.startsWith("platform.")
                || required.startsWith("extension.")
                || !CAPABILITY_NAME_PATTERN.matcher(required).matches()) {
            throw new IllegalArgumentException(
                    "capabilityName must be a lowercase dotted name"
            );
        }
        return required;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    name + " must be non-blank"
            );
        }
        return value;
    }
}
