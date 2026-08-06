package com.xa.mass.worker.runtime;

import java.io.IOException;
import java.util.Optional;

public interface WorkerIdentityStore {

    Optional<String> loadWorkerId() throws IOException;

    void saveWorkerId(String workerId) throws IOException;

    static WorkerIdentityStore noCache() {
        return new WorkerIdentityStore() {
            @Override
            public Optional<String> loadWorkerId() {
                return Optional.empty();
            }

            @Override
            public void saveWorkerId(String workerId) {
                // An explicit no-cache Worker re-registers on start.
            }
        };
    }
}
