package com.xa.mass.scenarioworkers;

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

class ScenarioWorkerLabEventsTest {

    @Test
    void backgroundFaultsExposeOnlyDelayAndFail() {
        assertThat(ScenarioWorkerLabEvents.backgroundFaults())
                .extracting(WorkerEventDefinition::eventName)
                .containsExactly(
                        ScenarioWorkerLabEvents.DELAY_EVENT_CODE,
                        ScenarioWorkerLabEvents.FAIL_EVENT_CODE
                );
    }

    @Test
    void delayWaitsAndReturnsOneSuccessfulOutcome() {
        WorkerCommandDispatcher dispatcher = dispatcher();
        long startedAt = System.nanoTime();

        WorkerCommandOutcome outcome = execute(
                dispatcher,
                ScenarioWorkerLabEvents.DELAY_EVENT_CODE,
                "{\"delayMillis\":25}"
        );

        long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS
                .toMillis(System.nanoTime() - startedAt);
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(20L);
        assertThat(outcome.outcomeCode()).isEqualTo("200");
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
                            ScenarioWorkerLabEvents.DELAY_EVENT_CODE,
                            payload
                    ),
                    WorkerErrorCode.EVENT_INPUT_INVALID
            );
        }
        assertFailure(
                execute(
                        dispatcher,
                        ScenarioWorkerLabEvents.FAIL_EVENT_CODE,
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
                        ScenarioWorkerLabEvents.FAIL_EVENT_CODE,
                        "{}"
                ),
                WorkerErrorCode.EVENT_EXECUTION_FAILED
        );
        assertThat(execute(
                dispatcher,
                ScenarioWorkerLabEvents.DELAY_EVENT_CODE,
                "{\"delayMillis\":1}"
        ).outcomeCode()).isEqualTo("200");
    }

    private static WorkerCommandDispatcher dispatcher() {
        return WorkerCommandDispatcher.forWorker(
                ScenarioWorkerLabEvents.backgroundFaults()
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
        assertThat(outcome.outcomeCode())
                .isEqualTo(Integer.toString(errorCode.code()));
        assertThat(outcome.payload()).isEqualTo(errorCode.defaultMessage());
    }
}
