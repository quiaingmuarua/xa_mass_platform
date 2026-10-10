package com.xa.mass.server.assembly.runtime;

import com.xa.mass.server.delivery.adapter.WorkerRouteVerificationBatcher;
import com.xa.mass.server.task.TaskCreationService;
import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.server.task.TaskLifecycleService;
import com.xa.mass.server.task.call.TaskCallSubmissionService;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.scenario.sms.SmsReceptionService;
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
    @ValueSource(ints = {-1, 0, 1, 2, 3, 4})
    void allScenariosStopBeforePlatformIncludingPartialStartupFailure(int failedRegistration) {
        var registrations = mock(ProjectDirectory.class);
        var submissions = mock(TaskCallSubmissionService.class);
        var results = mock(TaskDataService.class);
        var creation = mock(TaskCreationService.class);
        var lifecycle = mock(TaskLifecycleService.class);
        var adapters = mock(WorkerDeliveryAdapterManager.class);
        var verifier = mock(WorkerRouteVerificationBatcher.class);
        var initializer = mock(ServerWorkerGroupInitializer.class);
        var requirements = mock(ProjectWorkerRequirementsValidator.class);
        var platform = new ServerConfiguredRuntimeLifecycleHost(initializer, requirements, mock(com.xa.mass.server.project.ProjectTaskInitializer.class), adapters, verifier);
        if (failedRegistration == -1)
            doThrow(new IllegalStateException("dependency unavailable")).when(requirements).validate();
        var registrationCount = new AtomicInteger();
        List<String> events = new CopyOnWriteArrayList<>();
        List<SmartLifecycle> scenarios = new CopyOnWriteArrayList<>();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("preview");
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "window-fixture", java.util.Map.of(
                    "xa.mass.scenarios.messages.applications[0].id", "demo",
                    "xa.mass.scenarios.messages.applications[0].label", "Demo",
                    "xa.mass.scenarios.messages.applications[0].worker-group-id", "demo-sim",
                    "xa.mass.worker-pools.assignment-window.groups.app-a-sim.window-millis", "60000",
                    "xa.mass.worker-pools.assignment-window.groups.app-b-sim.window-millis", "60000",
                    "xa.mass.worker-pools.assignment-window.groups.app-a-sim.max-count", "10",
                    "xa.mass.worker-pools.assignment-window.groups.app-b-sim.max-count", "10")));
            context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof SmsReceptionService || bean instanceof MessageTaskService || bean instanceof AppCheckTaskService)
                        scenarios.add((SmartLifecycle) bean);
                    return bean;
                }
            });
            context.registerBean(ProjectDirectory.class, () -> registrations);
            context.registerBean(com.xa.mass.server.project.ProjectTaskQueryService.class,
                    () -> mock(com.xa.mass.server.project.ProjectTaskQueryService.class));
            context.registerBean(TaskCallSubmissionService.class, () -> submissions);
            context.registerBean(TaskDataService.class, () -> results);
            context.registerBean(com.xa.mass.server.task.call.TaskRpcCallService.class,
                    () -> mock(com.xa.mass.server.task.call.TaskRpcCallService.class));
            context.registerBean(com.xa.mass.server.task.observation.TaskLeaseProjectionService.class,
                    () -> mock(com.xa.mass.server.task.observation.TaskLeaseProjectionService.class));
            context.registerBean(TaskCreationService.class, () -> creation);
            context.registerBean(TaskLifecycleService.class, () -> lifecycle);
            context.registerBean(com.xa.mass.server.operation.OperationGuard.class);
            context.registerBean(com.xa.mass.server.task.result.TaskResultsExportService.class,
                    () -> mock(com.xa.mass.server.task.result.TaskResultsExportService.class));
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
            if (failedRegistration == -1) {
                assertThatThrownBy(context::refresh).hasRootCauseMessage("dependency unavailable");
                assertThat(scenarios).hasSize(3).allSatisfy(scenario -> assertThat(scenario.isRunning()).isFalse());
            } else if (failedRegistration != 0) {
                assertThatThrownBy(context::refresh).hasRootCauseMessage("registration unavailable");
            } else {
                context.refresh();
                assertThat(scenarios).hasSize(3).allSatisfy(scenario -> {
                    assertThat(scenario.isRunning()).isTrue();
                    assertThat(scenario.getPhase()).isGreaterThan(platform.getPhase());
                });
                context.close();
                assertThatThrownBy(() -> ((SmsReceptionService) scenarios.stream()
                        .filter(SmsReceptionService.class::isInstance).findFirst().orElseThrow()).lease(java.util.Map.of(
                                "applicationId", "A", "country", "CN", "leaseSeconds", 60)))
                        .isInstanceOf(SmsReceptionService.ProductError.class);
            }
        }
        if (failedRegistration == -1) {
            assertThat(events).isEmpty();
            assertThat(registrationCount).hasValue(0);
            verify(adapters, never()).start();
            return;
        }
        assertThat(events.getFirst()).isEqualTo("platform-start");
        assertThat(events.getLast()).isEqualTo("platform-close");
        assertThat(registrationCount).hasValue(failedRegistration == 0 ? 4 : failedRegistration);
        verify(adapters).close();
        verify(verifier).close();
    }
}
