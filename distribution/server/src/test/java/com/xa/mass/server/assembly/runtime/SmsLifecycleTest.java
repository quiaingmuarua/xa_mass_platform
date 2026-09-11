package com.xa.mass.server.assembly.runtime;

import com.xa.mass.server.assembly.runtime.ServerConfiguredRuntimeLifecycleHost;
import com.xa.mass.server.assembly.runtime.ServerWorkerGroupInitializer;
import com.xa.mass.server.delivery.adapter.WorkerRouteVerificationBatcher;
import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.server.task.call.TaskCallSubmissionService;
import com.xa.mass.server.worker.group.WorkerGroupRegistrationService;
import com.xa.mass.sms.backend.ListenerService;
import com.xa.mass.sms.backend.SmsProductConfiguration;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterManager;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SmsLifecycleTest {
    @Test void productStopsAdmissionAndJobsBeforePlatformCloses() {
        verifyLifecycle(false);
    }

    @Test void failedProductInitializationClosesTheAlreadyStartedPlatform() {
        verifyLifecycle(true);
    }

    private void verifyLifecycle(boolean failRegistration) {
        var registrations = mock(WorkerGroupRegistrationService.class);
        var submissions = mock(TaskCallSubmissionService.class);
        var results = mock(TaskDataService.class);
        var adapters = mock(WorkerDeliveryAdapterManager.class);
        var verifier = mock(WorkerRouteVerificationBatcher.class);
        var initializer = mock(ServerWorkerGroupInitializer.class);
        var platform = new ServerConfiguredRuntimeLifecycleHost(initializer, adapters, verifier);
        List<String> events = new CopyOnWriteArrayList<>();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("sms-reception");
            context.registerBean(WorkerGroupRegistrationService.class, () -> registrations);
            context.registerBean(TaskCallSubmissionService.class, () -> submissions);
            context.registerBean(TaskDataService.class, () -> results);
            context.registerBean(ServerConfiguredRuntimeLifecycleHost.class, () -> platform,
                    definition -> definition.setDestroyMethodName("stop"));
            context.register(SmsProductConfiguration.class, com.xa.mass.distribution.ProductWorkerConfiguration.class);
            doAnswer(call -> { events.add("platform-start"); return null; }).when(adapters).start();
            when(results.loadTaskItemResults(anyString(), anyList())).thenReturn(java.util.Map.of());
            when(registrations.register(anyString(), anyMap(), anyList())).thenAnswer(call -> {
                assertThat(platform.isRunning()).isTrue();
                events.add("register");
                if (failRegistration) throw new IllegalStateException("registration unavailable");
                String id = call.getArgument(0);
                assertThat(id).isEqualTo("demo-sim");
                return new WorkerGroupRegistrationService.Registration(id, "task-" + id, "registered");
            });
            doAnswer(call -> {
                // Singleton destruction follows lifecycle shutdown, including failed refresh.
                events.add("platform-close");
                return null;
            }).when(adapters).close();
            if (failRegistration) {
                assertThatThrownBy(context::refresh).hasRootCauseMessage("registration unavailable");
                // Failed refresh invokes destroy methods, so the platform lifecycle bean must own cleanup.
            } else {
                context.refresh();
                var product = context.getBean(ListenerService.class);
                doAnswer(call -> {
                    assertThat(product.isRunning()).isFalse();
                    assertThatThrownBy(() -> product.create(java.util.Map.of("requestId", "closed", "applicationId", "A",
                            "country", "CN", "listenSeconds", 60))).isInstanceOf(ListenerService.ProductError.class);
                    events.add("platform-close");
                    return null;
                }).when(adapters).close();
                assertThat(product.getPhase()).isGreaterThan(platform.getPhase());
                context.close();
                assertThat(events).containsExactly("platform-start", "register", "platform-close");
            }
        }
        assertThat(events.getLast()).isEqualTo("platform-close");
        verify(adapters).close();
        verify(verifier).close();
    }
}
