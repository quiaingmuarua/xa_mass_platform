package com.xa.mass.integration.workercallperformance;

import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Selection, acceptance and presentation belong to the Java experiment, not its process runner. */
final class ExperimentReport {
    private ExperimentReport() {}
    static boolean valid(Map<String, Object> row) {
        return "passed".equals(row.get("status")) && "complete".equals(row.get("evidenceStatus"));
    }
    static boolean meets(Map<String, Object> row, int target) {
        if (!valid(row) || CapacityEvidence.decimal(row, "completedPerSecond") < target) return false;
        if (!(row.get("acceptance30Seconds") instanceof List<?> windows) || windows.isEmpty()) return false;
        return windows.stream().map(CallApi::object).allMatch(w -> CapacityEvidence.decimal(w, "completedPerSecond") >= target);
    }
    static Map<String, Object> summarize(ExperimentConfig config, List<Map<String, Object>> rows) {
        var result = new LinkedHashMap<String, Object>();
        result.put("measurementVersion", 2);
        result.put("experiment", config.values().get("name"));
        result.put("referenceHost", false);
        result.put("cases", rows);
        var selected = rows.stream().filter(ExperimentReport::valid)
                .filter(r -> "screening".equals(r.get("experimentStage")))
                .max(Comparator.comparingDouble(r -> CapacityEvidence.decimal(r, "completedPerSecond")));
        selected.ifPresent(row -> result.put("selectedProfile", row.get("profile")));
        int repeats = ExperimentConfig.integer(config.object("confirmation"), "anyRepetitions", 1, 5);
        int target = ExperimentConfig.integer(config.values(), "targetQps", 1, 100_000);
        var any = rows.stream().filter(r -> "confirmation".equals(r.get("experimentStage"))
                && "task-any".equals(r.get("path"))).toList();
        boolean formalShape = !"capacity".equals(config.values().get("purpose"))
                || any.stream().allMatch(r -> r.get("measurementSeconds") instanceof Number seconds && seconds.intValue() == 120
                    && r.get("acceptance30Seconds") instanceof List<?> windows && windows.size() == 4
                    && windows.stream().map(CallApi::object).allMatch(w -> w.get("seconds") instanceof Number n && n.doubleValue() == 30));
        boolean identities = any.stream().map(r -> r.get("repetition")).distinct().count() == any.size();
        String state = any.size() != repeats || !formalShape || !identities || any.stream().anyMatch(r -> !valid(r))
                ? "inconclusive" : any.stream().allMatch(r -> meets(r, target)) ? "met" : "not-met";
        if ("smoke".equals(config.values().get("purpose")) && !state.equals("inconclusive")) state = "smoke-passed";
        result.put("targetStatus", state);
        result.put("targetQps", target);
        result.put("anyValidRepetitions", any.stream().filter(ExperimentReport::valid).count());
        var values = any.stream().filter(ExperimentReport::valid).mapToDouble(r -> CapacityEvidence.decimal(r, "completedPerSecond")).sorted().toArray();
        if (values.length > 0) result.put("anyThroughput", Map.of("min", values[0], "max", values[values.length - 1],
                "median", values.length % 2 == 1 ? values[values.length / 2] : (values[values.length / 2 - 1] + values[values.length / 2]) / 2));
        return result;
    }
    static void run(Map<String, String> options) throws Exception {
        var config = ExperimentConfig.read(options.get("--experiment-config"));
        var input = Jsons.parseObject(Files.readString(Path.of(options.get("--input"))));
        var rows = ((List<?>) input.get("cases")).stream().map(CallApi::object).toList();
        var result = summarize(config, rows);
        if (input.containsKey("environment")) result.put("environment", input.get("environment"));
        Path output = Path.of(options.get("--output"));
        Files.createDirectories(output);
        Files.writeString(output.resolve("experiment-summary.json"), Jsons.toJson(result));
        Files.writeString(output.resolve("summary.md"), markdown(result));
        Files.writeString(output.resolve("resource-scan.svg"), chart(rows));
    }
    static String markdown(Map<String, Object> result) {
        var text = new StringBuilder("# Capacity experiment\n\nTarget: **")
                .append(result.get("targetStatus")).append("** (").append(result.get("targetQps")).append(" completed Items/s, both Groups combined).\n\n")
                .append("Single Runtime, DEFAULT policy, MD5. WSL/local evidence; excluded from CI trends. Completion is the successful Result-store return event, reconciled with unique exported results.\n\n")
                .append("| Stage | Profile | Path | Rep | Validity | Items/s | 30s minimum | Reason |\n|---|---|---|---:|---|---:|---:|---|\n");
        for (var raw : (List<?>) result.get("cases")) {
            var row = CallApi.object(raw);
            double min = row.get("acceptance30Seconds") instanceof List<?> buckets
                    ? buckets.stream().map(CallApi::object).mapToDouble(w -> CapacityEvidence.decimal(w, "completedPerSecond")).min().orElse(0) : 0;
            text.append("| ").append(row.get("experimentStage")).append(" | ").append(row.get("profile")).append(" | ")
                    .append(row.get("path")).append(" | ").append(row.get("repetition")).append(" | ").append(row.get("status"))
                    .append(" | ").append(format(row.get("completedPerSecond"))).append(" | ").append(format(min))
                    .append(" | ").append(row.getOrDefault("invalidReasons", List.of())).append(" |\n");
        }
        return text.append("\n![Resource scan](resource-scan.svg)\n\nPer-case evidence contains resource costs, GC, dispatch/refill attribution, ten-second throughput and full-count reconciliation. Private recordings and payloads are not published.\n\n")
                .append("A not-met result bounds only this version and tested environment; it is not an absolute platform limit. No Item Call capacity or production SLA is inferred.\n").toString();
    }
    private static String format(Object value) {
        return value instanceof Number n ? String.format(java.util.Locale.ROOT, "%.1f", n.doubleValue()) : "—";
    }
    static String chart(List<Map<String, Object>> rows) {
        var scan = rows.stream().filter(r -> "screening".equals(r.get("experimentStage"))).toList();
        double max = Math.max(10_000, scan.stream().filter(ExperimentReport::valid)
                .mapToDouble(r -> CapacityEvidence.decimal(r, "completedPerSecond")).max().orElse(0));
        var svg = new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"800\" height=\"380\" viewBox=\"0 0 800 380\"><rect width=\"800\" height=\"380\" fill=\"white\"/><g font-family=\"sans-serif\" font-size=\"14\" fill=\"#172b4d\"><text x=\"30\" y=\"30\">Any resource scan — completed Items/s</text>");
        for (int i = 0; i < scan.size(); i++) {
            var row = scan.get(i);
            double qps = valid(row) ? CapacityEvidence.decimal(row, "completedPerSecond") : 0;
            double h = 240 * qps / max;
            int x = 45 + i * 148;
            svg.append("<rect x=\"").append(x).append("\" y=\"").append(300 - h).append("\" width=\"100\" height=\"").append(h)
                    .append("\" fill=\"#287c9f\"/><text x=\"").append(x).append("\" y=\"").append(290 - h).append("\">")
                    .append(valid(row) ? format(qps) : "invalid").append("</text><text x=\"").append(x).append("\" y=\"330\">")
                    .append(escape(String.valueOf(row.get("profile")))).append("</text>");
        }
        double y = 300 - 240 * 10_000 / max;
        return svg.append("<path d=\"M30 ").append(y).append(" H780\" stroke=\"#bc4b36\" stroke-dasharray=\"5 4\"/><text x=\"705\" y=\"")
                .append(y - 5).append("\">10,000</text></g></svg>").toString();
    }
    static String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;"); }
}
