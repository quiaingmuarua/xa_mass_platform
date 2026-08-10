package com.xa.mass.worker.runtime;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.transport.client.TextMessageClientFactory;
import com.xa.mass.worker.execution.WorkerCommandExecutor;

import java.util.Objects;

/**
 * Creates one text-message Transport for a prepared Worker run.
 */
public final class TextMessageWorkerTransportFactory {

    private final TextMessageClientFactory clientFactory;
    private final WorkerCommandExecutor commandDispatcher;

    public TextMessageWorkerTransportFactory(
            TextMessageClientFactory clientFactory,
            WorkerCommandExecutor commandDispatcher
    ) {
        this.clientFactory = Objects.requireNonNull(
                clientFactory,
                "clientFactory"
        );
        this.commandDispatcher = Objects.requireNonNull(
                commandDispatcher,
                "commandDispatcher"
        );
    }

    TextMessageWorkerTransport create(
            PreparedWorker preparedWorker,
            TextMessageWorkerTransport.Listener listener
    ) {
        Objects.requireNonNull(preparedWorker, "preparedWorker");
        TextMessageClient client = Objects.requireNonNull(
                clientFactory.create(preparedWorker.endpointUri()),
                "clientFactory returned null"
        );
        try {
            return new TextMessageWorkerTransport(
                    client,
                    preparedWorker.workerId(),
                    commandDispatcher,
                    listener
            );
        } catch (RuntimeException | Error failure) {
            closeQuietly(client);
            throw failure;
        }
    }

    private static void closeQuietly(TextMessageClient client) {
        try {
            client.close();
        } catch (RuntimeException ignored) {
            // Candidate cleanup must preserve the construction failure.
        }
    }
}
