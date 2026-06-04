package com.xa.mass.sdk.auth;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PrincipalContextScopeCompatibilityTest {

    @Test
    void emptyProjectAndEventScopesRemainTemporaryBroadAccessCompatibility() {
        PrincipalContext principal = PrincipalContext.builder()
                .principalId("legacy-empty-scope")
                .permissions(List.of(PrincipalContext.TASK_CREATE_PERMISSION))
                .projectScopes(List.of())
                .eventScopes(List.of())
                .build();

        assertThat(principal.allowsProject("anyProject")).isTrue();
        assertThat(principal.allowsEvent("any.event")).isTrue();
    }

    @Test
    void explicitWildcardScopeAlsoGrantsBroadAccess() {
        PrincipalContext principal = PrincipalContext.builder()
                .principalId("explicit-wildcard-scope")
                .permissions(List.of(PrincipalContext.TASK_CREATE_PERMISSION))
                .projectScopes(List.of(PrincipalContext.WILDCARD_SCOPE))
                .eventScopes(List.of(PrincipalContext.WILDCARD_SCOPE))
                .build();

        assertThat(principal.allowsProject("anyProject")).isTrue();
        assertThat(principal.allowsEvent("any.event")).isTrue();
    }

    @Test
    void boundedScopesRejectUnlistedProjectAndEvent() {
        PrincipalContext principal = PrincipalContext.builder()
                .principalId("bounded-scope")
                .permissions(List.of(PrincipalContext.TASK_CREATE_PERMISSION))
                .projectScopes(List.of("crawlerApp"))
                .eventScopes(List.of("crawler.fetch-page"))
                .build();

        assertThat(principal.allowsProject("crawlerApp")).isTrue();
        assertThat(principal.allowsProject("otherApp")).isFalse();
        assertThat(principal.allowsEvent("crawler.fetch-page")).isTrue();
        assertThat(principal.allowsEvent("other.event")).isFalse();
    }
}
