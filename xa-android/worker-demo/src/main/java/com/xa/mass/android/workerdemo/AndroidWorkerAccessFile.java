package com.xa.mass.android.workerdemo;

import android.content.Context;
import android.util.JsonReader;
import android.util.JsonToken;
import com.xa.mass.worker.runtime.WorkerConnectionOptions;
import com.xa.mass.worker.runtime.WorkerGatewayCredential;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads one private deployment input at process startup; it never changes Runtime coordinates. */
final class AndroidWorkerAccessFile {
    static final String FILE_NAME = "worker-access.json";
    static final int MAX_BYTES = 65_536;

    private AndroidWorkerAccessFile() { }

    static WorkerConnectionOptions load(Context context, URI runtimeApiBaseUrl, boolean required) {
        return load(runtimeApiBaseUrl, required, () -> new FileInputStream(
                new File(context.getNoBackupFilesDir(), FILE_NAME)));
    }

    static WorkerConnectionOptions load(URI runtimeApiBaseUrl, boolean required, Input input) {
        if (!required) return WorkerConnectionOptions.defaults();
        byte[] bytes = null;
        try (InputStream source = input.open()) {
            bytes = readBounded(source);
            String document = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            Map<String, String> values = parse(document);
            URI allowedOrigin = URI.create(values.get("allowedOrigin"));
            requireOrigin(allowedOrigin);
            WorkerGatewayCredential credential = WorkerGatewayCredential.of(values.get("keyId"), values.get("secret"));
            credential.validateRuntimeUri(runtimeApiBaseUrl);
            if (!allowedOrigin.getHost().equalsIgnoreCase(runtimeApiBaseUrl.getHost())
                    || effectivePort(allowedOrigin) != effectivePort(runtimeApiBaseUrl)) {
                throw new IllegalArgumentException();
            }
            return WorkerConnectionOptions.defaults().withGatewayCredential(credential);
        } catch (Exception unavailable) {
            // File, parser and URI causes can contain private input; keep only a fixed safe diagnostic.
            throw new IllegalStateException("Required Worker gateway credential file is unavailable or invalid");
        } finally {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
        }
    }

    private static byte[] readBounded(InputStream source) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = source.read(buffer)) != -1) {
                if (output.size() + count > MAX_BYTES) throw new IOException();
                output.write(buffer, 0, count);
            }
            if (output.size() == 0) throw new IOException();
            return output.toByteArray();
        }
    }

    private static Map<String, String> parse(String document) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(document))) {
            reader.setLenient(false);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) throw new IOException();
            reader.beginObject();
            Map<String, String> values = new LinkedHashMap<>();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!("allowedOrigin".equals(name) || "keyId".equals(name) || "secret".equals(name))
                        || values.containsKey(name) || reader.peek() != JsonToken.STRING) {
                    throw new IOException();
                }
                values.put(name, reader.nextString());
            }
            reader.endObject();
            if (values.size() != 3 || reader.peek() != JsonToken.END_DOCUMENT) throw new IOException();
            return values;
        }
    }

    private static void requireOrigin(URI origin) {
        String path = origin.getRawPath();
        if (!"https".equalsIgnoreCase(origin.getScheme()) || origin.getHost() == null
                || origin.getRawUserInfo() != null || origin.getRawQuery() != null || origin.getRawFragment() != null
                || (path != null && !path.isEmpty() && !"/".equals(path))
                || origin.getPort() == 0 || origin.getPort() < -1 || origin.getPort() > 65535) {
            throw new IllegalArgumentException();
        }
    }

    private static int effectivePort(URI uri) { return uri.getPort() == -1 ? 443 : uri.getPort(); }

    interface Input { InputStream open() throws IOException; }
}
