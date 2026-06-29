package com.xa.mass.transport.starter;

import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.runtime.TransportRegistrationResolver;
import com.xa.mass.transport.runtime.embedded.EmbeddedAdapterRuntimeEnvironment;
import com.xa.mass.transport.runtime.embedded.EmbeddedAdapterRuntimeSpec;
import com.xa.mass.transport.runtime.embedded.EmbeddedTransportAdapterRuntime;
import com.xa.mass.transport.runtime.embedded.EmbeddedTransportAdapterRuntimeFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Fixed embedded adapter factory registry.
 *
 * <p>This is the single owner for embedded adapter type lookup and descriptor
 * generation. It intentionally does not discover factories dynamically.
 */
public final class EmbeddedAdapterRuntimeFactoryRegistry {

    private final Map<String, EmbeddedTransportAdapterRuntimeFactory> factoryByType;

    public EmbeddedAdapterRuntimeFactoryRegistry(List<EmbeddedTransportAdapterRuntimeFactory> factories) {
        this.factoryByType = indexFactories(factories);
    }

    public EmbeddedTransportAdapterRuntime create(EmbeddedAdapterRuntimeSpec spec,
                                                  EmbeddedAdapterRuntimeEnvironment environment) {
        return Objects.requireNonNull(factory(spec).create(spec, environment), "factory.create");
    }

    public TransportAdapterDescriptor descriptor(EmbeddedAdapterRuntimeSpec spec) {
        return Objects.requireNonNull(factory(spec).descriptor(spec), "factory.descriptor");
    }

    public List<TransportAdapterDescriptor> descriptors(List<EmbeddedAdapterRuntimeSpec> specs) {
        List<TransportAdapterDescriptor> descriptors = new ArrayList<>();
        for (EmbeddedAdapterRuntimeSpec spec : List.copyOf(Objects.requireNonNull(specs, "specs"))) {
            descriptors.add(descriptor(spec));
        }
        return List.copyOf(descriptors);
    }

    public TransportRegistrationResolver registrationResolver(List<EmbeddedAdapterRuntimeSpec> specs) {
        return new TransportRegistrationResolver(descriptors(specs));
    }

    private EmbeddedTransportAdapterRuntimeFactory factory(EmbeddedAdapterRuntimeSpec spec) {
        Objects.requireNonNull(spec, "spec");
        EmbeddedTransportAdapterRuntimeFactory factory = factoryByType.get(spec.type());
        if (factory == null) {
            throw new IllegalArgumentException("Unsupported embedded adapter type '" + spec.type()
                    + "'; available types=" + factoryByType.keySet());
        }
        return factory;
    }

    private static Map<String, EmbeddedTransportAdapterRuntimeFactory> indexFactories(
            List<EmbeddedTransportAdapterRuntimeFactory> factories) {
        LinkedHashMap<String, EmbeddedTransportAdapterRuntimeFactory> indexed = new LinkedHashMap<>();
        for (EmbeddedTransportAdapterRuntimeFactory factory : List.copyOf(Objects.requireNonNull(factories, "factories"))) {
            String type = normalizeType(factory.type());
            EmbeddedTransportAdapterRuntimeFactory existing = indexed.putIfAbsent(type, factory);
            if (existing != null) {
                throw new IllegalArgumentException("Duplicate embedded adapter runtime factory type: " + type);
            }
        }
        if (indexed.isEmpty()) {
            throw new IllegalArgumentException("At least one embedded adapter runtime factory is required");
        }
        return Map.copyOf(indexed);
    }

    private static String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("adapter runtime factory type must not be blank");
        }
        return type.trim().toLowerCase(Locale.ROOT);
    }
}
