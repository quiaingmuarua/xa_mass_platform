package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.RefillTarget;
import com.xa.mass.kernel.assignment.WorkerMatching;
import com.xa.mass.kernel.assignment.WorkerQuery;
import java.util.List;

/** Query and Pool supply admission alongside bounded Pacer operations. */
public interface WorkerMatchingCatalog extends WorkerMatching {
    /** Idempotent Server admission, without Redis reads or stock changes. */
    WorkerQuery normalizeQuery(String workerGroupId, WorkerQuery query);

    /** Normalizes explicit immutable supply, including an empty list, without defaults, Redis or stock access. */
    List<RefillTarget> normalizeRefill(String workerGroupId, List<RefillTarget> declarations);
}
