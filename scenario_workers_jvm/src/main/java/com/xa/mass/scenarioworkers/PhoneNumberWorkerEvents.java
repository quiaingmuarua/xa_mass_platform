package com.xa.mass.scenarioworkers;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberToCarrierMapper;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventHandler;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.workerdelivery.json.Jsons;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class PhoneNumberWorkerEvents {

    static final String E164_EVENT_CODE =
            "extension.worker.phonenumber.e164";
    static final String COUNTRY_EVENT_CODE =
            "extension.worker.phonenumber.country";
    static final String ORIGINAL_CARRIER_EVENT_CODE =
            "extension.worker.phonenumber.original-carrier";

    private static final String E164_CAPABILITY = "phonenumber.e164";
    private static final String COUNTRY_CAPABILITY = "phonenumber.country";
    private static final String ORIGINAL_CARRIER_CAPABILITY =
            "phonenumber.original-carrier";

    private static final PhoneNumberUtil PHONE_NUMBERS =
            PhoneNumberUtil.getInstance();
    private static final PhoneNumberToCarrierMapper CARRIERS =
            PhoneNumberToCarrierMapper.getInstance();

    private PhoneNumberWorkerEvents() {
    }

    static List<WorkerEventDefinition<?>> definitions() {
        return List.of(
                definition(E164_CAPABILITY, PhoneNumberWorkerEvents::e164),
                definition(
                        COUNTRY_CAPABILITY,
                        PhoneNumberWorkerEvents::country
                ),
                definition(
                        ORIGINAL_CARRIER_CAPABILITY,
                        PhoneNumberWorkerEvents::originalCarrier
                )
        );
    }

    private static WorkerEventDefinition<Map<String, Object>> definition(
            String capabilityName,
            WorkerEventHandler<Map<String, Object>> handler
    ) {
        return WorkerEventDefinition.extension(
                capabilityName,
                WorkerEventParameterResolvers.jsonMap(),
                handler
        );
    }

    private static String e164(Map<String, Object> payload) {
        Evaluation evaluation = evaluate(payload);
        if (evaluation.valid()) {
            evaluation.result().put(
                    "e164",
                    PHONE_NUMBERS.format(
                            evaluation.number(),
                            PhoneNumberFormat.E164
                    )
            );
        }
        return Jsons.toJson(evaluation.result());
    }

    private static String country(Map<String, Object> payload) {
        Evaluation evaluation = evaluate(payload);
        if (evaluation.valid()) {
            PhoneNumber number = evaluation.number();
            String regionCode = PHONE_NUMBERS.getRegionCodeForNumber(number);
            evaluation.result().put(
                    "countryCallingCode",
                    number.getCountryCode()
            );
            evaluation.result().put("regionCode", regionCode);
            evaluation.result().put("country", countryName(regionCode));
        }
        return Jsons.toJson(evaluation.result());
    }

    private static String originalCarrier(Map<String, Object> payload) {
        Evaluation evaluation = evaluate(payload);
        if (evaluation.valid()) {
            evaluation.result().put(
                    "originalCarrier",
                    CARRIERS.getNameForNumber(
                            evaluation.number(),
                            Locale.ENGLISH
                    )
            );
        }
        return Jsons.toJson(evaluation.result());
    }

    private static Evaluation evaluate(Map<String, Object> payload) {
        Object rawValue = payload.get("rawNumber");
        if (!(rawValue instanceof String)
                || ((String) rawValue).trim().isEmpty()) {
            return Evaluation.invalid(
                    invalid(rawValue, false, "RAW_NUMBER_REQUIRED")
            );
        }

        String rawNumber = ((String) rawValue).trim();
        Object regionValue = payload.get("defaultRegion");
        if (regionValue != null && !(regionValue instanceof String)) {
            return Evaluation.invalid(
                    invalid(
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

            Map<String, Object> result = base(rawNumber);
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
                            rawNumber,
                            false,
                            "PARSE_" + error.getErrorType().name()
                    )
            );
        }
    }

    private static Map<String, Object> invalid(
            Object input,
            boolean possible,
            String error
    ) {
        Map<String, Object> result = base(input);
        result.put("possible", possible);
        result.put("valid", false);
        result.put("error", error);
        return result;
    }

    private static Map<String, Object> base(Object input) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("input", input);
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

    private record Evaluation(
            PhoneNumber number,
            Map<String, Object> result,
            boolean valid
    ) {

        private static Evaluation invalid(Map<String, Object> result) {
            return new Evaluation(null, result, false);
        }
    }
}
