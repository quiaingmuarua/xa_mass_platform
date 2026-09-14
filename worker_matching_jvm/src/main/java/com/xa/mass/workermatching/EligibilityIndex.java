package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.WorkerCandidateIndex.HeldCandidate;
import com.xa.mass.kernel.assignment.WorkerCandidateIndex.InitialHold;
import java.util.List;
import java.util.Map;

/** Three paired operations over one shared Eligibility and one Rule interpretation. */
interface EligibilityIndex {
    Map<EligibilityQuery, Integer> deficits(List<EligibilityQuery> targets);
    int refill(List<EligibilityQuery> targets, int budget, InitialHold kernelHold);
    Map<EligibilityQuery, List<HeldCandidate>> take(List<EligibilityQuery> requests);
}
