package com.xa.mass.worker.execution;

import java.util.Map;

@FunctionalInterface
public interface WorkerEventParameterResolver<P> {

    P resolve(Map<String, Object> parameters) throws Exception;
}
