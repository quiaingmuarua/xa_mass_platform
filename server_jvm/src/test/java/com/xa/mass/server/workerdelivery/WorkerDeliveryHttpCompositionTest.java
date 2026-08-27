package com.xa.mass.server.workerdelivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.kernel.delivery.TaskResultRuntime;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.server.api.ApiExceptionHandler;
import com.xa.mass.server.api.RequestIdFilter;
import com.xa.mass.server.api.v1.workerdelivery.AdapterBatchDeliveryController;
import com.xa.mass.server.api.v1.workerdelivery.WorkerPointDeliveryController;
import com.xa.mass.kernel.task.TaskLifecycleCommands;
import com.xa.mass.server.kernelredis.KernelRedisConfiguration;
import com.xa.mass.server.kernelredis.KernelRedisHealthIndicator;
import com.xa.mass.server.workerbinding.WorkerBindingService;
import com.xa.mass.server.workerdelivery.application.WorkerDeliveryService;
import com.xa.mass.server.directcall.DirectCallService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class WorkerDeliveryHttpCompositionTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            IsolatedWorkerDeliveryHttpApplication.class
                    )
                    .withPropertyValues(
                            "xa.mass.redis.url="
                                    + "redis://127.0.0.1:6379/15",
                            "xa.mass.redis.scope="
                                    + "test_worker_delivery_composition"
                    )
                    .withBean(
                            WorkerBindingService.class,
                            () -> org.mockito.Mockito.mock(
                                    WorkerBindingService.class
                            )
                    )
                    .withBean(
                            WorkerResourceCatalog.class,
                            () -> org.mockito.Mockito.mock(
                                    WorkerResourceCatalog.class
                            )
                    )
                    .withBean(
                            DirectCallService.class,
                            () -> org.mockito.Mockito.mock(
                                    DirectCallService.class
                            )
                    );

    @Test
    void assemblesWithoutTaskResourceOrPythonKernelOwners() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(WorkerCommandRuntime.class);
            assertThat(context).hasSingleBean(TaskResultRuntime.class);
            assertThat(context).hasSingleBean(
                    WorkerServiceabilityRuntime.class
            );
            assertThat(context).hasSingleBean(WorkerDeliveryService.class);
            assertThat(context)
                    .hasSingleBean(WorkerPointDeliveryController.class);
            assertThat(context)
                    .hasSingleBean(AdapterBatchDeliveryController.class);
            assertThat(context)
                    .hasSingleBean(KernelRedisHealthIndicator.class);
            assertThat(context).hasSingleBean(ApiExceptionHandler.class);
            assertThat(context).hasSingleBean(RequestIdFilter.class);

            assertThat(context).doesNotHaveBean(TaskRuntime.class);
            assertThat(context).doesNotHaveBean(TaskResourceCatalog.class);
            assertThat(context).doesNotHaveBean(
                    com.xa.mass.kernel.worker.WorkerRuntime.class
            );
            assertThat(context).hasSingleBean(WorkerResourceCatalog.class);
            assertThat(context).doesNotHaveBean(
                    TaskLifecycleCommands.class
            );
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            KernelRedisConfiguration.class,
            WorkerDeliveryOwnerAssemblyConfiguration.class,
            WorkerDeliveryConfiguration.class,
            WorkerPointDeliveryController.class,
            AdapterBatchDeliveryController.class,
            ApiExceptionHandler.class,
            RequestIdFilter.class
    })
    static class IsolatedWorkerDeliveryHttpApplication {
    }
}
