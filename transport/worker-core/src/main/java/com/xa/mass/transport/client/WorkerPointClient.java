package com.xa.mass.transport.client;

import java.io.IOException;
import java.util.Optional;

public interface WorkerPointClient extends AutoCloseable {

    Optional<String> pollCommand(String workerId) throws IOException;

    void submitResult(String workerId, String encodedResult)
            throws IOException;

    @Override
    void close();
}
