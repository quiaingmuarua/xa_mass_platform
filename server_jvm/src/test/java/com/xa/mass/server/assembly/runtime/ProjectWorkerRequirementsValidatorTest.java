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
    private static final ProjectWorkerRequirements REQUIRED = new ProjectWorkerRequirements("messages", Map.of("shared", new ProjectWorkerRequirements.Resources(Set.of("send"), Set.of("delivery"), Set.of("delivery.take", "delivery.phone"))));

    @Test void hostBindingsWithoutAnyScenarioAreValidatedAndShareTheBoundedRead() {
        var groups = new TreeMap<String, FixedWindowBindingsProperties.Group>();
        IntStream.range(0, 205).forEach(i -> groups.put("g" + i,
                new FixedWindowBindingsProperties.Group(Map.of("window", new FixedWindowBindingsProperties.Limits(null, null)), List.of("observe"))));
        var project = directory(new ProjectDefinition("host-binding", List.copyOf(groups.keySet()), null));
        var host = new com.xa.mass.server.worker.observation.WorkerGroupBindings(Map.of(), new FixedWindowBindingsProperties(
                Map.of("window", new FixedWindowBindingsProperties.Pool("window.take", "at", "count", 100, 2)),
                Map.of("observe", new FixedWindowBindingsProperties.Feedback("window", List.of("check"))), Map.of(),
                Map.of("host-binding", groups.get("g0"))), List.of(), project);
        var reads = new ArrayList<Integer>();
        when(workers.getWorkerGroupDescriptors(anyList())).thenAnswer(call -> {
            List<String> ids = call.getArgument(0); reads.add(ids.size());
            var result = new HashMap<String, WorkerGroupDescriptor>();
            ids.forEach(id -> result.put(id, new WorkerGroupDescriptor(id, Map.of(), Set.of("check", "extra"))));
            return result;
        });
        var validator = new ProjectWorkerRequirementsValidator(project, workers, host, List.of());
        validator.validate();
        assertThat(reads).containsExactly(100, 100, 5);
        when(workers.getWorkerGroupDescriptors(anyList())).thenReturn(Map.of());
        assertThatThrownBy(validator::validate).hasMessageContaining("group=g0").hasMessageContaining("observe").hasMessageContaining("not registered");
        when(workers.getWorkerGroupDescriptors(anyList())).thenReturn(Map.of("g0", new WorkerGroupDescriptor("g0", Map.of(), Set.of("other"))));
        assertThatThrownBy(validator::validate).hasMessageContaining("missing feedback events: [check]");
        var unavailable = new IllegalStateException("read unavailable");
        when(workers.getWorkerGroupDescriptors(anyList())).thenThrow(unavailable);
        assertThatThrownBy(validator::validate).hasCause(unavailable);
    }

    @Test void groupRequirementsDoNotBecomeAUnionAcrossDifferentProtocols() {
        var requirements = new ProjectWorkerRequirements("checks", Map.of(
                "window", new ProjectWorkerRequirements.Resources(Set.of("simulate"), Set.of("window"), Set.of("window.take")),
                "plain", new ProjectWorkerRequirements.Resources(Set.of("lookup"), Set.of("any"), Set.of("worker.any"))));
        when(workers.getWorkerGroupDescriptors(anyList())).thenReturn(Map.of(
                "window", new WorkerGroupDescriptor("window", Map.of(), Set.of("simulate", "extra")),
                "plain", new WorkerGroupDescriptor("plain", Map.of(), Set.of("lookup"))));
        var matching = new MatchingProperties(Map.of(
                "window", new MatchingGroup(Set.of("window"), Set.of("window.take")),
                "plain", new MatchingGroup(Set.of("any"), Set.of("worker.any"))));
        var validator = validator(directory(new ProjectDefinition("checks", List.of("window","plain"), null)), matching, List.of(requirements));
        validator.validate();
        when(workers.getWorkerGroupDescriptors(anyList())).thenReturn(Map.of(
                "window", new WorkerGroupDescriptor("window", Map.of(), Set.of("simulate")),
                "plain", new WorkerGroupDescriptor("plain", Map.of(), Set.of("simulate"))));
        assertThatThrownBy(validator::validate).hasMessageContaining("group=plain").hasMessageContaining("missing events: [lookup]");
    }

    @Test void noRequirementsMakeNoOwnerCalls() {
        new ProjectWorkerRequirementsValidator(directory(), workers, bindings(new MatchingProperties(Map.of())), List.of()).validate();
        verifyNoInteractions(workers);
    }

    @Test void projectsCanShareAnExistingGroupWithExtraCapabilitiesAndOnlyOneRead() {
        var other = new ProjectWorkerRequirements("sms", Map.of("shared", new ProjectWorkerRequirements.Resources(Set.of("listen"), Set.of(), Set.of())));
        when(workers.getWorkerGroupDescriptors(List.of("shared"))).thenReturn(Map.of("shared",
                new WorkerGroupDescriptor("shared", Map.of("host", "retained"), Set.of("send", "listen", "extra"))));
        var validator = new ProjectWorkerRequirementsValidator(directory(new ProjectDefinition("messages", List.of("shared"), null),
                new ProjectDefinition("sms", List.of("shared"), null)), workers, bindings(available()), List.of(REQUIRED, other));
        verifyNoInteractions(workers);
        validator.validate();
        verify(workers).getWorkerGroupDescriptors(List.of("shared"));
        verifyNoMoreInteractions(workers);
    }

    @Test void callerKnownGroupReadsAreDeduplicatedAndBoundedWithoutLimitingTheProject() {
        var groups = IntStream.range(0, 205).mapToObj(i -> "g" + i).toList();
        var requirements = List.of(new ProjectWorkerRequirements("p", groups.stream().collect(java.util.stream.Collectors.toMap(group -> group, group -> new ProjectWorkerRequirements.Resources(Set.of("send"), Set.of(), Set.of()), (left, right) -> { throw new IllegalArgumentException("Duplicate Group"); }, java.util.LinkedHashMap::new))));
        var pages = new ArrayList<List<String>>();
        when(workers.getWorkerGroupDescriptors(anyList())).thenAnswer(call -> {
            List<String> ids = List.copyOf(call.getArgument(0)); pages.add(ids);
            var result = new LinkedHashMap<String, WorkerGroupDescriptor>();
            ids.forEach(id -> result.put(id, new WorkerGroupDescriptor(id, Map.of(), Set.of("send"))));
            return result;
        });
        new ProjectWorkerRequirementsValidator(directory(new ProjectDefinition("p", groups, null)), workers,
                bindings(new MatchingProperties(Map.of())), requirements).validate();
        assertThat(pages).extracting(List::size).containsExactly(100, 100, 5);
        assertThat(pages.stream().flatMap(List::stream).toList()).containsExactlyInAnyOrderElementsOf(groups);
        verify(workers, times(3)).getWorkerGroupDescriptors(anyList());
        verifyNoMoreInteractions(workers);
    }

    @Test void unknownProjectBindingAndDuplicatePairFailAtConstructionWithoutReading() {
        assertThatThrownBy(() -> validator(directory(), available(), List.of(REQUIRED)))
                .hasMessageContaining("Project is not declared").hasMessageContaining("project=messages");
        assertThatThrownBy(() -> validator(directory(new ProjectDefinition("messages", List.of("other"), null)), available(), List.of(REQUIRED)))
                .hasMessageContaining("Group is not declared").hasMessageContaining("group=shared");
        assertThatThrownBy(() -> validator(knownProject(), available(), List.of(REQUIRED, REQUIRED)))
                .hasMessageContaining("duplicate Project/Group");
        var overlap = new ProjectWorkerRequirements("messages", List.of("other", "shared").stream().collect(java.util.stream.Collectors.toMap(group -> group, group -> new ProjectWorkerRequirements.Resources(Set.of(), Set.of(), Set.of()), (left, right) -> { throw new IllegalArgumentException("Duplicate Group"); }, java.util.LinkedHashMap::new)));
        assertThatThrownBy(() -> validator(directory(new ProjectDefinition("messages", List.of("shared", "other"), null)),
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
        var requirement = new ProjectWorkerRequirements("p", groups.stream().collect(java.util.stream.Collectors.toMap(group -> group, group -> new ProjectWorkerRequirements.Resources(events, Set.of(), Set.of()), (left, right) -> { throw new IllegalArgumentException("Duplicate Group"); }, java.util.LinkedHashMap::new)));
        groups.clear();
        events.clear();
        assertThat(requirement.requirementsByGroup().keySet()).containsExactly("g");
        assertThatThrownBy(() -> requirement.requirementsByGroup().keySet().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new ProjectWorkerRequirements("p", Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(requirement.requirementsByGroup().get("g").eventCodes()).containsExactly("send");
        assertThatThrownBy(() -> requirement.requirementsByGroup().get("g").eventCodes().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new ProjectWorkerRequirements(" ", Map.of("g", new ProjectWorkerRequirements.Resources(Set.of(), Set.of(), Set.of())))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProjectWorkerRequirements("p", Map.of(" ", new ProjectWorkerRequirements.Resources(Set.of(), Set.of(), Set.of())))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProjectWorkerRequirements("p", Map.of("g", new ProjectWorkerRequirements.Resources(null, Set.of(), Set.of())))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProjectWorkerRequirements("p", Map.of("g", new ProjectWorkerRequirements.Resources(Set.of(), Set.of(" "), Set.of())))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProjectWorkerRequirements("p", Map.of("g", new ProjectWorkerRequirements.Resources(Set.of(), Set.of(), Set.of(""))))).isInstanceOf(IllegalArgumentException.class);
    }

    private ProjectWorkerRequirementsValidator validator(ProjectDirectory projects, MatchingProperties matching,
            List<ProjectWorkerRequirements> requirements) {
        return new ProjectWorkerRequirementsValidator(projects, workers, bindings(matching), requirements);
    }
    private static com.xa.mass.server.worker.observation.WorkerGroupBindings bindings(MatchingProperties matching) {
        return new com.xa.mass.server.worker.observation.WorkerGroupBindings(matching.groups(), new FixedWindowBindingsProperties(null, null, null, null), List.of(), directory());
    }
    private static ProjectDirectory knownProject() { return directory(new ProjectDefinition("messages", List.of("shared"), null)); }
    private static ProjectDirectory directory(ProjectDefinition... projects) {
        return new ProjectDirectory(new ProjectAssemblyProperties(List.of()), List.of(projects));
    }
    private static MatchingProperties available() {
        return new MatchingProperties(Map.of("shared", new MatchingGroup(Set.of("delivery", "extra"),
                Set.of("delivery.take", "delivery.phone", "extra"))));
    }
}
