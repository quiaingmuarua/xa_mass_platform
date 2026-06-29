package com.xa.mass.transport.starter;

import com.xa.mass.transport.channel.ResultIngressEntry;
import com.xa.mass.transport.channel.TransportResultIngressChannel;
import com.xa.mass.transport.runtime.ResolvedPullWorkerTransport;
import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.runtime.TransportRuntimeRegistry;
import com.xa.mass.transport.runtime.TransportResultIngressQueue;
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
 * Embedded adapter runtime starter.
 *
 * <p>The starter owns adapter runtime creation/start/stop and runtime binding
 * registration. Queue stores, lease stores, executors, and sinks are backend
 * ports injected at construction time; adapter declarations stay as small
 * {@link EmbeddedAdapterRuntimeSpec} records.
 */
public final class EmbeddedAdapterStarter implements AutoCloseable {

    private final EmbeddedAdapterRuntimeEnvironment environment;
    private final EmbeddedAdapterRuntimeFactoryRegistry factoryRegistry;
    private final Map<String, EmbeddedTransportAdapterRuntime> runtimeByAdapterId = new LinkedHashMap<>();
    private final TransportResultIngressChannel resultIngressChannel;

    private TransportRuntimeRegistry runtimeRegistry;

    public EmbeddedAdapterStarter(EmbeddedAdapterRuntimeEnvironment environment,
                                  List<EmbeddedTransportAdapterRuntimeFactory> factories) {
        this(environment, new EmbeddedAdapterRuntimeFactoryRegistry(factories));
    }

    public EmbeddedAdapterStarter(EmbeddedAdapterRuntimeEnvironment environment,
                                  EmbeddedAdapterRuntimeFactoryRegistry factoryRegistry) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.factoryRegistry = Objects.requireNonNull(factoryRegistry, "factoryRegistry");
        this.resultIngressChannel = this::offerDefaultResult;
    }

    public EmbeddedAdapterCreateResult create(List<EmbeddedAdapterRuntimeSpec> specs) {
        List<EmbeddedAdapterRuntimeSpec> requestedSpecs = List.copyOf(
                Objects.requireNonNull(specs, "specs")
        );
        if (runtimeRegistry != null || !runtimeByAdapterId.isEmpty()) {
            throw new IllegalStateException("Embedded adapter runtimes have already been created");
        }
        List<String> adapterIds = new ArrayList<>();
        List<TransportBinding> bindings = new ArrayList<>();
        Map<String, EmbeddedTransportAdapterRuntime> createdRuntimes = new LinkedHashMap<>();
        try {
            for (EmbeddedAdapterRuntimeSpec spec : requestedSpecs) {
                validateResultQueueKey(spec);
                EmbeddedTransportAdapterRuntime runtime = factoryRegistry.create(spec, environment);
                String adapterId = normalizeAdapterId(runtime.descriptor().getAdapterId());
                if (createdRuntimes.putIfAbsent(adapterId, runtime) != null) {
                    throw new IllegalArgumentException("Duplicate embedded adapterId configured: " + adapterId);
                }
                adapterIds.add(adapterId);
                bindings.add(runtime.binding());
            }
            if (!bindings.isEmpty()) {
                runtimeRegistry = new TransportRuntimeRegistry(resultIngressChannel, environment.endpointLeaseStore(), bindings);
            }
            runtimeByAdapterId.putAll(createdRuntimes);
            return new EmbeddedAdapterCreateResult(adapterIds);
        } catch (RuntimeException e) {
            createdRuntimes.values().forEach(this::closeQuietly);
            throw e;
        }
    }

    public void start(String adapterId) {
        runtime(adapterId).start();
    }

    public void startAll() {
        List<EmbeddedTransportAdapterRuntime> started = new ArrayList<>();
        try {
            for (EmbeddedTransportAdapterRuntime runtime : runtimeByAdapterId.values()) {
                runtime.start();
                started.add(runtime);
            }
        } catch (RuntimeException e) {
            for (int i = started.size() - 1; i >= 0; i--) {
                closeQuietly(started.get(i));
            }
            throw e;
        }
    }

    public void close(String adapterId) {
        runtime(adapterId).close();
    }

    public boolean isRunning() {
        if (runtimeByAdapterId.isEmpty()) {
            return true;
        }
        return runtimeByAdapterId.values().stream().allMatch(EmbeddedTransportAdapterRuntime::isRunning);
    }

    public List<TransportAdapterDescriptor> descriptors() {
        return runtimeByAdapterId.values().stream()
                .map(EmbeddedTransportAdapterRuntime::descriptor)
                .toList();
    }

    public String resolveRegistrationAdapterId(String requestedAdapterId, String transportHint) {
        return requireRuntimeRegistry().resolveRegistrationAdapterId(requestedAdapterId, transportHint);
    }

    public TransportBinding resolveBinding(String requestedAdapterId, String transportHint) {
        return requireRuntimeRegistry().resolveBinding(requestedAdapterId, transportHint);
    }

    public TransportBinding resolveBindingByAdapterId(String adapterId) {
        return requireRuntimeRegistry().resolveBindingByAdapterId(adapterId);
    }

    public ResolvedPullWorkerTransport resolvePullWorkerTransport(String workerId,
                                                                  String workerGroupId,
                                                                  String requestedAdapterId,
                                                                  String transportHint) {
        return requireRuntimeRegistry().resolvePullWorkerTransport(
                workerId,
                workerGroupId,
                requestedAdapterId,
                transportHint
        );
    }

    @Override
    public void close() {
        List<EmbeddedTransportAdapterRuntime> runtimes = new ArrayList<>(runtimeByAdapterId.values());
        runtimeByAdapterId.clear();
        runtimeRegistry = null;
        for (int i = runtimes.size() - 1; i >= 0; i--) {
            closeQuietly(runtimes.get(i));
        }
    }

    private boolean offerDefaultResult(ResultIngressEntry entry) {
        return environment.resultQueue().offer(TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY, entry);
    }

    private EmbeddedTransportAdapterRuntime runtime(String adapterId) {
        EmbeddedTransportAdapterRuntime runtime = runtimeByAdapterId.get(normalizeAdapterId(adapterId));
        if (runtime == null) {
            throw new IllegalArgumentException("No embedded adapter runtime is registered for adapterId '"
                    + adapterId + "'; available adapterIds=" + runtimeByAdapterId.keySet());
        }
        return runtime;
    }

    private TransportRuntimeRegistry requireRuntimeRegistry() {
        if (runtimeRegistry == null) {
            throw new IllegalStateException("No embedded adapter runtime registry is available");
        }
        return runtimeRegistry;
    }

    private static void validateResultQueueKey(EmbeddedAdapterRuntimeSpec spec) {
        if (!TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY.equals(spec.resultQueueKey())) {
            throw new IllegalArgumentException("Embedded adapter resultQueueKey must be '"
                    + TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY
                    + "' in v1; actual=" + spec.resultQueueKey());
        }
    }

    private static String normalizeAdapterId(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must not be blank");
        }
        return adapterId.trim().toLowerCase(Locale.ROOT);
    }

    private void closeQuietly(EmbeddedTransportAdapterRuntime runtime) {
        try {
            runtime.close();
        } catch (RuntimeException ignored) {
            // Best-effort rollback/close path.
        }
    }
}
