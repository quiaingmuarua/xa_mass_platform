package com.xa.mass.sdk.authz;

import com.xa.mass.sdk.catalog.EventMetadata;
import com.xa.mass.sdk.catalog.ProjectEventCatalog;
import com.xa.mass.sdk.catalog.ProjectMetadata;
import com.xa.mass.sdk.event.EventPrincipal;
import com.xa.mass.sdk.event.EventRequest;
import com.xa.mass.sdk.event.SdkEventDefinition;
import com.xa.mass.sdk.event.SdkEventDefinitionRegistry;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Phase-1 event permission service based on client/user allow-list intersection.
 */
public class DefaultEventPermissionService implements EventPermissionService {

    private final ClientPermissionProvider clientPermissionProvider;
    private final UserPermissionProvider userPermissionProvider;
    private final ProjectEventCatalog projectEventCatalog;
    private final SdkEventDefinitionRegistry eventDefinitionRegistry;

    public DefaultEventPermissionService(ClientPermissionProvider clientPermissionProvider,
                                         UserPermissionProvider userPermissionProvider,
                                         ProjectEventCatalog projectEventCatalog,
                                         SdkEventDefinitionRegistry eventDefinitionRegistry) {
        this.clientPermissionProvider = Objects.requireNonNull(clientPermissionProvider, "clientPermissionProvider");
        this.userPermissionProvider = Objects.requireNonNull(userPermissionProvider, "userPermissionProvider");
        this.projectEventCatalog = Objects.requireNonNull(projectEventCatalog, "projectEventCatalog");
        this.eventDefinitionRegistry = Objects.requireNonNull(eventDefinitionRegistry, "eventDefinitionRegistry");
    }

    @Override
    public AuthorizationDecision authorize(EventPrincipal principal, EventRequest request) {
        Objects.requireNonNull(request, "request");
        String eventCode = request.getEvent().value();
        AuthorizationDecision catalogDecision = validateCatalogAndDescriptor(eventCode, request.getProject());
        if (!catalogDecision.isAllowed()) {
            return catalogDecision;
        }

        Set<String> clientAllowed = clientPermissionProvider.allowedEventCodes(
                principal == null ? null : principal.getClientId()
        );
        Set<String> userAllowed = userPermissionProvider.allowedEventCodes(
                principal == null ? null : principal.getUserId()
        );
        if (clientAllowed.isEmpty() || userAllowed.isEmpty()) {
            return AuthorizationDecision.deny("event not allowed for principal");
        }
        Set<String> intersection = new HashSet<>(clientAllowed);
        intersection.retainAll(userAllowed);
        if (!intersection.contains(eventCode)) {
            return AuthorizationDecision.deny("event not allowed for principal");
        }
        return AuthorizationDecision.allow();
    }

    private AuthorizationDecision validateCatalogAndDescriptor(String eventCode, String projectCode) {
        SdkEventDefinition definition = eventDefinitionRegistry.get(eventCode);
        if (definition != null) {
            if (!definition.getMetadata().isEnabled()) {
                return AuthorizationDecision.deny("event disabled: " + eventCode);
            }
            if (definition.hasHandler()) {
                if (!definition.getProjectCodes().isEmpty()
                        && (projectCode == null || projectCode.isBlank() || !definition.getProjectCodes().contains(projectCode))) {
                    return AuthorizationDecision.deny("project does not support event: " + eventCode);
                }
                return AuthorizationDecision.allow();
            }
            if (projectCode == null || projectCode.isBlank()) {
                return AuthorizationDecision.deny("project is required for catalog event: " + eventCode);
            }
            if (definition.getProjectCodes().isEmpty() || !definition.getProjectCodes().contains(projectCode)) {
                return AuthorizationDecision.deny("project does not support event: " + eventCode);
            }
            return AuthorizationDecision.allow();
        }

        EventMetadata eventMetadata = projectEventCatalog.getEvent(eventCode);
        if (eventMetadata == null) {
            return AuthorizationDecision.deny("unknown event: " + eventCode);
        }
        if (!eventMetadata.isEnabled()) {
            return AuthorizationDecision.deny("event disabled: " + eventCode);
        }
        if (projectCode == null || projectCode.isBlank()) {
            return AuthorizationDecision.deny("project is required for catalog event: " + eventCode);
        }
        ProjectMetadata projectMetadata = projectEventCatalog.getProject(projectCode);
        if (projectMetadata == null) {
            return AuthorizationDecision.deny("unknown project: " + projectCode);
        }
        if (!projectMetadata.isEnabled()) {
            return AuthorizationDecision.deny("project disabled: " + projectCode);
        }
        if (!projectMetadata.getEventCodes().contains(eventCode)) {
            return AuthorizationDecision.deny("project does not support event: " + eventCode);
        }
        return AuthorizationDecision.allow();
    }
}
