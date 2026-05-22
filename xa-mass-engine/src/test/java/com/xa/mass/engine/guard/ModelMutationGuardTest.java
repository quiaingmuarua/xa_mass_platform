package com.xa.mass.engine.guard;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.xa.mass.base.enums.task.TaskHoldReason;
import com.xa.mass.base.enums.task.TaskIntakeStatus;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.TaskManager;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ModelMutationGuardTest {

    private static final String TASK_LIFECYCLE_SERVICE = "com.xa.mass.engine.TaskLifecycleService";
    private static final String TASK_STATE_RESOLVER = "com.xa.mass.engine.TaskStateResolver";

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
                .and().doNotHaveFullyQualifiedName(TASK_LIFECYCLE_SERVICE)
                .should().callMethod(Task.class, "setIntakeStatus", TaskIntakeStatus.class)
                .check(MAIN_CLASSES);

        noClasses()
                .that().doNotHaveFullyQualifiedName(Task.class.getName())
                .and().doNotHaveFullyQualifiedName(TaskManager.class.getName())
                .and().doNotHaveFullyQualifiedName(TASK_LIFECYCLE_SERVICE)
                .and().doNotHaveFullyQualifiedName(TASK_STATE_RESOLVER)
                .should().callMethod(Task.class, "sealIntake")
                .check(MAIN_CLASSES);

        noClasses()
                .that().doNotHaveFullyQualifiedName(Task.class.getName())
                .and().doNotHaveFullyQualifiedName(TASK_LIFECYCLE_SERVICE)
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
