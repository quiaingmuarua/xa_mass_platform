package com.xa.mass.kernel.assignment;

import java.util.List;

public interface CandidateWarmupSchedule {

    void scheduleCandidateWarmups(
            List<String> taskIds,
            long dueTimeMillis
    );

    List<String> consumeDueCandidateWarmups(
            long beforeTimeMillis,
            int limit
    );
}
