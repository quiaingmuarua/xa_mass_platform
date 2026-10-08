package com.xa.mass.scenario.appchecks;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashSet;

/** Request-local spool. No Item write happens until the complete input has passed validation. */
record AppCheckNumberFile(Path file, long inputCount, long emptyCount, long duplicateCount, int uniqueCount) implements AutoCloseable {
    static final int MAX_NUMBERS = 100_000;
    static final int MAX_BYTES = 10 * 1024 * 1024;

    static AppCheckNumberFile read(InputStream input, String country) throws IOException {
        Path raw = Files.createTempFile("app-check-input-", ".txt");
        Path normalized = null;
        boolean complete = false;
        try {
            try (var output = Files.newOutputStream(raw)) {
                byte[] buffer = new byte[8192];
                long size = 0;
                for (int count; (count = input.read(buffer)) != -1;) {
                    size += count;
                    if (size > MAX_BYTES) throw new AppCheckTaskService.RequestFailure(413, "号码文件不能超过 10 MiB", null);
                    output.write(buffer, 0, count);
                }
            }
            normalized = Files.createTempFile("app-check-numbers-", ".txt");
            var unique = new HashSet<String>();
            long rows = 0, empty = 0, duplicates = 0, invalid = 0, firstInvalid = 0;
            try (var reader = Files.newBufferedReader(raw, StandardCharsets.UTF_8);
                 var writer = Files.newBufferedWriter(normalized, StandardCharsets.UTF_8)) {
                for (String line; (line = reader.readLine()) != null;) {
                    rows++;
                    if (rows == 1 && line.startsWith("\uFEFF")) line = line.substring(1);
                    String number = line.strip();
                    if (number.isEmpty()) { empty++; continue; }
                    if (!number.startsWith("+")) number = "+" + number;
                    String prefix = AppCheckSpecification.PREFIXES.get(country);
                    if (prefix == null || !number.matches("\\+[1-9][0-9]{1,14}")
                            || !number.startsWith(prefix) || number.length() <= prefix.length()) {
                        invalid++; if (firstInvalid == 0) firstInvalid = rows; continue;
                    }
                    if (!unique.add(number)) { duplicates++; continue; }
                    if (unique.size() > MAX_NUMBERS)
                        throw new AppCheckTaskService.RequestFailure(400, "每次最多导入 100,000 个去重号码", null);
                    writer.write(number); writer.newLine();
                }
            }
            if (invalid > 0) throw new AppCheckTaskService.RequestFailure(400,
                    "号码文件有 " + invalid + " 个无效号码，首个错误在第 " + firstInvalid + " 行；未写入任何号码", null);
            if (unique.isEmpty()) throw new AppCheckTaskService.RequestFailure(400, "号码文件不能为空", null);
            complete = true;
            return new AppCheckNumberFile(normalized, rows, empty, duplicates, unique.size());
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new AppCheckTaskService.RequestFailure(400, "号码文件必须为有效 UTF-8", null);
        } finally {
            Files.deleteIfExists(raw);
            if (!complete && normalized != null) Files.deleteIfExists(normalized);
        }
    }

    @Override public void close() throws IOException { Files.deleteIfExists(file); }
}
