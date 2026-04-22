package com.xa.mass.api.console;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;

@Component
public class FrontendConsoleRoutingService {

    private static final Map<String, String> LEGACY_ROUTE_MAPPING = Map.of(
            "/status", "/",
            "/status/", "/",
            "/status/tasks", "/tasks",
            "/status/workers", "/resources/workers",
            "/status/rules", "/resources/rules",
            "/config", "/resources/configs"
    );

    @Value("${mass.frontend.console-url:http://localhost:4173/}")
    private String frontendConsoleUrl;

    @Value("${mass.frontend.dist-path:frontend/dist}")
    private String frontendDistPath;

    public String resolveSpaPath(String requestUri) {
        return LEGACY_ROUTE_MAPPING.getOrDefault(requestUri, requestUri);
    }

    public Optional<Resource> loadLocalIndexHtml() {
        Path indexPath = getLocalIndexPath();
        if (!Files.isRegularFile(indexPath)) {
            return Optional.empty();
        }
        return Optional.of(new FileSystemResource(indexPath));
    }

    public boolean hasLocalBuild() {
        return Files.isRegularFile(getLocalIndexPath());
    }

    public String getLocalDistRootResourceLocation() {
        return normalizeDirectoryUri(Paths.get(frontendDistPath).toAbsolutePath().normalize());
    }

    public URI resolveExternalConsoleUri(String requestUri) {
        String normalizedBaseUrl = normalizeBaseUrl(frontendConsoleUrl);
        if (normalizedBaseUrl == null) {
            return null;
        }

        String spaPath = resolveSpaPath(requestUri);
        String relativePath = spaPath.startsWith("/") ? spaPath.substring(1) : spaPath;
        return URI.create(normalizedBaseUrl).resolve(relativePath);
    }

    private Path getLocalIndexPath() {
        return Paths.get(frontendDistPath).resolve("index.html").toAbsolutePath().normalize();
    }

    private String normalizeDirectoryUri(Path directory) {
        String uri = directory.toUri().toString();
        return uri.endsWith("/") ? uri : uri + "/";
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        String trimmed = baseUrl.trim();
        return trimmed.endsWith("/") ? trimmed : trimmed + "/";
    }
}
