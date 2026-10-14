package com.xa.mass.scenario.appchecks;

import com.xa.mass.kernel.assignment.*;
import com.xa.mass.workerdelivery.json.Jsons;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AppCheckContractsTest {
    final AppCheckContracts contracts = AppCheckTestSettings.contracts();
    @Test void selectsAProtocolWithoutApplicationOrEventNameBranches() {
        AppCheckContract alternative = new AppCheckContract() {
            public String id() { return "vendor-proof-v1"; }
            public String description() { return "Independent proof protocol"; }
            public Map<String,Object> parameterExample() { return Map.of(); }
            public Map<String,Object> inputExample() { return Map.of("recipient", "+86123"); }
            public Map<String,Object> resultExample() { return Map.of("answer", "yes"); }
            public Map<String,Object> normalizeParameters(Object value) {
                return AppCheckSettings.object(value, Set.of(), Set.of(), "parameters");
            }
            public Map<String,Object> payload(Context context, String number) { return Map.of("recipient", number); }
            public String inputNumber(Map<String,Object> input) { return (String) input.get("recipient"); }
            public Result result(Context context, Map<String,Object> input, Map<String,Object> output) {
                if (!Set.of("yes", "no").contains(output.getOrDefault("answer", "")))
                    throw new IllegalArgumentException("Missing vendor answer");
                return new Result(inputNumber(input), "yes".equals(output.get("answer")), "worker", context.workerGroupId(), null);
            }
        };
        var registry = new AppCheckContracts(List.of(alternative, AppCheckContracts.registration()));
        var config = AppCheckSettings.parse(Map.of("common", Map.of("contractDefaults", Map.of(alternative.id(), Map.of())),
                "applications", Map.of("arbitrary-app", Map.of("label", "Proof", "workerGroupId", "other-group",
                        "contractId", alternative.id(), "execution", Map.of("eventCode", "extension.worker.vendor.lookup",
                                "refill", List.of(Map.of("poolName", "any", "target", Map.of(), "count", 100)),
                                "workerSelector", Map.of("executorName", "worker.any", "input", Map.of())))),
                "poolGroups", Map.of()), registry);
        var app = config.requireApplication("arbitrary-app");
        var metadata = new HashMap<>(AppCheckTaskServiceTest.metadata());
        metadata.put("appId", "arbitrary-app"); metadata.put("contractId", app.contractId());
        metadata.put("eventCode", app.execution().eventCode());
        metadata.put("workerSelector", Jsons.toJson(AppCheckSettings.selectorMap(app.execution().workerSelector())));
        metadata.put("contractParameters", Jsons.toJson(config.parametersFor(app)));
        var saved = AppCheckTaskSnapshot.read(app.workerGroupId(), metadata, registry);
        assertThat(saved.eventCode()).isEqualTo("extension.worker.vendor.lookup");
        assertThat(saved.selector().executorName()).isEqualTo("worker.any");
        var input = saved.contract().payload(saved.context(), "+86123");
        assertThat(input).containsOnlyKeys("recipient");
        assertThat(saved.contract().result(saved.context(), input, Map.of("answer", "yes")).registered()).isTrue();
        assertThatThrownBy(() -> saved.contract().result(saved.context(), input, Map.of()))
                .hasMessageContaining("Missing vendor answer");
        assertThat(config.poolGroups()).isEmpty();
    }
    @Test void standardResultNeedsItsAnswerAndAssociationsButNoSimulationFields() {
        var contract = contracts.require(AppCheckContracts.REGISTRATION);
        var context = new AppCheckContract.Context("a", "CN", "g", null, Map.of());
        var input = contract.payload(context, "+86123");
        assertThat(input).containsExactly(Map.entry("number", "+86123"));
        var output = new HashMap<String,Object>(Map.of("number", "+86123", "registered", false, "workerId", "w", "workerGroupId", "g"));
        assertThat(contract.result(context, input, output).registered()).isFalse();
        assertThat(contract.result(context, input, output).simulatedDelayMillis()).isNull();
        for (String field : List.of("number", "registered", "workerId", "workerGroupId")) {
            var invalid = new HashMap<>(output); invalid.remove(field);
            assertThatThrownBy(() -> contract.result(context, input, invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        output.put("number", "+86124");
        assertThatThrownBy(() -> contract.result(context, input, output)).hasMessageContaining("association");
    }
    @Test void applicationParametersReplaceDefaultsAndSnapshotsDoNotConsultCurrentConfiguration() {
        var original = AppCheckTestSettings.settings();
        var document = Jsons.parseObject(Jsons.toJson(original.toMap()));
        @SuppressWarnings("unchecked") var apps = (Map<String, Map<String,Object>>) document.get("applications");
        apps.get("app-a").put("parameters", Map.of("ranges", Map.of("registered", List.of(0,0), "unregistered", List.of(0,1000), "failed", List.of(1000,1000)), "delayMs", List.of(0,0)));
        var changed = AppCheckSettings.parse(document, contracts);
        assertThat(changed.parametersFor(changed.requireApplication("app-a"))).isNotEqualTo(original.parametersFor(original.requireApplication("app-a")));
        var saved = AppCheckTaskSnapshot.read("app-a-sim", AppCheckTaskServiceTest.metadata(), contracts);
        assertThat(saved.context().parameters().get("delayMs")).isEqualTo(List.of(2000L,5000L));
        apps.get("app-a").put("parameters", Map.of("delayMs", List.of(0,0)));
        assertThatThrownBy(() -> AppCheckSettings.parse(document, contracts)).hasMessageContaining("applications.app-a");
        apps.get("app-a").put("contractId", "missing");
        assertThatThrownBy(() -> AppCheckSettings.parse(document, contracts)).hasMessageContaining("Unknown App Checks contract");
        assertThatThrownBy(() -> new AppCheckContracts(List.of(AppCheckContracts.registration(), AppCheckContracts.registration())))
                .hasMessageContaining("Duplicate");
    }
}
