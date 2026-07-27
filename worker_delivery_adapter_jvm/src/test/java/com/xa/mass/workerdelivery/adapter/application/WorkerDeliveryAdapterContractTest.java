package com.xa.mass.workerdelivery.adapter.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.application.WorkerConnection.CommandDeliveryAttempt;
import com.xa.mass.workerdelivery.adapter.application.WorkerConnection.WorkerConnectionCloseReason;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter.AdapterRoundResult;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter.WorkerResultAcceptance;
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
                        "acceptWorkerResult(SeedResult)"
                                + ":WorkerResultAcceptance",
                        "close():void",
                        "connectWorker(String,WorkerConnection)"
                                + ":void",
                        "disconnectWorker(String,WorkerConnection):void",
                        "dispatchOnce():AdapterRoundResult"
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
                        WorkerResultAcceptance.BUFFER_FULL
                );
        assertThat(Set.of(WorkerConnectionCloseReason.values()))
                .containsExactlyInAnyOrder(
                        WorkerConnectionCloseReason.REPLACED,
                        WorkerConnectionCloseReason.RESULT_BUFFER_FULL,
                        WorkerConnectionCloseReason.TRANSPORT_ERROR,
                        WorkerConnectionCloseReason.ADAPTER_STOPPING
                );
        assertThat(AdapterRoundResult.class.isRecord()).isTrue();
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
