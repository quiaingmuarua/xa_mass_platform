package com.xa.mass.scenario.messages;

import com.xa.mass.server.task.TaskCreationService;
import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.project.ProjectDefinition;
import com.xa.mass.server.project.ProjectWorkerRequirements;
import com.xa.mass.server.operation.OperationGuard;
import com.xa.mass.workermatching.QualifiedCountryDefinition;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import static com.xa.mass.scenario.messages.MessageWorkerSupply.*;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@Import(MessageController.class)
@EnableConfigurationProperties(MessageScenarioProperties.class)
public class MessageCampaignsScenarioConfiguration {
    @Bean
    ProjectDefinition messageProject(MessageScenarioProperties config) {
        return new ProjectDefinition(PROJECT, config.workerGroupIds(), null);
    }

    @Bean
    ProjectWorkerRequirements messageWorkerRequirements(MessageScenarioProperties config) {
        var groups = new java.util.LinkedHashMap<String, ProjectWorkerRequirements.Resources>();
        config.workerGroupIds().forEach(group -> groups.put(group,
                new ProjectWorkerRequirements.Resources(Set.of(EVENT), Set.of(POOL), Set.of(POOL_FUNCTION))));
        return new ProjectWorkerRequirements(PROJECT, groups);
    }

    @Bean
    QualifiedCountryDefinition messageWorkerQualification() {
        return new QualifiedCountryDefinition(POOL, POOL_FUNCTION, PHONE_FUNCTION,
                REQUIRED_PROPERTY, REQUIRED_VALUE, COUNTRY_PROPERTY);
    }

    @Bean(destroyMethod = "close")
    MessageTaskService messageTasks(ProjectDirectory projects, com.xa.mass.server.project.ProjectTaskQueryService queries, TaskCreationService creation,
            TaskDataService data,
            MessageScenarioProperties config, OperationGuard operations) {
        return new MessageTaskService(projects, queries, creation, data, config, operations);
    }
}
