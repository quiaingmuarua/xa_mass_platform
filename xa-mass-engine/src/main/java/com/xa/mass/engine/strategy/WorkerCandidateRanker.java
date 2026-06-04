package com.xa.mass.engine.strategy;

import com.xa.mass.engine.model.WorkerMatchContext;
import com.xa.mass.engine.runtime.scheduling.TaskDispatchIntent;

import java.util.List;

/**
 * Orders rule-passed worker candidates before lock acquisition.
 *
 * <p>Eligibility stays owned by matching rules and prefilter checks. A ranker
 * only changes preference among candidates that already passed those gates.</p>
 */
public interface WorkerCandidateRanker {

    List<WorkerMatchContext> rank(List<WorkerMatchContext> candidates, TaskDispatchIntent dispatchIntent);
}
