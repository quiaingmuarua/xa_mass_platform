package com.xa.mass.workerdelivery.adapter.application;

import java.util.concurrent.CompletionStage;

/** Asynchronous Server-owned verification port for one claimed Worker route. */
@FunctionalInterface
public interface WorkerRouteVerifier {

    CompletionStage<Decision> verify(String adapterId, String workerId);

    enum Decision {
        VERIFIED,
        REJECTED
    }
}
