package com.xa.mass.worker.runtime.selection;

public interface WorkerSelectionRuntime {

    WorkerSelectionResult selectAndReserve(WorkerSelectionRequest request);

    boolean confirmSelected(SelectedWorkerHandle handle);

    void releaseSelected(SelectedWorkerHandle handle);

    void releaseSelected(SelectedWorkerEvidence evidence);

    void recordSelectedClaimed(SelectedWorkerHandle handle);

    void recordSelectedFinal(SelectedWorkerEvidence evidence);

    void releaseSelectedLock(SelectedWorkerHandle handle);

    void releaseSelectedLock(SelectedWorkerEvidence evidence);
}
