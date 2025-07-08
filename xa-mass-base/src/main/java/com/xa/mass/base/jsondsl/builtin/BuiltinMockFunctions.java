package com.xa.mass.base.jsondsl.builtin;

import com.github.javafaker.Faker;

import java.util.Random;

public class BuiltinMockFunctions {

   static Faker faker = new Faker();

    //$RANDOM_NAME
    public static String randomName() {
        return faker.name().fullName();

    }

    //$RANDOM_EMAIL
    public static String randomEmail() {
        return faker.internet().emailAddress();
    }

    //$RANDOM_PHONE_NUMBER
    public static String randomPhoneNumber() {
        return faker.phoneNumber().cellPhone();
    }
}
