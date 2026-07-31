package com.xa.mass.integration.phonenumber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class PhoneNumberSeedTest {

    @Test
    void seedContainsOneHundredDistinctValidExamples()
            throws Exception {
        List<String> numbers = Files.readAllLines(
                        Path.of("seed.txt"),
                        StandardCharsets.UTF_8
                ).stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();

        assertEquals(100, numbers.size());
        assertEquals(100, new LinkedHashSet<>(numbers).size());

        PhoneNumberUtil phoneNumbers = PhoneNumberUtil.getInstance();
        for (String rawNumber : numbers) {
            assertTrue(rawNumber.startsWith("+"), rawNumber);
            assertTrue(
                    phoneNumbers.isValidNumber(
                            phoneNumbers.parse(rawNumber, "ZZ")
                    ),
                    rawNumber
            );
        }
    }
}
