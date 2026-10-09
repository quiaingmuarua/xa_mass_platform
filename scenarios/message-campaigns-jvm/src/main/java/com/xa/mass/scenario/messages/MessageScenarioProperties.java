package com.xa.mass.scenario.messages;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;
import java.util.HashSet;

/** Explicit environment binding; no shared String bean or default Group. */
@ConfigurationProperties(prefix = "xa.mass.scenarios.messages", ignoreUnknownFields = false)
public record MessageScenarioProperties(List<Application> applications) {
    public MessageScenarioProperties {
        if (applications == null || applications.isEmpty())
            throw new IllegalArgumentException("Messages applications must be explicitly configured");
        var ids = new HashSet<String>();
        var groups = new HashSet<String>();
        for (var application : applications)
            if (application == null || !ids.add(application.id()) || !groups.add(application.workerGroupId()))
                throw new IllegalArgumentException("Messages application and Group identities must be unique");
        applications = List.copyOf(applications);
    }

    public List<String> workerGroupIds() { return applications.stream().map(Application::workerGroupId).toList(); }

    public java.util.Optional<Application> findApplication(String id) {
        return applications.stream().filter(app -> app.id().equals(id)).findFirst();
    }

    public record Application(String id, String label, String workerGroupId) {
        public Application {
            if (id == null || id.isBlank() || id.length() > 128 || label == null || label.isBlank()
                    || workerGroupId == null || workerGroupId.isBlank())
                throw new IllegalArgumentException("Messages application id, label and worker-group-id must be present");
        }
    }
}
