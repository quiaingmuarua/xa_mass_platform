package com.xa.mass.server.workerassembly.phonenumber;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberToCarrierMapper;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.workerdelivery.json.Jsons;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class PhoneNumberCapability {

    public static final String EVENT_CODE = "phonenumber.lookup";

    private static final PhoneNumberUtil PHONE_NUMBERS =
            PhoneNumberUtil.getInstance();
    private static final PhoneNumberToCarrierMapper CARRIERS =
            PhoneNumberToCarrierMapper.getInstance();

    private PhoneNumberCapability() {
    }

    static WorkerEventDefinition<Map<String, Object>> definition(
            String workerId
    ) {
        return WorkerEventDefinition.of(
                "TASK",
                EVENT_CODE,
                WorkerEventParameterResolvers.jsonMap(),
                payload -> Jsons.toJson(lookup(workerId, payload))
        );
    }

    static Map<String, Object> lookup(
            String workerId,
            Map<String, Object> payload
    ) {
        Object rawValue = payload.get("rawNumber");
        if (!(rawValue instanceof String)
                || ((String) rawValue).trim().isEmpty()) {
            return invalid(
                    workerId,
                    rawValue,
                    false,
                    "RAW_NUMBER_REQUIRED"
            );
        }

        String rawNumber = ((String) rawValue).trim();
        Object regionValue = payload.get("defaultRegion");
        if (regionValue != null && !(regionValue instanceof String)) {
            return invalid(
                    workerId,
                    rawNumber,
                    false,
                    "DEFAULT_REGION_INVALID"
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
            result.put(
                    "e164",
                    PHONE_NUMBERS.format(number, PhoneNumberFormat.E164)
            );
            result.put("countryCallingCode", number.getCountryCode());
            if (!valid) {
                result.put("error", "INVALID_PHONE_NUMBER");
                return result;
            }

            String regionCode =
                    PHONE_NUMBERS.getRegionCodeForNumber(number);
            result.put("regionCode", regionCode);
            result.put("country", countryName(regionCode));
            result.put(
                    "numberType",
                    PHONE_NUMBERS.getNumberType(number).name()
            );
            result.put(
                    "originalCarrier",
                    CARRIERS.getNameForNumber(
                            number,
                            Locale.ENGLISH
                    )
            );
            return result;
        } catch (NumberParseException error) {
            return invalid(
                    workerId,
                    rawNumber,
                    false,
                    "PARSE_" + error.getErrorType().name()
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
}
