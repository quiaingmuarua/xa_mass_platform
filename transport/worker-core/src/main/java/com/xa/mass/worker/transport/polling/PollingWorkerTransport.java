package com.xa.mass.worker.transport.polling;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_COMMAND_SUCCEEDED;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_COMMAND_FAILED;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_TASK_OUTCOME_OBSERVED;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.TASK;

import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.worker.execution.WorkerCommandExecutor;
import com.xa.mass.worker.execution.WorkerOutcomeReporter;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.TaskOutcomeObservation;
import com.xa.mass.worker.execution.WorkerCommandOutcome;
import com.xa.mass.transport.client.WorkerPointClient;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PollingWorkerTransport implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(
            PollingWorkerTransport.class.getName()
    );

    private final WorkerPointClient client;
    private final String workerId;
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
    private final WorkerCommandExecutor commandExecutor;
    private volatile boolean closed;
    private volatile DeliveryReport pendingResult;

    public PollingWorkerTransport(
            WorkerPointClient client,
            String workerId,
            WorkerCommandExecutor commandExecutor
    ) {
        this.client = requirePresent(client, "client");
        this.workerId = requireNonBlank(workerId, "workerId");
        this.commandExecutor = requirePresent(
                commandExecutor,
                "commandExecutor"
        );
    }

    public boolean runOnce() throws IOException, InterruptedException {
        requireOpen();
        DeliveryReport pending = pendingResult;
        if (pending != null) {
            submitPendingResult(pending);
            return true;
        }

        Optional<String> encodedCommand = client.pollCommand(
                workerId
        );
        if (!encodedCommand.isPresent()) {
            return false;
        }
        DeliveryCommand command = codec.decodeDeliveryCommand(
                encodedCommand.get()
        );
        if (command == null
                || command.dst() != WORKER
                || command.src() == WORKER) {
            throw new WorkerException(
                    WorkerErrorCode.COMMAND_MESSAGE_INVALID,
                    "polling.decodeCommand",
                    null,
                    null
            );
        }
        Optional<WorkerCommandOutcome> result = commandExecutor.execute(command, reporter(command));
        if (!result.isPresent()) {
            return false;
        }
        WorkerCommandOutcome outcome = result.get();
        pendingResult = DeliveryReport.fromCommand(
                command,
                WORKER,
                workerId,
                outcome.isSuccess() ? WORKER_COMMAND_SUCCEEDED : WORKER_COMMAND_FAILED,
                outcome.diagnosticCode(),
                outcome.payload()
        );
        submitPendingResult(pendingResult);
        return true;
    }

    public void runForever(Duration pollInterval)
            throws InterruptedException {
        requirePositive(pollInterval, "pollInterval");
        while (!closed && !Thread.currentThread().isInterrupted()) {
            try {
                boolean handled = runOnce();
                if (!handled && !closed) {
                    Thread.sleep(pollInterval.toMillis());
                }
            } catch (IOException | WorkerException error) {
                if (!closed) {
                    WorkerException failure = classifyRetry(error);
                    LOGGER.log(
                            Level.WARNING,
                            "errorCode={0} operation={1} message={2}",
                            new Object[]{
                                    failure.errorCode().code(),
                                    failure.operation(),
                                    failure.getMessage()
                            }
                    );
                    Thread.sleep(pollInterval.toMillis());
                }
            }
        }
    }

    private WorkerOutcomeReporter reporter(DeliveryCommand command) {
        if (command.src() != TASK) {
            return WorkerOutcomeReporter.UNAVAILABLE;
        }
        String forward = command.forward();
        return (tag, time, payload) -> {
            if (closed) {
                return false;
            }
            try {
                String encoded = codec.encodeDeliveryReport(DeliveryReport.create(
                        WORKER, workerId, TASK, WORKER_TASK_OUTCOME_OBSERVED, "",
                        codec.encodeTaskOutcomeObservation(new TaskOutcomeObservation(tag, time, payload)),
                        forward));
                if (encoded.getBytes(StandardCharsets.UTF_8).length > 1_000_000) {
                    return false;
                }
                client.submitResult(workerId, encoded);
                return true;
            } catch (IOException | RuntimeException failure) {
                LOGGER.log(Level.WARNING, "errorCode={0} operation=polling.outcome.send failureType={1}",
                        new Object[]{WorkerErrorCode.RESULT_SUBMIT_FAILED.code(), failure.getClass().getSimpleName()});
                return false;
            }
        };
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        client.close();
    }

    public boolean hasPendingResult() {
        return pendingResult != null;
    }

    private void submitPendingResult(DeliveryReport sending)
            throws IOException {
        client.submitResult(
                workerId,
                codec.encodeDeliveryReport(sending)
        );
        if (pendingResult == sending) {
            pendingResult = null;
        }
    }

    private static WorkerException classifyRetry(Exception error) {
        if (error instanceof WorkerException) {
            return (WorkerException) error;
        }
        return new WorkerException(
                WorkerErrorCode.COMMAND_POLL_FAILED,
                "polling.pollCommand",
                "Worker command poll request failed",
                error
        );
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "PollingWorkerTransport is closed"
            );
        }
    }

    private static Duration requirePositive(
            Duration value,
            String name
    ) {
        if (value == null
                || value.isZero()
                || value.isNegative()
                || value.toMillis() <= 0) {
            throw new IllegalArgumentException(
                    name + " must be positive"
            );
        }
        return value;
    }

    private static <T> T requirePresent(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(
                    name + " must be present"
            );
        }
        return value;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value;
    }
}
