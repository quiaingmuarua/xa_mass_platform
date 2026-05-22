package com.xa.mass.trace.scenario;

import com.xa.mass.trace.query.TraceQueryBackend;
import com.xa.mass.trace.query.TraceSource;

public interface TraceScenarioAnalyzer {

    String id();

    TraceScenarioReport analyze(TraceQueryBackend queryBackend,
                                TraceSource source,
                                String taskId) throws Exception;
}
