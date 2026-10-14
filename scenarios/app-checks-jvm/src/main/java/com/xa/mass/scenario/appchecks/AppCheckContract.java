package com.xa.mass.scenario.appchecks;

import java.util.Map;

/** A statically installed, pure input/result protocol. No resources or execution lifecycle. */
public interface AppCheckContract {
    String id();
    String description();
    Map<String, Object> parameterExample();
    Map<String, Object> inputExample();
    Map<String, Object> resultExample();
    Map<String, Object> normalizeParameters(Object value);
    Map<String, Object> payload(Context context, String number);
    String inputNumber(Map<String, Object> payload);
    Result result(Context context, Map<String, Object> payload, Map<String, Object> content);

    record Context(String appId, String country, String workerGroupId, String salt,
                   Map<String, Object> parameters) {
        public Context { parameters = AppCheckSettings.parameters(parameters, "task.parameters"); }
    }
    record Result(String number, boolean registered, String workerId, String workerGroupId,
                  Long simulatedDelayMillis) {
        public Result {
            if (number == null || !number.matches("\\+[1-9][0-9]{1,14}"))
                throw new IllegalArgumentException("Invalid result number");
            AppCheckSpecification.text(workerId, 256);
            AppCheckSpecification.text(workerGroupId, 128);
            if (simulatedDelayMillis != null && (simulatedDelayMillis < 0 || simulatedDelayMillis > 30_000))
                throw new IllegalArgumentException("Invalid simulated delay");
        }
    }
}
