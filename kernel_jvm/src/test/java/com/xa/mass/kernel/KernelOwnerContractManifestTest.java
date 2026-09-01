package com.xa.mass.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.kernel.delivery.TaskResultRuntime;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

class KernelOwnerContractManifestTest {

    private static final Map<String, Class<?>> CONTRACTS = Map.ofEntries(
            Map.entry("TaskRuntime", TaskRuntime.class),
            Map.entry("TaskResourceCatalog", TaskResourceCatalog.class),
            Map.entry("WorkerRuntime", WorkerRuntime.class),
            Map.entry(
                    "WorkerResourceCatalog",
                    WorkerResourceCatalog.class
            ),
            Map.entry("TaskScoreBandCore", TaskScoreBandCore.class),
            Map.entry(
                    "TaskItemScoreBandCore",
                    TaskItemScoreBandCore.class
            ),
            Map.entry("WorkerScoreCore", WorkerScoreCore.class),
            Map.entry(
                    "CandidateWorkerCache",
                    CandidateWorkerCache.class
            ),
            Map.entry(
                    "WorkerCommandRuntime",
                    WorkerCommandRuntime.class
            ),
            Map.entry("TaskResultRuntime", TaskResultRuntime.class),
            Map.entry(
                    "WorkerServiceabilityRuntime",
                    WorkerServiceabilityRuntime.class
            )
    );

    private static final Map<String, Class<?>> DTOS = Map.ofEntries(
            Map.entry(
                    "CandidateWorkerEntry",
                    CandidateWorkerCache.CandidateWorkerEntry.class
            ),
            Map.entry(
                    "DeliveryReport",
                    WorkerDeliveryProtocol.DeliveryReport.class
            ),
            Map.entry(
                    "TaskCreationResult",
                    TaskRuntime.TaskCreationResult.class
            ),
            Map.entry(
                    "TaskDescriptor",
                    TaskRuntime.TaskDescriptor.class
            ),
            Map.entry("TaskItem", TaskRuntime.TaskItem.class),
            Map.entry(
                    "TaskItemAppendResult",
                    TaskRuntime.TaskItemAppendResult.class
            ),
            Map.entry(
                    "TaskItemResult",
                    TaskRuntime.TaskItemResult.class
            ),
            Map.entry(
                    "TaskItemResultPage",
                    TaskRuntime.TaskItemResultPage.class
            ),
            Map.entry(
                    "TaskItemScoreState",
                    TaskItemScoreBandCore.TaskItemScoreState.class
            ),
            Map.entry(
                    "TaskItemScoreTransitionResult",
                    TaskItemScoreBandCore
                            .TaskItemScoreTransitionResult.class
            ),
            Map.entry(
                    "TaskScoreState",
                    TaskScoreBandCore.TaskScoreState.class
            ),
            Map.entry(
                    "TaskScoreTransitionResult",
                    TaskScoreBandCore.TaskScoreTransitionResult.class
            ),
            Map.entry(
                    "DeliveryCommand",
                    WorkerDeliveryProtocol.DeliveryCommand.class
            ),
            Map.entry(
                    "WorkerDeclaration",
                    WorkerRuntime.WorkerDeclaration.class
            ),
            Map.entry(
                    "WorkerDescriptor",
                    WorkerRuntime.WorkerDescriptor.class
            ),
            Map.entry(
                    "WorkerGroupDescriptor",
                    WorkerRuntime.WorkerGroupDescriptor.class
            ),
            Map.entry(
                    "WorkerRuntimeResult",
                    WorkerRuntime.WorkerRuntimeResult.class
            ),
            Map.entry(
                    "WorkerScoreState",
                    WorkerScoreCore.WorkerScoreState.class
            ),
            Map.entry(
                    "WorkerScoreTransitionResult",
                    WorkerScoreCore.WorkerScoreTransitionResult.class
            )
    );

