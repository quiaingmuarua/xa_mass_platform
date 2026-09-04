package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.netty.internal.remote.WorkerDeliveryRemoteApi;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** Owns the three finite Report lanes and their one resident consumer. */
public final class DeliveryReportDispatcher {

    private static final int BATCH_SIZE =
            WorkerDeliveryRemoteApi.MAX_RESULTS_PER_APPEND;
    private static final int LANE_COUNT = 3;
    private static final System.Logger LOGGER = System.getLogger(
            DeliveryReportDispatcher.class.getName()
    );

    private final Object taskAdmissionGate = new Object();
    private final Object systemAdmissionGate = new Object();
    private final Object kernelAdmissionGate = new Object();
    private final LinkedBlockingQueue<DeliveryReport> taskQueue;
    private final LinkedBlockingQueue<DeliveryReport> systemQueue;
    private final LinkedBlockingQueue<DeliveryReport> kernelQueue;
    private final ReentrantLock availabilityGate = new ReentrantLock();
    private final Condition reportAvailable = availabilityGate.newCondition();
    private final WorkerDeliveryRemoteApi remoteApi;
    private final String adapterId;
    private final int softCapacity;
    private final long backoffMillis;
    private final Thread thread;
    private final AtomicLong systemIngressDrops = new AtomicLong();
    private final AtomicLong kernelIngressDrops = new AtomicLong();
    private volatile boolean accepting = true;
    private volatile boolean stopped;
    private int nextLane;

    public DeliveryReportDispatcher(
            String adapterId,
            int softCapacity,
            Duration backoff,
            WorkerDeliveryRemoteApi remoteApi
    ) {
        this.adapterId = requireNonBlank(adapterId, "adapterId");
        if (softCapacity <= 0) {
            throw new IllegalArgumentException(
                    "softCapacity must be positive"
            );
        }
        long taskPhysicalCapacity = (long) softCapacity + BATCH_SIZE;
        if (taskPhysicalCapacity > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("softCapacity is too large");
        }
        this.softCapacity = softCapacity;
        backoffMillis = requirePositiveMillis(backoff, "backoff");
        this.remoteApi = Objects.requireNonNull(remoteApi, "remoteApi");
        taskQueue = new LinkedBlockingQueue<>((int) taskPhysicalCapacity);
        systemQueue = new LinkedBlockingQueue<>(softCapacity);
        kernelQueue = new LinkedBlockingQueue<>(softCapacity);
        thread = new Thread(
                this::runLoop,
                "worker-delivery-" + adapterId + "-delivery-report"
        );
        thread.setDaemon(true);
    }

    public DispatchStatus tryDispatch(DeliveryReport report) {
        DeliveryReport required = Objects.requireNonNull(report, "report");
        DispatchStatus status = switch (required.dst()) {
            case TASK -> admit(
                    required,
                    taskAdmissionGate,
                    taskQueue
            );
            case SYSTEM -> admit(
                    required,
                    systemAdmissionGate,
                    systemQueue
            );
            case KERNEL -> admit(
                    required,
                    kernelAdmissionGate,
                    kernelQueue
            );
            case ADAPTER, WORKER -> throw new IllegalArgumentException(
                    "Report destination must be TASK, SYSTEM, or KERNEL"
            );
        };
        if (status != DispatchStatus.ACCEPTED
                && required.dst() != DeliveryEndpoint.TASK) {
            recordBestEffortIngressDrop(required.dst(), status);
        }
        return status;
    }

    void start() {
        thread.start();
    }

    void stopIngress() {
        accepting = false;
        awaitAdmission(taskAdmissionGate);
        awaitAdmission(systemAdmissionGate);
        awaitAdmission(kernelAdmissionGate);
    }

    void stop() {
        stopped = true;
        signalReportAvailable();
        thread.interrupt();
    }

    boolean isAlive() {
        return thread.isAlive();
    }

    Thread thread() {
        return thread;
    }

    void join(long remainingNanos) throws InterruptedException {
        if (remainingNanos <= 0) {
            return;
        }
        long millis = remainingNanos / 1_000_000L;
        int nanos = (int) (remainingNanos % 1_000_000L);
        thread.join(millis, nanos);
    }

