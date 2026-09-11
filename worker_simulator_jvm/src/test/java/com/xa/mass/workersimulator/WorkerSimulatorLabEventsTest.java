package com.xa.mass.workersimulator;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.execution.WorkerCommandDispatcher;
import com.xa.mass.worker.execution.WorkerCommandOutcome;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorkerSimulatorLabEventsTest {

    @Test
    void backgroundFaultsExposeOnlyDelayAndFail() {
        assertThat(WorkerSimulatorLabEvents.backgroundFaults())
                .extracting(WorkerEventDefinition::eventName)
                .containsExactly(
                        WorkerSimulatorLabEvents.DELAY_EVENT_CODE,
                        WorkerSimulatorLabEvents.FAIL_EVENT_CODE
                );
    }

    @Test
    void delayWaitsAndReturnsOneSuccessfulOutcome() {
        WorkerCommandDispatcher dispatcher = dispatcher();
        long startedAt = System.nanoTime();

        WorkerCommandOutcome outcome = execute(
                dispatcher,
                WorkerSimulatorLabEvents.DELAY_EVENT_CODE,
                "{\"delayMillis\":25}"
        );

        long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS
                .toMillis(System.nanoTime() - startedAt);
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(20L);
        assertThat(outcome.diagnosticCode()).isEqualTo("");
        assertThat(outcome.payload()).isEqualTo("null");
    }

    @Test
    void delayAndFailPayloadsAreStrict() {
        WorkerCommandDispatcher dispatcher = dispatcher();

        for (String payload : List.of(
                "{}",
                "{\"delayMillis\":0}",
                "{\"delayMillis\":30001}",
                "{\"delayMillis\":1.5}",
                "{\"delayMillis\":1,\"extra\":true}"
        )) {
            assertFailure(
                    execute(
                            dispatcher,
                            WorkerSimulatorLabEvents.DELAY_EVENT_CODE,
                            payload
                    ),
                    WorkerErrorCode.EVENT_INPUT_INVALID
            );
        }
        assertFailure(
                execute(
                        dispatcher,
                        WorkerSimulatorLabEvents.FAIL_EVENT_CODE,
                        "{\"unexpected\":true}"
                ),
                WorkerErrorCode.EVENT_INPUT_INVALID
        );
    }

    @Test
    void failMapsToExecutionFailureWithoutPoisoningDispatcher() {
        WorkerCommandDispatcher dispatcher = dispatcher();

        assertFailure(
                execute(
                        dispatcher,
                        WorkerSimulatorLabEvents.FAIL_EVENT_CODE,
                        "{}"
                ),
                WorkerErrorCode.EVENT_EXECUTION_FAILED
        );
        assertThat(execute(
                dispatcher,
                WorkerSimulatorLabEvents.DELAY_EVENT_CODE,
                "{\"delayMillis\":1}"
        ).diagnosticCode()).isEqualTo("");
    }

    private static WorkerCommandDispatcher dispatcher() {
        return WorkerCommandDispatcher.forWorker(
                WorkerSimulatorLabEvents.backgroundFaults()
        );
    }

    private static WorkerCommandOutcome execute(
            WorkerCommandDispatcher dispatcher,
            String eventCode,
            String payload
    ) {
        Optional<WorkerCommandOutcome> outcome = dispatcher.execute(
                DeliveryCommand.create(
                        TASK,
                        WORKER,
                        eventCode,
                        Long.MAX_VALUE,
                        payload,
                        "context"
                )
        );
        return outcome.orElseThrow();
    }

    private static void assertFailure(
            WorkerCommandOutcome outcome,
            WorkerErrorCode errorCode
    ) {
        assertThat(outcome.diagnosticCode())
                .isEqualTo(Integer.toString(errorCode.code()));
        assertThat(outcome.payload()).isEqualTo(errorCode.defaultMessage());
    }
}
