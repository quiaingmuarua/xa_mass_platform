package com.xa.mass.scenario.appchecks;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AppCheckCallerTest {
    static HttpHeaders headers(String user, String role) {
        var headers = new HttpHeaders();
        headers.set("X-XA-Dev-User", Base64.getUrlEncoder().withoutPadding().encodeToString(user.getBytes(StandardCharsets.UTF_8)));
        headers.set("X-XA-Dev-Role", role);
        return headers;
    }
    @Test void decodesUnicodeWithoutAnAccountDirectoryAndDoesNotDefaultMissingIdentity() {
        var input = headers("  外部调用方-A  ", "operator");
        var caller = AppCheckCaller.fromHeaders(input.getFirst("X-XA-Dev-User"), input.getFirst("X-XA-Dev-Role"));
        assertThat(caller.userId()).isEqualTo("外部调用方-A");
        assertThat(caller.canRead("外部调用方-a")).isFalse();
        assertThat(caller.canRead(null)).isFalse();
        for (String invalid : new String[]{null, "", " ", "***", "_w"})
            assertThatThrownBy(() -> AppCheckCaller.fromHeaders(invalid, "operator"))
                    .isInstanceOfSatisfying(AppCheckAccessException.class, error -> assertThat(error.status).isEqualTo(400));
        for (String invalid : new String[]{null, "", "ADMIN", "owner"})
            assertThatThrownBy(() -> new AppCheckCaller("proof", invalid)).isInstanceOf(AppCheckAccessException.class);
        assertThatThrownBy(() -> new AppCheckCaller(" ", "admin")).isInstanceOf(AppCheckAccessException.class);
        assertThatThrownBy(() -> new AppCheckCaller("x".repeat(129), "admin")).isInstanceOf(AppCheckAccessException.class);
    }
    @Test void refusesConfigurationBeforeStorageAndRejectsAmbiguousHeaders() {
        var tasks = mock(AppCheckTaskService.class);
        var store = mock(AppCheckConfigurationStore.class);
        var controller = new AppCheckController(tasks, store);
        var operator = headers("not-in-ui", "operator");
        assertThatThrownBy(() -> controller.configuration(operator)).isInstanceOfSatisfying(AppCheckAccessException.class,
                error -> assertThat(error.status).isEqualTo(403));
        assertThatThrownBy(() -> controller.saveConfiguration(operator, Map.of())).isInstanceOf(AppCheckAccessException.class);
        assertThatThrownBy(() -> controller.configuration(new HttpHeaders())).isInstanceOf(AppCheckAccessException.class);
        operator.add("X-XA-Dev-Role", "admin");
        assertThatThrownBy(() -> controller.configuration(operator)).isInstanceOfSatisfying(AppCheckAccessException.class,
                error -> assertThat(error.status).isEqualTo(400));
        verifyNoInteractions(store, tasks);
        controller.configuration(headers("arbitrary-admin", "admin"));
        verify(store).view();
        controller.catalog();
        verify(tasks).catalog();
    }
}
