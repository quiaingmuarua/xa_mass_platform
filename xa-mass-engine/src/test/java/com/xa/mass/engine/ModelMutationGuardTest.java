package com.xa.mass.engine;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.xa.mass.base.enums.task.TaskHoldReason;
import com.xa.mass.base.enums.task.TaskIntakeStatus;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
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
                .and().doNotHaveFullyQualifiedName(TaskLifecycleService.class.getName())
                .should().callMethod(Task.class, "setHoldReason", TaskHoldReason.class)
                .check(MAIN_CLASSES);
    }

    @Test
    void latestAttemptProjectionMustNotUseIndividualTaskMsgProjectionSettersDirectly() {
        noClasses()
                .that().doNotHaveFullyQualifiedName(TaskMsg.class.getName())
                .should().callMethod(TaskMsg.class, "setStatus", TaskMsgStatus.class)
                .check(MAIN_CLASSES);

        noClasses()
                .that().doNotHaveFullyQualifiedName(TaskMsg.class.getName())
                .should().callMethod(TaskMsg.class, "setLatestAttemptWorkerId", String.class)
                .check(MAIN_CLASSES);

        noClasses()
                .that().doNotHaveFullyQualifiedName(TaskMsg.class.getName())
                .should().callMethod(TaskMsg.class, "setLatestAttemptWorkerContextId", String.class)
                .check(MAIN_CLASSES);

        noClasses()
                .that().doNotHaveFullyQualifiedName(TaskMsg.class.getName())
                .should().callMethod(TaskMsg.class, "setLatestAttemptBatchId", String.class)
                .check(MAIN_CLASSES);
    }
}
