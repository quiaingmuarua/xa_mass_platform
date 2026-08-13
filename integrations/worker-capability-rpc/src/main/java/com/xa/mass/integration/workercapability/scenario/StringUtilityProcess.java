package com.xa.mass.integration.workercapability.scenario;

import com.xa.mass.integration.workercapability.process.RpcProcess;
import com.xa.mass.integration.workercapability.process.RpcResult;
import com.xa.mass.integration.workercapability.runtimeapi.WorkerGroupRpcClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class StringUtilityProcess {

    public static final String WORKER_GROUP_ID =
            "scenario-string-utils-workers";
    public static final List<String> EVENT_CODES = List.of(
            "string.md5",
            "string.sha1",
            "string.base64.encode"
    );

    private StringUtilityProcess() {
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
                .processName("string")
                .workerGroupId(WORKER_GROUP_ID)
                .lines(lines)
                .eventCodes(EVENT_CODES)
                .parseLine(line -> Map.of("value", line))
                .middlewares(List.of(
                        StringUtilityProcess::verify,
                        new JsonlResultWriter(outputPath)
                ))
                .maxWorkers(30)
                .waitTimeoutMillis(waitTimeoutMillis)
                .build();
    }

    private static void verify(List<RpcResult> results) {
        for (RpcResult result : results) {
            if (!Boolean.TRUE.equals(result.result().get("valid"))) {
                throw invalid(result, "valid result");
            }
            String requiredField = switch (result.eventCode()) {
                case "string.md5" -> "md5";
                case "string.sha1" -> "sha1";
                case "string.base64.encode" -> "base64";
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
                "String RPC result for "
                        + result.messageId()
                        + " requires "
                        + expected
        );
    }
}
