package com.xa.mass.server.worker.identity;

import java.util.List;

interface WorkerIdentityRegistry {
    /** Returns one UUID per registration key, in input order, in one bounded operation. */
    List<String> registerAll(String workerGroupId, List<String> registrationKeys);
}
