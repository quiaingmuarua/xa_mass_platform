package com.xa.mass.android.workerdemo;

import com.xa.mass.worker.runtime.WorkerConnectionOptions;
import com.xa.mass.worker.runtime.WorkerGatewayCredential;
import com.xa.mass.workerdelivery.json.Jsons;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** One immutable Host connection selection. Never expose its serialized form to UI or HTTP. */
final class AndroidConnectionSettings {
    private final URI runtimeApiBaseUrl;
    private final WorkerGatewayCredential credential;
    private final String secret;

    private AndroidConnectionSettings(URI runtimeApiBaseUrl, WorkerGatewayCredential credential, String secret) {
        this.runtimeApiBaseUrl = Objects.requireNonNull(runtimeApiBaseUrl, "Runtime address must be present");
        this.credential = credential;
        this.secret = secret;
    }

    static AndroidConnectionSettings defaults(URI runtimeApiBaseUrl) {
        return new AndroidConnectionSettings(runtimeApiBaseUrl, null, null);
    }

    static AndroidConnectionSettings authenticated(String runtimeUrl, String keyId, String secret) {
        URI runtime;
        try {
            runtime = URI.create(Objects.requireNonNull(runtimeUrl).trim());
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Runtime address must be a valid HTTPS URI");
        }
        WorkerGatewayCredential credential = WorkerGatewayCredential.of(keyId, secret);
        credential.validateRuntimeUri(runtime);
        return new AndroidConnectionSettings(runtime, credential, secret);
    }

    URI runtimeApiBaseUrl() { return runtimeApiBaseUrl; }
    String keyId() { return credential == null ? null : credential.keyId(); }
    boolean hasGatewayCredential() { return credential != null; }

    WorkerConnectionOptions connectionOptions() {
        return credential == null ? WorkerConnectionOptions.defaults()
                : WorkerConnectionOptions.defaults().withGatewayCredential(credential);
    }

    boolean sameConnection(AndroidConnectionSettings other) {
        return other != null && runtimeApiBaseUrl.equals(other.runtimeApiBaseUrl)
                && Objects.equals(keyId(), other.keyId()) && Objects.equals(secret, other.secret);
    }

    /** Sensitive plaintext exists only at the encrypted-store boundary. */
    byte[] encodeForStorage() {
        if (credential == null) throw new IllegalArgumentException("Only authenticated settings can be saved");
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("runtimeApiBaseUrl", runtimeApiBaseUrl.toString());
        fields.put("keyId", credential.keyId());
        fields.put("secret", secret);
        return Jsons.toJson(fields).getBytes(StandardCharsets.UTF_8);
    }

    static AndroidConnectionSettings decodeFromStorage(byte[] plaintext) {
        try {
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(plaintext)).toString();
            Map<String, Object> fields = Jsons.parseObject(json);
            if (fields.size() != 3 || !(fields.get("runtimeApiBaseUrl") instanceof String)
                    || !(fields.get("keyId") instanceof String) || !(fields.get("secret") instanceof String)) {
                throw new IllegalArgumentException("Invalid settings fields");
            }
            return authenticated((String) fields.get("runtimeApiBaseUrl"),
                    (String) fields.get("keyId"), (String) fields.get("secret"));
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Saved connection settings are invalid");
        }
    }

    @Override public String toString() {
        return "AndroidConnectionSettings[gateway=" + (credential == null ? "absent" : "configured") + "]";
    }
}
