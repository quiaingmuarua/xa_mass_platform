package com.xa.mass.scenario.appchecks;

import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.kernel.assignment.RefillTarget;
import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.workerdelivery.json.Jsons;
import java.util.*;

/** Complete scenario configuration; resource ownership stays with the existing Owners. */
public record AppCheckSettings(Map<String, Map<String, Object>> typeDefaults,
                               Map<String, Check> checks) {
    public record Execution(String eventCode, List<RefillTarget> refill, WorkerQuery workerSelector, int seedsPerItem) {
        public Execution {
            AppCheckSpecification.text(eventCode, 256);
            refill = List.copyOf(refill);
            if (refill.size() > 100) throw new IllegalArgumentException("execution.refill: at most 100 declarations");
            Objects.requireNonNull(workerSelector, "execution.workerSelector");
            if (seedsPerItem < 1 || seedsPerItem > 100)
                throw new IllegalArgumentException("execution.seedsPerItem: 必须是 1～100 的整数");
            if (Jsons.toJson(selectorMap(workerSelector)).length() > 4096)
                throw new IllegalArgumentException("execution.workerSelector: exceeds Task metadata bound");
        }
        public Map<String, Object> toMap() {
            return Map.of("eventCode", eventCode, "refill", refill.stream().map(target -> Map.of(
                    "poolName", target.poolName(), "target", target.target().query(), "count", target.count())).toList(),
                    "workerSelector", selectorMap(workerSelector), "seedsPerItem", seedsPerItem);
        }
    }
    public record Check(String label, String workerGroupId, Execution execution,
                              String checkType, Map<String, Object> parameters, long unitPrice) {
        public Check {
            AppCheckSpecification.text(label, 128);
            if (unitPrice < 1 || unitPrice > com.xa.mass.server.credits.CreditModels.MAX_POINTS)
                throw new IllegalArgumentException("unitPrice: 必须是正的安全整数");
            AppCheckSpecification.text(workerGroupId, 128); AppCheckSpecification.text(checkType, 128);
            Objects.requireNonNull(execution, "execution");
            if (parameters != null) parameters = AppCheckSettings.parameters(parameters, "parameters");
        }
        public Map<String, Object> toMap() {
            var value = new LinkedHashMap<String, Object>();
            value.put("unitPrice", unitPrice); value.put("label", label); value.put("workerGroupId", workerGroupId);
            value.put("execution", execution.toMap()); value.put("checkType", checkType);
            if (parameters != null) value.put("parameters", parameters);
            return Collections.unmodifiableMap(value);
        }
    }
    public AppCheckSettings {
        var defaults = new LinkedHashMap<String, Map<String, Object>>();
        typeDefaults.forEach((id, value) -> defaults.put(id, parameters(value, "common.typeDefaults." + id)));
        typeDefaults = Collections.unmodifiableMap(defaults);
        if (checks == null || checks.isEmpty()) throw new IllegalArgumentException("checks: 检查项配置不能为空");
        checks.forEach((checkId, app) -> {
            AppCheckSpecification.text(checkId, 128);
        });
        // Object key order is not configuration: Catalog uses stable checkId order.
        checks = Collections.unmodifiableMap(new TreeMap<>(checks));
    }
    public Check requireCheck(String checkId) {
        var app = checks.get(checkId);
        if (app == null) throw new IllegalArgumentException("Unsupported checkId");
        return app;
    }
    public List<String> workerGroupIds() { return checks.values().stream().map(Check::workerGroupId).distinct().toList(); }
    public Map<String, Object> parametersFor(Check app) {
        var selected = app.parameters() == null ? typeDefaults.get(app.checkType()) : app.parameters();
        if (selected == null) throw new IllegalArgumentException("parameters: 缺少契约参数：" + app.checkType());
        return selected;
    }
    void requireUpdateOf(AppCheckSettings previous) {
        for (var existing : previous.checks().entrySet()) {
            var current = checks.get(existing.getKey());
            if (current == null || !current.workerGroupId().equals(existing.getValue().workerGroupId())
                    || !current.checkType().equals(existing.getValue().checkType()))
                throw new IllegalArgumentException("checks: 已有检查项不可删除、换类型或换绑 WorkerGroup：" + existing.getKey());
        }
    }
    public Map<String, Object> toMap() {
        var apps = new TreeMap<String, Object>();
        checks.forEach((id, app) -> apps.put(id, app.toMap()));
        return Map.of("common", Map.of("typeDefaults", typeDefaults), "checks", apps);
    }
    public static AppCheckSettings parse(Object input, AppCheckContracts contracts) {
        var root = object(input, Set.of("common", "checks"), Set.of(), "configuration");
        var common = object(root.get("common"), Set.of("typeDefaults"), Set.of(), "common");
        var defaults = new LinkedHashMap<String, Map<String, Object>>();
        map(common.get("typeDefaults"), "common.typeDefaults").forEach((id, value) ->
                defaults.put(id, normalize(contracts, id, value, "common.typeDefaults." + id)));
        var apps = new LinkedHashMap<String, Check>();
        for (var entry : map(root.get("checks"), "checks").entrySet()) {
            String path = "checks." + entry.getKey();
            try {
                String checkId = AppCheckSpecification.text(entry.getKey(), 128);
                var app = object(entry.getValue(), Set.of("label", "workerGroupId", "execution", "checkType", "unitPrice"), Set.of("parameters"), path);
                String id = AppCheckSpecification.text(app.get("checkType"), 128);
                var contract = contracts.require(id);
                var execution = execution(app.get("execution"), path + ".execution");
                if (execution.seedsPerItem() > contract.maxSeedsPerItem())
                    throw new IllegalArgumentException("execution.seedsPerItem: 此契约最多支持 " + contract.maxSeedsPerItem() + " 个种子");
                var selected = app.containsKey("parameters") ? normalize(contracts, id, app.get("parameters"), path + ".parameters") : null;
                if (selected == null && !defaults.containsKey(id)) throw new IllegalArgumentException("parameters: 缺少参数及共同默认值");
                apps.put(checkId, new Check(AppCheckSpecification.text(app.get("label"), 128),
                        AppCheckSpecification.text(app.get("workerGroupId"), 128), execution, id, selected, AppCheckSpecification.integer(app.get("unitPrice"))));
            } catch (IllegalArgumentException invalid) { throw at(path, invalid); }
        }
        return new AppCheckSettings(defaults, apps);
    }
    private static Map<String, Object> normalize(AppCheckContracts contracts, String id, Object value, String path) {
        try { return parameters(contracts.require(id).normalizeParameters(value), path); }
        catch (IllegalArgumentException invalid) { throw at(path, invalid); }
    }
    private static Execution execution(Object value, String path) {
        var execution = object(value, Set.of("eventCode", "refill", "workerSelector"), Set.of("seedsPerItem"), path);
        if (!(execution.get("refill") instanceof List<?> targets)) throw new IllegalArgumentException(path + ".refill: 必须是数组");
        var refill = new ArrayList<RefillTarget>();
        for (int i = 0; i < targets.size(); i++) {
            var target = object(targets.get(i), Set.of("poolName", "target", "count"), Set.of(), path + ".refill[" + i + "]");
            var query = new LinkedHashMap<String, List<String>>();
            map(target.get("target"), path + ".refill[" + i + "].target").forEach((key, items) -> {
                if (!(items instanceof List<?> list) || list.stream().anyMatch(item -> !(item instanceof String)))
                    throw new IllegalArgumentException(path + ".refill.target." + key + ": 必须是字符串数组");
                query.put(key, ((List<?>) items).stream().map(String.class::cast).toList());
            });
            long count = AppCheckSpecification.integer(target.get("count"));
            if (count <= 0 || count > Integer.MAX_VALUE) throw new IllegalArgumentException(path + ".refill[" + i + "].count: 必须是正整数且不超过 2147483647");
            refill.add(RefillTarget.of(AppCheckSpecification.text(target.get("poolName"), 128), new EligibilityQuery(query),
                    (int) count));
        }
        long count = execution.containsKey("seedsPerItem") ? AppCheckSpecification.integer(execution.get("seedsPerItem")) : 1;
        if (count < 1 || count > 100) throw new IllegalArgumentException(path + ".seedsPerItem: 必须是 1～100 的整数");
        return new Execution(AppCheckSpecification.text(execution.get("eventCode"), 256), refill, selector(execution.get("workerSelector")), (int) count);
    }
    static WorkerQuery selector(Object value) {
        var query = object(value, Set.of("executorName", "input"), Set.of(), "workerSelector");
        return new WorkerQuery(AppCheckSpecification.text(query.get("executorName"), 128), query.get("input"));
    }
    static Map<String, Object> selectorMap(WorkerQuery selector) { return Map.of("executorName", selector.executorName(), "input", selector.input()); }
    static Map<String, Object> object(Object value, Set<String> required, Set<String> optional, String path) {
        var result = map(value, path);
        for (String key : required) if (!result.containsKey(key)) throw new IllegalArgumentException(path + "." + key + ": 缺少字段");
        for (String key : result.keySet()) if (!required.contains(key) && !optional.contains(key)) throw new IllegalArgumentException(path + "." + key + ": 未知字段");
        return result;
    }
    static Map<String, Object> map(Object value, String path) {
        if (!(value instanceof Map<?, ?> source)) throw new IllegalArgumentException(path + ": 必须是对象");
        var copy = new LinkedHashMap<String, Object>();
        source.forEach((key, item) -> {
            if (!(key instanceof String text) || text.isBlank()) throw new IllegalArgumentException(path + ": 无效键");
            copy.put(text, item);
        });
        return copy;
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> parameters(Object value, String path) {
        var input = map(value, path);
        if (Jsons.toJson(input).length() > 4096) throw new IllegalArgumentException(path + ": 超过 Task metadata 4096 字符限制");
        return (Map<String, Object>) new WorkerQuery("app-check-parameters", input).input();
    }
    private static IllegalArgumentException at(String path, IllegalArgumentException cause) {
        return new IllegalArgumentException(path + ": " + cause.getMessage(), cause);
    }
}
