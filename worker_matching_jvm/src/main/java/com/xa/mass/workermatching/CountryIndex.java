package com.xa.mass.workermatching;

/** Exact Base-26 country coordinate and 43-bit relative millisecond coordinate. */
final class CountryIndex {
    static final long TIME_SCALE = 1L << 43;
    static final long EPOCH_MILLIS = 946684800000L;

    private CountryIndex() { }

    static int code(String country) {
        if (country == null || country.length() != 2
                || country.charAt(0) < 'A' || country.charAt(0) > 'Z'
                || country.charAt(1) < 'A' || country.charAt(1) > 'Z') {
            throw new IllegalArgumentException("country must match [A-Z]{2}");
        }
        return (country.charAt(0) - 'A') * 26 + country.charAt(1) - 'A';
    }

    static long score(String country, long lastTakenMillis) {
        long relative = Math.subtractExact(lastTakenMillis, EPOCH_MILLIS);
        if (relative < 0 || relative >= TIME_SCALE) {
            throw new IllegalArgumentException("country index time is outside its 43-bit range");
        }
        return code(country) * TIME_SCALE + relative;
    }

    static int optionalCode(Object country) {
        try {
            return country instanceof String text ? code(text) : -1;
        } catch (IllegalArgumentException invalid) {
            return -1;
        }
    }
}
