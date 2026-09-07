package com.xa.mass.server.delivery.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.delivery.TaskResultRuntime;
import com.xa.mass.kernel.delivery.TaskResultRuntime.TaskResultClass;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerDescriptor;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import com.xa.mass.workermatching.WorkerMatchingCatalog.MutationResult;
import com.xa.mass.workermatching.WorkerMatchingCatalog.MutationStatus;
import com.xa.mass.server.delivery.directcall.DirectCallService;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.worker.scheduling.WorkerSchedulingService;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import com.xa.mass.workerdelivery.json.Jsons;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkerDeliveryServiceTest {

    @Test
    void emptyAndSuccessfulPollsPublishObservationBeforeCommandConsumption() {
        long before = System.currentTimeMillis();
        assertThat(service.pollWorkerCommand(POLLING, "worker-1")).isNull();
        var command = DeliveryCommand.create(DeliveryEndpoint.TASK, DeliveryEndpoint.WORKER,
                "event", before + 60_000, "opaque", "forward");
        when(commandRuntime.consumeWorkerCommand(POLLING, "worker-1")).thenReturn(command);
        assertThat(service.pollWorkerCommand(POLLING, "worker-1")).isSameAs(command);
        var order = org.mockito.Mockito.inOrder(bindings, serviceability, commandRuntime);
        for (int i = 0; i < 2; i++) {
            order.verify(bindings).getWorkerDescriptors(List.of("worker-1"));
            order.verify(serviceability).appendNetworkEvidenceResults(org.mockito.ArgumentMatchers.argThat(reports -> {
                var report = reports.getFirst();
                assertThat(report.src()).isEqualTo(DeliveryEndpoint.SERVER);
                assertThat(report.sourceId()).isEqualTo(POLLING);
                assertThat(report.dst()).isEqualTo(DeliveryEndpoint.KERNEL);
                assertThat(report.messageType()).isEqualTo("platform.server.worker-poll.observed");
                var payload = Jsons.parseObject(report.payload());
                assertThat(payload.get("workerId")).isEqualTo("worker-1");
                assertThat(((Number) payload.get("observedAtMillis")).longValue()).isBetween(before, System.currentTimeMillis());
                return true;
            }));
            order.verify(commandRuntime).consumeWorkerCommand(POLLING, "worker-1");
        }
    }

    @Test
    void lostObservationDoesNotChangePollingOutcome() {
        when(serviceability.appendNetworkEvidenceResults(anyList()))
                .thenReturn(0).thenThrow(new IllegalStateException("handoff unavailable"));
        assertThat(service.pollWorkerCommand(POLLING, "worker-1")).isNull();
        var command = DeliveryCommand.create(DeliveryEndpoint.TASK, DeliveryEndpoint.WORKER,
                "event", System.currentTimeMillis() + 60_000, "opaque", "forward");
        when(commandRuntime.consumeWorkerCommand(POLLING, "worker-1")).thenReturn(command);
        assertThat(service.pollWorkerCommand(POLLING, "worker-1")).isSameAs(command);
    }

    @Test
    void invalidBindingAndPublicServerObservationCannotReachActivationHandoff() {
        when(bindings.getWorkerDescriptors(List.of("worker-1"))).thenReturn(Map.of());
        assertThatThrownBy(() -> service.pollWorkerCommand(POLLING, "worker-1")).isInstanceOf(ServerException.class);
        when(bindings.getWorkerDescriptors(List.of("worker-1"))).thenReturn(Map.of("worker-1",
                new WorkerDescriptor("worker-1", "group", "other-endpoint")));
        assertThatThrownBy(() -> service.pollWorkerCommand(POLLING, "worker-1")).isInstanceOf(ServerException.class);
        var forged = DeliveryReport.create(DeliveryEndpoint.SERVER, POLLING, DeliveryEndpoint.KERNEL,
                "platform.server.worker-poll.observed", "200", "{}", "worker-serviceability-evidence:v1");
        assertThat(service.appendAdapterReports("adapter-1", List.of(forged)))
                .isEqualTo(new WorkerDeliveryService.WorkerResultAppendCounts(0, 1));
        verifyNoInteractions(serviceability, commandRuntime);
    }

    private static final String COMMAND_ID =
            "a5e9e10d-f78b-469e-93ab-864b49c189c1";
    private static final String POLLING =
            WorkerDeliveryProtocol.SYSTEM_POLLING_ENDPOINT_MANAGER_ID;
    private WorkerCommandRuntime commandRuntime;
    private TaskResultRuntime resultRuntime;
    private WorkerResourceCatalog bindings;
    private DirectCallService directCalls;
    private WorkerServiceabilityRuntime serviceability;
    private WorkerDeliveryService service;
    private WorkerMatchingCatalog matchingCatalog;
    private WorkerSchedulingService scheduling;

    @BeforeEach
    void setUp() {
        commandRuntime = mock(WorkerCommandRuntime.class);
        resultRuntime = mock(TaskResultRuntime.class);
        bindings = mock(WorkerResourceCatalog.class);
        when(bindings.getWorkerDescriptors(List.of("worker-1")))
                .thenReturn(Map.of("worker-1", new WorkerDescriptor("worker-1", "group", POLLING)));
        directCalls = mock(DirectCallService.class);
        serviceability = mock(WorkerServiceabilityRuntime.class);
        matchingCatalog = mock(WorkerMatchingCatalog.class);
        scheduling = mock(WorkerSchedulingService.class);
        when(serviceability.consumeProbeRequests(anyString(), anyInt()))
                .thenReturn(List.of());
        when(directCalls.consumeAdapterCommands(anyString(), anyInt()))
                .thenReturn(List.of());
        when(directCalls.completeReports(anyString(), anyList()))
                .thenAnswer(invocation ->
                        new DirectCallService.ResultAppendCounts(
                                invocation.<List<?>>getArgument(1).size(),
                                0
                        ));
        service = new WorkerDeliveryService(
                commandRuntime,
                resultRuntime,
                bindings,
                directCalls,
                serviceability,
                matchingCatalog,
                scheduling
        );
    }

    @Test
    void systemPropertiesUseLastValidSnapshotAndCountEveryAcceptedInput() {
        var latest = Map.of("network.type", "cellular", "empty", "");
        when(bindings.getWorkerDescriptors(List.of("w", "missing")))
                .thenReturn(Map.of("w", new WorkerDescriptor("w", "group", "adapter-1")));
        when(matchingCatalog.upsertWorkerFactsBatch("group", Map.of("w", latest)))
                .thenReturn(Map.of("w", new MutationResult(MutationStatus.APPLIED)));
        var reports = List.of(
                propertiesReport("adapter-1", "w", Map.of("network.type", "wifi")),
                propertiesReport("adapter-1", "w", latest),
                propertiesReport("adapter-1", "w", Map.of("network.type", 87)),
                propertiesReport("adapter-1", "missing", Map.of())
        );
        assertThat(service.appendAdapterReports("adapter-1", reports))
                .isEqualTo(new WorkerDeliveryService.WorkerResultAppendCounts(2, 2));
        verify(bindings).getWorkerDescriptors(List.of("w", "missing"));
        verify(matchingCatalog).upsertWorkerFactsBatch("group", Map.of("w", latest));
        verifyNoMoreInteractions(bindings, matchingCatalog);
        verifyNoInteractions(directCalls, resultRuntime, serviceability, commandRuntime);
    }

    @Test
    void systemPropertiesAdmitBoundWorkersAndWriteOneCompleteBatchPerGroup() {
        List<String> ids = List.of("a", "b", "c", "wrong-adapter", "unknown");
        var properties = Map.of("network.type", "wifi");
        List<DeliveryReport> reports = ids.stream()
                .map(id -> propertiesReport("adapter-1", id, properties)).toList();
        when(bindings.getWorkerDescriptors(ids)).thenReturn(Map.of(
                "a", new WorkerDescriptor("a", "g1", "adapter-1"),
                "b", new WorkerDescriptor("b", "g1", "adapter-1"),
                "c", new WorkerDescriptor("c", "g2", "adapter-1"),
                "wrong-adapter", new WorkerDescriptor("wrong-adapter", "g1", "other")));
        when(matchingCatalog.upsertWorkerFactsBatch("g1", Map.of("a", properties, "b", properties)))
                .thenReturn(Map.of("a", new MutationResult(MutationStatus.APPLIED),
                        "b", new MutationResult(MutationStatus.UNCHANGED)));
        when(matchingCatalog.upsertWorkerFactsBatch("g2", Map.of("c", properties)))
                .thenReturn(Map.of("c", new MutationResult(MutationStatus.APPLIED)));

        assertThat(service.appendAdapterReports("adapter-1", reports))
                .isEqualTo(new WorkerDeliveryService.WorkerResultAppendCounts(3, 2));
        var order = inOrder(bindings, matchingCatalog, scheduling);
        order.verify(bindings).getWorkerDescriptors(ids);
        order.verify(matchingCatalog).upsertWorkerFactsBatch("g1", Map.of("a", properties, "b", properties));
        order.verify(scheduling).invalidateCandidates("g1", List.of("a"));
        order.verify(matchingCatalog).upsertWorkerFactsBatch("g2", Map.of("c", properties));
        order.verify(scheduling).invalidateCandidates("g2", List.of("c"));
        verifyNoMoreInteractions(scheduling);
        verifyNoMoreInteractions(bindings, matchingCatalog);
        verifyNoInteractions(directCalls, resultRuntime, serviceability, commandRuntime);
    }

    @Test
    void propertiesRemainAcceptedWhenScoreInvalidationFailsAndUnchangedRetryDoesNotReplay() {
        var scores = mock(com.xa.mass.kernel.score.WorkerScoreCore.class);
        service = new WorkerDeliveryService(commandRuntime, resultRuntime, bindings, directCalls,
                serviceability, matchingCatalog, new WorkerSchedulingService(scores));
        when(bindings.getWorkerDescriptors(List.of("w")))
                .thenReturn(Map.of("w", new WorkerDescriptor("w", "g", "adapter-1")));
        when(matchingCatalog.upsertWorkerFactsBatch("g", Map.of("w", Map.of("key", "value"))))
                .thenReturn(Map.of("w", new MutationResult(MutationStatus.APPLIED)),
                        Map.of("w", new MutationResult(MutationStatus.UNCHANGED)));
        when(scores.markCurrentLeasesDirty("g", List.of("w"))).thenThrow(new IllegalStateException("unavailable"));
        var reports = List.of(propertiesReport("adapter-1", "w", Map.of("key", "value")));
        for (int i = 0; i < 2; i++) {
            assertThat(service.appendAdapterReports("adapter-1", reports))
                    .isEqualTo(new WorkerDeliveryService.WorkerResultAppendCounts(1, 0));
        }
        var order = inOrder(matchingCatalog, scores);
        order.verify(matchingCatalog).upsertWorkerFactsBatch("g", Map.of("w", Map.of("key", "value")));
        order.verify(scores).markCurrentLeasesDirty("g", List.of("w"));
        order.verify(matchingCatalog).upsertWorkerFactsBatch("g", Map.of("w", Map.of("key", "value")));
        verifyNoMoreInteractions(scores);
    }

    @Test
    void failedFactsWriteNeverInvalidatesCandidates() {
        when(bindings.getWorkerDescriptors(List.of("w")))
                .thenReturn(Map.of("w", new WorkerDescriptor("w", "g", "adapter-1")));
        when(matchingCatalog.upsertWorkerFactsBatch("g", Map.of("w", Map.of())))
                .thenThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(() -> service.appendAdapterReports("adapter-1",
                List.of(propertiesReport("adapter-1", "w", Map.of())))).isInstanceOf(ServerException.class);
        verifyNoInteractions(scheduling);
    }

    @Test
    void systemPropertiesWithoutBindingsNeverCreateFacts() {
        when(bindings.getWorkerDescriptors(List.of("unknown"))).thenReturn(Map.of());
        assertThat(service.appendAdapterReports("adapter-1", List.of(
                propertiesReport("adapter-1", "unknown", Map.of()))))
                .isEqualTo(new WorkerDeliveryService.WorkerResultAppendCounts(0, 1));
        verify(bindings).getWorkerDescriptors(List.of("unknown"));
        verifyNoMoreInteractions(bindings);
        verifyNoInteractions(matchingCatalog, directCalls, resultRuntime, serviceability, commandRuntime);
    }

    @Test
    void systemPropertiesCountEachInputUsingItsWorkersMutationOutcome() {
        List<DeliveryReport> reports = new ArrayList<>();
        Map<String, WorkerDescriptor> descriptors = new LinkedHashMap<>();
        Map<String, Map<String, String>> snapshots = new LinkedHashMap<>();
        Map<String, MutationResult> results = new LinkedHashMap<>();
        for (MutationStatus status : MutationStatus.values()) {
            String workerId = status.name();
            reports.add(propertiesReport("adapter-1", workerId, Map.of("old", "value")));
            reports.add(propertiesReport("adapter-1", workerId, Map.of()));
            descriptors.put(workerId, new WorkerDescriptor(workerId, "group", "adapter-1"));
            snapshots.put(workerId, Map.of());
            results.put(workerId, new MutationResult(status));
        }
        when(bindings.getWorkerDescriptors(List.copyOf(descriptors.keySet()))).thenReturn(descriptors);
        when(matchingCatalog.upsertWorkerFactsBatch("group", snapshots)).thenReturn(results);

        assertThat(service.appendAdapterReports("adapter-1", reports))
                .isEqualTo(new WorkerDeliveryService.WorkerResultAppendCounts(4, reports.size() - 4));
        verify(matchingCatalog).upsertWorkerFactsBatch("group", snapshots);
        verifyNoMoreInteractions(matchingCatalog);
    }

    @Test
    void systemPropertiesPartialGroupFailureDoesNotRollbackEarlierWrites() {
        when(bindings.getWorkerDescriptors(List.of("a", "b"))).thenReturn(Map.of(
                "a", new WorkerDescriptor("a", "g1", "adapter-1"),
                "b", new WorkerDescriptor("b", "g2", "adapter-1")));
        when(matchingCatalog.upsertWorkerFactsBatch("g1", Map.of("a", Map.of())))
                .thenReturn(Map.of("a", new MutationResult(MutationStatus.APPLIED)));
        var failure = new IllegalStateException("storage unavailable");
        when(matchingCatalog.upsertWorkerFactsBatch("g2", Map.of("b", Map.of())))
                .thenThrow(failure);

        assertThatThrownBy(() -> service.appendAdapterReports("adapter-1", List.of(
                propertiesReport("adapter-1", "a", Map.of()),
                propertiesReport("adapter-1", "b", Map.of()))))
                .isInstanceOfSatisfying(ServerException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(ServerErrorCode.WORKER_RESOURCE_UNAVAILABLE);
                    assertThat(error.operation()).isEqualTo("workerDelivery.appendAdapterPropertiesReports");
                    assertThat(error.getCause()).isSameAs(failure);
                });
        var order = inOrder(bindings, matchingCatalog);
        order.verify(bindings).getWorkerDescriptors(List.of("a", "b"));
        order.verify(matchingCatalog).upsertWorkerFactsBatch("g1", Map.of("a", Map.of()));
        order.verify(matchingCatalog).upsertWorkerFactsBatch("g2", Map.of("b", Map.of()));
        verifyNoMoreInteractions(bindings, matchingCatalog);
        verifyNoInteractions(directCalls, resultRuntime, serviceability, commandRuntime);
    }

    @Test
    void systemPropertiesBindingFailureRemainsPropertiesUnavailable() {
        when(bindings.getWorkerDescriptors(List.of("w")))
                .thenThrow(new IllegalStateException("Binding unavailable"));
        assertThatThrownBy(() -> service.appendAdapterReports("adapter-1", List.of(
                propertiesReport("adapter-1", "w", Map.of()))))
                .isInstanceOfSatisfying(ServerException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(ServerErrorCode.WORKER_RESOURCE_UNAVAILABLE);
                    assertThat(error.operation()).isEqualTo("workerDelivery.appendAdapterPropertiesReports");
                });
        verifyNoInteractions(matchingCatalog, directCalls, resultRuntime, serviceability, commandRuntime);
    }

    @Test
    void malformedOrUntrustedSystemEventsNeverCallThePropertiesOwner() {
        List<DeliveryReport> reports = new ArrayList<>();
        reports.add(propertiesReport("other-adapter", "w", Map.of()));
        String valid = "{\"workerId\":\"w\",\"properties\":{}}";
        reports.add(DeliveryReport.create(DeliveryEndpoint.WORKER, "adapter-1", DeliveryEndpoint.SYSTEM,
                "platform.adapter.worker-properties.observed", "200", valid, ""));
        reports.add(DeliveryReport.create(DeliveryEndpoint.ADAPTER, "adapter-1", DeliveryEndpoint.SYSTEM,
                "platform.adapter.worker-properties.observed", "23001", valid, ""));
        reports.add(DeliveryReport.create(DeliveryEndpoint.ADAPTER, "adapter-1", DeliveryEndpoint.SYSTEM,
                "platform.adapter.worker-properties.observed", "200", valid, "direct-call:v1:x"));
        reports.add(DeliveryReport.create(DeliveryEndpoint.ADAPTER, "adapter-1", DeliveryEndpoint.SYSTEM,
                "platform.adapter.unknown", "200", valid, ""));
        for (String event : List.of("platform.worker.properties.updated", "platform.worker.properties.replaced")) {
            reports.add(DeliveryReport.create(DeliveryEndpoint.WORKER, "w", DeliveryEndpoint.SYSTEM,
                    event, "200", "{\"network.type\":\"wifi\"}", ""));
            reports.add(DeliveryReport.create(DeliveryEndpoint.ADAPTER, "adapter-1", DeliveryEndpoint.SYSTEM,
                    event, "200", valid, ""));
        }
        for (String payload : List.of("null", "[]", "not-json", "{}",
                "{\"workerId\":\" \",\"properties\":{}}",
                "{\"workerId\":\"w\",\"properties\":{},\"version\":1}",
                "{\"workerId\":\"w\",\"properties\":{\"x\":null}}",
                "{\"workerId\":\"w\",\"properties\":{\"x\":true}}",
                "{\"workerId\":\"w\",\"properties\":{\"x\":{}}}",
                "{\"workerId\":\"w\",\"properties\":{\" \":\"value\"}}")) {
            reports.add(DeliveryReport.create(DeliveryEndpoint.ADAPTER, "adapter-1", DeliveryEndpoint.SYSTEM,
                    "platform.adapter.worker-properties.observed", "200", payload, ""));
        }
        reports.add(propertiesReport("adapter-1", "w", Map.of("large", "界".repeat(334_000))));
        assertThat(service.appendAdapterReports("adapter-1", reports))
                .isEqualTo(new WorkerDeliveryService.WorkerResultAppendCounts(0, reports.size()));
        verifyNoInteractions(matchingCatalog, directCalls, resultRuntime, serviceability, bindings);
    }

    private static DeliveryReport propertiesReport(String adapterId, String workerId, Map<String, ?> properties) {
        return DeliveryReport.create(DeliveryEndpoint.ADAPTER, adapterId, DeliveryEndpoint.SYSTEM,
                "platform.adapter.worker-properties.observed", "200",
                Jsons.toJson(Map.of("workerId", workerId, "properties", properties)), "");
    }

    @Test
    void pointPollDropsACommandThatExpiredAfterRedisConsumption() {
        when(commandRuntime.consumeWorkerCommand(POLLING, "worker-1"))
                .thenReturn(DeliveryCommand.create(
                        DeliveryEndpoint.TASK,
                        DeliveryEndpoint.WORKER,
                        "test.event",
                        System.currentTimeMillis() - 1,
                        "item",
                        "context"
                ));

        assertThat(service.pollWorkerCommand(POLLING, "worker-1"))
                .isNull();
        verify(bindings).getWorkerDescriptors(List.of("worker-1"));
    }

    @Test
    void adapterCommandsFillTheLimitBeforeAnyWorkerSourceIsRead() {
        DeliveryCommand first = DeliveryCommand.create(
                DeliveryEndpoint.SERVER,
                DeliveryEndpoint.ADAPTER,
                "platform.adapter.probe",
                System.currentTimeMillis() + 10_000,
                "null",
                "direct-call:v1:first"
        );
        DeliveryCommand second = DeliveryCommand.create(
                DeliveryEndpoint.SERVER,
                DeliveryEndpoint.ADAPTER,
                "platform.adapter.events.snapshot",
                System.currentTimeMillis() + 10_000,
                "null",
                "direct-call:v1:second"
        );
        when(directCalls.consumeAdapterCommands("endpoint-1", 2))
                .thenReturn(List.of(first, second));

        Map<String, DeliveryCommand> commands =
                service.consumeWorkerCommands("endpoint-1", 2);

        assertThat(commands.values()).containsExactly(first, second);
        assertThat(commands.keySet()).hasSize(2);
        verify(commandRuntime, never()).consumeWorkerCommands(
                anyString(),
                anyInt()
        );
        verify(serviceability, never()).consumeProbeRequests(
                anyString(),
                anyInt()
        );
    }

    @Test
    void adapterPrefixUsesRemainingLimitFromSharedWorkerHash() {
        DeliveryCommand adapter = DeliveryCommand.create(
                DeliveryEndpoint.SERVER,
                DeliveryEndpoint.ADAPTER,
                "platform.adapter.probe",
                System.currentTimeMillis() + 10_000,
                "null",
                "direct-call:v1:adapter"
        );
        DeliveryCommand control = DeliveryCommand.create(
                DeliveryEndpoint.SERVER,
                DeliveryEndpoint.WORKER,
                "platform.worker.properties.snapshot",
                System.currentTimeMillis() + 10_000,
                "{}",
                "direct-call:v1:test"
        );
        when(directCalls.consumeAdapterCommands("endpoint-1", 4))
                .thenReturn(List.of(adapter));
        when(commandRuntime.consumeWorkerCommands("endpoint-1", 3))
                .thenReturn(Map.of("worker-1", control));

        assertThat(service.consumeWorkerCommands("endpoint-1", 4).values())
                .containsExactly(adapter, control);
        verify(commandRuntime).consumeWorkerCommands("endpoint-1", 3);
    }

    @Test
    void sharedWorkerHashUsesTheRemainingLimitOnce() {
        DeliveryCommand adapter = DeliveryCommand.create(
                DeliveryEndpoint.SERVER,
                DeliveryEndpoint.ADAPTER,
                "platform.adapter.probe",
                System.currentTimeMillis() + 10_000,
                "null",
                "direct-call:v1:adapter"
        );
        DeliveryCommand task = DeliveryCommand.create(
                DeliveryEndpoint.TASK,
                DeliveryEndpoint.WORKER,
                "test.event",
                System.currentTimeMillis() + 10_000,
                "{}",
                "task-context"
        );
        when(directCalls.consumeAdapterCommands("endpoint-1", 4))
                .thenReturn(List.of(adapter));
        when(commandRuntime.consumeWorkerCommands("endpoint-1", 3))
                .thenReturn(Map.of("entry:0", task));

        Map<String, DeliveryCommand> commands =
                service.consumeWorkerCommands("endpoint-1", 4);

        assertThat(commands).containsEntry("entry:0", task);
        assertThat(commands).containsValue(adapter).hasSize(2);
        assertThat(commands.entrySet().stream()
                .filter(entry -> entry.getValue().equals(adapter))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow()).isNotEqualTo("entry:0");
        verify(commandRuntime).consumeWorkerCommands("endpoint-1", 3);
    }

    @Test
    void workerCommandsFillTheLimitBeforeProbeRequestsAreRead() {
        DeliveryCommand task = DeliveryCommand.create(
                DeliveryEndpoint.TASK,
                DeliveryEndpoint.WORKER,
                "test.event",
                System.currentTimeMillis() + 10_000,
                "{}",
                "task-context"
        );
        when(commandRuntime.consumeWorkerCommands("endpoint-1", 1))
                .thenReturn(Map.of("worker-1", task));

        assertThat(service.consumeWorkerCommands("endpoint-1", 1))
                .containsExactly(Map.entry("worker-1", task));

        verify(serviceability, never()).consumeProbeRequests(
                anyString(),
                anyInt()
        );
    }

    @Test
    void remainingCapacityAddsOneLowPriorityServiceabilityCommand() {
        DeliveryCommand task = DeliveryCommand.create(
                DeliveryEndpoint.TASK,
                DeliveryEndpoint.WORKER,
                "test.event",
                System.currentTimeMillis() + 10_000,
                "{}",
                "task-context"
        );
        when(commandRuntime.consumeWorkerCommands("endpoint-1", 3))
                .thenReturn(Map.of("worker-1", task));
        when(serviceability.consumeProbeRequests("endpoint-1", 100))
                .thenReturn(List.of("worker-2", "worker-3"));
        long before = System.currentTimeMillis();

        Map<String, DeliveryCommand> commands =
                service.consumeWorkerCommands("endpoint-1", 3);

        assertThat(commands).hasSize(2).containsEntry("worker-1", task);
        DeliveryCommand probe = commands.values().stream()
                .filter(command -> command.src() == DeliveryEndpoint.KERNEL)
                .findFirst()
                .orElseThrow();
        assertThat(probe.dst()).isEqualTo(DeliveryEndpoint.ADAPTER);
        assertThat(probe.messageType()).isEqualTo(
                "platform.adapter.worker-connections.snapshot"
        );
        assertThat(probe.payload()).isEqualTo(
                "{\"workerIds\":[\"worker-2\",\"worker-3\"]}"
        );
        assertThat(probe.forward()).startsWith(
                "worker-serviceability:v1:"
        );
        long checkStartedAt = Long.parseLong(probe.forward().substring(
                "worker-serviceability:v1:".length()
        ));
        assertThat(checkStartedAt).isBetween(
                before,
                System.currentTimeMillis()
        );
        assertThat(probe.executeBeforeMillis())
                .isEqualTo(checkStartedAt + 5_000L);
    }

    @Test
    void probeRuntimeFailureDoesNotDiscardHigherPriorityCommands() {
        DeliveryCommand task = DeliveryCommand.create(
                DeliveryEndpoint.TASK,
                DeliveryEndpoint.WORKER,
                "test.event",
                System.currentTimeMillis() + 10_000,
                "{}",
                "task-context"
        );
        when(commandRuntime.consumeWorkerCommands("endpoint-1", 2))
                .thenReturn(Map.of("worker-1", task));
        when(serviceability.consumeProbeRequests("endpoint-1", 100))
                .thenThrow(new IllegalStateException("unavailable"));

        assertThat(service.consumeWorkerCommands("endpoint-1", 2))
                .containsExactly(Map.entry("worker-1", task));
    }

    @Test
    void acquiredAdapterCommandsSurviveLowerPrioritySourceFailure() {
        DeliveryCommand adapter = DeliveryCommand.create(
                DeliveryEndpoint.SERVER,
                DeliveryEndpoint.ADAPTER,
                "platform.adapter.probe",
                System.currentTimeMillis() + 10_000,
                "null",
                "direct-call:v1:adapter"
        );
        when(directCalls.consumeAdapterCommands("endpoint-1", 2))
                .thenReturn(List.of(adapter));
        when(commandRuntime.consumeWorkerCommands("endpoint-1", 1))
                .thenThrow(new IllegalStateException("unavailable"));

        Map<String, DeliveryCommand> commands =
                service.consumeWorkerCommands("endpoint-1", 2);

        assertThat(commands.values()).containsExactly(adapter);
        verify(commandRuntime).consumeWorkerCommands("endpoint-1", 1);
    }

    @Test
    void pointWorkerResultsAreMappedToSuccessAndFailureLanes() {
        DeliveryReport success = result(COMMAND_ID, "200");
        DeliveryReport failure = result(
                "9f0d983c-8010-4d59-a6d2-e8fedb8d0059",
                "3500"
        );
        when(resultRuntime.appendTaskResults(
                TaskResultClass.SUCCESS,
                List.of(success)
        )).thenReturn(1);
        when(resultRuntime.appendTaskResults(
                TaskResultClass.FAILURE,
                List.of(failure)
        )).thenReturn(1);

        service.appendWorkerResult(POLLING, "worker-1", success);
        service.appendWorkerResult(POLLING, "worker-1", failure);

        verify(resultRuntime).appendTaskResults(
                TaskResultClass.SUCCESS,
                List.of(success)
        );
        verify(resultRuntime).appendTaskResults(
                TaskResultClass.FAILURE,
                List.of(failure)
        );
    }

    @Test
    void workerResultRejectsAdapterEvidence() {
        DeliveryReport result = result(COMMAND_ID, "23002");

        assertThatThrownBy(() -> service.appendWorkerResult(
                POLLING,
                "worker-1",
                result
        ))
                .isInstanceOf(ServerException.class)
                .extracting(
                        error -> ((ServerException) error).errorCode()
                )
                .isEqualTo(
                        ServerErrorCode.INVALID_WORKER_DELIVERY_REQUEST
                );
        verify(resultRuntime, never()).appendTaskResults(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList()
        );
        verify(bindings).getWorkerDescriptors(List.of("worker-1"));
    }

    @Test
    void pointResultRejectsAnotherWorkerSourceId() {
        DeliveryReport result = DeliveryReport.create(
                DeliveryEndpoint.WORKER,
                "worker-2",
                DeliveryEndpoint.TASK,
                "test.event",
                "200",
                "null",
                "context"
        );

        assertThatThrownBy(() -> service.appendWorkerResult(
                POLLING,
                "worker-1",
                result
        )).isInstanceOf(ServerException.class);
        verify(resultRuntime, never()).appendTaskResults(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList()
        );
    }

    @Test
    void taskBatchAcceptsSuccessFailureAndAdapterRejection() {
        DeliveryReport success = result(COMMAND_ID, "200");
        DeliveryReport failure = result(
                "9f0d983c-8010-4d59-a6d2-e8fedb8d0059",
                "3500"
        );
        DeliveryReport forgedRejection = result(
                "66f60ac8-e68f-4783-90e3-13b20a54ca13",
                "23002"
        );
        when(resultRuntime.appendTaskResults(
                TaskResultClass.SUCCESS,
                List.of(success)
        )).thenReturn(1);
        when(resultRuntime.appendTaskResults(
                TaskResultClass.FAILURE,
                List.of(failure, forgedRejection)
        )).thenReturn(2);

        var counts = service.appendAdapterReports(
                "endpoint-1",
                List.of(
                        success,
                        failure,
                        forgedRejection
                )
        );

        assertThat(counts.acceptedCount()).isEqualTo(3);
        assertThat(counts.rejectedCount()).isZero();
        verify(resultRuntime).appendTaskResults(
                TaskResultClass.SUCCESS,
                List.of(success)
        );
        verify(resultRuntime).appendTaskResults(
                TaskResultClass.FAILURE,
                List.of(failure, forgedRejection)
        );
    }

    @Test
    void mixedDestinationBatchFailsBeforeOwnerSideEffects() {
        DeliveryReport success = result(COMMAND_ID, "200");
        DeliveryReport wrongDestination = DeliveryReport.create(
                DeliveryEndpoint.ADAPTER,
                "endpoint-1",
                DeliveryEndpoint.SERVER,
                "test.event",
                "23002",
                "null",
                "context"
        );
        assertThatThrownBy(() -> service.appendAdapterReports(
                "endpoint-1",
                List.of(success, wrongDestination)
        )).isInstanceOf(ServerException.class)
                .extracting(error -> ((ServerException) error).errorCode())
                .isEqualTo(ServerErrorCode.INVALID_WORKER_DELIVERY_REQUEST);
        verifyNoInteractions(resultRuntime, directCalls, serviceability);
    }

    @Test
    void unsupportedDestinationBatchFailsBeforeOwnerSideEffects() {
        DeliveryReport unsupported = DeliveryReport.create(
                DeliveryEndpoint.WORKER,
                "worker-1",
                DeliveryEndpoint.ADAPTER,
                "test.event",
                "200",
                "null",
                "context"
        );

        assertThatThrownBy(() -> service.appendAdapterReports(
                "endpoint-1",
                List.of(unsupported)
        )).isInstanceOf(ServerException.class)
                .extracting(error -> ((ServerException) error).errorCode())
                .isEqualTo(ServerErrorCode.INVALID_WORKER_DELIVERY_REQUEST);
        verifyNoInteractions(resultRuntime, directCalls, serviceability);
    }

    @Test
    void adapterBatchRejectsAnotherAdapterSourceId() {
        DeliveryReport success = result(COMMAND_ID, "200");
        DeliveryReport foreignAdapter = DeliveryReport.create(
                DeliveryEndpoint.ADAPTER,
                "endpoint-2",
                DeliveryEndpoint.TASK,
                "test.event",
                "23002",
                "null",
                "context"
        );
        when(resultRuntime.appendTaskResults(
                TaskResultClass.SUCCESS,
                List.of(success)
        )).thenReturn(1);

        var counts = service.appendAdapterReports(
                "endpoint-1",
                List.of(success, foreignAdapter)
        );

        assertThat(counts.acceptedCount()).isEqualTo(1);
        assertThat(counts.rejectedCount()).isEqualTo(1);
        verify(resultRuntime).appendTaskResults(
                TaskResultClass.SUCCESS,
                List.of(success)
        );
    }

    @Test
    void kernelResultCapacityFailureIsUnavailableForBatchRetry() {
        DeliveryReport kernel = DeliveryReport.create(
                DeliveryEndpoint.ADAPTER,
                "endpoint-1",
                DeliveryEndpoint.KERNEL,
                "platform.adapter.worker-connections.snapshot",
                "200",
                "{\"stateByWorkerId\":{\"worker-1\":\"CONNECTED\"}}",
                "worker-serviceability:v1:123"
        );
        when(serviceability.appendNetworkEvidenceResults(List.of(kernel)))
                .thenReturn(0);

        assertThatThrownBy(() -> service.appendAdapterReports(
                "endpoint-1",
                List.of(kernel)
        ))
                .isInstanceOf(ServerException.class)
                .extracting(error -> ((ServerException) error).errorCode())
                .isEqualTo(ServerErrorCode.WORKER_DELIVERY_UNAVAILABLE);
    }

    @Test
    void homogeneousBatchesRouteOnlyToTheirOwner() {
        DeliveryReport task = result(COMMAND_ID, "200");
        DeliveryReport direct = DeliveryReport.create(
                DeliveryEndpoint.WORKER,
                "worker-1",
                DeliveryEndpoint.SERVER,
                "platform.worker.probe",
                "200",
                "{}",
                "direct-call:v1:test"
        );
        DeliveryReport kernel = DeliveryReport.create(
                DeliveryEndpoint.ADAPTER,
                "endpoint-1",
                DeliveryEndpoint.KERNEL,
                "platform.adapter.worker-connections.snapshot",
                "200",
                "{\"stateByWorkerId\":{\"worker-1\":\"CONNECTED\"}}",
                "worker-serviceability:v1:123"
        );
        DeliveryReport routeChange = DeliveryReport.create(
                DeliveryEndpoint.ADAPTER,
                "endpoint-1",
                DeliveryEndpoint.KERNEL,
                "platform.adapter.worker-connection.changed",
                "200",
                "{\"workerId\":\"worker-1\",\"state\":\"CONNECTED\","
                        + "\"observedAtMillis\":123}",
                "worker-serviceability-evidence:v1"
        );
        DeliveryReport unknownServer = DeliveryReport.create(
                DeliveryEndpoint.ADAPTER,
                "endpoint-1",
                DeliveryEndpoint.SERVER,
                "platform.adapter.unknown",
                "200",
                "{}",
                "unknown"
        );
        when(resultRuntime.appendTaskResults(
                TaskResultClass.SUCCESS,
                List.of(task)
        )).thenReturn(1);
        when(directCalls.completeReports(
                "endpoint-1",
                List.of(direct, unknownServer)
        )).thenReturn(new DirectCallService.ResultAppendCounts(1, 1));
        when(serviceability.appendNetworkEvidenceResults(List.of(
                kernel,
                routeChange
        ))).thenReturn(2);

        var taskCounts = service.appendAdapterReports(
                "endpoint-1",
                List.of(task)
        );
        var serverCounts = service.appendAdapterReports(
                "endpoint-1",
                List.of(direct, unknownServer)
        );
        var kernelCounts = service.appendAdapterReports(
                "endpoint-1",
                List.of(kernel, routeChange)
        );

        assertThat(taskCounts).isEqualTo(new WorkerDeliveryService
                .WorkerResultAppendCounts(1, 0));
        assertThat(serverCounts).isEqualTo(new WorkerDeliveryService
                .WorkerResultAppendCounts(1, 1));
        assertThat(kernelCounts).isEqualTo(new WorkerDeliveryService
                .WorkerResultAppendCounts(2, 0));
        verify(resultRuntime).appendTaskResults(
                TaskResultClass.SUCCESS,
                List.of(task)
        );
        verify(directCalls).completeReports(
                "endpoint-1",
                List.of(direct, unknownServer)
        );
        verify(serviceability).appendNetworkEvidenceResults(List.of(
                kernel,
                routeChange
        ));
    }

    @Test
    void systemReportsAreRejectedWithoutCallingAnyBusinessOwner() {
        DeliveryReport event = DeliveryReport.create(
                DeliveryEndpoint.WORKER, "worker-1", DeliveryEndpoint.SYSTEM,
                "platform.worker.probe", "200", "{}", "direct-call:v1:test"
        );
        assertThat(service.appendAdapterReports("endpoint-1", List.of(event)))
                .isEqualTo(new WorkerDeliveryService.WorkerResultAppendCounts(0, 1));
        verifyNoInteractions(directCalls, resultRuntime, serviceability, bindings, commandRuntime);
    }

    @Test
    void adapterBatchAcceptsOneHundredKernelReports() {
        DeliveryReport kernel = DeliveryReport.create(
                DeliveryEndpoint.ADAPTER,
                "endpoint-1",
                DeliveryEndpoint.KERNEL,
                "platform.adapter.worker-connection.changed",
                "200",
                "{\"workerId\":\"worker-1\",\"state\":\"CONNECTED\","
                        + "\"observedAtMillis\":123}",
                "worker-serviceability-evidence:v1"
        );
        List<DeliveryReport> reports = Collections.nCopies(100, kernel);
        when(serviceability.appendNetworkEvidenceResults(reports))
                .thenReturn(100);

        var counts = service.appendAdapterReports(
                "endpoint-1",
                Collections.nCopies(100, kernel)
        );

        assertThat(counts.acceptedCount()).isEqualTo(100);
        assertThat(counts.rejectedCount()).isZero();
        verify(serviceability).appendNetworkEvidenceResults(reports);
    }

    @Test
    void oversizedAdapterBatchFailsBeforeOwnerSideEffects() {
        assertThatThrownBy(() -> service.appendAdapterReports(
                "endpoint-1",
                Collections.nCopies(101, result(COMMAND_ID, "200"))
        ))
                .isInstanceOf(ServerException.class)
                .extracting(error -> ((ServerException) error).errorCode())
                .isEqualTo(ServerErrorCode.INVALID_WORKER_DELIVERY_REQUEST);

        verifyNoInteractions(resultRuntime, directCalls, serviceability);
    }

    @Test
    void foreignKernelItemsAreRejectedWithoutOwnerSideEffects() {
        DeliveryReport foreign = DeliveryReport.create(
                DeliveryEndpoint.ADAPTER,
                "endpoint-2",
                DeliveryEndpoint.KERNEL,
                "platform.adapter.worker-connection.changed",
                "200",
                "{}",
                "worker-serviceability-evidence:v1"
        );

        var counts = service.appendAdapterReports(
                "endpoint-1",
                List.of(foreign)
        );

        assertThat(counts.acceptedCount()).isZero();
        assertThat(counts.rejectedCount()).isEqualTo(1);
        verifyNoInteractions(resultRuntime, directCalls, serviceability);
    }

    @Test
    void incompleteRuntimeAppendIsUnavailableForRetry() {
        DeliveryReport success = result(COMMAND_ID, "200");
        when(resultRuntime.appendTaskResults(
                TaskResultClass.SUCCESS,
                List.of(success)
        )).thenReturn(0);

        assertThatThrownBy(() -> service.appendAdapterReports(
                "endpoint-1",
                List.of(success)
        ))
                .isInstanceOf(ServerException.class)
                .extracting(
                        error -> ((ServerException) error).errorCode()
                )
                .isEqualTo(
                        ServerErrorCode.WORKER_DELIVERY_UNAVAILABLE
                );
    }

    @Test
    void systemPollingCannotUseAdapterBatchOperations() {
        assertThatThrownBy(() -> service.appendAdapterReports(
                WorkerDeliveryProtocol
                        .SYSTEM_POLLING_ENDPOINT_MANAGER_ID,
                List.of(result(COMMAND_ID, "200"))
        )).isInstanceOf(ServerException.class);
    }

    private static DeliveryReport result(
            String messageId,
            String outcomeCode
    ) {
        DeliveryEndpoint source = !"200".equals(outcomeCode)
                && outcomeCode.startsWith("2")
                ? DeliveryEndpoint.ADAPTER
                : DeliveryEndpoint.WORKER;
        return DeliveryReport.create(
                source,
                source == DeliveryEndpoint.ADAPTER
                        ? "endpoint-1"
                        : "worker-1",
                DeliveryEndpoint.TASK,
                "test.event",
                outcomeCode,
                "null",
                "context"
        );
    }
}
