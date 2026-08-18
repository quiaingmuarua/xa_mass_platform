package com.xa.mass.kernel.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerGroupDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeResult;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MappedWorkerPropertyIndexRuntimeTest {

    private Catalog catalog;
    private Index workerIndex;
    private Index platformIndex;
    private MappedWorkerPropertyIndexRuntime runtime;

    @BeforeEach
    void setUp() {
        catalog = new Catalog();
        workerIndex = new Index();
        platformIndex = new Index();
        runtime = new MappedWorkerPropertyIndexRuntime(
                catalog,
                Map.of(
                        "index.worker.region", workerIndex,
                        "index.platform.pool", platformIndex
                )
        );
    }

    @Test
    void rejectsInvalidRegistryFields() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MappedWorkerPropertyIndexRuntime(
                        catalog,
                        Map.of("worker.region", new Index())
                )
        );
    }

    @Test
    void updatesRouteQualifiedFieldsWithoutGroupDeclaration() {
        Map<String, WorkerRuntimeResult> results =
                runtime.updateIndexedProperties(
                        "group-1",
                        "worker-1",
                        Map.of(
                                "index.worker.region", "cn-east",
                                "index.platform.pool", "batch",
                                "index.missing", "value",
                                "worker.invalid", "value"
                        )
                );

        assertEquals(
                WorkerRuntimeStatus.OK,
                results.get("index.worker.region").status()
        );
        assertEquals(
                WorkerRuntimeStatus.OK,
                results.get("index.platform.pool").status()
        );
        assertEquals(
                WorkerRuntimeStatus.NOT_FOUND,
                results.get("index.missing").status()
        );
        assertEquals(
                WorkerRuntimeStatus.INVALID,
                results.get("worker.invalid").status()
        );
        assertEquals(
                List.of(new Update("group-1", "worker-1", "cn-east")),
                workerIndex.updates
        );
    }

    @Test
    void loadKeepsProviderFailureDistinctFromMissingValues() {
        Map<String, Object> result = runtime.loadIndexedPropertyValues(
                "group-2",
                "index.worker.region",
                List.of("worker-1", "worker-1")
        );
        assertEquals(Map.of("worker-1", "cn-east"), result);
        assertEquals(
                List.of(new Load(
                        "group-2",
                        List.of("worker-1")
                )),
                workerIndex.loads
        );

        assertThrows(
                IllegalStateException.class,
                () -> runtime.loadIndexedPropertyValues(
                        "group-1",
                        "index.platform.missing",
                        List.of("worker-1")
                )
        );
        workerIndex.loadFailure = new IllegalStateException("unavailable");
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> runtime.loadIndexedPropertyValues(
                        "group-1",
                        "index.worker.region",
                        List.of("worker-1")
                )
        );
        assertEquals("unavailable", error.getMessage());
    }

    @Test
    void loadRejectsEmptyAndOversizedWorkerBatches() {
        assertThrows(
                IllegalArgumentException.class,
                () -> runtime.loadIndexedPropertyValues(
                        "group-1",
                        "index.worker.region",
                        List.of()
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> runtime.loadIndexedPropertyValues(
                        "group-1",
                        "index.worker.region",
                        java.util.stream.IntStream.range(0, 101)
                                .mapToObj(index -> "worker-" + index)
                                .toList()
                )
        );
    }

    private record Update(
            String workerGroupId,
            String workerId,
            Object value
    ) {
    }

    private record Load(
            String workerGroupId,
            List<String> workerIds
    ) {
    }

    private static final class Index implements WorkerPropertyIndex {

        private final List<Update> updates = new ArrayList<>();
        private final List<Load> loads = new ArrayList<>();
        private RuntimeException loadFailure;

        @Override
        public WorkerRuntimeResult update(
                String workerGroupId,
                String workerId,
                Object value
        ) {
            updates.add(new Update(workerGroupId, workerId, value));
            return new WorkerRuntimeResult(WorkerRuntimeStatus.OK);
        }

        @Override
        public Map<String, Object> load(
                String workerGroupId,
                List<String> workerIds
        ) {
            if (loadFailure != null) {
                throw loadFailure;
            }
            loads.add(new Load(workerGroupId, workerIds));
            return Map.of("worker-1", "cn-east");
        }
    }

    private static final class Catalog implements WorkerResourceCatalog {

        private final Map<String, WorkerGroupDescriptor> groups = Map.of(
                "group-1", group("group-1"),
                "group-2", group("group-2")
        );

        @Override
        public WorkerRuntimeResult upsertWorkerGroup(
                WorkerGroupDescriptor descriptor
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, WorkerGroupDescriptor> getWorkerGroupDescriptors(
                List<String> workerGroupIds
        ) {
            var result = new LinkedHashMap<String, WorkerGroupDescriptor>();
            for (String workerGroupId : workerGroupIds) {
                result.put(workerGroupId, groups.get(workerGroupId));
            }
            return result;
        }

        @Override
        public Map<String, WorkerDescriptor> getWorkerDescriptors(
                String workerGroupId,
                List<String> workerIds
        ) {
            var result = new LinkedHashMap<String, WorkerDescriptor>();
            for (String workerId : workerIds) {
                result.put(
                        workerId,
                        "worker-1".equals(workerId)
                                ? new WorkerDescriptor(
                                        workerId,
                                        workerGroupId,
                                        "endpoint-1",
                                        Map.of(),
                                        Map.of()
                                )
                                : null
                );
            }
            return result;
        }

        @Override
        public Map<String, String> getWorkerGroupIds(List<String> workerIds) {
            var result = new LinkedHashMap<String, String>();
            for (String workerId : workerIds) {
                result.put(workerId, "worker-1".equals(workerId)
                        ? "group-1"
                        : null);
            }
            return result;
        }

        @Override
        public Map<String, WorkerDescriptor> sampleWorkerDescriptors(
                String workerGroupId,
                int sampleLimit
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WorkerRuntimeResult patchWorkerPlatformProperties(
                String workerGroupId,
                String workerId,
                Map<String, Object> properties
        ) {
            throw new UnsupportedOperationException();
        }

        private static WorkerGroupDescriptor group(String workerGroupId) {
            return new WorkerGroupDescriptor(
                    workerGroupId,
                    Map.of(),
                    Set.of("observe")
            );
        }
    }
}
