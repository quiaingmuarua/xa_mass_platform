package com.xa.mass.runtime.worker;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;

/**
 * Random bounded sampler for large worker candidate buckets.
 *
 * <p>This is a Stage-1 acquisition policy only. Stage-2 still owns rule
 * evaluation, ranking, capacity admission, and dispatch locks.</p>
 */
public final class RandomWorkerCandidateSamplingPolicy implements WorkerCandidateSamplingPolicy {

    private static final RandomWorkerCandidateSamplingPolicy DEFAULT =
            new RandomWorkerCandidateSamplingPolicy(bound -> ThreadLocalRandom.current().nextInt(bound));

    private final IntUnaryOperator randomIndex;

    public RandomWorkerCandidateSamplingPolicy(IntUnaryOperator randomIndex) {
        this.randomIndex = Objects.requireNonNull(randomIndex, "randomIndex");
    }

    public static RandomWorkerCandidateSamplingPolicy defaultPolicy() {
        return DEFAULT;
    }

    @Override
    public List<String> sample(WorkerCandidateSamplingContext context,
                               List<String> workerIds,
                               int maxCandidateCount) {
        if (workerIds == null || workerIds.isEmpty() || maxCandidateCount <= 0) {
            return List.of();
        }
        if (workerIds.size() <= maxCandidateCount) {
            return List.copyOf(workerIds);
        }

        int bucketSize = workerIds.size();
        int sampleSize = Math.min(maxCandidateCount, bucketSize);
        Set<Integer> selectedIndexes = new LinkedHashSet<>();
        for (int cursor = bucketSize - sampleSize; cursor < bucketSize; cursor++) {
            int candidateIndex = Math.floorMod(randomIndex.applyAsInt(cursor + 1), cursor + 1);
            if (!selectedIndexes.add(candidateIndex)) {
                selectedIndexes.add(cursor);
            }
        }

        List<String> selected = new ArrayList<>(sampleSize);
        for (Integer index : selectedIndexes) {
            selected.add(workerIds.get(index));
        }
        return List.copyOf(selected);
    }
}
