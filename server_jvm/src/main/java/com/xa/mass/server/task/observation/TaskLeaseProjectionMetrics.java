package com.xa.mass.server.task.observation;

import java.util.Map;

/** Application read capability; exposes neither producer admission nor consumer lifecycle. */
@FunctionalInterface
public interface TaskLeaseProjectionMetrics {
    Map<String, Object> metrics();
}
