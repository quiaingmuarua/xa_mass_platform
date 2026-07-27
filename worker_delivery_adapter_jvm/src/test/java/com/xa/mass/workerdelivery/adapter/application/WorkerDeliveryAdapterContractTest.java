package com.xa.mass.workerdelivery.adapter.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.application.WorkerConnection.CommandDeliveryAttempt;
import com.xa.mass.workerdelivery.adapter.application.WorkerConnection.WorkerConnectionCloseReason;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter.AdapterRoundResult;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter.WorkerResultAcceptance;
import com.xa.mass.workerdelivery.adapter.application.WorkerSessionDirectory.WorkerSessionToken;
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
    void locksConnectionAndSessionContracts() {
        assertThat(signatures(WorkerConnection.class))
                .containsExactlyInAnyOrder(
                        "close(WorkerConnectionCloseReason):void",
                        "deliver(WorkerCommandEnvelope)"
                                + ":CommandDeliveryAttempt"
                );
        assertThat(signatures(WorkerSessionDirectory.class))
                .containsExactlyInAnyOrder(
                        "bind(String,WorkerConnection):WorkerSessionToken",
                        "close(WorkerSessionToken,"
                                + "WorkerConnectionCloseReason):void",
                        "closeAll(WorkerConnectionCloseReason):void",
                        "deliver(String,WorkerCommandEnvelope)"
                                + ":CommandDeliveryAttempt",
                        "isCurrent(WorkerSessionToken):boolean",
                        "unbind(WorkerSessionToken):void"
                );
    }

    @Test
    void locksAdapterContractAndExplicitOutcomes() {
        assertThat(signatures(WorkerDeliveryAdapter.class))
                .containsExactlyInAnyOrder(
                        "acceptWorkerResult(WorkerSessionToken,SeedResult)"
                                + ":WorkerResultAcceptance",
                        "close():void",
                        "connectWorker(String,WorkerConnection)"
                                + ":WorkerSessionToken",
                        "disconnectWorker(WorkerSessionToken):void",
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
                        WorkerResultAcceptance.STALE_SESSION,
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

    @Test
    void sessionTokensAreOpaqueAndIssuedByDirectoryImplementations()
            throws ReflectiveOperationException {
        assertThat(WorkerSessionToken.class.isInterface()).isTrue();
        assertThat(WorkerSessionToken.class.getDeclaredConstructors())
                .isEmpty();
        assertThat(WorkerSessionToken.class.getMethod("workerId").getReturnType())
                .isEqualTo(String.class);
        assertThat(
                WorkerSessionToken.class.getMethod("generation").getReturnType()
        ).isEqualTo(long.class);
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
