package com.xa.mass.scenario.messages;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Request-local materials; all validation completes before the caller writes any Items. */
record MessageRecipientFile(Path file, long inputCount, long emptyCount, long duplicateCount, int uniqueCount) implements AutoCloseable {
    static final int MAX_RECIPIENTS = 100_000, MAX_BYTES = 10 * 1024 * 1024;
    private static final Map<String, String> PREFIXES = Map.of("CN", "+86", "US", "+1", "GB", "+44");
    private static final java.util.regex.Pattern NUMBER = java.util.regex.Pattern.compile("\\+[1-9][0-9]{1,14}");

    static MessageRecipientFile read(InputStream input, String country) throws IOException {
        Path raw = Files.createTempFile("message-input-", ".txt"), normalized = null;
        boolean complete = false;
        try {
            try (var output = Files.newOutputStream(raw)) {
                byte[] buffer = new byte[8192]; long size = 0;
                for (int n; (n = input.read(buffer)) != -1;) {
                    size += n;
                    if (size > MAX_BYTES) throw invalid(413, "号码文件不能超过 10 MiB");
                    output.write(buffer, 0, n);
                }
            }
            normalized = Files.createTempFile("message-recipients-", ".txt");
            var seen = new HashSet<String>();
            long rows = 0, empty = 0, duplicates = 0, invalid = 0, firstInvalid = 0;
            try (var reader = Files.newBufferedReader(raw, StandardCharsets.UTF_8);
                 var writer = Files.newBufferedWriter(normalized, StandardCharsets.UTF_8)) {
                for (String line; (line = reader.readLine()) != null;) {
                    rows++;
                    if (rows == 1 && line.startsWith("\uFEFF")) line = line.substring(1);
                    String number = line.strip();
                    if (number.isEmpty()) { empty++; continue; }
                    if (!number.startsWith("+")) number = "+" + number;
                    String prefix = PREFIXES.get(country);
                    if (!NUMBER.matcher(number).matches() || prefix == null
                            || !number.startsWith(prefix) || number.length() <= prefix.length()) {
                        invalid++; if (firstInvalid == 0) firstInvalid = rows; continue;
                    }
                    if (!seen.add(number)) { duplicates++; continue; }
                    if (seen.size() > MAX_RECIPIENTS) throw invalid(400, "每次最多导入 100,000 个去重收件号码");
                    writer.write(number); writer.newLine();
                }
            }
            if (invalid > 0) throw invalid(400, "号码文件有 " + invalid + " 个无效号码，首个错误在第 " + firstInvalid + " 行；未写入任何收件人");
            if (seen.isEmpty()) throw invalid(400, "号码文件不能为空");
            complete = true;
            return new MessageRecipientFile(normalized, rows, empty, duplicates, seen.size());
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw invalid(400, "号码文件必须为有效 UTF-8");
        } finally {
            try { Files.deleteIfExists(raw); }
            catch (IOException cleanup) {
                if (complete && normalized != null) {
                    try { Files.deleteIfExists(normalized); } catch (IOException extra) { cleanup.addSuppressed(extra); }
                }
                throw cleanup;
            }
            finally { if (!complete && normalized != null) Files.deleteIfExists(normalized); }
        }
    }
    private static MessageTaskService.ProductError invalid(int status, String message) { return new MessageTaskService.ProductError(status, message, null); }
    @Override public void close() throws IOException { Files.deleteIfExists(file); }
}
