package com.xa.mass.trace.query;

import java.util.List;

public interface TraceQueryBackend {

    List<TraceTimelineRow> query(TraceSource source,
                                 TraceQueryFilter filter,
                                 int limit) throws Exception;

    List<TraceTimelineRow> timeline(TraceSource source,
                                    String taskId,
                                    String messageId,
                                    int limit) throws Exception;

    List<TraceStatsRow> stats(TraceSource source,
                              String taskId,
                              String eventType,
                              String severity,
                              int limit) throws Exception;

    List<TraceAssignmentRow> assignment(TraceSource source,
                                        String taskId,
                                        int limit) throws Exception;

    long countRows(TraceSource source) throws Exception;
}
