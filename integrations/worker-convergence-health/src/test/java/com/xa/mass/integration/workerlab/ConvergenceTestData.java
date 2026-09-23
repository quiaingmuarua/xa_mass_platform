package com.xa.mass.integration.workerlab;

import com.xa.mass.integration.workerlab.ConvergenceWorkload.Batch;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ConvergenceTestData {

    private ConvergenceTestData() {
    }

    static List<Batch> batches(int waves) {
        List<Batch> batches = new ArrayList<>();
        for (int wave = 1; wave <= waves; wave++) {
            for (String group : List.of(
                    WorkerLabConvergenceSupport.PHONE_GROUP,
                    WorkerLabConvergenceSupport.STRING_GROUP
            )) {
                String prefix = "wave-" + wave + "-" + group;
                Set<String> ids = new LinkedHashSet<>();
                for (int item = 1;
                     item <= ConvergenceWorkload.ITEMS_PER_GROUP_PER_WAVE;
                     item++) {
                    ids.add(prefix + "-" + item);
                }
                batches.add(new Batch(
                        "wave-" + wave,
                        group,
                        "task-" + group,
                        ids,
                        prefix + "-1",
                        ConvergenceWorkload.INVALID_ITEMS_PER_GROUP_PER_WAVE
                ));
            }
        }
        return List.copyOf(batches);
    }

    static Map<String, String> workerIds() {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 1;
             index <= WorkerLabConvergenceSupport.WORKER_COUNT;
             index++) {
            values.put("coordinate-" + index, "worker-" + index);
        }
        return Map.copyOf(values);
    }
}
