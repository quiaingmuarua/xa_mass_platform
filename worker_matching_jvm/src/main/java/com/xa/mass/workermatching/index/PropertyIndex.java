package com.xa.mass.workermatching.index;

import java.util.List;
import java.util.Map;

/** Exact, non-consuming lookup for one property. Missing values are omitted. */
@FunctionalInterface
public interface PropertyIndex {
    /** Caller-bounded unique nonempty values; returns an immutable map in request order. */
    Map<String, String> lookup(String workerGroupId, List<String> values);
}
