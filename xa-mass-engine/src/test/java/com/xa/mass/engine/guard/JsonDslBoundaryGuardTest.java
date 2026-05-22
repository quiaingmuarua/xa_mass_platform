package com.xa.mass.engine.guard;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class JsonDslBoundaryGuardTest {

    private static final JavaClasses MAIN_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.xa.mass");

    @Test
    void mainlineMatchingPackagesMustNotDependOnLegacyJsonDslOrMonkeyFixtures() {
        noClasses()
                .that().resideInAnyPackage(
                        "com.xa.mass.engine.listener..",
                        "com.xa.mass.engine.strategy..",
                        "com.xa.mass.engine.rules..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.xa.mass.engine.monkey..",
                        "com.xa.mass.base.jsondsl..")
                .check(MAIN_CLASSES);
    }
}
