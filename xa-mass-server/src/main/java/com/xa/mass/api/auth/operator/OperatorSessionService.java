package com.xa.mass.api.auth.operator;

import com.xa.mass.api.auth.OperatorAuthProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

public final class OperatorSessionService {

    public static final String COOKIE_NAME = "XA_MASS_OPERATOR_SESSION";
    public static final String CSRF_HEADER_NAME = "X-Mass-Csrf-Token";

    private final OperatorSessionStore sessionStore;
    private final OperatorAuthProperties authProperties;
    private final Duration ttl;
    private final String configuredCookieSecure;
    private final SecureRandom random = new SecureRandom();

    public OperatorSessionService(OperatorSessionStore sessionStore,
                                  OperatorAuthProperties authProperties,
                                  @Value("${mass.auth.operator.session.ttl:8h}") String ttl,
                                  @Value("${mass.auth.operator.session.cookie-secure:}")
                                  String configuredCookieSecure) {
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        this.authProperties = Objects.requireNonNull(authProperties, "authProperties");
        this.ttl = parseDuration(ttl);
        this.configuredCookieSecure = configuredCookieSecure == null ? "" : configuredCookieSecure.trim();
    }

    public OperatorSessionRecord createSession(String userId) {
        Instant now = Instant.now();
        OperatorSessionRecord session = new OperatorSessionRecord(
                UUID.randomUUID().toString(),
                userId,
                token(),
                now,
                now.plus(ttl),
                false
        );
        return sessionStore.save(session);
    }

    public OperatorSessionRecord resolve(HttpServletRequest request) {
        String sessionId = readCookie(request);
        if (sessionId == null) {
            return null;
        }
        OperatorSessionRecord session = sessionStore.get(sessionId);
        return session != null && session.active(Instant.now()) ? session : null;
    }

    public void revokeCurrent(HttpServletRequest request) {
        String sessionId = readCookie(request);
        if (sessionId != null) {
            sessionStore.revoke(sessionId);
        }
    }

    public boolean csrfMatches(HttpServletRequest request) {
        OperatorSessionRecord session = resolve(request);
        if (session == null) {
            return false;
        }
        String header = request.getHeader(CSRF_HEADER_NAME);
        return header != null && session.csrfToken().equals(header.trim());
    }

    public void writeSessionCookie(HttpServletResponse response, OperatorSessionRecord session) {
        response.addHeader("Set-Cookie", ResponseCookie.from(COOKIE_NAME, session.sessionId())
                .httpOnly(true)
                .secure(cookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(ttl)
                .build()
                .toString());
    }

    public void clearSessionCookie(HttpServletResponse response) {
        response.addHeader("Set-Cookie", ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(cookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build()
                .toString());
    }

    private boolean cookieSecure() {
        if (!configuredCookieSecure.isBlank()) {
            return Boolean.parseBoolean(configuredCookieSecure);
        }
        return false;
    }

    private String readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue().trim();
            }
        }
        return null;
    }

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private Duration parseDuration(String value) {
        if (value == null || value.isBlank()) {
            return Duration.ofHours(8);
        }
        String trimmed = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (trimmed.endsWith("ms")) {
            return Duration.ofMillis(Long.parseLong(trimmed.substring(0, trimmed.length() - 2)));
        }
        if (trimmed.endsWith("s")) {
            return Duration.ofSeconds(Long.parseLong(trimmed.substring(0, trimmed.length() - 1)));
        }
        if (trimmed.endsWith("m")) {
            return Duration.ofMinutes(Long.parseLong(trimmed.substring(0, trimmed.length() - 1)));
        }
        if (trimmed.endsWith("h")) {
            return Duration.ofHours(Long.parseLong(trimmed.substring(0, trimmed.length() - 1)));
        }
        if (trimmed.endsWith("d")) {
            return Duration.ofDays(Long.parseLong(trimmed.substring(0, trimmed.length() - 1)));
        }
        return Duration.parse(value);
    }
}
