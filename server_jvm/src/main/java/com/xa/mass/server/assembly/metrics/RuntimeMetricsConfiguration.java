package com.xa.mass.server.assembly.metrics;

import com.xa.mass.kernel.pacer.KernelPacerRuntime;
import com.xa.mass.server.api.AccessKeyFilter;
import com.xa.mass.server.api.ApiExceptionHandler;
import com.xa.mass.server.assembly.pacer.WorkerObservationMetrics;
import com.xa.mass.server.delivery.directcall.DirectCallRegistry;
import com.xa.mass.server.task.call.TaskRpcWaitRegistry;
import com.xa.mass.server.task.observation.TaskLeaseProjectionService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Isolated Spring assembly for Micrometer runtime metrics.
 * No other production package in the repository imports {@code io.micrometer}.
 */
@Configuration(proxyBeanMethods = false)
public class RuntimeMetricsConfiguration {

    @Bean
    SmartInitializingSingleton runtimeMetricsRegistrar(ApplicationContext context) {
        return () -> {
            MeterRegistry registry = context.getBeanProvider(MeterRegistry.class).getIfAvailable();
            if (registry == null) {
                return;
            }
            var recorder = new RuntimeMetricsRecorder(registry);
            context.getBeanProvider(ApiExceptionHandler.class)
                    .ifAvailable(handler -> handler.setErrorObserver(recorder));
            context.getBeanProvider(AccessKeyFilter.class)
                    .ifAvailable(filter -> filter.setErrorObserver(recorder));
            context.getBeanProvider(KernelPacerRuntime.class).ifAvailable(pacer -> {
                pacer.setMetricsSink(recorder);
                RuntimeMetricsBinder.bindPacer(registry, pacer);
            });
            context.getBeanProvider(TaskRpcWaitRegistry.class).ifAvailable(waiters -> {
                waiters.setMetricsSink(recorder);
                RuntimeMetricsBinder.bindRpcWaiters(registry, waiters);
            });
            context.getBeanProvider(DirectCallRegistry.class).ifAvailable(directCalls -> {
                directCalls.setMetricsSink(recorder);
                RuntimeMetricsBinder.bindDirectCalls(registry, directCalls);
            });
            context.getBeanProvider(WorkerObservationMetrics.class)
                    .ifAvailable(metrics -> RuntimeMetricsBinder.bindWorkerObservations(registry, metrics));
            context.getBeanProvider(TaskLeaseProjectionService.class)
                    .ifAvailable(projections -> RuntimeMetricsBinder.bindLeaseProjections(registry, projections));
        };
    }
}
