package com.xa.mass.integration.workercapability.scenario;

import com.xa.mass.integration.workercapability.process.RpcProcess;
import com.xa.mass.integration.workercapability.process.RpcResult;
import com.xa.mass.integration.workercapability.runtimeapi.WorkerGroupRpcClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class PhoneNumberProcess {

    public static final String WORKER_GROUP_ID =
            "scenario-phone-number-workers";
    public static final List<String> EVENT_CODES = List.of(
            "phonenumber.e164",
            "phonenumber.country",
            "phonenumber.original-carrier"
    );

    private PhoneNumberProcess() {
    }

    public static RpcProcess create(
            WorkerGroupRpcClient rpc,
            String scenarioId,
            List<String> lines,
            Path outputPath,
            long waitTimeoutMillis
    ) {
        return RpcProcess.builder(rpc)
                .scenarioId(scenarioId)
                .processName("phone")
                .workerGroupId(WORKER_GROUP_ID)
                .lines(lines)
                .eventCodes(EVENT_CODES)
                .parseLine(PhoneNumberProcess::payload)
                .middlewares(List.of(
                        PhoneNumberProcess::verify,
                        new JsonlResultWriter(outputPath)
                ))
                .maxWorkers(30)
                .waitTimeoutMillis(waitTimeoutMillis)
                .build();
    }

    private static Map<String, Object> payload(String line) {
        if (line == null || line.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "phone number line must not be blank"
            );
        }
        return Map.of("rawNumber", line.trim());
    }

    private static void verify(List<RpcResult> results) {
        for (RpcResult result : results) {
            if (!Boolean.TRUE.equals(result.result().get("valid"))) {
                throw invalid(result, "valid result");
            }
            String requiredField = switch (result.eventCode()) {
                case "phonenumber.e164" -> "e164";
                case "phonenumber.country" -> "countryCallingCode";
                case "phonenumber.original-carrier" -> "originalCarrier";
                default -> throw invalid(result, "known eventCode");
            };
            if (!result.result().containsKey(requiredField)) {
                throw invalid(result, requiredField);
            }
        }
    }

    private static IllegalStateException invalid(
            RpcResult result,
            String expected
    ) {
        return new IllegalStateException(
                "Phone RPC result for "
                        + result.messageId()
                        + " requires "
                        + expected
        );
    }
}
