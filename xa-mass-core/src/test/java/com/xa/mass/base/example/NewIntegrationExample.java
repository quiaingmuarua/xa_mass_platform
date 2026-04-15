package com.xa.mass.base.example;

import com.xa.mass.base.jsondsl.model.JsonDslContext;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.parser.JsonDslParser;
import com.xa.mass.base.jsondsl.processor.FilterProcessor;
import com.xa.mass.base.jsondsl.processor.FilterResult;
import com.xa.mass.base.jsondsl.processor.GenerateProcessor;
import com.xa.mass.base.jsondsl.processor.ProcessingContext;
import com.xa.mass.base.jsondsl.processor.ProcessorRegistry;
import com.xa.mass.base.model.Device;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NewIntegrationExample {

    public static void main(String[] args) {
        System.out.println("=== New Standard DSL Integration Example ===");

        List<Device> devices = generateDevices();
        System.out.println("Generated devices: " + devices.size());

        JsonDslDefinition filterDef = buildFilterDef();
        String filterJson = """
                {
                  "uniqueId": "device_filter_json",
                  "type": "filter",
                  "description": "Filter devices by id range, group range, and ONLINE status",
                  "author": "integration_test",
                  "priority": 10,
                  "fieldDsl": {
                    "deviceId": {"$lt": 100},
                    "deviceGroupId": {"$lt": 100},
                    "status": {"$eq": "ONLINE"}
                  },
                  "combineDsl": {
                    "device_group_check": "parseInt(deviceId) < 100 && parseInt(deviceGroupId) < 100",
                    "status_check": "status == 'ONLINE'"
                  }
                }
                """;

        JsonDslDefinition filterDefFromJson = JsonDslParser.parse(filterJson);
        filterDefFromJson.validate();

        List<Device> filteredDevices = filterDevices(devices, filterDefFromJson);
        System.out.println("Filtered devices: " + filteredDevices.size());
        explainFilter(devices, filterDefFromJson);

        System.out.println("\n=== Filtered Device Preview ===");
        filteredDevices.forEach(device ->
                System.out.println("Device=" + device.getDeviceId()
                        + ", group=" + device.getDeviceGroupId()
                        + ", status=" + device.getStatus())
        );

        printStatistics(devices, filteredDevices);
    }

    private static List<Device> generateDevices() {
        JsonDslDefinition definition = new JsonDslDefinition("device_generator", JsonDslDefinition.DslType.GENERATE);
        definition.setDescription("Generate 300 devices for integration filtering");
        definition.setAuthor("integration_test");
        definition.setTags(new String[]{"device", "integration"});
        definition.setPriority(1);

        JsonDslContext context = new JsonDslContext("com.xa.mass.base.model.Device", 300);
        context.setScopeName("Device");
        context.setDebug(false);
        definition.setContext(context);

        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("deviceId", Map.of("$JOIN", Arrays.asList("", "&.index")));
        fieldDsl.put("deviceGroupId", Map.of("$RANGE", Arrays.asList(16, 65)));
        fieldDsl.put("status", Map.of("$CHOICE", Arrays.asList("OFFLINE", "ONLINE")));
        definition.setFieldDsl(fieldDsl);

        definition.validate();
        GenerateProcessor processor = ProcessorRegistry.getGenerateProcessor();
        return processor.generate(definition, new ProcessingContext("test-context"), Device.class);
    }

    private static List<Device> filterDevices(List<Device> devices, JsonDslDefinition filterDef) {
        FilterProcessor filterProcessor = ProcessorRegistry.getFilterProcessor();
        FilterResult<Device> result = filterProcessor.filterList(devices, filterDef, new ProcessingContext("test-context"));
        return result.getPassed();
    }

    private static void explainFilter(List<Device> devices, JsonDslDefinition filterDef) {
        System.out.println("\n=== Filter Explain Report ===");

        FilterProcessor filterProcessor = ProcessorRegistry.getFilterProcessor();
        FilterResult<Device> report = filterProcessor.filterList(devices, filterDef, new ProcessingContext("test-context"));
        System.out.println("Passed: " + report.getPassed().size());

        for (FilterResult.FilterFailure<Device> failure : report.getFailed()) {
            Device device = failure.getData();
            System.out.println("Failed device=" + device.getDeviceId()
                    + ", group=" + device.getDeviceGroupId()
                    + ", status=" + device.getStatus()
                    + ", reasons=" + String.join("; ", failure.getReasons()));
        }
    }

    private static void printStatistics(List<Device> allDevices, List<Device> filteredDevices) {
        System.out.println("\n=== Statistics ===");
        System.out.println("Total devices: " + allDevices.size());

        long onlineCount = allDevices.stream()
                .filter(device -> "ONLINE".equals(device.getStatus().name()))
                .count();
        System.out.println("ONLINE devices: " + onlineCount);

        long deviceIdLessThan100 = allDevices.stream()
                .filter(device -> {
                    try {
                        return Integer.parseInt(device.getDeviceId()) < 100;
                    } catch (NumberFormatException e) {
                        return false;
                    }
                })
                .count();
        System.out.println("deviceId < 100: " + deviceIdLessThan100);

        long deviceGroupIdLessThan25 = allDevices.stream()
                .filter(device -> {
                    try {
                        return Integer.parseInt(device.getDeviceGroupId()) < 25;
                    } catch (NumberFormatException e) {
                        return false;
                    }
                })
                .count();
        System.out.println("deviceGroupId < 25: " + deviceGroupIdLessThan25);

        System.out.println("Filtered devices: " + filteredDevices.size());
        double filterRate = (double) filteredDevices.size() / allDevices.size() * 100;
        System.out.printf("Filter rate: %.2f%%%n", filterRate);
    }

    private static JsonDslDefinition buildFilterDef() {
        JsonDslDefinition filterDef = new JsonDslDefinition("device_filter", JsonDslDefinition.DslType.FILTER);
        filterDef.setDescription("Filter ONLINE devices with id < 100 and deviceGroupId < 25");
        filterDef.setAuthor("integration_test");
        filterDef.setPriority(10);

        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("deviceId", Map.of("$lt", 100));
        fieldDsl.put("deviceGroupId", Map.of("$lt", 25));
        fieldDsl.put("status", Map.of("$eq", "ONLINE"));
        filterDef.setFieldDsl(fieldDsl);

        Map<String, Object> combineDsl = new HashMap<>();
        combineDsl.put("device_group_check", "parseInt(deviceId) < 100 && parseInt(deviceGroupId) < 25");
        combineDsl.put("status_check", "status == 'ONLINE'");
        filterDef.setCombineDsl(combineDsl);
        filterDef.validate();
        return filterDef;
    }
}
