package com.xa.mass.testing.workerfault;

import com.xa.mass.trace.scenario.TraceScenarioRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProofRegistryClosureGuardTest {

    private static final Path REPO_ROOT = Path.of("..");
    private static final Path PROOF_REGISTRY = REPO_ROOT.resolve("doc").resolve("PROOF_REGISTRY.md");
    private static final Pattern ANALYZER_TOKEN = Pattern.compile("analyzer `([^`]+)`");
    private static final Pattern CLASS_TOKEN = Pattern.compile(
            "`([A-Z][A-Za-z0-9_]*(?:Test|Suite|Runner|Scenario|Guard))`");

    @Test
    void coveredCriticalInvariantRowsMustKeepRequiredProofColumns() throws IOException {
        List<String> violations = new ArrayList<>();
        for (ProofRegistryRow row : criticalInvariantRows()) {
            if (!"covered".equals(row.status())) {
                continue;
            }
            requireProofCell(row, "primary proof", row.primaryProof(), violations);
            requireProofCell(row, "representative integrated proof", row.representativeProof(), violations);
            requireProofCell(row, "trace proof", row.traceProof(), violations);
        }

        assertTrue(violations.isEmpty(),
                "Covered rows in doc/PROOF_REGISTRY.md must keep required proof columns populated:\n"
                        + String.join("\n", violations));
    }

    @Test
    void namedTraceAnalyzersMustResolveThroughTraceScenarioRegistry() throws IOException {
        TraceScenarioRegistry registry = new TraceScenarioRegistry();
        List<String> violations = new ArrayList<>();
        for (ProofRegistryRow row : criticalInvariantRows()) {
            for (String analyzerId : analyzerIds(row.joinedProofText())) {
                try {
                    registry.require(analyzerId);
                } catch (IllegalArgumentException e) {
                    violations.add(row.invariantId() + " names unknown trace analyzer `" + analyzerId + "`");
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Trace analyzers named in doc/PROOF_REGISTRY.md must resolve through TraceScenarioRegistry:\n"
                        + String.join("\n", violations));
    }

    @Test
    void namedProofClassesMustExistInSourceTree() throws IOException {
        Set<String> sourceClassNames = sourceClassNames();
        List<String> violations = new ArrayList<>();
        for (ProofRegistryRow row : criticalInvariantRows()) {
            for (String className : namedProofClasses(row.joinedProofText())) {
                if (!sourceClassNames.contains(className)) {
                    violations.add(row.invariantId() + " names missing proof class `" + className + "`");
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Proof classes named in doc/PROOF_REGISTRY.md must exist in the source tree:\n"
                        + String.join("\n", violations));
    }

    private static void requireProofCell(ProofRegistryRow row,
                                         String columnName,
                                         String value,
                                         List<String> violations) {
        if (value.isBlank() || "-".equals(value) || placeholderOnly(value)) {
            violations.add(row.invariantId() + " has no concrete " + columnName + " cell");
        }
    }

    private static boolean placeholderOnly(String value) {
        String normalized = value.toLowerCase();
        return normalized.equals("todo")
                || normalized.equals("tbd")
                || normalized.equals("missing")
                || normalized.equals("none");
    }

    private static List<ProofRegistryRow> criticalInvariantRows() throws IOException {
        List<ProofRegistryRow> rows = new ArrayList<>();
        boolean inSection = false;
        for (String line : Files.readAllLines(PROOF_REGISTRY, StandardCharsets.UTF_8)) {
            if (line.startsWith("## 2. Critical Invariants")) {
                inSection = true;
                continue;
            }
            if (inSection && line.startsWith("## 2.1 ")) {
                break;
            }
            if (!inSection || !line.startsWith("| `")) {
                continue;
            }
            String[] cells = line.substring(1, line.length() - 1).split("\\|", -1);
            if (cells.length != 8) {
                throw new IllegalStateException("Unexpected PROOF_REGISTRY.md row shape: " + line);
            }
            rows.add(new ProofRegistryRow(
                    trimBackticks(cells[0].trim()),
                    cells[2].trim(),
                    cells[3].trim(),
                    cells[4].trim(),
                    cells[5].trim(),
                    trimBackticks(cells[6].trim())
            ));
        }
        assertTrue(!rows.isEmpty(), "doc/PROOF_REGISTRY.md critical invariant table must not be empty");
        return rows;
    }

    private static Set<String> analyzerIds(String text) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher matcher = ANALYZER_TOKEN.matcher(text);
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }

    private static Set<String> namedProofClasses(String text) {
        Set<String> classes = new LinkedHashSet<>();
        Matcher matcher = CLASS_TOKEN.matcher(text);
        while (matcher.find()) {
            classes.add(matcher.group(1));
        }
        return classes;
    }

    private static Set<String> sourceClassNames() throws IOException {
        Set<String> names = new LinkedHashSet<>();
        try (Stream<Path> paths = Files.walk(REPO_ROOT)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> !path.toString().contains("\\target\\"))
                    .map(path -> path.getFileName().toString())
                    .map(fileName -> fileName.substring(0, fileName.length() - ".java".length()))
                    .forEach(names::add);
        }
        return names;
    }

    private static String trimBackticks(String value) {
        if (value.startsWith("`") && value.endsWith("`")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private record ProofRegistryRow(String invariantId,
                                    String primaryProof,
                                    String representativeProof,
                                    String traceProof,
                                    String distributedEdgeProof,
                                    String status) {
        String joinedProofText() {
            return String.join(" ", primaryProof, representativeProof, traceProof, distributedEdgeProof);
        }
    }
}
