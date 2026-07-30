package com.xa.mass.worker.transport.polling.client;

import java.io.IOException;
import java.util.Optional;

public interface WorkerPointClient extends AutoCloseable {

    Optional<String> pollCommand() throws IOException;

    void submitResult(String encodedResult) throws IOException;

    @Override
    void close();
}
