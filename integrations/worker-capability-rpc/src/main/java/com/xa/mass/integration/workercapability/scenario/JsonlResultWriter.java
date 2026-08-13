package com.xa.mass.integration.workercapability.scenario;

import com.xa.mass.integration.workercapability.process.RpcResult;
import com.xa.mass.integration.workercapability.process.RpcResultMiddleware;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JsonlResultWriter implements RpcResultMiddleware {

    private final Path outputPath;

    public JsonlResultWriter(Path outputPath) {
        this.outputPath = outputPath;
    }

    @Override
    public void process(List<RpcResult> results) throws IOException {
        Path temporaryPath = outputPath.resolveSibling(
                outputPath.getFileName() + ".tmp"
        );
        if (Files.exists(outputPath) || Files.exists(temporaryPath)) {
            throw new IllegalStateException(
                    "Result output already exists: " + outputPath
            );
        }
        List<String> encodedResults = results.stream()
                .map(JsonlResultWriter::encode)
                .toList();
        try {
            Files.write(
                    temporaryPath,
                    encodedResults,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            );
            moveToFinalPath(temporaryPath, outputPath);
        } catch (IOException | RuntimeException error) {
            Files.deleteIfExists(temporaryPath);
            throw error;
        }
    }

    private static String encode(RpcResult result) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("workerGroupId", result.workerGroupId());
        output.put("messageId", result.messageId());
        output.put("eventCode", result.eventCode());
        output.put("input", result.input());
        output.put("result", result.result());
        return Jsons.toJson(output);
    }

    private static void moveToFinalPath(
            Path temporaryPath,
            Path outputPath
    ) throws IOException {
        try {
            Files.move(
                    temporaryPath,
                    outputPath,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(temporaryPath, outputPath);
        }
    }
}
