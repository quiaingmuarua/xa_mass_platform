package com.xa.mass.worker.runtime;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_CLOSE_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_IDENTIFY_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.execution.WorkerCommandExecutor;
import com.xa.mass.worker.execution.WorkerCommandOutcome;
import com.xa.mass.worker.execution.WorkerManagementEventDefinitions;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Runs Worker Delivery text protocol over one prepared endpoint. */
final class TextMessageWorkerTransport
        implements AutoCloseable, TextMessageClient.Listener {

    @FunctionalInterface
    interface Listener {

        void onTerminated(
                TextMessageWorkerTransport transport,
                Throwable failure
        );
    }

    private static final Logger LOGGER = Logger.getLogger(
            TextMessageWorkerTransport.class.getName()
    );

    private static final String PROPERTIES_REPORTED_EVENT =
            "platform.worker.properties.reported";
    private static final int MAX_REPORT_BYTES = 1_000_000;

    private final TextMessageClient client;
    private final WorkerPropertiesProvider propertiesProvider;
    private final String workerId;
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
    private final WorkerCommandExecutor commandDispatcher;
    private final Listener listener;

    TextMessageWorkerTransport(
            TextMessageClient client,
            String workerId,
            WorkerCommandExecutor commandDispatcher,
            WorkerPropertiesProvider propertiesProvider,
            Listener listener
    ) {
        this.client = Objects.requireNonNull(client, "client");
        this.workerId = requireNonBlank(workerId, "workerId");
        this.commandDispatcher = Objects.requireNonNull(
                commandDispatcher,
                "commandDispatcher"
        );
        this.propertiesProvider = Objects.requireNonNull(propertiesProvider, "propertiesProvider");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    void start() {
        client.start(this);
    }

    @Override
    public void onOpen() {
        Throwable failure = sendIdentity();
        if (failure != null) {
            client.closeCurrent(TextMessageClient.CloseReason.SEND_FAILURE);
            rethrowError(failure);
        }
    }

    @Override
    public void onMessage(String message) {
        try {
            DeliveryCommand command = codec.decodeDeliveryCommand(message);
            if (command == null
                    || command.dst() != WORKER
                    || command.src() == WORKER) {
                log(
                        WorkerErrorCode.COMMAND_MESSAGE_INVALID,
                        "command.decode",
                        null
                );
                return;
            }
            if (isConnectionClose(command)) {
                if (System.currentTimeMillis()
                        < command.executeBeforeMillis()) {
                    terminateFromAdapter();
                }
                return;
            }

            Optional<WorkerCommandOutcome> result = Objects.requireNonNull(
                    commandDispatcher.execute(command),
                    "commandDispatcher returned null"
            );
            if (!result.isPresent()) {
                return;
            }

            WorkerCommandOutcome outcome = result.get();
            if (command.src() == ADAPTER
                    && WorkerManagementEventDefinitions.PROPERTIES_SNAPSHOT_EVENT
                            .equals(command.messageType())) {
                if ("200".equals(outcome.outcomeCode())) {
                    sendProperties(outcome.payload());
                }
                return;
            }
            DeliveryReport report = DeliveryReport.fromCommand(
                    command,
                    WORKER,
                    workerId,
                    outcome.outcomeCode(),
                    outcome.payload()
            );
            String encoded = codec.encodeDeliveryReport(report);
            if (!client.send(encoded)) {
                log(
                        WorkerErrorCode.RESULT_SUBMIT_FAILED,
                        "result.send",
                        null
                );
            }
        } catch (RuntimeException unexpected) {
            log(
                    WorkerErrorCode.EVENT_EXECUTION_FAILED,
                    "command.process",
                    unexpected
            );
        }
    }

    boolean reportProperties() {
        try {
            Map<String, String> properties = WorkerDeliveryCodec.copyWorkerProperties(
                    propertiesProvider.loadProperties()
            );
            if (properties.containsKey("clientWorkerKey")) {
                throw new IllegalArgumentException(
                        "workerProperties must not expose clientWorkerKey"
                );
            }
            return sendProperties(Jsons.toJson(Map.of("properties", properties)));
        } catch (Exception failure) {
            log(WorkerErrorCode.RESULT_SUBMIT_FAILED, "properties.report", failure);
            return false;
        }
    }

    static String propertiesPatch(Map<String, String> set, Set<String> remove) {
        Map<String, String> captured = WorkerDeliveryCodec.copyWorkerProperties(set);
        Objects.requireNonNull(remove, "remove");
        List<String> removed = new ArrayList<>();
        for (Object key : remove) {
            if (!(key instanceof String) || ((String) key).isBlank()
                    || captured.containsKey(key)) {
                throw new IllegalArgumentException(
                        "remove requires non-blank keys disjoint from set"
                );
            }
            removed.add((String) key);
        }
        return Jsons.toJson(Map.of("set", captured, "remove", removed));
    }

    boolean sendProperties(String payload) {
        try {
            String encoded = codec.encodeDeliveryReport(DeliveryReport.create(
                    WORKER, workerId, ADAPTER, PROPERTIES_REPORTED_EVENT,
                    "200", payload, ""
            ));
            if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_REPORT_BYTES) {
                log(WorkerErrorCode.RESULT_SUBMIT_FAILED, "properties.size", null);
                return false;
            }
            return client.send(encoded);
        } catch (RuntimeException failure) {
            log(WorkerErrorCode.RESULT_SUBMIT_FAILED, "properties.send", failure);
            return false;
        }
    }

    @Override
    public void onEndpointTerminated() {
        notifyTerminated();
    }

    private void terminateFromAdapter() {
        closeClientQuietly();
        notifyTerminated();
    }

    @Override
    public void close() {
        closeClientQuietly();
    }

    private Throwable sendIdentity() {
        try {
            DeliveryReport identity = DeliveryReport.create(
                    WORKER,
                    workerId,
                    ADAPTER,
                    WORKER_CONNECTION_IDENTIFY_EVENT_CODE,
                    "200",
                    "null",
                    ""
            );
            return client.send(codec.encodeDeliveryReport(identity))
                    ? null
                    : new IllegalStateException(
                            "Worker connection identity was not accepted"
                    );
        } catch (Throwable failure) {
            return failure;
        }
    }

    private void notifyTerminated() {
        try {
            listener.onTerminated(this, null);
        } catch (RuntimeException ignored) {
            // The controller owns current-run callback suppression.
        }
    }

    private void log(
            WorkerErrorCode errorCode,
            String operation,
            Exception failure
    ) {
        LOGGER.log(
                Level.WARNING,
                "errorCode={0} operation={1} workerId={2} failureType={3}",
                new Object[]{
                        errorCode.code(),
                        operation,
                        workerId,
                        failure == null
                                ? "none"
                                : failure.getClass().getSimpleName()
                }
        );
    }

    private void closeClientQuietly() {
        try {
            client.close();
        } catch (RuntimeException ignored) {
            // Current-run revocation does not depend on network teardown.
        }
    }

    private static boolean isConnectionClose(DeliveryCommand command) {
        return command.src() == ADAPTER
                && WORKER_CONNECTION_CLOSE_EVENT_CODE.equals(
                command.messageType()
        );
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value;
    }

    private static void rethrowError(Throwable failure) {
        if (failure instanceof Error) {
            throw (Error) failure;
        }
    }
}