    private static final Map<String, Class<? extends Enum<?>>> ENUMS =
            Map.ofEntries(
                    Map.entry(
                            "DeliveryReportOutcomeClass",
                            WorkerDeliveryProtocol
                                    .DeliveryReportOutcomeClass.class
                    ),
                    Map.entry(
                            "TaskResultClass",
                            TaskResultRuntime.TaskResultClass.class
                    ),
                    Map.entry(
                            "TaskCreationStatus",
                            TaskRuntime.TaskCreationStatus.class
                    ),
                    Map.entry(
                            "TaskItemAppendStatus",
                            TaskRuntime.TaskItemAppendStatus.class
                    ),
                    Map.entry(
                            "TaskItemScoreBand",
                            TaskItemScoreBandCore.TaskItemScoreBand.class
                    ),
                    Map.entry(
                            "TaskItemScoreTransitionStatus",
                            TaskItemScoreBandCore
                                    .TaskItemScoreTransitionStatus.class
                    ),
                    Map.entry(
                            "TaskScoreBand",
                            TaskScoreBandCore.TaskScoreBand.class
                    ),
                    Map.entry(
                            "TaskScoreTransitionStatus",
                            TaskScoreBandCore
                                    .TaskScoreTransitionStatus.class
                    ),
                    Map.entry(
                            "WorkerAllocationMechanism",
                            TaskRuntime.WorkerAllocationMechanism.class
                    ),
                    Map.entry(
                            "TaskIdleDisposition",
                            TaskRuntime.TaskIdleDisposition.class
                    ),
                    Map.entry(
                            "WorkerCommandAppendStatus",
                            WorkerCommandRuntime
                                    .WorkerCommandAppendStatus.class
                    ),
                    Map.entry(
                            "WorkerCommandOfferStatus",
                            WorkerCommandRuntime
                                    .WorkerCommandOfferStatus.class
                    ),
                    Map.entry(
                            "ProbeRequestOfferStatus",
                            WorkerServiceabilityRuntime
                                    .ProbeRequestOfferStatus.class
                    ),
                    Map.entry(
                            "DeliveryEndpoint",
                            WorkerDeliveryProtocol.DeliveryEndpoint.class
                    ),
                    Map.entry(
                            "WorkerRuntimeStatus",
                            WorkerRuntime.WorkerRuntimeStatus.class
                    ),
                    Map.entry(
                            "WorkerScorePolarity",
                            WorkerScoreCore.WorkerScorePolarity.class
                    ),
                    Map.entry(
                            "WorkerScoreTransitionStatus",
                            WorkerScoreCore
                                    .WorkerScoreTransitionStatus.class
                    )
            );

    @Test
    void jvmContractsMatchTheOwnerManifest() throws Exception {
        Map<String, Object> manifest = manifest();
        assertEquals(
                manifest.get("contracts"),
                contractMethods()
        );
        assertEquals(manifest.get("dtos"), dtoFields());
        assertEquals(
                normalizeNumbers(manifest.get("enums")),
                normalizeNumbers(enumValues())
        );
        assertEquals(
                normalizeNumbers(manifestConstants()),
                constants()
        );
    }

    @Test
    void javaTaskInitialEncodingUsesOneFixedSlot() {
        assertEquals(100, TaskScoreBandCore.INITIAL_TIME_SLOT);
        assertEquals(10_000, TaskScoreBandCore.INITIAL_TIME_MILLIS);
        assertEquals(101, TaskScoreBandCore.NORMAL_TIME_SLOT_MIN);
        assertEquals(10_100, TaskScoreBandCore.NORMAL_TIME_MIN_MILLIS);
    }

