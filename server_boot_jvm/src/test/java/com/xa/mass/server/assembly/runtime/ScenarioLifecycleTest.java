package com.xa.mass.server.assembly.runtime;

import com.xa.mass.server.delivery.adapter.WorkerRouteVerificationBatcher;
import com.xa.mass.server.task.TaskCreationService;
import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.server.task.TaskLifecycleService;
import com.xa.mass.server.task.call.TaskCallSubmissionService;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.scenario.sms.ListenerService;
import com.xa.mass.scenario.messages.MessageTaskService;
import com.xa.mass.scenario.appchecks.AppCheckTaskService;
import com.xa.mass.serverboot.PreviewConfiguration;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterManager;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScenarioLifecycleTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void allScenariosStopBeforePlatformIncludingPartialStartupFailure(int failedRegistration) {
        var registrations = mock(ProjectDirectory.class);
        var submissions = mock(TaskCallSubmissionService.class);
        var results = mock(TaskDataService.class);
        var creation = mock(TaskCreationService.class);
        var lifecycle = mock(TaskLifecycleService.class);
        var adapters = mock(WorkerDeliveryAdapterManager.class);
        var verifier = mock(WorkerRouteVerificationBatcher.class);
        var initializer = mock(ServerWorkerGroupInitializer.class);
        var platform = new ServerConfiguredRuntimeLifecycleHost(initializer, mock(com.xa.mass.server.project.ProjectTaskInitializer.class), adapters, verifier);
        var registrationCount = new AtomicInteger();
        List<String> events = new CopyOnWriteArrayList<>();
        List<SmartLifecycle> scenarios = new CopyOnWriteArrayList<>();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("preview");
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "window-fixture", java.util.Map.of(
                    "xa.mass.worker-matching.groups.app-a-sim.assignment-window.window-millis", "60000",
                    "xa.mass.worker-matching.groups.app-b-sim.assignment-window.window-millis", "60000")));
            context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof ListenerService || bean instanceof MessageTaskService || bean instanceof AppCheckTaskService)
                        scenarios.add((SmartLifecycle) bean);
                    return bean;
                }
            });
            context.registerBean(ProjectDirectory.class, () -> registrations);
            context.registerBean(com.xa.mass.server.project.ProjectTaskQueryService.class,
                    () -> mock(com.xa.mass.server.project.ProjectTaskQueryService.class));
            context.registerBean(TaskCallSubmissionService.class, () -> submissions);
            context.registerBean(TaskDataService.class, () -> results);
            context.registerBean(TaskCreationService.class, () -> creation);
            context.registerBean(TaskLifecycleService.class, () -> lifecycle);
            context.registerBean(ServerConfiguredRuntimeLifecycleHost.class, () -> platform,
                    definition -> definition.setDestroyMethodName("stop"));
            context.register(PreviewConfiguration.class);
            doAnswer(call -> { events.add("platform-start"); return null; }).when(adapters).start();
            when(results.loadTaskItemResults(anyString(), anyList())).thenReturn(java.util.Map.of());
            when(registrations.requireManagedTaskId(anyString(), anyString())).thenAnswer(call -> {
                assertThat(platform.isRunning()).isTrue();
                events.add("register");
                if (registrationCount.incrementAndGet() == failedRegistration)
                    throw new IllegalStateException("registration unavailable");
                String id = call.getArgument(1);
                String project = call.getArgument(0);
                if (project.equals("app-checks")) assertThat(id).isIn("app-a-sim", "app-b-sim");
                else assertThat(id).isEqualTo("demo-sim");
                return "task-" + id;
            });
            doAnswer(call -> {
                assertThat(scenarios).hasSize(3).allSatisfy(scenario -> assertThat(scenario.isRunning()).isFalse());
                events.add("platform-close");
                return null;
            }).when(adapters).close();
            if (failedRegistration != 0) {
                assertThatThrownBy(context::refresh).hasRootCauseMessage("registration unavailable");
            } else {
                context.refresh();
                assertThat(scenarios).hasSize(3).allSatisfy(scenario -> {
                    assertThat(scenario.isRunning()).isTrue();
                    assertThat(scenario.getPhase()).isGreaterThan(platform.getPhase());
                });
                context.close();
                assertThatThrownBy(() -> ((ListenerService) scenarios.stream()
                        .filter(ListenerService.class::isInstance).findFirst().orElseThrow()).create(java.util.Map.of(
                                "requestId", "closed", "applicationId", "A", "country", "CN", "listenSeconds", 60)))
                        .isInstanceOf(ListenerService.ProductError.class);
            }
        }
        assertThat(events.getFirst()).isEqualTo("platform-start");
        assertThat(events.getLast()).isEqualTo("platform-close");
        assertThat(registrationCount).hasValue(failedRegistration == 0 ? 4 : failedRegistration);
        verify(adapters).close();
        verify(verifier).close();
    }
}
