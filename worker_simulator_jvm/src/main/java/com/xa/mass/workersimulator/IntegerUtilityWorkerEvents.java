package com.xa.mass.workersimulator;

import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.workerdelivery.json.Jsons;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stateless bounded computation; no scheduling or Scenario dependency. */
final class IntegerUtilityWorkerEvents {
    static final String PRIME_EVENT_CODE = "extension.worker.integer.uint32-prime";

    private IntegerUtilityWorkerEvents() {}

    static List<WorkerEventDefinition<?>> definitions() {
        return List.of(WorkerEventDefinition.extension("integer.uint32-prime",
                WorkerEventParameterResolvers.jsonMap(), IntegerUtilityWorkerEvents::prime));
    }

    private static String prime(Map<String, Object> payload) {
        Object input = payload.get("value");
        var result = new LinkedHashMap<String, Object>();
        result.put("input", input);
        if (!(input instanceof String value) || !value.matches("[0-9]{1,10}")
                || Long.parseLong(value) > 4_294_967_295L) {
            result.put("valid", false);
            result.put("error", "INVALID_UINT32");
        } else {
            result.put("valid", true);
            result.put("isPrime", isPrime(Long.parseLong(value)));
        }
        return Jsons.toJson(result);
    }

    private static boolean isPrime(long value) {
        if (value < 2) return false;
        if (value % 2 == 0) return value == 2;
        for (long divisor = 3; divisor <= value / divisor; divisor += 2) {
            if (value % divisor == 0) return false;
        }
        return true;
    }
}
