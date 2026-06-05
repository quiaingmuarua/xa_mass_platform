package com.xa.mass.server.config;

import com.xa.mass.storage.jdbc.JdbcStorageRuntime;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile({"dev", "prod"})
public class ServerControlPlaneMigrationConfiguration {

    @Bean
    public ServerControlPlaneMigrationRunner serverControlPlaneMigrationRunner(JdbcStorageRuntime jdbcStorageRuntime) {
        ServerControlPlaneMigrationRunner runner = new ServerControlPlaneMigrationRunner(jdbcStorageRuntime);
        runner.migrate();
        return runner;
    }
}
