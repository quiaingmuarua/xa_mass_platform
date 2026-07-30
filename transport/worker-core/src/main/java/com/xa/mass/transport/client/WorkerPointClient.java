package com.xa.mass.transport.client;

import java.io.IOException;
import java.util.Optional;

public interface WorkerPointClient extends AutoCloseable {

    Optional<String> pollCommand() throws IOException;

    void submitResult(String encodedResult) throws IOException;

    @Override
    void close();
}
