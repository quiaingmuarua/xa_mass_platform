package com.xa.mass.engine.event;

import com.xa.mass.base.event.TargetScope;
import com.xa.mass.command.event.CoreEventDescriptor;
import com.xa.mass.command.event.CoreEventPrincipal;
import com.xa.mass.command.event.CoreEventRequest;
import com.xa.mass.command.event.CoreEventResponse;
import com.xa.mass.command.event.InMemoryMassEventRuntime;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KernelEventHandlerRegistryTest {

    @Test
    void registersRouteOnlyKernelTargetedHandler() {
        InMemoryMassEventRuntime runtime = new InMemoryMassEventRuntime();
        KernelEventHandlerRegistry registry = new KernelEventHandlerRegistry(runtime);

        registry.register(
                CoreEventDescriptor.builder()
                        .event("kernel.route.probe")
                        .targetScope(TargetScope.TASK_ENGINE)
                        .build(),
                (request, principal) -> CoreEventResponse.success(
                        Map.of(
                                "event", request.getEvent(),
                                "targetScope", runtime.getDescriptor(request.getEvent()).getTargetScope().name(),
                                "clientId", principal.clientId()
                        ),
                        request.getRequestId()
                )
        );

        CoreEventResponse response = runtime.dispatch(
                CoreEventRequest.builder()
                        .event("kernel.route.probe")
                        .requestId("route-1")
                        .build(),
                new CoreEventPrincipal("worker-ingress-test", "kernel-test")
        );

        assertTrue(response.isSuccess());
        assertEquals("route-1", response.getRequestId());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getData();
        assertEquals("kernel.route.probe", data.get("event"));
        assertEquals("TASK_ENGINE", data.get("targetScope"));
        assertEquals("worker-ingress-test", data.get("clientId"));
    }

    @Test
    void rejectsWorkerTargetedHandlers() {
        KernelEventHandlerRegistry registry = new KernelEventHandlerRegistry(new InMemoryMassEventRuntime());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> registry.register(
                        CoreEventDescriptor.builder()
                                .event("worker.task.work")
                                .targetScope(TargetScope.WORKER)
                                .build(),
                        (request, principal) -> CoreEventResponse.success(Boolean.TRUE, request.getRequestId())
                ));

        assertTrue(error.getMessage().contains("kernel event handler target"));
    }

    @Test
    void rejectsOperatorHandlersUntilAConcreteOperatorOwnerExists() {
        KernelEventHandlerRegistry registry = new KernelEventHandlerRegistry(new InMemoryMassEventRuntime());

        assertThrows(IllegalArgumentException.class,
                () -> registry.register(
                        CoreEventDescriptor.builder()
                                .event("operator.control")
                                .targetScope(TargetScope.OPERATOR)
                                .build(),
                        (request, principal) -> CoreEventResponse.success(Boolean.TRUE, request.getRequestId())
                ));
    }

    @Test
    void registersWorkerManagerEventWithoutExposingTargetScopeToConcreteOwner() {
        InMemoryMassEventRuntime runtime = new InMemoryMassEventRuntime();
        KernelEventHandlerRegistry registry = new KernelEventHandlerRegistry(runtime);

        registry.registerWorkerManagerEvent("kernel.worker.manager.probe",
                (request, principal) -> CoreEventResponse.success(request.getEvent(), request.getRequestId()));

        assertEquals(TargetScope.WORKER_MANAGER,
                runtime.getDescriptor("kernel.worker.manager.probe").getTargetScope());
        assertEquals("kernel.worker.manager.probe",
                runtime.dispatch(CoreEventRequest.builder()
                                .event("kernel.worker.manager.probe")
                                .requestId("probe")
                                .build(),
                        new CoreEventPrincipal("worker", "test"))
                        .getData());
    }
}
