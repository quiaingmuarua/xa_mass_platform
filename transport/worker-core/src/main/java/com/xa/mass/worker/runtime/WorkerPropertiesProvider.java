package com.xa.mass.worker.runtime;

import java.util.Map;

/**
 * Host-owned consistent flat string snapshot. Keys are non-blank, values non-null;
 * empty strings and literal dotted keys are allowed. The SDK retains no live copy.
 */
@FunctionalInterface
public interface WorkerPropertiesProvider {

    Map<String, String> loadProperties() throws Exception;
}
