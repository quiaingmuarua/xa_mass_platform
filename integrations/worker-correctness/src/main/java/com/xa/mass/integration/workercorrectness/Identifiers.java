package com.xa.mass.integration.workercorrectness;

final class Identifiers {

    private Identifiers() {
    }

    static String require(String value, String name) {
        if (value == null
                || !value.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(
                    name + " must match [A-Za-z0-9._-]+"
            );
        }
        return value;
    }
}
