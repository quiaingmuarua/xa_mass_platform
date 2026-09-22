package com.xa.mass.server.assembly.pacer;

import com.xa.mass.kernel.pacer.KernelPacerRuntime.WorkerObservation;
import java.util.List;

/**
 * Consumes a nonempty immutable batch of matched notices in receipt order.
 * A batch may span Groups and event selections. Calls are serial and best-effort;
 * implementations must not depend on another handler's effects or request replay.
 */
@FunctionalInterface
interface FunctionHandler {
    void handle(List<WorkerObservation> observations);
}
