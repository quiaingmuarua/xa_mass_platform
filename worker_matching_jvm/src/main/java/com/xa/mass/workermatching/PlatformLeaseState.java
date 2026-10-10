package com.xa.mass.workermatching;

import java.util.List;
import java.util.Map;

/** Matching-owned absolute-time eligibility state, independent of Worker Platform JSON. */
public interface PlatformLeaseState {
    record Coordinate(String subject, String partition, String workerId) {
        public Coordinate { RuleInputs.text(subject); RuleInputs.text(partition); RuleInputs.text(workerId); }
    }
    Map<Coordinate, Long> read(String group, String pool, List<Coordinate> coordinates);
    void record(String group, String pool, Map<Coordinate, Long> deadlines);
}
