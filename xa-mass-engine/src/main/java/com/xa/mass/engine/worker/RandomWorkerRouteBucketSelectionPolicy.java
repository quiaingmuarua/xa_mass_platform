package com.xa.mass.engine.worker;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;

/**
 * Random bounded sampler for large route buckets.
 *
 * <p>This avoids fixed-prefix hot spots without turning Stage-1 acquisition
 * into a full scheduling decision. Stage-2 policies still own eligibility,
 * ranking, capacity admission, and locking.</p>
 */
public final class RandomWorkerRouteBucketSelectionPolicy implements WorkerRouteBucketSelectionPolicy {

    private static final RandomWorkerRouteBucketSelectionPolicy DEFAULT =
            new RandomWorkerRouteBucketSelectionPolicy(bound -> ThreadLocalRandom.current().nextInt(bound));

    private final IntUnaryOperator randomIndex;

    public RandomWorkerRouteBucketSelectionPolicy(IntUnaryOperator randomIndex) {
        this.randomIndex = Objects.requireNonNull(randomIndex, "randomIndex");
    }

    public static RandomWorkerRouteBucketSelectionPolicy defaultPolicy() {
        return DEFAULT;
    }

    @Override
    public List<String> select(WorkerRouteBucketSelectionContext context,
                               List<String> workerIds,
                               int maxCandidateCount) {
        if (workerIds == null || workerIds.isEmpty() || maxCandidateCount <= 0) {
            return List.of();
        }
        if (workerIds.size() <= maxCandidateCount) {
            return List.copyOf(workerIds);
        }

        Set<Integer> selectedIndexes = new LinkedHashSet<>();
        int bucketSize = workerIds.size();
        int sampleSize = Math.min(maxCandidateCount, bucketSize);
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
