package com.xa.mass.workermatching;

/** Immutable startup binding for a string-qualified country Pool and independent Phone query. */
public record QualifiedCountryDefinition(
        String poolName,
        String poolFunctionName,
        String phoneFunctionName,
        String requiredProperty,
        String requiredValue,
        String countryProperty
) {
    public QualifiedCountryDefinition {
        requireName(poolName); requireName(poolFunctionName); requireName(phoneFunctionName);
        requireName(requiredProperty); requireName(requiredValue); requireName(countryProperty);
        if (poolFunctionName.equals(phoneFunctionName))
            throw new IllegalArgumentException("Pool and Phone function names must differ");
    }

    private static void requireName(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("non-blank name or value required");
    }
}
