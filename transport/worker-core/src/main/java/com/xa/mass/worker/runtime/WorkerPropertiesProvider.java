package com.xa.mass.worker.runtime;

import java.util.Map;

@FunctionalInterface
public interface WorkerPropertiesProvider {

    Map<String, Object> loadProperties() throws Exception;
}
