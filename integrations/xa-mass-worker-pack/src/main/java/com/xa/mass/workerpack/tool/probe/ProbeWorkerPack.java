package com.xa.mass.workerpack.tool.probe;

import com.google.gson.Gson;
import com.xa.mass.client.MassPlatform;
import com.xa.mass.client.payload.MassPayload;
import com.xa.mass.client.worker.WorkerGroupSpec;
import com.xa.mass.client.worker.WorkerAction;
import com.xa.mass.client.worker.WorkerRuntimeDefinition;
import com.xa.mass.client.worker.WorkerSpec;
import com.xa.mass.client.worker.handler.WorkerActionHandler;
import com.xa.mass.client.worker.handler.WorkerActionResult;
import com.xa.mass.client.worker.runtime.PollingWorkerRuntime;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class ProbeWorkerPack {
    public static final String PHONE_METADATA_EVENT = "probe.phone.metadata";
    public static final String URL_DNS_EVENT = "probe.url.dns";
    public static final String CSV_VALIDATE_EVENT = "probe.csv.validate";
    public static final String JSON_SCHEMA_EVENT = "probe.json.schema";

    public static final String PHONE_DEVICE_GROUP_ID = "phone-device-probe";
    public static final String PUBLIC_PROBE_GROUP_ID = "public-probe";
    public static final String DATA_QUALITY_GROUP_ID = "data-quality-probe";
    public static final String PROVIDER = "worker-pack-local-probe";
    private static final Gson RESULT_GSON = new Gson();

    private ProbeWorkerPack() {
    }

    public static WorkerGroupSpec phoneDeviceGroupSpec(List<String> projectCodes) {
        return group(PHONE_DEVICE_GROUP_ID, PHONE_METADATA_EVENT, projectCodes)
                .defaultAttribute("executionProfile", "phone-device")
                .defaultAttribute("provider", PROVIDER)
                .defaultAttribute("country", "SG")
                .build();
    }

    public static WorkerGroupSpec urlDnsGroupSpec(List<String> projectCodes) {
        return group(PUBLIC_PROBE_GROUP_ID, URL_DNS_EVENT, projectCodes)
                .defaultAttribute("provider", PROVIDER)
                .defaultAttribute("networkProfile", "offline-fixture")
                .build();
    }

    public static WorkerGroupSpec dataQualityGroupSpec(List<String> projectCodes) {
        return WorkerGroupSpec.builder()
                .groupId(DATA_QUALITY_GROUP_ID)
                .bindEvent(CSV_VALIDATE_EVENT, projectCodes == null ? List.of() : List.copyOf(projectCodes))
                .bindEvent(JSON_SCHEMA_EVENT, projectCodes == null ? List.of() : List.copyOf(projectCodes))
                .defaultAttribute("provider", PROVIDER)
                .defaultAttribute("validatorProfile", "local")
                .defaultMaxConcurrentWork(4)
                .build();
    }

    public static WorkerActionHandler phoneMetadataHandler() {
        return dispatch -> {
            ProbeEnvelope envelope = ProbeEnvelope.from(dispatch);
            String phoneNumber = firstText(dispatch, "phoneNumber", "phone");
            if (phoneNumber.isBlank()) {
                return businessFailure("PHONE_NUMBER_REQUIRED", "phoneNumber is required", envelope,
                        Map.of("valid", false));
            }
            String normalized = phoneNumber.replaceAll("[^+\\d]", "");
            PhonePreset preset = phonePreset(normalized);
            if (preset == null || !normalized.startsWith("+") || normalized.length() < 8) {
                return businessFailure("PHONE_METADATA_INVALID", "unsupported or invalid phone number", envelope,
                        Map.of("phoneNumber", phoneNumber, "normalizedPhoneNumber", normalized, "valid", false));
            }
            Map<String, Object> output = envelope.output();
            output.put("classification", "VALID_E164");
            output.put("phoneNumber", phoneNumber);
            output.put("normalizedPhoneNumber", normalized);
            output.put("e164", normalized);
            output.put("region", preset.region());
            output.put("countryCode", preset.countryCode());
            output.put("numberType", preset.numberType());
            output.put("possible", true);
            output.put("valid", true);
            output.put("provider", PROVIDER);
            copyIfPresent(output, dispatch, "defaultRegion", "requiredFingerprintProfile",
                    "requiredNetworkOperatorMccMnc");
            return success("phone metadata resolved", output);
        };
    }

    public static WorkerActionHandler urlDnsHandler() {
        return dispatch -> {
            ProbeEnvelope envelope = ProbeEnvelope.from(dispatch);
            String rawUrl = firstText(dispatch, "url", "targetUrl");
            if (rawUrl.isBlank()) {
                return businessFailure("URL_REQUIRED", "url is required", envelope, Map.of());
            }
            URI uri;
            try {
                uri = URI.create(rawUrl);
            } catch (IllegalArgumentException e) {
                return businessFailure("URL_INVALID", "url is invalid", envelope, Map.of("url", rawUrl));
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return businessFailure("URL_HOST_REQUIRED", "url host is required", envelope, Map.of("url", rawUrl));
            }
            Map<String, Object> output = envelope.output();
            output.put("url", rawUrl);
            output.put("scheme", uri.getScheme());
            output.put("host", host);
            output.put("provider", PROVIDER);
            if (host.endsWith(".invalid")) {
                return businessFailure("DNS_NXDOMAIN", "reserved invalid host does not resolve", envelope, output);
            }
            output.put("classification", "DNS_RESOLVED");
            output.put("resolved", true);
            output.put("addresses", List.of("fixture-" + Math.abs(host.toLowerCase(Locale.ROOT).hashCode() % 250)
                    + ".local"));
            return success("url dns resolved", output);
        };
    }

    public static WorkerActionHandler csvValidateHandler() {
        return dispatch -> {
            ProbeEnvelope envelope = ProbeEnvelope.from(dispatch);
            String csv = firstText(dispatch, "csv", "content");
            if (csv.isBlank()) {
                return businessFailure("CSV_REQUIRED", "csv is required", envelope, Map.of());
            }
            List<String> lines = csv.lines()
                    .map(String::trim)
                    .filter(line -> !line.isBlank())
                    .toList();
            if (lines.isEmpty()) {
                return businessFailure("CSV_EMPTY", "csv is empty", envelope, Map.of());
            }
            List<String> columns = splitCsvLine(lines.getFirst());
            List<String> requiredColumns = stringList(dispatch, "requiredColumns");
            List<String> missing = requiredColumns.stream()
                    .filter(column -> !columns.contains(column))
                    .toList();
            Map<String, Object> output = envelope.output();
            output.put("columns", columns);
            output.put("rowCount", Math.max(0, lines.size() - 1));
            output.put("provider", PROVIDER);
            if (!missing.isEmpty()) {
                output.put("missingColumns", missing);
                return businessFailure("CSV_INVALID", "csv missing required columns", envelope, output);
            }
            output.put("classification", "CSV_VALID");
            return success("csv valid", output);
        };
    }

    public static WorkerActionHandler jsonSchemaHandler() {
        return dispatch -> {
            ProbeEnvelope envelope = ProbeEnvelope.from(dispatch);
            Object payload = bodyPayload(dispatch).get("payload").orElse(null);
            if (!(payload instanceof Map<?, ?> payloadMap)) {
                return businessFailure("JSON_PAYLOAD_REQUIRED", "payload object is required", envelope, Map.of());
            }
            List<String> requiredFields = stringList(dispatch, "requiredFields");
            List<String> missing = requiredFields.stream()
                    .filter(field -> !payloadMap.containsKey(field))
                    .toList();
            Map<String, Object> output = envelope.output();
            output.put("fieldCount", payloadMap.size());
            output.put("requiredFields", requiredFields);
            output.put("provider", PROVIDER);
            if (!missing.isEmpty()) {
                output.put("missingFields", missing);
                return businessFailure("SCHEMA_INVALID", "payload missing required fields", envelope, output);
            }
            output.put("classification", "SCHEMA_VALID");
            return success("json schema valid", output);
        };
    }

    public static PhoneDevicePollingBuilder phoneDevicePolling(MassPlatform platform) {
        return new PhoneDevicePollingBuilder(platform);
    }

    private static WorkerGroupSpec.Builder group(String groupId, String eventCode, List<String> projectCodes) {
        return WorkerGroupSpec.builder()
                .groupId(groupId)
                .bindEvent(eventCode, projectCodes == null ? List.of() : List.copyOf(projectCodes))
                .defaultMaxConcurrentWork(4);
    }

    private static WorkerActionResult businessFailure(String errorCode, String detail, ProbeEnvelope envelope,
                                                Map<String, Object> output) {
        Map<String, Object> values = envelope.output();
        values.put("classification", errorCode);
        values.putAll(output);
        values.putIfAbsent("provider", PROVIDER);
        return WorkerActionResult.failure(errorCode, resultBody(detail, values));
    }

    private static WorkerActionResult success(String detail, Map<String, Object> output) {
        return WorkerActionResult.success(resultBody(detail, output));
    }

    private static String resultBody(String detail, Map<String, Object> output) {
        Map<String, Object> values = new LinkedHashMap<>(output == null ? Map.of() : output);
        if (detail != null && !detail.isBlank()) {
            values.put("detail", detail);
        }
        return RESULT_GSON.toJson(values);
    }

    private static String firstText(WorkerAction dispatch, String... keys) {
        MassPayload body = bodyPayload(dispatch);
        for (String key : keys) {
            String value = body.getString(key).map(String::trim).orElse("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static void copyIfPresent(Map<String, Object> output, WorkerAction dispatch, String... keys) {
        MassPayload body = bodyPayload(dispatch);
        for (String key : keys) {
            body.get(key).ifPresent(value -> output.put(key, value));
        }
    }

    private static List<String> stringList(WorkerAction dispatch, String key) {
        Object raw = bodyPayload(dispatch).get(key).orElse(List.of());
        if (raw instanceof List<?> values) {
            return values.stream()
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .toList();
        }
        String value = String.valueOf(raw).trim();
        if (value.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String item : value.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isBlank()) {
                values.add(trimmed);
            }
        }
        return List.copyOf(values);
    }

    private static List<String> splitCsvLine(String line) {
        List<String> values = new ArrayList<>();
        for (String value : line.split(",")) {
            values.add(value.trim());
        }
        return List.copyOf(values);
    }

    private static PhonePreset phonePreset(String normalized) {
        if (normalized.startsWith("+65")) {
            return new PhonePreset("SG", "65", "MOBILE");
        }
        if (normalized.startsWith("+1")) {
            return new PhonePreset("US", "1", "MOBILE");
        }
        if (normalized.startsWith("+86")) {
            return new PhonePreset("CN", "86", "MOBILE");
        }
        if (normalized.startsWith("+44")) {
            return new PhonePreset("GB", "44", "MOBILE");
        }
        if (normalized.startsWith("+81")) {
            return new PhonePreset("JP", "81", "MOBILE");
        }
        return null;
    }

    private record PhonePreset(String region, String countryCode, String numberType) {
    }

    private record ProbeEnvelope(String expectedOutcome, String traceLabel, long timeoutMs, long sleepMs) {
        static ProbeEnvelope from(WorkerAction dispatch) {
            MassPayload body = bodyPayload(dispatch);
            return new ProbeEnvelope(
                    body.getString("expectedOutcome").orElse("SUCCESS"),
                    body.getString("traceLabel").orElse(""),
                    longValue(dispatch, "timeoutMs", 0L),
                    longValue(dispatch, "sleepMs", 0L)
            );
        }

        Map<String, Object> output() {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("expectedOutcome", expectedOutcome);
            if (!traceLabel.isBlank()) {
                output.put("traceLabel", traceLabel);
            }
            if (timeoutMs > 0) {
                output.put("timeoutMs", timeoutMs);
            }
            if (sleepMs > 0) {
                output.put("sleepMs", sleepMs);
            }
            return output;
        }

        private static long longValue(WorkerAction dispatch, String key, long fallback) {
            return bodyPayload(dispatch).get(key)
                    .map(value -> {
                        if (value instanceof Number number) {
                            return number.longValue();
                        }
                        try {
                            return Long.parseLong(String.valueOf(value));
                        } catch (NumberFormatException e) {
                            return fallback;
                        }
                    })
                    .orElse(fallback);
        }
    }

    @SuppressWarnings("unchecked")
    private static MassPayload bodyPayload(WorkerAction dispatch) {
        String body = dispatch.body();
        if (body == null || body.isBlank()) {
            return MassPayload.of(Map.of());
        }
        Object decoded = RESULT_GSON.fromJson(body, Object.class);
        if (decoded instanceof Map<?, ?> values) {
            return MassPayload.of((Map<String, Object>) values);
        }
        return MassPayload.of(Map.of("rawBody", body));
    }

    public static final class PhoneDevicePollingBuilder {
        private final MassPlatform platform;
        private String workerId;
        private List<String> projectCodes = List.of("deviceProbe");
        private Map<String, String> attributes = defaultPhoneAttributes();
        private Duration pollInterval = Duration.ofMillis(50);
        private Duration heartbeatInterval = Duration.ofMillis(100);

        private PhoneDevicePollingBuilder(MassPlatform platform) {
            this.platform = Objects.requireNonNull(platform, "platform is required");
        }

        public PhoneDevicePollingBuilder workerId(String workerId) {
            this.workerId = workerId;
            return this;
        }

        public PhoneDevicePollingBuilder projectCodes(List<String> projectCodes) {
            this.projectCodes = projectCodes == null ? List.of() : List.copyOf(projectCodes);
            return this;
        }

        public PhoneDevicePollingBuilder attributes(Map<String, String> attributes) {
            this.attributes = attributes == null ? defaultPhoneAttributes() : new LinkedHashMap<>(attributes);
            return this;
        }

        public PhoneDevicePollingBuilder attribute(String key, String value) {
            this.attributes.put(key, value);
            return this;
        }

        public PhoneDevicePollingBuilder pollInterval(Duration pollInterval) {
            this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval is required");
            return this;
        }

        public PhoneDevicePollingBuilder heartbeatInterval(Duration heartbeatInterval) {
            this.heartbeatInterval = Objects.requireNonNull(heartbeatInterval, "heartbeatInterval is required");
            return this;
        }

        public PollingWorkerRuntime startPolling() {
            String resolvedWorkerId = requireText(workerId, "workerId");
            platform.workers().declareGroup(phoneDeviceGroupSpec(projectCodes));
            WorkerRuntimeDefinition definition = WorkerRuntimeDefinition.builder()
                    .workerId(resolvedWorkerId)
                    .workerGroupId(PHONE_DEVICE_GROUP_ID)
                    .attributes(attributes)
                    .eventHandler(PHONE_METADATA_EVENT, phoneMetadataHandler())
                    .build();
            platform.workers().registerWorker(WorkerSpec.polling(definition));
            return platform.workerRuntimes().polling(definition)
                    .pollInterval(pollInterval)
                    .heartbeatInterval(heartbeatInterval)
                    .start();
        }

        private static Map<String, String> defaultPhoneAttributes() {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("executionProfile", "phone-device");
            values.put("provider", PROVIDER);
            values.put("country", "SG");
            return values;
        }

        private static String requireText(String value, String fieldName) {
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(fieldName + " is required");
            }
            return value.trim();
        }
    }
}
