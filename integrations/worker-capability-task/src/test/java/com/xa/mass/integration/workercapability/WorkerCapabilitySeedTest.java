package com.xa.mass.integration.workercapability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkerCapabilitySeedTest {

    @Test
    void phoneSeedContainsOneHundredDistinctValidExamples()
            throws Exception {
        List<String> numbers = inputs("phone-seed.txt");

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

    @Test
    void stringSeedCoversAsciiCjkAndUnicode()
            throws Exception {
        List<String> values = inputs("string-seed.txt");

        assertTrue(values.size() >= 10);
        assertEquals(
                values.size(),
                new LinkedHashSet<>(values).size()
        );
        assertTrue(values.stream().anyMatch(value ->
                value.chars().allMatch(character ->
                        character < 128
                )
        ));
        assertTrue(values.stream().anyMatch(value ->
                value.codePoints().anyMatch(character ->
                        Character.UnicodeScript.of(character)
                                == Character.UnicodeScript.HAN
                )
        ));
        assertTrue(values.stream().anyMatch(value ->
                value.codePoints().anyMatch(character ->
                        character > Character.MAX_VALUE
                )
        ));
    }

    private static List<String> inputs(String filename)
            throws Exception {
        return Files.readAllLines(
                        Path.of(filename),
                        StandardCharsets.UTF_8
                ).stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }
}
