package com.xa.mass.scenario.appchecks;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.xa.mass.workerdelivery.json.Jsons;

/** Request-local spool. No Item write happens until the complete input has passed validation. */
record AppCheckSeedFile(Path file, long inputCount, long emptyCount, long duplicateCount, int uniqueCount) implements AutoCloseable {
    static final int MAX_SEEDS = 100_000;
    static final int MAX_BYTES = 10 * 1024 * 1024;

    static AppCheckSeedFile read(InputStream input, AppCheckContract contract, String country) throws IOException {
        var type = contract.seedType();
        type.requireCountry(country);
        Path raw = Files.createTempFile("app-check-input-", ".txt");
        Path normalized = null;
        boolean complete = false;
        try {
            try (var output = Files.newOutputStream(raw)) {
                byte[] buffer = new byte[8192];
                long size = 0;
                for (int count; (count = input.read(buffer)) != -1;) {
                    size += count;
                    if (size > MAX_BYTES) throw new AppCheckTaskService.RequestFailure(413, "种子文件不能超过 10 MiB", null);
                    output.write(buffer, 0, count);
                }
            }
            normalized = Files.createTempFile("app-check-seeds-", ".txt");
            var unique = new HashSet<String>();
            long rows = 0, empty = 0, duplicates = 0, invalid = 0, firstInvalid = 0;
              try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
                 var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                var seeds = new ArrayList<String>(snapshot.seedsPerItem());
                for (String seed; (seed = reader.readLine()) != null;) {
                    seeds.add(seed);
                    if (seeds.size() == snapshot.seedsPerItem()) {
                        writeGroup(writer, snapshot, seeds); count++; seeds.clear();
                    }
                }
                if (!seeds.isEmpty()) { writeGroup(writer, snapshot, seeds); count++; }
            }
            complete = true;
            return new AppCheckSeedFile(normalized, rows, empty, duplicates, unique.size());
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new AppCheckTaskService.RequestFailure(400, "种子文件必须为有效 UTF-8", null);
        } finally {
            Files.deleteIfExists(raw);
            if (!complete && normalized != null) Files.deleteIfExists(normalized);
        }
    }

    /** Complete assembly is spooled before the first Owner write; retries reconstruct identical groups. */
    PreparedGroups prepareGroups(AppCheckTaskSnapshot snapshot) throws IOException {
        Path path = Files.createTempFile("app-check-groups-", ".jsonl");
        boolean complete = false;
        int count = 0;
        try {
          try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
             var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            var seeds = new ArrayList<String>(snapshot.seedsPerItem());
            for (String seed; (seed = reader.readLine()) != null;) {
                seeds.add(seed);
                if (seeds.size() == snapshot.seedsPerItem()) {
                    writeGroup(writer, snapshot, seeds); count++; seeds.clear();
                }
            }
            if (!seeds.isEmpty()) { writeGroup(writer, snapshot, seeds); count++; }
          }
            complete = true;
            return new PreparedGroups(path, count);
        } catch (IllegalArgumentException invalid) {
            throw new AppCheckTaskService.RequestFailure(400, "第 " + (count + 1) + " 个种子组无法组装：" + invalid.getMessage() + "；未写入任何执行项", null);
        } finally {
            if (!complete) Files.deleteIfExists(path);
        }
    }
    private static void writeGroup(BufferedWriter writer, AppCheckTaskSnapshot snapshot, List<String> seeds) throws IOException {
        writer.write(Jsons.toJson(Map.of("messageId", snapshot.messageId(seeds), "payload", snapshot.payload(seeds))));
        writer.newLine();
    }
    record PreparedGroups(Path file, int itemCount) implements AutoCloseable {
        @Override public void close() throws IOException { Files.deleteIfExists(file); }
    }
    @Override public void close() throws IOException { Files.deleteIfExists(file); }
}
