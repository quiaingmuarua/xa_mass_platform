package com.xa.mass.server.api;

import com.xa.mass.server.api.v1.contract.ApiErrorResponse;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.user.UserDirectory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * The one front door for {@code /api/**}: every request carries exactly one {@code Authorization: Bearer} access
 * key, except the exact Worker and internal Adapter machine routes below. Their exposure stays with the gateway
 * Worker realm and loopback binding. Authorization of the admitted caller remains with each operation.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public final class AccessKeyFilter extends OncePerRequestFilter {
    public static final String CALLER_ATTRIBUTE = AccessKeyFilter.class.getName() + ".caller";
    private static final String SEGMENT = "[A-Za-z0-9_.:-]+";
    public static final List<Exemption> EXEMPTIONS = List.of(
            new Exemption("POST", "/api/v1/worker-groups/{workerGroupId}/workers:prepare"),
            new Exemption("POST", "/api/v1/worker-groups/{workerGroupId}/workers:prepare-batch"),
            new Exemption("POST", "/api/v1/worker-delivery/endpoint-managers/{endpointManagerId}/workers/{workerId}/commands:poll"),
            new Exemption("POST", "/api/v1/worker-delivery/endpoint-managers/{endpointManagerId}/workers/{workerId}/results"),
            new Exemption("POST", "/api/v1/worker-delivery/endpoint-managers/{endpointManagerId}/commands:consume"),
            new Exemption("POST", "/api/v1/worker-delivery/endpoint-managers/{endpointManagerId}/results:append"));

    private final UserDirectory directory;
    private final ObjectMapper mapper;

    public AccessKeyFilter(UserDirectory directory, ObjectMapper mapper) {
        this.directory = directory;
        this.mapper = mapper;
    }

    /** Exact method and route-template comparison, used by OpenAPI documentation. */
    public static boolean exemptTemplate(String method, String template) {
        return EXEMPTIONS.stream().anyMatch(exemption -> exemption.method().equalsIgnoreCase(method)
                && exemption.template().equals(template));
    }

    static boolean exemptRequest(String method, String path) {
        return EXEMPTIONS.stream().anyMatch(exemption -> exemption.method().equals(method)
                && exemption.pattern().matcher(path).matches());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.equals("/api") && !path.startsWith("/api/")) return true;
        return exemptRequest(request.getMethod(), path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        final TaskCaller caller;
        try {
            caller = directory.authenticate(bearer(request));
        } catch (ServerException failure) {
            if (failure.errorCode() != ServerErrorCode.UNAUTHENTICATED) throw failure;
            reject(request, response);
            return;
        }
        request.setAttribute(CALLER_ATTRIBUTE, caller);
        chain.doFilter(request, response);
    }

    private static String bearer(HttpServletRequest request) {
        var values = Collections.list(request.getHeaders("Authorization"));
        if (values.size() != 1) return null;
        String value = values.get(0);
        if (value == null || value.length() < 8 || !value.substring(0, 7).toLowerCase(Locale.ROOT).equals("bearer ")) return null;
        return value.substring(7);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Object requestId = request.getAttribute(RequestIdFilter.ATTRIBUTE_NAME);
        var body = new ApiErrorResponse(ServerErrorCode.UNAUTHENTICATED.code(), ServerErrorCode.UNAUTHENTICATED.defaultMessage(),
                requestId instanceof String value ? value : null, null);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(mapper.writeValueAsString(body));
    }

    public record Exemption(String method, String template, Pattern pattern) {
        Exemption(String method, String template) {
            this(method, template, Pattern.compile(String.join(SEGMENT,
                    java.util.Arrays.stream(template.split("\\{[^}]+}", -1)).map(Pattern::quote).toList())));
        }
    }
}
