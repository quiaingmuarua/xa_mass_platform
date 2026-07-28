package com.xa.mass.workerdelivery.adapter.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.application.WorkerConnection.CommandDeliveryAttempt;
import com.xa.mass.workerdelivery.adapter.application.WorkerConnection.WorkerConnectionCloseReason;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterCore.AdapterRoundResult;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterCore.WorkerResultAcceptance;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class WorkerDeliveryAdapterContractTest {

    @Test
    void locksGatewayClientContract() {
        assertThat(signatures(WorkerDeliveryGatewayClient.class))
                .containsExactlyInAnyOrder(
                        "appendResults(String,List):void",
                        "consumeWorkerCommands(String,String,int)"
                                + ":WorkerCommandPage"
                );
    }

    @Test
    void locksConnectionAndRegistryContracts() {
        assertThat(signatures(WorkerConnection.class))
                .containsExactlyInAnyOrder(
                        "close(WorkerConnectionCloseReason):void",
                        "deliver(WorkerCommandEnvelope)"
                                + ":CommandDeliveryAttempt"
                );
        assertThat(signatures(WorkerConnectionRegistry.class))
                .containsExactlyInAnyOrder(
                        "bind(String,WorkerConnection):void",
                        "closeAll(WorkerConnectionCloseReason):void",
                        "deliver(String,WorkerCommandEnvelope)"
                                + ":CommandDeliveryAttempt",
                        "unbind(String,WorkerConnection):void"
                );
    }

    @Test
    void locksAdapterContractAndExplicitOutcomes() {
        assertThat(signatures(WorkerDeliveryAdapter.class))
                .containsExactlyInAnyOrder(
                        "adapterType():WorkerDeliveryAdapterType",
                        "close():void",
                        "endpointManagerId():String",
                        "start():void",
                        "state():WorkerDeliveryAdapterState"
                );
        assertThat(signatures(WorkerDeliveryAdapterFactory.class))
                .containsExactlyInAnyOrder(
                        "adapterType():WorkerDeliveryAdapterType",
                        "create(WorkerDeliveryAdapterRuntimeConfig,"
                                + "WorkerDeliveryAdapterPrivateConfig)"
                                + ":WorkerDeliveryAdapter",
                        "privateConfigType():Class"
                );
        assertThat(signatures(WorkerDeliveryAdapterManager.class))
                .containsExactlyInAnyOrder(
                        "close():void",
                        "register(WorkerDeliveryAdapterDefinition):void",
                        "start():void",
                        "state():WorkerDeliveryAdapterState"
                );
        assertThat(Set.of(CommandDeliveryAttempt.values()))
                .containsExactlyInAnyOrder(
                        CommandDeliveryAttempt.DELIVERED,
                        CommandDeliveryAttempt.REJECTED_BEFORE_SEND,
                        CommandDeliveryAttempt.UNKNOWN
                );
        assertThat(Set.of(WorkerResultAcceptance.values()))
                .containsExactlyInAnyOrder(
                        WorkerResultAcceptance.ACCEPTED,
                        WorkerResultAcceptance.INVALID_OUTCOME,
                        WorkerResultAcceptance.BUFFER_FULL,
                        WorkerResultAcceptance.ADAPTER_CLOSED
                );
        assertThat(Set.of(WorkerConnectionCloseReason.values()))
                .containsExactlyInAnyOrder(
                        WorkerConnectionCloseReason.REPLACED,
                        WorkerConnectionCloseReason.RESULT_BUFFER_FULL,
                        WorkerConnectionCloseReason.TRANSPORT_ERROR,
                        WorkerConnectionCloseReason.ADAPTER_STOPPING
        );
        assertThat(AdapterRoundResult.class.isRecord()).isTrue();
        assertThat(Set.of(WorkerDeliveryAdapterType.values()))
                .containsExactly(WorkerDeliveryAdapterType.WEBSOCKET);
        assertThat(Set.of(WorkerDeliveryAdapterState.values()))
                .containsExactlyInAnyOrder(
                        WorkerDeliveryAdapterState.REGISTERED,
                        WorkerDeliveryAdapterState.RUNNING,
                        WorkerDeliveryAdapterState.STOPPING,
                        WorkerDeliveryAdapterState.CLOSED
                );
        assertThat(WorkerDeliveryAdapterDefinition.class.isRecord())
                .isTrue();
        assertThat(WorkerDeliveryAdapterRuntimeConfig.class.isRecord())
                .isTrue();
        assertThat(WebSocketWorkerDeliveryAdapterConfig.class.isRecord())
                .isTrue();
    }

    private static Set<String> signatures(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(WorkerDeliveryAdapterContractTest::signature)
                .collect(Collectors.toSet());
    }

    private static String signature(Method method) {
        String parameters = Arrays.stream(method.getParameterTypes())
                .map(Class::getSimpleName)
                .collect(Collectors.joining(","));
        return method.getName()
                + "("
                + parameters
                + "):"
                + method.getReturnType().getSimpleName();
    }
}
