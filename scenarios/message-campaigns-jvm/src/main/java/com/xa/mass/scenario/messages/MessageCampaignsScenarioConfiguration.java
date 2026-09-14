package com.xa.mass.scenario.messages;

import com.xa.mass.server.task.TaskCreationService;
import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.server.task.TaskLifecycleService;
import com.xa.mass.server.worker.group.WorkerGroupRegistrationService;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@Import(MessageController.class)
public class MessageCampaignsScenarioConfiguration {
    @Bean(destroyMethod = "close")
    CampaignService campaigns(WorkerGroupRegistrationService registrations, TaskCreationService creation,
            TaskDataService data, TaskLifecycleService lifecycle,
            @Qualifier("scenarioWorkerGroup") String workerGroupId,
            @Qualifier("scenarioWorkerEvents") List<String> events) {
        return new CampaignService(registrations, creation, data, lifecycle, workerGroupId, events);
    }
}
