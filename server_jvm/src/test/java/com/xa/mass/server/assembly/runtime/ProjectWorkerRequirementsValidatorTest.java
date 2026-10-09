package com.xa.mass.server.assembly.runtime;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerGroupDescriptor;
import com.xa.mass.server.assembly.matching.MatchingProperties;
import com.xa.mass.server.project.*;
import com.xa.mass.workermatching.MatchingGroup;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectWorkerRequirementsValidatorTest {
    private final WorkerResourceCatalog workers = mock(WorkerResourceCatalog.class);
    private static final ProjectWorkerRequirements REQUIRED = new ProjectWorkerRequirements(
            "messages", List.of("shared"), Set.of("send"), Set.of("delivery"), Set.of("delivery.take", "delivery.phone"));

    @Test void noRequirementsMakeNoOwnerCalls() {
        new ProjectWorkerRequirementsValidator(directory(), workers, new MatchingProperties(Map.of()), List.of()).validate();
        verifyNoInteractions(workers);
    }

    @Test void projectsCanShareAnExistingGroupWithExtraCapabilitiesAndOnlyOneRead() {
        var other = new ProjectWorkerRequirements("sms", List.of("shared"), Set.of("listen"), Set.of(), Set.of());
        when(workers.getWorkerGroupDescriptors(List.of("shared"))).thenReturn(Map.of("shared",
                new WorkerGroupDescriptor("shared", Map.of("host", "retained"), Set.of("send", "listen", "extra"))));
        var validator = new ProjectWorkerRequirementsValidator(directory(new ProjectDefinition("messages", List.of("shared")),
                new ProjectDefinition("sms", List.of("shared"))), workers, available(), List.of(REQUIRED, other));
        verifyNoInteractions(workers);
        validator.validate();
        verify(workers).getWorkerGroupDescriptors(List.of("shared"));
        verifyNoMoreInteractions(workers);
    }

    @Test void callerKnownGroupReadsAreDeduplicatedAndBoundedWithoutLimitingTheProject() {
        var groups = IntStream.range(0, 205).mapToObj(i -> "g" + i).toList();
        var requirements = List.of(new ProjectWorkerRequirements("p", groups, Set.of("send"), Set.of(), Set.of()));
        var pages = new ArrayList<List<String>>();
        when(workers.getWorkerGroupDescriptors(anyList())).thenAnswer(call -> {
            List<String> ids = List.copyOf(call.getArgument(0)); pages.add(ids);
            var result = new LinkedHashMap<String, WorkerGroupDescriptor>();
            ids.forEach(id -> result.put(id, new WorkerGroupDescriptor(id, Map.of(), Set.of("send"))));
            return result;
        });
        new ProjectWorkerRequirementsValidator(directory(new ProjectDefinition("p", groups)), workers,
                new MatchingProperties(Map.of()), requirements).validate();
        assertThat(pages).extracting(List::size).containsExactly(100, 100, 5);
        assertThat(pages.stream().flatMap(List::stream).toList()).containsExactlyInAnyOrderElementsOf(groups);
        verify(workers, times(3)).getWorkerGroupDescriptors(anyList());
        verifyNoMoreInteractions(workers);
    }

    @Test void unknownProjectBindingAndDuplicatePairFailAtConstructionWithoutReading() {
        assertThatThrownBy(() -> validator(directory(), available(), List.of(REQUIRED)))
                .hasMessageContaining("Project is not declared").hasMessageContaining("project=messages");
        assertThatThrownBy(() -> validator(directory(new ProjectDefinition("messages", List.of("other"))), available(), List.of(REQUIRED)))
                .hasMessageContaining("Group is not declared").hasMessageContaining("group=shared");
        assertThatThrownBy(() -> validator(knownProject(), available(), List.of(REQUIRED, REQUIRED)))
                .hasMessageContaining("duplicate Project/Group");
        var overlap = new ProjectWorkerRequirements("messages", List.of("other", "shared"), Set.of(), Set.of(), Set.of());
        assertThatThrownBy(() -> validator(directory(new ProjectDefinition("messages", List.of("shared", "other"))),
                available(), List.of(REQUIRED, overlap))).hasMessageContaining("duplicate Project/Group").hasMessageContaining("group=shared");
        verifyNoInteractions(workers);
    }

    @Test void missingEnabledResourcesFailWithoutEnablingOrReadingAnything() {
        assertThatThrownBy(() -> validator(knownProject(), new MatchingProperties(Map.of()), List.of(REQUIRED)).validate())
                .hasMessageContaining("missing Pools: [delivery]");
        var noPhone = new MatchingProperties(Map.of("shared", new MatchingGroup(Set.of("delivery"), Set.of("delivery.take"))));
        assertThatThrownBy(() -> validator(knownProject(), noPhone, List.of(REQUIRED)).validate())
                .hasMessageContaining("missing functions: [delivery.phone]");
        verifyNoInteractions(workers);
    }

    @Test void missingRegisteredGroupOrEventIsAStartupFailureWithProjectAndGroupContext() {
        when(workers.getWorkerGroupDescriptors(anyList())).thenReturn(Map.of());
        var validator = validator(knownProject(), available(), List.of(REQUIRED));
        assertThatThrownBy(validator::validate).hasMessageContaining("project=messages group=shared")
                .hasMessageContaining("Group is not registered");
        when(workers.getWorkerGroupDescriptors(anyList())).thenReturn(Map.of("shared", new WorkerGroupDescriptor(
                "shared", Map.of(), Set.of("listen"))));
        assertThatThrownBy(validator::validate).hasMessageContaining("missing events: [send]");
    }

    @Test void failedOwnerReadPreservesTheCause() {
        var cause = new IllegalStateException("read failed");
        when(workers.getWorkerGroupDescriptors(anyList())).thenThrow(cause);
        assertThatThrownBy(() -> validator(knownProject(), available(), List.of(REQUIRED)).validate())
                .hasMessageContaining("operation=project.dependencies.validate project=messages group=shared")
                .hasCause(cause);
    }

    @Test void declarationCapturesExactNamesAndAllowsEmptySets() {
        var events = new HashSet<>(Set.of("send"));
        var groups = new ArrayList<>(List.of("g"));
        var requirement = new ProjectWorkerRequirements("p", groups, events, Set.of(), Set.of());
        groups.clear();
        events.clear();
        assertThat(requirement.workerGroupIds()).containsExactly("g");
        assertThatThrownBy(() -> requirement.workerGroupIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new ProjectWorkerRequirements("p", List.of("g", "g"), events, Set.of(), Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProjectWorkerRequirements("p", List.of(), events, Set.of(), Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(requirement.eventCodes()).containsExactly("send");
        assertThatThrownBy(() -> requirement.eventCodes().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new ProjectWorkerRequirements(" ", List.of("g"), Set.of(), Set.of(), Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProjectWorkerRequirements("p", List.of(" "), Set.of(), Set.of(), Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProjectWorkerRequirements("p", List.of("g"), null, Set.of(), Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProjectWorkerRequirements("p", List.of("g"), Set.of(), Set.of(" "), Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProjectWorkerRequirements("p", List.of("g"), Set.of(), Set.of(), Set.of(""))).isInstanceOf(IllegalArgumentException.class);
    }

    private ProjectWorkerRequirementsValidator validator(ProjectDirectory projects, MatchingProperties matching,
            List<ProjectWorkerRequirements> requirements) {
        return new ProjectWorkerRequirementsValidator(projects, workers, matching, requirements);
    }
    private static ProjectDirectory knownProject() { return directory(new ProjectDefinition("messages", List.of("shared"))); }
    private static ProjectDirectory directory(ProjectDefinition... projects) {
        return new ProjectDirectory(new ProjectAssemblyProperties(List.of()), List.of(projects));
    }
    private static MatchingProperties available() {
        return new MatchingProperties(Map.of("shared", new MatchingGroup(Set.of("delivery", "extra"),
                Set.of("delivery.take", "delivery.phone", "extra"))));
    }
}
