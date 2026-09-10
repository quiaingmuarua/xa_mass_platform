package com.xa.mass.server;

import com.xa.mass.sms.backend.SmsProductConfiguration;
import com.xa.mass.distribution.ConsoleFrontendConfiguration;
import com.xa.mass.distribution.ProductWorkerConfiguration;
import com.xa.mass.messages.backend.MessageProductConfiguration;
import org.springframework.boot.SpringApplication;

/** The distribution composes platform and optional product configuration once. */
public final class XaMassServerApplication {
    private XaMassServerApplication() {}

    public static void main(String[] args) {
        SpringApplication.run(new Class<?>[]{XaMassServerConfiguration.class, SmsProductConfiguration.class,
                MessageProductConfiguration.class, ProductWorkerConfiguration.class, ConsoleFrontendConfiguration.class}, args);
    }
}
