package com.xa.mass.scenario.sms;

import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.task.call.TaskCallSubmissionService;
import com.xa.mass.server.task.TaskDataService;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@Import(SmsController.class)
public class SmsScenarioConfiguration {
    @Bean(destroyMethod = "close")
    ListenerService listenerService(ProjectDirectory projects,
            TaskCallSubmissionService submissions, TaskDataService results,
            @Qualifier("scenarioWorkerGroup") String workerGroupId) {
        return new ListenerService(projects, submissions, results, workerGroupId);
    }
}
