package com.xa.mass.server;

import com.xa.mass.sms.backend.SmsProductConfiguration;
import com.xa.mass.distribution.ConsoleFrontendConfiguration;
import org.springframework.boot.SpringApplication;

/** The distribution composes platform and optional product configuration once. */
public final class XaMassServerApplication {
    private XaMassServerApplication() {}

    public static void main(String[] args) {
        SpringApplication.run(new Class<?>[]{XaMassServerConfiguration.class, SmsProductConfiguration.class,
                ConsoleFrontendConfiguration.class}, args);
    }
}
