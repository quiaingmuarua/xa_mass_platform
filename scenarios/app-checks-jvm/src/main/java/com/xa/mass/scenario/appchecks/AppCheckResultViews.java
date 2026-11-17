package com.xa.mass.scenario.appchecks;

import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.server.task.result.TaskResultRecord;
import com.xa.mass.server.api.v1.contract.task.TaskItemResultResponse;
import com.xa.mass.server.api.v1.contract.task.TaskItemResultStatus;
import com.xa.mass.scenario.appchecks.AppCheckTaskService.CsvExport;
import com.xa.mass.scenario.appchecks.AppCheckTaskService.RequestFailure;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** Pure saved-snapshot interpretation shared by the explicit Runtime and Storage readers. */
final class AppCheckResultViews {
    private AppCheckResultViews() { }
    static CsvExport export(String taskId, AppCheckTaskSnapshot snapshot, String filter,
            Consumer<Consumer<TaskResultRecord>> read) {
        if (!Set.of("all", "matched", "unmatched").contains(filter)) throw new RequestFailure(400, "无效导出范围", taskId);
            Path csv = null;
            boolean complete = false;
            try {
                csv = Files.createTempFile("app-check-results-", ".csv");
                long[] count = {0};
                try (var writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
                    var headers = new ArrayList<>(List.of("种子", "判定", "检查类型", "执行配置"));
                    if (snapshot.contract().seedType() == AppCheckContract.SeedType.PHONENUMBER) headers.add("地区");
                    headers.addAll(snapshot.contract().exportHeaders());
                    writer.write("\uFEFF" + String.join(",", headers.stream().map(AppCheckResultViews::csvCell).toList()) + "\r\n");
                    read.accept(result -> {
                        if (!result.result().succeeded()) return;
                        final List<AppCheckContract.Result> answers;
                        try {
                            answers = interpret(snapshot, new TaskDataService.ResultEntry(result.messageId(), result.item(),
                                    TaskItemResultResponse.from(result.result())));
                        } catch (RuntimeException invalid) { return; }
                        for (var answer : answers) {
                            if (filter.equals("matched") && !answer.matched() || filter.equals("unmatched") && answer.matched()) continue;
                            var values = new ArrayList<>(List.of(answer.seed(), answer.matched() ? "满足" : "不满足",
                                    snapshot.checkTypeLabel(), snapshot.checkLabel()));
                            if (snapshot.contract().seedType() == AppCheckContract.SeedType.PHONENUMBER) values.add(snapshot.context().country());
                            values.addAll(snapshot.contract().exportValues(answer));
                            if (values.size() != headers.size()) throw new IllegalStateException("Contract export column mismatch");
                            try {
                                writer.write(String.join(",", values.stream().map(AppCheckResultViews::csvCell).toList()) + "\r\n");
                                count[0]++;
                            } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
                        }
                    });
                }
                complete = true;
                return new CsvExport(csv, taskId + "-" + filter + ".csv", count[0]);
            } catch (com.xa.mass.server.error.ServerException denied) {
                throw denied;
            } catch (IOException | RuntimeException error) {
                var failure = new RequestFailure(503, "结果导出失败，请重新发起", taskId); failure.initCause(error); throw failure;
            } finally {
                if (!complete) deleteTemporary(csv);
            }
    }

    private static String csvCell(String value) {
        if (!value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
    private static void deleteTemporary(Path file) {
        if (file != null) try { Files.deleteIfExists(file); } catch (IOException ignored) { }
    }
    static Map<String, Object> preview(String taskId, AppCheckTaskSnapshot snapshot, TaskDataService.ResultPreview preview) {
        var rows = new ArrayList<Map<String, Object>>();
        for (var entry : preview.results()) {
            for (var row : resultView(snapshot, entry)) {
                if (rows.size() == 100) return Map.of("taskId", taskId, "results", rows, "truncated", true);
                rows.add(row);
            }
        }
        return Map.of("taskId", taskId, "results", rows, "truncated", preview.truncated());
    }

    private static List<Map<String, Object>> resultView(AppCheckTaskSnapshot snapshot, TaskDataService.ResultEntry entry) {
        List<String> seeds = List.of();
        if (entry.item() != null) {
            try { seeds = snapshot.inputSeeds(entry.item().payload()); }
            catch (RuntimeException ignored) { /* Damaged input retains an Item-level diagnostic. */ }
        }
        String error = null;
        if (entry.result().status() != TaskItemResultStatus.FAILED) {
            try {
                var rows = new ArrayList<Map<String, Object>>();
                for (var answer : interpret(snapshot, entry)) {
                    var row = resultRow(entry, answer.seed(), null);
                    row.put("matched", answer.matched()); row.put("details", answer.details()); rows.add(row);
                }
                return rows;
            } catch (RuntimeException invalid) { error = "Application check result cannot be parsed or associated"; }
        }
        var rows = new ArrayList<Map<String, Object>>();
        if (seeds.isEmpty()) rows.add(resultRow(entry, null, error));
        else for (String seed : seeds) rows.add(resultRow(entry, seed, error));
        return rows;
    }

    private static Map<String, Object> resultRow(TaskDataService.ResultEntry entry, String seed, String error) {
        var row = new LinkedHashMap<String, Object>();
        row.put("messageId", entry.messageId()); row.put("resultStatus", entry.result().status().wireValue());
        if (seed != null) row.put("seed", seed);
        if (error != null) row.put("contentError", error);
        return row;
    }

    private static List<AppCheckContract.Result> interpret(AppCheckTaskSnapshot snapshot, TaskDataService.ResultEntry entry) {
        var item = entry.item();
        if (item == null || !entry.messageId().equals(item.messageId()) || !snapshot.eventCode().equals(item.eventCode()))
            throw new IllegalArgumentException("Item association mismatch");
        var seeds = snapshot.inputSeeds(item.payload());
        if (!entry.messageId().equals(snapshot.messageId(seeds))) throw new IllegalArgumentException("Item identity mismatch");
        var answers = snapshot.contract().results(snapshot.context(), item.payload(), Jsons.parseObject(entry.result().opaqueResultPayload()));
        if (answers.size() != seeds.size()) throw new IllegalArgumentException("Result cardinality mismatch");
        var bySeed = new LinkedHashMap<String, AppCheckContract.Result>();
        for (var answer : answers) {
            if (bySeed.putIfAbsent(answer.seed(), answer) != null) throw new IllegalArgumentException("Duplicate result seed");
        }
        if (!bySeed.keySet().equals(new java.util.HashSet<>(seeds))) throw new IllegalArgumentException("Result association mismatch");
        return seeds.stream().map(bySeed::get).toList();
    }

}
