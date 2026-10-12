package com.xa.mass.scenario.appchecks;

import com.xa.mass.workermatching.FixedWindowPoolDefinition.WindowLimit;
import java.util.*;

/** Scenario-owned configuration; executable identities and Task policy remain code-owned. */
public record AppCheckSettings(Map<String, Object> simulationDefault,
                               List<Application> applications, Map<String, WindowLimit> poolGroups) {
    public record Application(String appId, String label, String workerGroupId) {
        public Application {
            AppCheckSpecification.text(appId, 128);
            AppCheckSpecification.text(label, 128);
            AppCheckSpecification.text(workerGroupId, 128);
        }
    }

    public AppCheckSettings {
        simulationDefault = AppCheckSpecification.simulation(simulationDefault);
        if (applications == null || applications.isEmpty()) throw new IllegalArgumentException("应用列表不能为空");
        applications = List.copyOf(applications);
        var ids = new HashSet<String>();
        var groups = new HashSet<String>();
        for (var app : applications)
            if (!ids.add(app.appId()) || !groups.add(app.workerGroupId()))
                throw new IllegalArgumentException("应用 ID 和 WorkerGroup 必须分别唯一");
        if (poolGroups == null || !poolGroups.keySet().equals(groups))
            throw new IllegalArgumentException("窗口参数必须完整对应应用绑定的 WorkerGroup");
        poolGroups = Map.copyOf(poolGroups);
    }

    public Application requireApplication(String appId) {
        return applications.stream().filter(app -> app.appId().equals(appId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported appId"));
    }

    public List<String> workerGroupIds() { return applications.stream().map(Application::workerGroupId).toList(); }

    void requireUpdateOf(AppCheckSettings previous) {
        for (var existing : previous.applications()) {
            var current = applications.stream().filter(app -> app.appId().equals(existing.appId())).findFirst();
            if (current.isEmpty() || !current.get().workerGroupId().equals(existing.workerGroupId()))
                throw new IllegalArgumentException("已有应用不可删除或换绑 WorkerGroup：" + existing.appId());
        }
    }

    public Map<String, Object> toMap() {
        var windows = new TreeMap<String, Object>();
        poolGroups.forEach((group, limit) -> windows.put(group,
                Map.of("windowMillis", limit.windowMillis(), "maxCount", limit.maxCount())));
        return Map.of("common", Map.of("simulationDefault", simulationDefault),
                "applications", applications.stream().map(app -> Map.of("appId", app.appId(),
                        "label", app.label(), "workerGroupId", app.workerGroupId())).toList(), "poolGroups", windows);
    }

    public static AppCheckSettings parse(Object input) {
        var object = object(input, Set.of("common", "applications", "poolGroups"));
        var common = object(object.get("common"), Set.of("simulationDefault"));
        if (!(object.get("applications") instanceof List<?> values)) throw new IllegalArgumentException("应用列表无效");
        var apps = values.stream().map(value -> {
            var app = object(value, Set.of("appId", "label", "workerGroupId"));
            return new Application(AppCheckSpecification.text(app.get("appId"), 128),
                    AppCheckSpecification.text(app.get("label"), 128),
                    AppCheckSpecification.text(app.get("workerGroupId"), 128));
        }).toList();
        if (!(object.get("poolGroups") instanceof Map<?, ?> groups)) throw new IllegalArgumentException("Pool / Group 配置无效");
        var limits = new LinkedHashMap<String, WindowLimit>();
        groups.forEach((group, value) -> {
            var limit = object(value, Set.of("windowMillis", "maxCount"));
            limits.put(AppCheckSpecification.text(group, 128), new WindowLimit(
                    AppCheckSpecification.integer(limit.get("windowMillis")), AppCheckSpecification.integer(limit.get("maxCount"))));
        });
        return new AppCheckSettings(AppCheckSpecification.simulation(common.get("simulationDefault")), apps, limits);
    }

    static Map<?, ?> object(Object value, Set<String> keys) {
        if (!(value instanceof Map<?, ?> object) || !object.keySet().equals(keys))
            throw new IllegalArgumentException("配置字段缺失或包含未知字段：" + String.join(", ", new TreeSet<>(keys)));
        return object;
    }
}
