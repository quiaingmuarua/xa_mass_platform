package com.xa.mass.task.runtime;

/**
 * Temporary non-core read model for ordered final-result windows.
 */
public interface TaskRuntimeResultWindowReadModel {

    FinalResultWindow readFinalResults(FinalResultReadRequest request);
}