    @Test
    void removedWorkerPropertyMutationContractsRemainAbsent() {
        assertFalse(Arrays.stream(WorkerRuntime.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals(
                        "replaceWorkerProperties"
                )));
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName(
                        "com.xa.mass.kernel.worker.WorkerPropertyIndexRuntime"
                )
        );
    }

    private static Map<String, List<String>> contractMethods() {
        var actual = new TreeMap<String, List<String>>();
        CONTRACTS.forEach((name, contract) -> actual.put(
                name,
                Arrays.stream(contract.getDeclaredMethods())
                        .filter(method -> !Modifier.isStatic(
                                method.getModifiers()
                        ))
                        .map(Method::getName)
                        .map(KernelOwnerContractManifestTest::snakeCase)
                        .sorted()
                        .toList()
        ));
        return actual;
    }

    private static Map<String, List<String>> dtoFields() {
        var actual = new TreeMap<String, List<String>>();
        DTOS.forEach((name, dto) -> actual.put(
                name,
                dtoFieldNames(dto)
        ));
        return actual;
    }

    private static List<String> dtoFieldNames(Class<?> dto) {
        if (dto.isRecord()) {
            return Arrays.stream(dto.getRecordComponents())
                    .map(component -> component.getName())
                    .map(KernelOwnerContractManifestTest::snakeCase)
                    .toList();
        }
        return Arrays.stream(dto.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .filter(field -> !field.isSynthetic())
                .peek(field -> requireAccessor(dto, field.getName()))
                .map(field -> field.getName())
                .map(KernelOwnerContractManifestTest::snakeCase)
                .toList();
    }

    private static void requireAccessor(Class<?> dto, String fieldName) {
        try {
            dto.getMethod(fieldName);
        } catch (NoSuchMethodException error) {
            throw new IllegalStateException(
                    dto.getName() + " has no accessor for " + fieldName,
                    error
            );
        }
    }

    private static Map<String, List<Object>> enumValues() {
        var actual = new TreeMap<String, List<Object>>();
        ENUMS.forEach((name, enumType) -> {
            var values = new ArrayList<>();
            for (Enum<?> value : enumType.getEnumConstants()) {
                values.add(enumValue(value));
            }
            actual.put(name, values);
        });
        return actual;
    }

    private static Object enumValue(Enum<?> value) {
        try {
            Method wireValue = value.getClass().getMethod("wireValue");
            return wireValue.invoke(value);
        } catch (ReflectiveOperationException ignored) {
            try {
                Method numericValue = value.getClass().getMethod("value");
                return ((Number) numericValue.invoke(value)).longValue();
            } catch (ReflectiveOperationException noValueMethod) {
                return value.name();
            }
        }
    }

    private static Map<String, Map<String, Long>> constants()
            throws IllegalAccessException {
        var classes = Map.of(
                "TaskRuntime", TaskRuntime.class,
                "TaskScoreBandCore", TaskScoreBandCore.class,
                "TaskItemScoreBandCore", TaskItemScoreBandCore.class,
                "WorkerResourceCatalog", WorkerResourceCatalog.class,
                "WorkerScoreCore", WorkerScoreCore.class
        );
        var expected = new TreeMap<String, Map<String, Long>>();
        classes.forEach((name, type) -> {
            var values = new TreeMap<String, Long>();
            try {
                @SuppressWarnings("unchecked")
                Map<String, Number> names =
                        (Map<String, Number>) manifestConstants()
                                .get(name);
                for (String constantName : names.keySet()) {
                    Number value = (Number) type.getField(constantName)
                            .get(null);
                    values.put(constantName, value.longValue());
                }
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException(error);
            }
            expected.put(name, values);
        });
        return expected;
    }

    private static Map<String, Map<String, Number>>
            manifestConstants() {
        var classes = Map.of(
                "TaskRuntime", TaskRuntime.class,
                "TaskScoreBandCore", TaskScoreBandCore.class,
                "TaskItemScoreBandCore", TaskItemScoreBandCore.class,
                "WorkerResourceCatalog", WorkerResourceCatalog.class,
                "WorkerScoreCore", WorkerScoreCore.class
        );
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Number>> manifestConstants =
                (Map<String, Map<String, Number>>) (Map<?, ?>) manifest()
                        .get("constants");
        var shared = new TreeMap<String, Map<String, Number>>();
        int frozenTaskScoreConstants = 0;
        for (var entry : classes.entrySet()) {
            var values = new TreeMap<String, Number>();
            for (var constant : manifestConstants.get(entry.getKey())
                    .entrySet()) {
                try {
                    entry.getValue().getField(constant.getKey());
                    values.put(constant.getKey(), constant.getValue());
                } catch (NoSuchFieldException missing) {
                    if (entry.getValue() != TaskScoreBandCore.class) {
                        throw new IllegalStateException(missing);
                    }
                    frozenTaskScoreConstants++;
                }
            }
            shared.put(entry.getKey(), values);
        }
        if (frozenTaskScoreConstants != 2) {
            throw new IllegalStateException(
                    "unexpected frozen Task Score constant divergence: "
                            + frozenTaskScoreConstants
            );
        }
        return shared;
    }

    private static Map<String, Object> manifest() {
        try (var stream = KernelOwnerContractManifestTest.class
                .getResourceAsStream("/kernel_owner_contract_manifest.json")) {
            if (stream == null) {
                throw new IllegalStateException(
                        "Kernel owner contract manifest is not on classpath"
                );
            }
            ObjectMapper mapper = JsonMapper.builder().build();
            return mapper.readValue(
                    stream,
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    }
            );
        } catch (Exception error) {
            throw new IllegalStateException(
                    "Could not read Kernel owner contract manifest",
                    error
            );
        }
    }

    private static Object normalizeNumbers(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof Map<?, ?> map) {
            var normalized = new TreeMap<String, Object>();
            map.forEach((key, child) -> normalized.put(
                    String.valueOf(key),
                    normalizeNumbers(child)
            ));
            return normalized;
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(KernelOwnerContractManifestTest::normalizeNumbers)
                    .toList();
        }
        return value;
    }

    private static String snakeCase(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toLowerCase(java.util.Locale.ROOT);
    }
}