    private void runLoop() {
        try {
            while (isActive()) {
                ReportBatch batch = takeBatch();
                if (batch == null || !isActive()) {
                    return;
                }
                processOnce(batch);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            stopIngress();
            taskQueue.clear();
            systemQueue.clear();
            kernelQueue.clear();
        }
    }

    private ReportBatch takeBatch() throws InterruptedException {
        while (isActive()) {
            ReportBatch batch = pollBatch();
            if (batch != null) {
                return batch;
            }
            availabilityGate.lockInterruptibly();
            try {
                while (isActive() && queuesAreEmpty()) {
                    reportAvailable.await();
                }
            } finally {
                availabilityGate.unlock();
            }
        }
        return null;
    }

    private ReportBatch pollBatch() {
        for (int offset = 0; offset < LANE_COUNT; offset++) {
            int laneIndex = (nextLane + offset) % LANE_COUNT;
            DeliveryEndpoint destination = destination(laneIndex);
            LinkedBlockingQueue<DeliveryReport> queue = queue(destination);
            DeliveryReport first = queue.poll();
            if (first == null) {
                continue;
            }
            ArrayList<DeliveryReport> reports = new ArrayList<>(BATCH_SIZE);
            reports.add(first);
            queue.drainTo(reports, BATCH_SIZE - 1);
            nextLane = (laneIndex + 1) % LANE_COUNT;
            return new ReportBatch(destination, List.copyOf(reports));
        }
        return null;
    }

    private void processOnce(ReportBatch batch) throws InterruptedException {
        try {
            remoteApi.appendReports(adapterId, batch.reports());
        } catch (RuntimeException error) {
            if (!isActive()) {
                return;
            }
            WorkerDeliveryAdapterException failure = normalize(error);
            if (batch.destination() == DeliveryEndpoint.TASK
                    && failure.errorCode()
                    == WorkerDeliveryAdapterErrorCode
                    .REMOTE_API_UNAVAILABLE) {
                DispatchStatus status = requeueTask(batch.reports());
                logRetryDropIfRejected(status, batch.reports().size());
            }
            logFailure(failure, batch);
            awaitBackoff();
        }
    }

    private DispatchStatus admit(
            DeliveryReport report,
            Object admissionGate,
            LinkedBlockingQueue<DeliveryReport> queue
    ) {
        synchronized (admissionGate) {
            if (!accepting) {
                return DispatchStatus.CLOSED;
            }
            if (queue.size() >= softCapacity || !queue.offer(report)) {
                return DispatchStatus.FULL;
            }
            signalReportAvailable();
            return DispatchStatus.ACCEPTED;
        }
    }

    private DispatchStatus requeueTask(List<DeliveryReport> reports) {
        synchronized (taskAdmissionGate) {
            if (!accepting || stopped) {
                return DispatchStatus.CLOSED;
            }
            if (taskQueue.remainingCapacity() < reports.size()) {
                return DispatchStatus.FULL;
            }
            taskQueue.addAll(reports);
            signalReportAvailable();
            return DispatchStatus.ACCEPTED;
        }
    }

    private void signalReportAvailable() {
        availabilityGate.lock();
        try {
            reportAvailable.signal();
        } finally {
            availabilityGate.unlock();
        }
    }

    private boolean queuesAreEmpty() {
        return taskQueue.isEmpty()
                && systemQueue.isEmpty()
                && kernelQueue.isEmpty();
    }

    private LinkedBlockingQueue<DeliveryReport> queue(
            DeliveryEndpoint destination
    ) {
        return switch (destination) {
            case TASK -> taskQueue;
            case SYSTEM -> systemQueue;
            case KERNEL -> kernelQueue;
            case ADAPTER, WORKER -> throw new IllegalArgumentException(
                    "Unsupported Report destination"
            );
        };
    }

    private static DeliveryEndpoint destination(int laneIndex) {
        return switch (laneIndex) {
            case 0 -> DeliveryEndpoint.TASK;
            case 1 -> DeliveryEndpoint.SYSTEM;
            case 2 -> DeliveryEndpoint.KERNEL;
            default -> throw new IllegalArgumentException(
                    "Unknown Report lane"
            );
        };
    }

    private void logRetryDropIfRejected(
            DispatchStatus status,
            int itemCount
    ) {
        if (status == DispatchStatus.ACCEPTED
                || status == DispatchStatus.CLOSED && stopped) {
            return;
        }
        LOGGER.log(
                System.Logger.Level.WARNING,
                "errorCode={0} operation={1} adapterId={2} itemCount={3} "
                        + "queueStatus={4} message={5}",
                WorkerDeliveryAdapterErrorCode.REMOTE_API_UNAVAILABLE.code(),
                "deliveryReport.requeue",
                adapterId,
                itemCount,
                status,
                "TASK Report retry batch was dropped"
        );
    }

    private void recordBestEffortIngressDrop(
            DeliveryEndpoint destination,
            DispatchStatus status
    ) {
        AtomicLong counter = destination == DeliveryEndpoint.SYSTEM
                ? systemIngressDrops
                : kernelIngressDrops;
        long droppedCount = counter.incrementAndGet();
        if (droppedCount != 1L && droppedCount % 1_024L != 0L) {
            return;
        }
        LOGGER.log(
                System.Logger.Level.WARNING,
                "errorCode={0} operation={1} adapterId={2} destination={3} "
                        + "queueStatus={4} droppedCount={5} message={6}",
                WorkerDeliveryAdapterErrorCode.DELIVERY_INTERRUPTED.code(),
                "deliveryReport.admit",
                adapterId,
                destination,
                status,
                droppedCount,
                "Best-effort Report ingress was dropped"
        );
    }

    private void logFailure(
            WorkerDeliveryAdapterException failure,
            ReportBatch batch
    ) {
        LOGGER.log(
                System.Logger.Level.WARNING,
                "errorCode={0} operation={1} adapterId={2} destination={3} "
                        + "itemCount={4} message={5}",
                failure.errorCode().code(),
                failure.operation(),
                adapterId,
                batch.destination(),
                batch.reports().size(),
                failure.getMessage()
        );
    }

    private static WorkerDeliveryAdapterException normalize(
            RuntimeException error
    ) {
        if (error instanceof WorkerDeliveryAdapterException classified) {
            return classified;
        }
        return new WorkerDeliveryAdapterException(
                WorkerDeliveryAdapterErrorCode.DELIVERY_INTERRUPTED,
                "deliveryReport.dispatch",
                "DeliveryReport batch processing failed",
                error
        );
    }

    private void awaitBackoff() throws InterruptedException {
        if (isActive()) {
            Thread.sleep(backoffMillis);
        }
    }

    private boolean isActive() {
        return accepting
                && !stopped
                && !Thread.currentThread().isInterrupted();
    }

    private static void awaitAdmission(Object gate) {
        synchronized (gate) {
            // Wait only for a producer already inside its short admission.
        }
    }

    private static long requirePositiveMillis(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero()
                || value.isNegative()
                || value.toMillis() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value.toMillis();
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value;
    }

    public enum DispatchStatus {
        ACCEPTED,
        FULL,
        CLOSED
    }

    private record ReportBatch(
            DeliveryEndpoint destination,
            List<DeliveryReport> reports
    ) {
    }
}
