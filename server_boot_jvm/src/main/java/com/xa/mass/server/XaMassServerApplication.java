package com.xa.mass.server;

import com.xa.mass.serverboot.ServerBootConfiguration;
import org.springframework.boot.SpringApplication;

/** The sole executable Server entry; preview adds both business scenarios. */
public final class XaMassServerApplication {
    private XaMassServerApplication() {}

    public static void main(String[] args) {
        SpringApplication.run(ServerBootConfiguration.class, args);
    }
}
