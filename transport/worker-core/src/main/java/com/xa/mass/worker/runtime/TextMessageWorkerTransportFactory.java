package com.xa.mass.worker.runtime;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.worker.execution.WorkerCommandExecutor;

import java.net.URI;
import java.util.Objects;
import java.util.function.Function;

/**
 * Creates one text-message Transport for a prepared Worker run.
 */
public final class TextMessageWorkerTransportFactory {

    private final Function<URI, TextMessageClient> clientCreator;
    private final WorkerCommandExecutor commandDispatcher;
    private final WorkerPropertiesProvider propertiesProvider;

    public TextMessageWorkerTransportFactory(
            Function<URI, TextMessageClient> clientCreator,
            WorkerCommandExecutor commandDispatcher,
            WorkerPropertiesProvider propertiesProvider
    ) {
        this.propertiesProvider = Objects.requireNonNull(propertiesProvider, "propertiesProvider");
        this.clientCreator = Objects.requireNonNull(
                clientCreator,
                "clientCreator"
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
                clientCreator.apply(preparedWorker.endpointUri()),
                "clientCreator returned null"
        );
        try {
            return new TextMessageWorkerTransport(
                    client,
                    preparedWorker.workerId(),
                    commandDispatcher,
                    propertiesProvider,
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
