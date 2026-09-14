package com.xa.mass.scenario.sms;

import com.xa.mass.server.worker.group.WorkerGroupRegistrationService;
import com.xa.mass.server.task.call.TaskCallSubmissionService;
import com.xa.mass.server.task.TaskDataService;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@Import(SmsController.class)
public class SmsScenarioConfiguration {
    @Bean(destroyMethod = "close")
    ListenerService listenerService(WorkerGroupRegistrationService registrations,
            TaskCallSubmissionService submissions, TaskDataService results,
            @Qualifier("scenarioWorkerGroup") String workerGroupId,
            @Qualifier("scenarioWorkerEvents") List<String> events) {
        return new ListenerService(registrations, submissions, results, workerGroupId, events);
    }
}
