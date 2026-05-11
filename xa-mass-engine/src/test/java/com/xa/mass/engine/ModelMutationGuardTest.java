package com.xa.mass.engine;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.xa.mass.base.enums.task.TaskHoldReason;
import com.xa.mass.base.enums.task.TaskIntakeStatus;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ModelMutationGuardTest {

    private static final JavaClasses MAIN_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.xa.mass");

    @Test
    void mainlineCodeMustNotCallTaskDirectStatusSetters() {
        noClasses()
                .that().doNotHaveFullyQualifiedName(Task.class.getName())
                .should().callMethod(Task.class, "setStatus", TaskStatus.class)
                .check(MAIN_CLASSES);

        noClasses()
                .that().doNotHaveFullyQualifiedName(Task.class.getName())
                .should().callMethod(Task.class, "setTerminalReason", TaskTerminalReason.class)
                .check(MAIN_CLASSES);

        noClasses()
                .that().doNotHaveFullyQualifiedName(Task.class.getName())
                .should().callMethod(Task.class, "setOpenEnded", boolean.class)
                .check(MAIN_CLASSES);
    }

    @Test
    void taskLifecycleFieldsMustOnlyBeMutatedThroughExplicitOwners() {
        noClasses()
                .that().doNotHaveFullyQualifiedName(Task.class.getName())
                .and().doNotHaveFullyQualifiedName(TaskManager.class.getName())
                .and().doNotHaveFullyQualifiedName(TaskLifecycleService.class.getName())
                .should().callMethod(Task.class, "setIntakeStatus", TaskIntakeStatus.class)
                .check(MAIN_CLASSES);

        noClasses()
                .that().doNotHaveFullyQualifiedName(Task.class.getName())
                .and().doNotHaveFullyQualifiedName(TaskManager.class.getName())
                .and().doNotHaveFullyQualifiedName(TaskLifecycleService.class.getName())
                .and().doNotHaveFullyQualifiedName(TaskStateResolver.class.getName())
                .should().callMethod(Task.class, "sealIntake")
                .check(MAIN_CLASSES);

        noClasses()
                .that().doNotHaveFullyQualifiedName(Task.class.getName())
                .and().doNotHaveFullyQualifiedName(TaskLifecycleService.class.getName())
                .should().callMethod(Task.class, "setHoldReason", TaskHoldReason.class)
                .check(MAIN_CLASSES);
    }

    @Test
    void mainlineCodeMustNotDependOnLegacyTaskMessageModelsOrEnums() {
        noClasses()
                .should().dependOnClassesThat().resideInAnyPackage("com.xa.mass.base.enums.taskmsg..")
                .check(MAIN_CLASSES);

        noClasses()
                .should().dependOnClassesThat().haveFullyQualifiedName("com.xa.mass.base.model.TaskMsg")
                .check(MAIN_CLASSES);

        noClasses()
                .should().dependOnClassesThat().haveFullyQualifiedName("com.xa.mass.base.model.TaskMsgAttempt")
                .check(MAIN_CLASSES);

        noClasses()
                .should().dependOnClassesThat().haveFullyQualifiedName("com.xa.mass.base.model.TaskMessageSnapshot")
                .check(MAIN_CLASSES);
    }
}
