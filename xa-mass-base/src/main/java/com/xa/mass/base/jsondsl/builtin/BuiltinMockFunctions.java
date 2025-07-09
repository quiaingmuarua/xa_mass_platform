package com.xa.mass.base.jsondsl.builtin;

import com.github.javafaker.Faker;

public class BuiltinMockFunctions {

    static Faker faker = new Faker();

    //$RANDOM_NAME
    public static String randomName(Object obj) {
        return "randomName";

    }

    //$RANDOM_EMAIL
    public static String randomEmail(Object obj) {
        return "randomEmail";
    }

    //$RANDOM_PHONE_NUMBER
    public static String randomPhoneNumber(Object obj) {
        return "randomPhoneNumber";
    }
}
