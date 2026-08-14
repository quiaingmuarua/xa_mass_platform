package com.xa.mass.server.scenariorpc;

import com.xa.mass.scenariorpc.ScenarioRpcResult;
import com.xa.mass.scenariorpc.ScenarioRpcResultSink;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ScenarioRpcFileStore {

    private static final String UPLOAD_OPERATION =
            "scenarioRpc.uploadInput";
    private static final String READ_INPUT_OPERATION =
            "scenarioRpc.readInput";
    private static final String WRITE_OUTPUT_OPERATION =
            "scenarioRpc.writeOutput";
    private static final String READ_OUTPUT_OPERATION =
            "scenarioRpc.readOutput";

    private final Path inputDirectory;
    private final Path outputDirectory;
    private final int maxInputBytes;
    private final int maxInputLines;

    private ScenarioRpcFileStore(
            Path inputDirectory,
            Path outputDirectory,
            int maxInputBytes,
            int maxInputLines
    ) {
        this.inputDirectory = inputDirectory;
        this.outputDirectory = outputDirectory;
        this.maxInputBytes = maxInputBytes;
        this.maxInputLines = maxInputLines;
    }

    static ScenarioRpcFileStore open(
            ScenarioRpcProperties properties
    ) throws IOException {
        Path root = Path.of(properties.root())
                .toAbsolutePath()
                .normalize();
        if (!root.endsWith(Path.of("data", "rpc-task"))) {
            throw new IllegalArgumentException(
                    "Scenario RPC root must end with data/rpc-task"
            );
        }
        Files.createDirectories(root);
        Path realRoot = root.toRealPath();
        if (!realRoot.endsWith(Path.of("data", "rpc-task"))
                || Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException(
                    "Scenario RPC root must not traverse a symbolic link"
            );
        }
        Path input = realRoot.resolve("input");
        Path output = realRoot.resolve("output");
        Files.createDirectories(input);
        Files.createDirectories(output);
        requireRealDirectChild(realRoot, input);
        requireRealDirectChild(realRoot, output);
        return new ScenarioRpcFileStore(
                input,
                output,
                properties.maxInputBytes(),
                properties.maxInputLines()
        );
    }

    StoredInput upload(String fileName, byte[] content) {
        Path target = inputPath(fileName, UPLOAD_OPERATION);
        if (content == null || content.length > maxInputBytes) {
            throw invalid(UPLOAD_OPERATION, "input file exceeds byte limit");
        }
        List<String> lines = decodeLines(
                content,
                UPLOAD_OPERATION,
                maxInputLines
        );
        if (Files.exists(target)) {
            throw conflict(UPLOAD_OPERATION);
        }
        Path temporary = null;
        try {
            temporary = Files.createTempFile(
                    inputDirectory,
                    ".upload-",
                    ".tmp"
            );
            Files.write(
                    temporary,
                    content,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
            moveNew(temporary, target);
            temporary = null;
            return new StoredInput(fileName, content.length, lines.size());
        } catch (FileAlreadyExistsException error) {
            throw conflict(UPLOAD_OPERATION);
        } catch (IOException error) {
            throw unavailable(UPLOAD_OPERATION, error);
        } finally {
            deleteTemporary(temporary);
        }
    }

    List<String> readInput(String fileName) {
        Path path = inputPath(fileName, READ_INPUT_OPERATION);
        if (!Files.isRegularFile(path)) {
            throw notFound(READ_INPUT_OPERATION);
        }
        try {
            long size = Files.size(path);
            if (size > maxInputBytes) {
                throw invalid(
                        READ_INPUT_OPERATION,
                        "input file exceeds byte limit"
                );
            }
            return decodeLines(
                    Files.readAllBytes(path),
                    READ_INPUT_OPERATION,
                    maxInputLines
            );
        } catch (ServerException error) {
            throw error;
        } catch (IOException error) {
            throw unavailable(READ_INPUT_OPERATION, error);
        }
    }

    OutputSession output(String scenarioId) {
        if (scenarioId == null
                || !scenarioId.matches("[A-Za-z0-9._-]+")) {
            throw invalid(WRITE_OUTPUT_OPERATION, "scenarioId is invalid");
        }
        return new OutputSession(outputDirectory, scenarioId);
    }

    byte[] readOutput(String fileName) {
        Path path = outputPath(fileName, READ_OUTPUT_OPERATION);
        if (!Files.isRegularFile(path)) {
            throw notFound(READ_OUTPUT_OPERATION);
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException error) {
            throw unavailable(READ_OUTPUT_OPERATION, error);
        }
    }

    private Path inputPath(String fileName, String operation) {
        if (fileName == null
                || !fileName.matches("[A-Za-z0-9._-]+\\.txt")) {
            throw invalid(operation, "input file name is invalid");
        }
        return inputDirectory.resolve(fileName);
    }

    private Path outputPath(String fileName, String operation) {
        if (fileName == null
                || !fileName.matches("[A-Za-z0-9._-]+\\.jsonl")) {
            throw invalid(operation, "output file name is invalid");
        }
        return outputDirectory.resolve(fileName);
    }

    private static List<String> decodeLines(
            byte[] content,
            String operation,
            int maxLines
    ) {
        String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException error) {
            throw invalid(operation, "input file is not valid UTF-8");
        }
        List<String> lines = decoded.lines().toList();
        if (lines.size() > maxLines) {
            throw invalid(operation, "input file exceeds line limit");
        }
        return lines;
    }

    private static String encodeResult(ScenarioRpcResult result) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("workerGroupId", result.workerGroupId());
        output.put("messageId", result.messageId());
        output.put("eventCode", result.eventCode());
        output.put("input", result.input());
        output.put("result", result.result());
        return Jsons.toJson(output);
    }

    private static void moveNew(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(source, target);
        }
    }

    private static void requireRealDirectChild(
            Path root,
            Path child
    ) throws IOException {
        Path real = child.toRealPath();
        if (!real.getParent().equals(root)
                || Files.isSymbolicLink(child)) {
            throw new IllegalArgumentException(
                    "Scenario RPC directories must be direct real children"
            );
        }
    }

    private static void deleteTemporary(Path temporary) {
        if (temporary == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
            // Best effort cleanup; the primary operation already failed.
        }
    }

    private static ServerException invalid(
            String operation,
            String message
    ) {
        return new ServerException(
                ServerErrorCode.SCENARIO_RPC_INVALID_REQUEST,
                operation,
                message,
                null
        );
    }

    private static ServerException notFound(String operation) {
        return new ServerException(
                ServerErrorCode.SCENARIO_RPC_RESOURCE_NOT_FOUND,
                operation,
                null,
                null
        );
    }

    private static ServerException conflict(String operation) {
        return new ServerException(
                ServerErrorCode.SCENARIO_RPC_CONFLICT,
                operation,
                null,
                null
        );
    }

    private static ServerException unavailable(
            String operation,
            IOException cause
    ) {
        return new ServerException(
                ServerErrorCode.SCENARIO_RPC_UNAVAILABLE,
                operation,
                null,
                cause
        );
    }

    record StoredInput(String fileName, long byteCount, int lineCount) {
    }

    static final class OutputSession
            implements ScenarioRpcResultSink, AutoCloseable {
        private final Path outputDirectory;
        private final String scenarioId;
        private Path temporary;
        private BufferedWriter writer;
        private boolean published;

        private OutputSession(Path outputDirectory, String scenarioId) {
            this.outputDirectory = outputDirectory;
            this.scenarioId = scenarioId;
        }

        @Override
        public void accept(List<ScenarioRpcResult> results) {
            if (published) {
                throw new IllegalStateException("output is already published");
            }
            try {
                BufferedWriter current = writer();
                for (ScenarioRpcResult result : results) {
                    current.write(encodeResult(result));
                    current.newLine();
                }
                current.flush();
            } catch (IOException error) {
                throw unavailable(WRITE_OUTPUT_OPERATION, error);
            }
        }

        String publish(boolean partial) {
            if (published) {
                throw new IllegalStateException("output is already published");
            }
            String fileName = scenarioId
                    + (partial ? ".partial.jsonl" : ".jsonl");
            Path target = outputDirectory.resolve(fileName);
            if (Files.exists(target)) {
                throw conflict(WRITE_OUTPUT_OPERATION);
            }
            try {
                writer();
                closeWriter();
                moveNew(temporary, target);
                temporary = null;
                published = true;
                return fileName;
            } catch (FileAlreadyExistsException error) {
                throw conflict(WRITE_OUTPUT_OPERATION);
            } catch (IOException error) {
                throw unavailable(WRITE_OUTPUT_OPERATION, error);
            }
        }

        @Override
        public void close() {
            try {
                closeWriter();
            } catch (IOException ignored) {
                // Failed runs retain their unexposed temporary evidence.
            }
        }

        private BufferedWriter writer() throws IOException {
            if (writer == null) {
                temporary = Files.createTempFile(
                        outputDirectory,
                        "." + scenarioId + "-",
                        ".tmp"
                );
                writer = Files.newBufferedWriter(
                        temporary,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.WRITE
                );
            }
            return writer;
        }

        private void closeWriter() throws IOException {
            if (writer != null) {
                writer.close();
                writer = null;
            }
        }
    }
}
