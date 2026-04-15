package com.xa.mass.base.example;

import com.xa.mass.base.jsondsl.model.JsonDslContext;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.parser.JsonDslParser;
import com.xa.mass.base.jsondsl.processor.FilterProcessor;
import com.xa.mass.base.jsondsl.processor.FilterResult;
import com.xa.mass.base.jsondsl.processor.GenerateProcessor;
import com.xa.mass.base.jsondsl.processor.ProcessingContext;
import com.xa.mass.base.jsondsl.processor.ProcessorRegistry;
import com.xa.mass.base.model.Worker;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NewIntegrationExample {

    public static void main(String[] args) {
        System.out.println("=== New Standard DSL Integration Example ===");

        List<Worker> workers = generateWorkers();
        System.out.println("Generated workers: " + workers.size());

        JsonDslDefinition filterDef = buildFilterDef();
        String filterJson = """
                {
                  "uniqueId": "worker_filter_json",
                  "type": "filter",
                  "description": "Filter workers by id range, group range, and ONLINE status",
                  "author": "integration_test",
                  "priority": 10,
                  "fieldDsl": {
                    "workerId": {"$lt": 100},
                    "workerGroupId": {"$lt": 100},
                    "status": {"$eq": "ONLINE"}
                  },
                  "combineDsl": {
                    "worker_group_check": "parseInt(workerId) < 100 && parseInt(workerGroupId) < 100",
                    "status_check": "status == 'ONLINE'"
                  }
                }
                """;

        JsonDslDefinition filterDefFromJson = JsonDslParser.parse(filterJson);
        filterDefFromJson.validate();

        List<Worker> filteredWorkers = filterWorkers(workers, filterDefFromJson);
        System.out.println("Filtered workers: " + filteredWorkers.size());
        explainFilter(workers, filterDefFromJson);

        System.out.println("\n=== Filtered Worker Preview ===");
        filteredWorkers.forEach(worker ->
                System.out.println("Worker=" + worker.getWorkerId()
                        + ", group=" + worker.getWorkerGroupId()
                        + ", status=" + worker.getStatus())
        );

        printStatistics(workers, filteredWorkers);
    }

    private static List<Worker> generateWorkers() {
        JsonDslDefinition definition = new JsonDslDefinition("worker_generator", JsonDslDefinition.DslType.GENERATE);
        definition.setDescription("Generate 300 workers for integration filtering");
        definition.setAuthor("integration_test");
        definition.setTags(new String[]{"worker", "integration"});
        definition.setPriority(1);

        JsonDslContext context = new JsonDslContext("com.xa.mass.base.model.Worker", 300);
        context.setScopeName("Worker");
        context.setDebug(false);
        definition.setContext(context);

        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("workerId", Map.of("$JOIN", Arrays.asList("", "&.index")));
        fieldDsl.put("workerGroupId", Map.of("$RANGE", Arrays.asList(16, 65)));
        fieldDsl.put("status", Map.of("$CHOICE", Arrays.asList("OFFLINE", "ONLINE")));
        definition.setFieldDsl(fieldDsl);

        definition.validate();
        GenerateProcessor processor = ProcessorRegistry.getGenerateProcessor();
        return processor.generate(definition, new ProcessingContext("test-context"), Worker.class);
    }

    private static List<Worker> filterWorkers(List<Worker> workers, JsonDslDefinition filterDef) {
        FilterProcessor filterProcessor = ProcessorRegistry.getFilterProcessor();
        FilterResult<Worker> result = filterProcessor.filterList(workers, filterDef, new ProcessingContext("test-context"));
        return result.getPassed();
    }

    private static void explainFilter(List<Worker> workers, JsonDslDefinition filterDef) {
        System.out.println("\n=== Filter Explain Report ===");

        FilterProcessor filterProcessor = ProcessorRegistry.getFilterProcessor();
        FilterResult<Worker> report = filterProcessor.filterList(workers, filterDef, new ProcessingContext("test-context"));
        System.out.println("Passed: " + report.getPassed().size());

        for (FilterResult.FilterFailure<Worker> failure : report.getFailed()) {
            Worker worker = failure.getData();
            System.out.println("Failed worker=" + worker.getWorkerId()
                    + ", group=" + worker.getWorkerGroupId()
                    + ", status=" + worker.getStatus()
                    + ", reasons=" + String.join("; ", failure.getReasons()));
        }
    }

    private static void printStatistics(List<Worker> allWorkers, List<Worker> filteredWorkers) {
        System.out.println("\n=== Statistics ===");
        System.out.println("Total workers: " + allWorkers.size());

        long onlineCount = allWorkers.stream()
                .filter(worker -> "ONLINE".equals(worker.getStatus().name()))
                .count();
        System.out.println("ONLINE workers: " + onlineCount);

        long workerIdLessThan100 = allWorkers.stream()
                .filter(worker -> {
                    try {
                        return Integer.parseInt(worker.getWorkerId()) < 100;
                    } catch (NumberFormatException e) {
                        return false;
                    }
                })
                .count();
        System.out.println("workerId < 100: " + workerIdLessThan100);

        long workerGroupIdLessThan25 = allWorkers.stream()
                .filter(worker -> {
                    try {
                        return Integer.parseInt(worker.getWorkerGroupId()) < 25;
                    } catch (NumberFormatException e) {
                        return false;
                    }
                })
                .count();
        System.out.println("workerGroupId < 25: " + workerGroupIdLessThan25);

        System.out.println("Filtered workers: " + filteredWorkers.size());
        double filterRate = (double) filteredWorkers.size() / allWorkers.size() * 100;
        System.out.printf("Filter rate: %.2f%%%n", filterRate);
    }

    private static JsonDslDefinition buildFilterDef() {
        JsonDslDefinition filterDef = new JsonDslDefinition("worker_filter", JsonDslDefinition.DslType.FILTER);
        filterDef.setDescription("Filter ONLINE workers with id < 100 and workerGroupId < 25");
        filterDef.setAuthor("integration_test");
        filterDef.setPriority(10);

        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("workerId", Map.of("$lt", 100));
        fieldDsl.put("workerGroupId", Map.of("$lt", 25));
        fieldDsl.put("status", Map.of("$eq", "ONLINE"));
        filterDef.setFieldDsl(fieldDsl);

        Map<String, Object> combineDsl = new HashMap<>();
        combineDsl.put("worker_group_check", "parseInt(workerId) < 100 && parseInt(workerGroupId) < 25");
        combineDsl.put("status_check", "status == 'ONLINE'");
        filterDef.setCombineDsl(combineDsl);
        filterDef.validate();
        return filterDef;
    }
}
