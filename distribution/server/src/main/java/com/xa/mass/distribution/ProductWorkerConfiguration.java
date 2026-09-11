package com.xa.mass.distribution;

import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Fixed deployment input shared by the two product application services. */
@Configuration(proxyBeanMethods = false)
@Profile("sms-reception | message-campaigns")
public class ProductWorkerConfiguration {
    @Bean("productWorkerGroup")
    String workerGroup() {
        return "demo-sim";
    }

    @Bean("productWorkerEvents")
    List<String> workerEvents(Environment environment) {
        var events = new ArrayList<String>();
        events.addAll(List.of("extension.worker.string.md5", "extension.worker.string.sha1",
                "extension.worker.string.base64.encode"));
        if (environment.acceptsProfiles(Profiles.of("sms-reception"))) {
            events.add("extension.worker.sms.listen.start");
            events.add("extension.worker.sms.listen.cancel");
        }
        if (environment.acceptsProfiles(Profiles.of("message-campaigns"))) events.add("extension.worker.message.send");
        return List.copyOf(events);
    }
}
