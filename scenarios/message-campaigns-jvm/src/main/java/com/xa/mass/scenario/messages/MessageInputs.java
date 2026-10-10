package com.xa.mass.scenario.messages;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Pure business input rules. Validation does not normalize template content. */
final class MessageInputs {
    static final List<String> COUNTRIES = List.of("CN", "US", "GB");
    private static final Map<String, String> PREFIXES = Map.of("CN", "+86", "US", "+1", "GB", "+44");
    private static final Pattern NUMBER = Pattern.compile("\\+[1-9][0-9]{1,14}");

    private MessageInputs() {}

    static String text(Map<String, Object> input, String key, int max) {
        if (!(input.get(key) instanceof String value) || value.isBlank() || value.length() > max)
            throw new MessageError(400, "Invalid " + key, null);
        return value;
    }

    /** Caller handles blank rows and retains the original row number for diagnostics. */
    static String recipient(String value, String country) {
        String number = value.startsWith("+") ? value : "+" + value;
        String prefix = PREFIXES.get(country);
        return NUMBER.matcher(number).matches() && prefix != null && number.startsWith(prefix)
                && number.length() > prefix.length() ? number : null;
    }
}
