package com.xa.mass.server.runtimeview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.server.api.v1.runtimeview.model.WorkerNetworkObserveResponse;
import com.xa.mass.server.delivery.directcall.DirectCallProperties;
import com.xa.mass.server.delivery.directcall.DirectCallRegistry;
import com.xa.mass.server.delivery.directcall.DirectCallService;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.worker.binding.WorkerBindingProperties.EndpointProperties;
import com.xa.mass.server.worker.binding.WorkerBindingService;
import com.xa.mass.server.worker.binding.WorkerEndpointDirectory;
import com.xa.mass.server.worker.binding.WorkerTransportType;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.DeferredResult;

class WorkerNetworkObservationServiceTest {

    private static final String ADAPTER_ID = "adapter-1";

    private WorkerResourceCatalog workerCatalog;
    private WorkerCommandRuntime workerCommands;
    private WorkerBindingService workerBindings;
    private DirectCallRegistry registry;
    private DirectCallService directCalls;
    private WorkerNetworkObservationService service;

    @BeforeEach
    void setUp() {
        workerCatalog = mock(WorkerResourceCatalog.class);
        workerCommands = mock(WorkerCommandRuntime.class);
        workerBindings = mock(WorkerBindingService.class);
        DirectCallProperties properties = new DirectCallProperties(
                3_000,
                10_000,
                1_000,
                10_000
        );
        registry = new DirectCallRegistry(properties);
        directCalls = new DirectCallService(
                workerCatalog,
                workerCommands,
                workerBindings,
                new WorkerEndpointDirectory(Map.of(
                        ADAPTER_ID,
                        new EndpointProperties(
                                WorkerTransportType.WEBSOCKET,
                                URI.create("ws://127.0.0.1:18083")
                        )
                )),
                registry,
                properties
        );
        service = new WorkerNetworkObservationService(directCalls);
    }

    @Test
    void projectsOneRealAdapterSnapshotInRequestOrder() {
        List<String> workerIds = List.of("worker-2", "worker-1");
        DeferredResult<WorkerNetworkObserveResponse> deferred =
                service.observe(ADAPTER_ID, workerIds, "request-1");

        DeliveryCommand command = directCalls.consumeAdapterCommands(
                ADAPTER_ID,
                100
        ).getFirst();
        assertThat(command.src()).isEqualTo(DeliveryEndpoint.SYSTEM);
        assertThat(command.dst()).isEqualTo(DeliveryEndpoint.ADAPTER);
        assertThat(command.messageType()).isEqualTo(
                "platform.adapter.worker-connections.snapshot"
        );
        assertThat(Jsons.parseObject(command.payload()).get("workerIds"))
                .isEqualTo(workerIds);

        directCalls.completeReports(
                ADAPTER_ID,
                List.of(DeliveryReport.create(
                        DeliveryEndpoint.ADAPTER,
                        ADAPTER_ID,
                        DeliveryEndpoint.SYSTEM,
                        command.messageType(),
                        "200",
                        "{\"stateByWorkerId\":{"
                                + "\"worker-2\":\"DISCONNECTED\","
                                + "\"worker-1\":\"CONNECTED\"}}",
                        command.forward()
                ))
        );

        assertThat(deferred.getResult())
                .isInstanceOfSatisfying(
                        WorkerNetworkObserveResponse.class,
                        response -> {
                            assertThat(response.endpointManagerId())
                                    .isEqualTo(ADAPTER_ID);
                            assertThat(response.statesByWorkerId())
                                    .containsExactly(
                                            Map.entry(
                                                    "worker-2",
                                                    "disconnected"
                                            ),
                                            Map.entry(
                                                    "worker-1",
                                                    "connected"
                                            )
                                    );
                            assertThat(response.readAt()).isNotNull();
                        }
                );
        verifyNoInteractions(
                workerCatalog,
                workerCommands,
                workerBindings
        );
    }

    @Test
    void unobservedOrMalformedAdapterResultsStayUnavailable() {
        DeferredResult<WorkerNetworkObserveResponse> rejected =
                service.observe(
                        ADAPTER_ID,
                        List.of("worker-1"),
                        "request-rejected"
                );
        DeliveryCommand rejectedCommand = directCalls
                .consumeAdapterCommands(ADAPTER_ID, 100)
                .getFirst();
        directCalls.completeReports(
                ADAPTER_ID,
                List.of(DeliveryReport.create(
                        DeliveryEndpoint.ADAPTER,
                        ADAPTER_ID,
                        DeliveryEndpoint.SYSTEM,
                        rejectedCommand.messageType(),
                        "23005",
                        "null",
                        rejectedCommand.forward()
                ))
        );
        assertUnavailable(rejected);

        DeferredResult<WorkerNetworkObserveResponse> malformed =
                service.observe(
                        ADAPTER_ID,
                        List.of("worker-1"),
                        "request-malformed"
                );
        DeliveryCommand malformedCommand = directCalls
                .consumeAdapterCommands(ADAPTER_ID, 100)
                .getFirst();
        directCalls.completeReports(
                ADAPTER_ID,
                List.of(DeliveryReport.create(
                        DeliveryEndpoint.ADAPTER,
                        ADAPTER_ID,
                        DeliveryEndpoint.SYSTEM,
                        malformedCommand.messageType(),
                        "200",
                        "{\"stateByWorkerId\":{}}",
                        malformedCommand.forward()
                ))
        );
        assertUnavailable(malformed);
    }

    @Test
    void rejectsDuplicateWorkersBeforeCreatingADirectCall() {
        assertThatThrownBy(() -> service.observe(
                ADAPTER_ID,
                List.of("worker-1", "worker-1"),
                "request-duplicate"
        )).isInstanceOfSatisfying(ServerException.class, error ->
                assertThat(error.errorCode()).isEqualTo(
                        ServerErrorCode.MALFORMED_REQUEST
                ));

        assertThat(directCalls.consumeAdapterCommands(ADAPTER_ID, 100))
                .isEmpty();
    }

    @Test
    void registryShutdownDoesNotInventUnknownNetworkState() {
        DeferredResult<WorkerNetworkObserveResponse> deferred =
                service.observe(
                        ADAPTER_ID,
                        List.of("worker-1"),
                        "request-shutdown"
                );

        registry.close();

        assertUnavailable(deferred);
    }

    private static void assertUnavailable(
            DeferredResult<WorkerNetworkObserveResponse> deferred
    ) {
        assertThat(deferred.getResult()).isInstanceOfSatisfying(
                ServerException.class,
                error -> assertThat(error.errorCode()).isEqualTo(
                        ServerErrorCode.RUNTIME_VIEW_UNAVAILABLE
                )
        );
    }
}
