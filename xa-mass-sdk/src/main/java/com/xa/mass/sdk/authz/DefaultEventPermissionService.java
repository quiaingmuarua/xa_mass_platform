package com.xa.mass.sdk.authz;

import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.catalog.SdkMetadataCatalog;
import com.xa.mass.sdk.event.EventRequest;
import com.xa.mass.sdk.event.EventDefinition;

import java.util.Objects;

/**
 * Unified event permission service based on principal permissions and scopes.
 */
public class DefaultEventPermissionService implements EventPermissionService {

    private final SdkMetadataCatalog metadataCatalog;

    public DefaultEventPermissionService(SdkMetadataCatalog metadataCatalog) {
        this.metadataCatalog = Objects.requireNonNull(metadataCatalog, "metadataCatalog");
    }

    @Override
    public AuthorizationDecision authorize(PrincipalContext principal, EventRequest request) {
        Objects.requireNonNull(request, "request");
        String eventCode = request.getEvent().value();
        AuthorizationDecision catalogDecision = validateCatalogAndDescriptor(eventCode, request.getProject());
        if (!catalogDecision.isAllowed()) {
            return catalogDecision;
        }
        if (principal == null) {
            return AuthorizationDecision.deny("principal is required");
        }
        if (!principal.allowsEvent(eventCode)) {
            return AuthorizationDecision.deny("event not allowed for principal");
        }
        if (request.getProject() != null && !request.getProject().isBlank() && !principal.allowsProject(request.getProject())) {
            return AuthorizationDecision.deny("project not allowed for principal");
        }
        return AuthorizationDecision.allow();
    }

    private AuthorizationDecision validateCatalogAndDescriptor(String eventCode, String projectCode) {
        EventDefinition definition = metadataCatalog.getEvent(eventCode);
        if (definition != null) {
            if (!definition.isEnabled()) {
                return AuthorizationDecision.deny("event disabled: " + eventCode);
            }
            if (definition.getProjectCodes().isEmpty() && definition.getTaskModes().isEmpty()) {
                return AuthorizationDecision.allow();
            }
            if (!definition.getProjectCodes().isEmpty()
                    && (projectCode == null || projectCode.isBlank() || !definition.getProjectCodes().contains(projectCode))) {
                return AuthorizationDecision.deny("project does not support event: " + eventCode);
            }
            if (projectCode == null || projectCode.isBlank()) {
                return AuthorizationDecision.deny("project is required for event: " + eventCode);
            }
            return AuthorizationDecision.allow();
        }
        return AuthorizationDecision.deny("unknown event: " + eventCode);
    }
}
