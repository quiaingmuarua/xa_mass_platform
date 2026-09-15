package com.xa.mass.server.assembly.matching;

import com.xa.mass.kernel.assignment.EligibilityQuery;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ByteArrayResource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class MatchingRulePropertiesTest {
    @EnableConfigurationProperties(MatchingRuleProperties.class)
    static class Binding {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Binding.class);
    private final String target = "xa.mass.worker-matching.rules.default-refill-targets.g.[worker.country][0].";

    @Test void flatTargetBindingRetainsItsSharedQueryAndQuantity() {
        runner.withPropertyValues(target + "query.[worker.country][0]=CN", target + "count=7")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var value = context.getBean(MatchingRuleProperties.class).defaultRefillTargets()
                            .get("g").get("worker.country").getFirst();
                    assertThat(value.query()).isEqualTo(new EligibilityQuery(Map.of("worker.country", List.of("CN"))));
                    assertThat(value.count()).isEqualTo(7);
                });
    }
    @Test void yamlAndJsonUseTheSameFlatTargetShape() throws Exception {
        var yaml = new ByteArrayResource("""
                xa:
                  mass:
                    worker-matching:
                      rules:
                        default-refill-targets:
                          g:
                            "[worker.country]":
                              - query:
                                  "[worker.country]": [US, CN, US]
                                count: 7
                """.getBytes(StandardCharsets.UTF_8));
        var sources = new YamlPropertySourceLoader().load("query-test", yaml);
        runner.withInitializer(context -> sources.forEach(source ->
                context.getEnvironment().getPropertySources().addLast(source))).run(context -> {
            assertThat(context).hasNotFailed();
            var value = context.getBean(MatchingRuleProperties.class).defaultRefillTargets()
                    .get("g").get("worker.country").getFirst();
            var mapper = JsonMapper.builder().build();
            assertThat(mapper.readTree(mapper.writeValueAsString(value))).isEqualTo(mapper.readTree(
                    "{\"query\":{\"worker.country\":[\"US\",\"CN\",\"US\"]},\"count\":7}"));
        });
    }
    @Test void missingQueryMeansAnyButCountAndUnknownFieldsRemainStrict() {
        runner.withPropertyValues(target + "count=100").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(MatchingRuleProperties.class).defaultRefillTargets()
                    .get("g").get("worker.country").getFirst().query().query()).isEmpty();
        });
        for (String invalid : List.of("count=0", "count=1001", "query.[worker.country][0]=CN", "unknown=true")) {
            runner.withPropertyValues(target + invalid).run(context -> assertThat(context).hasFailed());
        }
        runner.withPropertyValues(target + "count=1", target + "unknown=true")
                .run(context -> assertThat(context).hasFailed());
    }
}
