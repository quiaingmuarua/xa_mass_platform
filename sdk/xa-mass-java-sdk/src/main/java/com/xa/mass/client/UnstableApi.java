package com.xa.mass.client;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks public SDK surface that is available for advanced diagnostics or
 * temporary route coverage but is not part of the stable compatibility
 * contract.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface UnstableApi {
    String value() default "advanced unstable API";
}
