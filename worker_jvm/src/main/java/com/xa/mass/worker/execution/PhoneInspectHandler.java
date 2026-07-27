package com.xa.mass.worker.execution;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

public final class PhoneInspectHandler implements WorkerEventHandler {

    public static final String EVENT_CODE = "telecom.phone.inspect";
    private final PhoneNumberUtil phoneNumbers;
    private final JsonMapper json;

    public PhoneInspectHandler() {
        this(PhoneNumberUtil.getInstance(), JsonMapper.builder().build());
    }

    PhoneInspectHandler(PhoneNumberUtil phoneNumbers, JsonMapper json) {
        this.phoneNumbers = phoneNumbers;
        this.json = json;
    }

    @Override
    public JsonNode execute(JsonNode payload) throws WorkerInputException {
        JsonNode phoneNumberNode = payload.get("phoneNumber");
        if (phoneNumberNode == null || !phoneNumberNode.isTextual()) {
            throw new WorkerInputException(
                    "phoneNumber must be a string"
            );
        }
        return inspectInternationalPhoneNumber(phoneNumberNode.textValue());
    }

    public ObjectNode inspectInternationalPhoneNumber(String value) {
        try {
            PhoneNumber number = phoneNumbers.parse(value, null);
            ObjectNode result = json.createObjectNode();
            result.put("countryCallingCode", number.getCountryCode());
            result.put(
                    "e164",
                    phoneNumbers.format(
                            number,
                            PhoneNumberUtil.PhoneNumberFormat.E164
                    )
            );
            result.put("isPossible", phoneNumbers.isPossibleNumber(number));
            result.put("isValid", phoneNumbers.isValidNumber(number));
            putNullable(
                    result,
                    "regionCode",
                    phoneNumbers.getRegionCodeForNumber(number)
            );
            return result;
        } catch (NumberParseException error) {
            ObjectNode result = json.createObjectNode();
            result.putNull("countryCallingCode");
            result.putNull("e164");
            result.put("isPossible", false);
            result.put("isValid", false);
            result.putNull("regionCode");
            return result;
        }
    }

    private static void putNullable(
            ObjectNode target,
            String field,
            String value
    ) {
        if (value == null) {
            target.putNull(field);
        } else {
            target.put(field, value);
        }
    }
}
