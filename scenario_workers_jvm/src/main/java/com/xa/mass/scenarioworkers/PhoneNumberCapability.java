package com.xa.mass.scenarioworkers;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberToCarrierMapper;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.workerdelivery.json.Jsons;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class PhoneNumberCapability {

    public static final String E164_EVENT_CODE = "phonenumber.e164";
    public static final String COUNTRY_EVENT_CODE =
            "phonenumber.country";
    public static final String ORIGINAL_CARRIER_EVENT_CODE =
            "phonenumber.original-carrier";
    public static final Set<String> EVENT_CODES = Set.of(
            E164_EVENT_CODE,
            COUNTRY_EVENT_CODE,
            ORIGINAL_CARRIER_EVENT_CODE
    );

    private static final PhoneNumberUtil PHONE_NUMBERS =
            PhoneNumberUtil.getInstance();
    private static final PhoneNumberToCarrierMapper CARRIERS =
            PhoneNumberToCarrierMapper.getInstance();

    private PhoneNumberCapability() {
    }

    public static List<WorkerEventDefinition<Map<String, Object>>>
    definitions(
            String workerId
    ) {
        return List.of(
                definition(
                        E164_EVENT_CODE,
                        workerId,
                        evaluation -> {
                            evaluation.result().put(
                                    "e164",
                                    PHONE_NUMBERS.format(
                                            evaluation.number(),
                                            PhoneNumberFormat.E164
                                    )
                            );
                        }
                ),
                definition(
                        COUNTRY_EVENT_CODE,
                        workerId,
                        evaluation -> {
                            PhoneNumber number = evaluation.number();
                            String regionCode =
                                    PHONE_NUMBERS
                                            .getRegionCodeForNumber(
                                                    number
                                            );
                            evaluation.result().put(
                                    "countryCallingCode",
                                    number.getCountryCode()
                            );
                            evaluation.result().put(
                                    "regionCode",
                                    regionCode
                            );
                            evaluation.result().put(
                                    "country",
                                    countryName(regionCode)
                            );
                        }
                ),
                definition(
                        ORIGINAL_CARRIER_EVENT_CODE,
                        workerId,
                        evaluation -> evaluation.result().put(
                                "originalCarrier",
                                CARRIERS.getNameForNumber(
                                        evaluation.number(),
                                        Locale.ENGLISH
                                )
                        )
                )
        );
    }

    private static WorkerEventDefinition<Map<String, Object>>
    definition(
            String eventCode,
            String workerId,
            ValidResultDecorator decorator
    ) {
        return WorkerEventDefinition.of(
                "TASK",
                eventCode,
                WorkerEventParameterResolvers.jsonMap(),
                payload -> Jsons.toJson(
                        execute(workerId, payload, decorator)
                )
        );
    }

    private static Map<String, Object> execute(
            String workerId,
            Map<String, Object> payload,
            ValidResultDecorator decorator
    ) {
        Evaluation evaluation = evaluate(workerId, payload);
        if (evaluation.valid()) {
            decorator.decorate(evaluation);
        }
        return evaluation.result();
    }

    private static Evaluation evaluate(
            String workerId,
            Map<String, Object> payload
    ) {
        Object rawValue = payload.get("rawNumber");
        if (!(rawValue instanceof String)
                || ((String) rawValue).trim().isEmpty()) {
            return Evaluation.invalid(
                    invalid(
                            workerId,
                            rawValue,
                            false,
                            "RAW_NUMBER_REQUIRED"
                    )
            );
        }

        String rawNumber = ((String) rawValue).trim();
        Object regionValue = payload.get("defaultRegion");
        if (regionValue != null && !(regionValue instanceof String)) {
            return Evaluation.invalid(
                    invalid(
                            workerId,
                            rawNumber,
                            false,
                            "DEFAULT_REGION_INVALID"
                    )
            );
        }
        String defaultRegion = regionValue == null
                || ((String) regionValue).isBlank()
                ? "ZZ"
                : ((String) regionValue).trim()
                        .toUpperCase(Locale.ROOT);

        try {
            PhoneNumber number = PHONE_NUMBERS.parse(
                    rawNumber,
                    defaultRegion
            );
            boolean possible = PHONE_NUMBERS.isPossibleNumber(number);
            boolean valid = PHONE_NUMBERS.isValidNumber(number);

            Map<String, Object> result = base(workerId, rawNumber);
            result.put("possible", possible);
            result.put("valid", valid);
            if (!valid) {
                result.put("error", "INVALID_PHONE_NUMBER");
                return Evaluation.invalid(result);
            }
            return new Evaluation(number, result, true);
        } catch (NumberParseException error) {
            return Evaluation.invalid(
                    invalid(
                            workerId,
                            rawNumber,
                            false,
                            "PARSE_" + error.getErrorType().name()
                    )
            );
        }
    }

    private static Map<String, Object> invalid(
            String workerId,
            Object input,
            boolean possible,
            String error
    ) {
        Map<String, Object> result = base(workerId, input);
        result.put("possible", possible);
        result.put("valid", false);
        result.put("error", error);
        return result;
    }

    private static Map<String, Object> base(
            String workerId,
            Object input
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("input", input);
        result.put("workerId", workerId);
        return result;
    }

    private static String countryName(String regionCode) {
        if (regionCode == null
                || regionCode.isBlank()
                || PhoneNumberUtil.REGION_CODE_FOR_NON_GEO_ENTITY
                        .equals(regionCode)) {
            return "";
        }
        return new Locale.Builder()
                .setRegion(regionCode)
                .build()
                .getDisplayCountry(Locale.ENGLISH);
    }

    @FunctionalInterface
    private interface ValidResultDecorator {

        void decorate(Evaluation evaluation);
    }

    private record Evaluation(
            PhoneNumber number,
            Map<String, Object> result,
            boolean valid
    ) {

        private static Evaluation invalid(
                Map<String, Object> result
        ) {
            return new Evaluation(null, result, false);
        }
    }
}
