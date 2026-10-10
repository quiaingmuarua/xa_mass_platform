package com.xa.mass.scenario.sms;

import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.task.call.TaskRpcCallService;
import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.server.task.observation.TaskLeaseProjection;
import com.xa.mass.server.task.observation.TaskLeaseProjectionMetrics;
import com.xa.mass.workermatching.PartitionedLeasePoolDefinition;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Qualifier;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@Import(SmsController.class)
public class SmsScenarioConfiguration {
    @Bean(destroyMethod = "close")
    SmsReceptionService smsReceptionService(ProjectDirectory projects,
            TaskRpcCallService calls, TaskDataService results, TaskLeaseProjectionMetrics projections,
            @Qualifier("scenarioWorkerGroup") String workerGroupId) {
        return new SmsReceptionService(projects, calls, results, projections, workerGroupId, Clock.systemUTC());
    }
    @Bean PartitionedLeasePoolDefinition smsPoolDefinition() {
        return new PartitionedLeasePoolDefinition(SmsReceptionService.POOL, SmsReceptionService.FUNCTION,
                "phone", "country", SmsReceptionService.TEMPLATES.keySet());
    }
    @Bean TaskLeaseProjection smsLeaseProjection(@Qualifier("scenarioWorkerGroup") String workerGroupId) {
        return new TaskLeaseProjection("sms", workerGroupId, SmsReceptionService.POOL, SmsReceptionService::leaseProjection);
    }
}
