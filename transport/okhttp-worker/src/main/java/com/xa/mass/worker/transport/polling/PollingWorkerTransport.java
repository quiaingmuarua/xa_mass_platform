package com.xa.mass.worker.transport.polling;

import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.worker.execution.WorkerCommandExecutor;
import com.xa.mass.worker.transport.polling.client.OkHttpWorkerPointClient;
import com.xa.mass.worker.transport.polling.client.WorkerPointClient;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

public final class PollingWorkerTransport implements AutoCloseable {

    private static final System.Logger LOGGER = System.getLogger(
            PollingWorkerTransport.class.getName()
    );

    private final WorkerPointClient client;
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
    private final WorkerCommandExecutor commandExecutor;
    private volatile boolean closed;
    private volatile WorkerResult pendingResult;

    public PollingWorkerTransport(
            URI serverUrl,
            String endpointManagerId,
            String workerId,
            Duration requestTimeout,
            WorkerCommandExecutor commandExecutor
    ) {
        this(
                new OkHttpWorkerPointClient(
                        serverUrl,
                        endpointManagerId,
                        workerId,
                        requestTimeout
                ),
                commandExecutor
        );
    }

    public PollingWorkerTransport(
            WorkerPointClient client,
            WorkerCommandExecutor commandExecutor
    ) {
        this.client = requirePresent(client, "client");
        this.commandExecutor = requirePresent(
                commandExecutor,
                "commandExecutor"
        );
    }

    public boolean runOnce() throws IOException, InterruptedException {
        requireOpen();
        WorkerResult pending = pendingResult;
        if (pending != null) {
            submitPendingResult(pending);
            return true;
        }

        Optional<String> encodedCommand = client.pollCommand();
        if (!encodedCommand.isPresent()) {
            return false;
        }
        Optional<WorkerResult> result = commandExecutor.execute(
                encodedCommand.get()
        );
        if (!result.isPresent()) {
            return false;
        }
        pendingResult = result.get();
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
                            System.Logger.Level.WARNING,
                            "errorCode={0} operation={1} message={2}",
                            failure.errorCode().code(),
                            failure.operation(),
                            failure.getMessage()
                    );
                    Thread.sleep(pollInterval.toMillis());
                }
            }
        }
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

    private void submitPendingResult(WorkerResult sending)
            throws IOException {
        client.submitResult(codec.encodeWorkerResult(sending));
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
}
