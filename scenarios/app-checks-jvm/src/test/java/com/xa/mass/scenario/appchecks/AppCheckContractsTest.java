package com.xa.mass.scenario.appchecks;

import com.xa.mass.kernel.assignment.*;
import com.xa.mass.workerdelivery.json.Jsons;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AppCheckContractsTest {
    final AppCheckContracts contracts = AppCheckTestSettings.contracts();
    @Test void demosValidateAssociationsAndInterpretAnswersWithoutRecomputingThem() {
        var context = new AppCheckContract.Context("demo", null, "demo-sim", "unused", Map.of());
        var prime = contracts.require(AppCheckContracts.UINT32_PRIME);
        var input = prime.payload(context, "017");
        assertThat(input).containsEntry("value", "017");
        var answer = prime.result(context, input, Map.of("input", "017", "valid", true, "isPrime", false));
        assertThat(answer.matched()).isFalse(); // The Worker owns the calculation, not this parser.
        assertThat(prime.exportValues(answer)).containsExactly("否");
        for (var invalid : List.<Map<String,Object>>of(Map.of("input", "17", "valid", true, "isPrime", true),
                Map.of("input", "017", "valid", false, "isPrime", true),
                Map.of("input", "017", "valid", true, "isPrime", "true")))
            assertThatThrownBy(() -> prime.result(context, input, invalid)).isInstanceOf(IllegalArgumentException.class);
        var md5 = contracts.require(AppCheckContracts.MD5_PREFIX);
        var known = Map.of("a", "0cc175b9c0f1b6a831c399e269772661", "中文", "a7bac2239fcdcb3a067903d8077c4a07");
        known.forEach((seed, hash) -> {
            var result = md5.result(context, md5.payload(context, seed), Map.of("input", seed, "valid", true, "md5", hash));
            assertThat(result.matched()).isEqualTo(seed.equals("a"));
            assertThat(md5.exportValues(result)).containsExactly(hash);
        });
        assertThatThrownBy(() -> md5.result(context, Map.of("value", "a"), Map.of("input", "a", "valid", true, "md5", "invalid")))
                .hasMessageContaining("MD5");
        assertThatThrownBy(() -> md5.result(context, Map.of("value", "a"), Map.of("input", "b", "valid", true, "md5", known.get("a"))))
                .hasMessageContaining("association");
    }
    @Test void generatedNamesKeepTimeAndRemainValidForLongUnicodeCheckLabels() {
        var now = java.time.Instant.parse("2026-10-16T00:00:00Z");
        assertThat(AppCheckSpecification.taskName("App A 模拟注册", "CN", now))
                .isEqualTo("App A 模拟注册 · CN · 2026-10-16T00:00:00Z");
        String name = AppCheckSpecification.taskName("😀".repeat(64), "CN", now);
        assertThat(name.length()).isLessThanOrEqualTo(128);
        assertThat(name).startsWith("😀").endsWith("… · CN · 2026-10-16T00:00:00Z");
        assertThat(java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(name)).isTrue();
    }
    @Test void selectsAProtocolWithoutApplicationOrEventNameBranches() {
        AppCheckContract alternative = new AppCheckContract() {
            public String id() { return "vendor-proof-v1"; }
            public String label() { return "Vendor proof"; }
            public SeedType seedType() { return SeedType.PHONENUMBER; }
            public String description() { return "Independent proof protocol"; }
            public Map<String,Object> parameterExample() { return Map.of(); }
            public Map<String,Object> inputExample() { return Map.of("recipient", "+86123"); }
            public Map<String,Object> resultExample() { return Map.of("answer", "yes"); }
            public Map<String,Object> normalizeParameters(Object value) {
                return AppCheckSettings.object(value, Set.of(), Set.of(), "parameters");
            }
            public Map<String,Object> payload(Context context, String number) { return Map.of("recipient", number); }
            public String inputSeed(Map<String,Object> input) { return (String) input.get("recipient"); }
            public Result result(Context context, Map<String,Object> input, Map<String,Object> output) {
                if (!Set.of("yes", "no").contains(output.getOrDefault("answer", "")))
                    throw new IllegalArgumentException("Missing vendor answer");
                return new Result(inputSeed(input), "yes".equals(output.get("answer")), Map.of("vendorAnswer", output.get("answer")));
            }
        };
        var registry = new AppCheckContracts(List.of(alternative, AppCheckContracts.registration()));
        var config = AppCheckSettings.parse(Map.of("common", Map.of("typeDefaults", Map.of(alternative.id(), Map.of())),
                "checks", Map.of("arbitrary-app", Map.of("label", "Proof", "workerGroupId", "other-group",
                        "checkType", alternative.id(), "execution", Map.of("eventCode", "extension.worker.vendor.lookup",
                                "refill", List.of(Map.of("poolName", "any", "target", Map.of(), "count", 100)),
                                "workerSelector", Map.of("executorName", "worker.any", "input", Map.of())))),
                "poolGroups", Map.of()), registry);
        var app = config.requireCheck("arbitrary-app");
        var metadata = new HashMap<>(AppCheckTaskServiceTest.metadata());
        metadata.put("checkId", "arbitrary-app"); metadata.put("checkType", app.checkType());
        metadata.put("eventCode", app.execution().eventCode());
        metadata.put("workerSelector", Jsons.toJson(AppCheckSettings.selectorMap(app.execution().workerSelector())));
        metadata.put("contractParameters", Jsons.toJson(config.parametersFor(app)));
        var saved = AppCheckTaskSnapshot.read(app.workerGroupId(), metadata, registry);
        assertThat(saved.eventCode()).isEqualTo("extension.worker.vendor.lookup");
        assertThat(saved.selector().executorName()).isEqualTo("worker.any");
        var input = saved.contract().payload(saved.context(), "+86123");
        assertThat(input).containsOnlyKeys("recipient");
        assertThat(saved.contract().result(saved.context(), input, Map.of("answer", "yes")).matched()).isTrue();
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
        assertThat(contract.result(context, input, output).matched()).isFalse();
        assertThat(contract.result(context, input, output).details()).containsEntry("registered", false).doesNotContainKey("simulatedDelayMillis");
        assertThat(contract.exportHeaders()).containsExactly("注册状态");
        assertThat(contract.exportValues(contract.result(context, input, output))).containsExactly("未注册");
        for (String field : List.of("number", "registered", "workerId", "workerGroupId")) {
            var invalid = new HashMap<>(output); invalid.remove(field);
            assertThatThrownBy(() -> contract.result(context, input, invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        output.put("number", "+86124");
        assertThatThrownBy(() -> contract.result(context, input, output)).hasMessageContaining("association");
    }
    @Test void carrierSeparatesValidNegativeAnswersFromBrokenProtocolWithoutInventingWorkerIdentity() {
        var contract = contracts.require(AppCheckContracts.ORIGINAL_CARRIER);
        var context = new AppCheckContract.Context("carrier", "CN", "phone", "unused", Map.of());
        var input = contract.payload(context, "+8613800000000");
        assertThat(input).containsExactly(Map.entry("rawNumber", "+8613800000000"));
        var positive = Map.<String,Object>of("input", "+8613800000000", "possible", true, "valid", true, "originalCarrier", "China Mobile");
        var answer = contract.result(context, input, positive);
        assertThat(answer.matched()).isTrue();
        assertThat(answer.details()).containsEntry("originalCarrier", "China Mobile").doesNotContainKeys("workerId", "workerGroupId");
        assertThat(contract.exportValues(answer)).containsExactly("China Mobile", "IDENTIFIED");
        var unmapped = new HashMap<>(positive); unmapped.put("originalCarrier", "");
        assertThat(contract.result(context, input, unmapped).matched()).isFalse();
        var invalidNumber = Map.<String,Object>of("input", "+8613800000000", "possible", false, "valid", false, "error", "INVALID_PHONE_NUMBER");
        assertThat(contract.result(context, input, invalidNumber).details()).containsEntry("reason", "INVALID_PHONE_NUMBER");
        assertThat(contract.result(context, input, invalidNumber).matched()).isFalse();
        for (String field : List.of("input", "possible", "valid", "originalCarrier")) {
            var broken = new HashMap<>(positive); broken.remove(field);
            assertThatThrownBy(() -> contract.result(context, input, broken)).isInstanceOf(IllegalArgumentException.class);
            broken.put(field, 3);
            assertThatThrownBy(() -> contract.result(context, input, broken)).isInstanceOf(IllegalArgumentException.class);
        }
        var wrongInput = new HashMap<>(positive); wrongInput.put("input", "+8613800000001");
        assertThatThrownBy(() -> contract.result(context, input, wrongInput)).hasMessageContaining("association");
        var brokenNegative = new HashMap<>(invalidNumber); brokenNegative.put("error", "RAW_NUMBER_REQUIRED");
        assertThatThrownBy(() -> contract.result(context, input, brokenNegative)).hasMessageContaining("negative");
        assertThatThrownBy(() -> contract.normalizeParameters(Map.of("locale", "en"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void applicationParametersReplaceDefaultsAndSnapshotsDoNotConsultCurrentConfiguration() {
        var original = AppCheckTestSettings.settings();
        var document = Jsons.parseObject(Jsons.toJson(original.toMap()));
        @SuppressWarnings("unchecked") var apps = (Map<String, Map<String,Object>>) document.get("checks");
        apps.get("app-a-registration").put("parameters", Map.of("ranges", Map.of("registered", List.of(0,0), "unregistered", List.of(0,1000), "failed", List.of(1000,1000)), "delayMs", List.of(0,0)));
        var changed = AppCheckSettings.parse(document, contracts);
        assertThat(changed.parametersFor(changed.requireCheck("app-a-registration"))).isNotEqualTo(original.parametersFor(original.requireCheck("app-a-registration")));
        var saved = AppCheckTaskSnapshot.read("app-a-sim", AppCheckTaskServiceTest.metadata(), contracts);
        assertThat(saved.context().parameters().get("delayMs")).isEqualTo(List.of(2000L,5000L));
        apps.get("app-a-registration").put("parameters", Map.of("delayMs", List.of(0,0)));
        assertThatThrownBy(() -> AppCheckSettings.parse(document, contracts)).hasMessageContaining("checks.app-a");
        apps.get("app-a-registration").put("checkType", "missing");
        assertThatThrownBy(() -> AppCheckSettings.parse(document, contracts)).hasMessageContaining("Unknown App Checks contract");
        assertThatThrownBy(() -> new AppCheckContracts(List.of(AppCheckContracts.registration(), AppCheckContracts.registration())))
                .hasMessageContaining("Duplicate");
    }
}
