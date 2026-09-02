package com.xa.mass.server.assembly.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class AgentForgeProfileContractTest {

    @Test
    void profileOwnsOneEmptyAgentForgeAdapterAssembly() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
                "agentforge",
                new ClassPathResource("application-agentforge.yaml")
        );
        StandardEnvironment environment = new StandardEnvironment();
        sources.forEach(environment.getPropertySources()::addFirst);

        assertThat(environment.getProperty("server.port"))
                .isEqualTo("18182");
        assertThat(environment.getProperty("xa.mass.redis.scope"))
                .isEqualTo("profile_agentforge");
        assertThat(environment.getProperty("xa.mass.kernel-pacer.preset"))
                .isEqualTo("DEFAULT");
        assertThat(environment.getProperty(
                "xa.mass.worker-assembly.group-config-json"
        )).isEqualTo("{}");
        assertThat(environment.getProperty(
                "xa.mass.worker-binding.endpoints.agentforge-websocket"
                        + ".public-uri"
        )).isEqualTo(
                "ws://127.0.0.1:18183"
                        + "/api/v1/worker-delivery/websocket"
        );
        assertThat(adapterIds(sources))
                .containsExactly("agentforge-websocket");
        assertThat(allPropertyNames(sources))
                .contains(
                        "xa.mass.worker-delivery.adapter.instances."
                                + "agentforge-websocket.command-backoff",
                        "xa.mass.worker-delivery.adapter.instances."
                                + "agentforge-websocket.report-queue-capacity",
                        "xa.mass.worker-delivery.adapter.instances."
                                + "agentforge-websocket.shutdown-timeout"
                )
                .noneMatch(name -> name.contains("scenario-websocket"))
                .noneMatch(name -> name.contains(".processes["));
    }

    private static Set<String> adapterIds(List<PropertySource<?>> sources) {
        String prefix = "xa.mass.worker-delivery.adapter.instances.";
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        allPropertyNames(sources).stream()
                .filter(name -> name.startsWith(prefix))
                .map(name -> name.substring(prefix.length()))
                .map(name -> name.substring(0, name.indexOf('.')))
                .forEach(ids::add);
        return ids;
    }

    private static Set<String> allPropertyNames(
            List<PropertySource<?>> sources
    ) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        sources.stream()
                .filter(EnumerablePropertySource.class::isInstance)
                .map(EnumerablePropertySource.class::cast)
                .map(EnumerablePropertySource::getPropertyNames)
                .flatMap(Arrays::stream)
                .forEach(names::add);
        return names;
    }
}
