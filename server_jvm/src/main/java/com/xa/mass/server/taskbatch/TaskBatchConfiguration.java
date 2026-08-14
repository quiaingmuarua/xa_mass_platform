package com.xa.mass.server.taskbatch;

import com.xa.mass.server.taskdata.TaskDataService;
import com.xa.mass.server.taskdata.WorkerGroupTaskCatalog;
import java.io.IOException;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("scenario-workers")
@EnableConfigurationProperties(TaskBatchProperties.class)
public class TaskBatchConfiguration {

    @Bean
    TaskBatchFileStore taskBatchFileStore(
            TaskBatchProperties properties
    ) throws IOException {
        return TaskBatchFileStore.open(properties);
    }

    @Bean
    Clock taskBatchClock() {
        return Clock.systemUTC();
    }

    @Bean
    TaskBatchService taskBatchService(
            TaskBatchFileStore files,
            TaskDataService taskData,
            WorkerGroupTaskCatalog taskCatalog,
            TaskBatchProperties properties,
            Clock taskBatchClock
    ) {
        return new TaskBatchService(
                files,
                taskData,
                taskCatalog,
                properties,
                taskBatchClock
        );
    }
}
