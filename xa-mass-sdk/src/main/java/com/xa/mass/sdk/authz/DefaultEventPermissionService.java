package com.xa.mass.sdk.authz;

import com.xa.mass.sdk.catalog.SdkMetadataCatalog;
import com.xa.mass.sdk.event.EventPrincipal;
import com.xa.mass.sdk.event.EventRequest;
import com.xa.mass.sdk.event.EventDefinition;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Phase-1 event permission service based on client/user allow-list intersection.
 */
public class DefaultEventPermissionService implements EventPermissionService {

    private final ClientPermissionProvider clientPermissionProvider;
    private final UserPermissionProvider userPermissionProvider;
    private final SdkMetadataCatalog metadataCatalog;

    public DefaultEventPermissionService(ClientPermissionProvider clientPermissionProvider,
                                         UserPermissionProvider userPermissionProvider,
                                         SdkMetadataCatalog metadataCatalog) {
        this.clientPermissionProvider = Objects.requireNonNull(clientPermissionProvider, "clientPermissionProvider");
        this.userPermissionProvider = Objects.requireNonNull(userPermissionProvider, "userPermissionProvider");
        this.metadataCatalog = Objects.requireNonNull(metadataCatalog, "metadataCatalog");
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
