package com.xa.mass.engine.load;

/**
 * Push-updated worker load read view for scheduling.
 *
 * <p>Matching uses reservations as an optimistic capacity guard between
 * candidate selection and runtime claim confirmation. Active counts are still
 * maintained from runtime lifecycle callbacks.</p>
 */
public interface WorkerLoadView {

    int getActiveLeaseCount(String workerId);

    int getReservedCount(String workerId);

    double getEstimatedLoadRatio(String workerId);

    WorkerLoadSnapshot snapshot(String workerId);

    default void recordDeclaredCapacity(String workerId, int declaredCapacity) {
    }

    boolean tryReserveCapacity(String workerId, String taskId);

    boolean confirmReservation(String workerId, String taskId);

    void releaseReservation(String workerId, String taskId);

    void recordWorkClaimed(String workerId, String taskId);

    void recordWorkFinal(String workerId, String taskId);
}
