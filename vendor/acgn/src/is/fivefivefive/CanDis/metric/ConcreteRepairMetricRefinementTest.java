package is.fivefivefive.CanDis.metric;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import is.fivefivefive.CanDis.metric.RepairView.Binding;
import is.fivefivefive.CanDis.metric.RepairView.BindingRole;
import is.fivefivefive.CanDis.metric.RepairView.ContainerKind;
import is.fivefivefive.CanDis.metric.RepairView.Declaration;
import is.fivefivefive.CanDis.metric.RepairView.Node;
import is.fivefivefive.CanDis.metric.RepairView.Phase;
import is.fivefivefive.CanDis.metric.RepairView.TemporalNode;
import is.fivefivefive.CanDis.theory.SemanticProfile;
import is.fivefivefive.CanDis.theory.StructuralKey;

/**
 * Bounded executable refinement check against vectors emitted by
 * {@code ConcreteRepairMetric.lean}. This covers only the finite observation
 * grammar named in that file; it is not a whole-program Java proof.
 */
public final class ConcreteRepairMetricRefinementTest {
    private static final String HEADER =
            "name\toutcome\ttotal\ttemporal\tquantifier\tmatrix";
    private static final int MAX_VECTOR_BYTES = 65_536;
    private static final int MAX_VECTOR_LINES = 128;
    private static final List<String> EXPECTED_NAMES = List.of(
            "profile_mismatch_precedes_kernel",
            "additive_decomposition",
            "quantifier_full_tuple_modify",
            "ordered_sequence_swap",
            "bag_multiplicity",
            "bag_minimum_assignment",
            "set_idempotence",
            "set_minimum_assignment",
            "alpha_maximum_cardinality",
            "kernel_zero_different_observation",
            "kernel_nonzero_same_observation");
    private static int checks;

    private ConcreteRepairMetricRefinementTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException(
                    "usage: ConcreteRepairMetricRefinementTest LEAN_VECTORS.tsv");
        }
        Path vectorPath = Path.of(args[0]);
        Map<String, Expected> vectors = readVectors(vectorPath);
        check(new ArrayList<>(vectors.keySet()).equals(EXPECTED_NAMES),
                "Lean vector names and order must match the bounded Java cases exactly");

        for (String name : EXPECTED_NAMES) {
            compare(name, vectors.get(name), javaCase(name));
        }

        List<String> original = Files.readAllLines(vectorPath, StandardCharsets.UTF_8);
        exerciseStrictVectorRejection(original);
        System.out.println("ConcreteRepairMetricRefinementTest passed: "
                + checks + " checks across " + vectors.size() + " Lean vectors");
    }

    private static void compare(String name, Expected expected, CasePair pair) {
        Objects.requireNonNull(expected, "expected vector " + name);
        try {
            QuotientRepairDistance.Result actual =
                    QuotientRepairDistance.evaluateClaimedProducerConsistencyForTesting(
                            pair.left, pair.right);
            check(expected.outcome == Outcome.ACCEPT,
                    name + " unexpectedly returned an accepted Java result");
            check(actual.distance() == expected.total,
                    name + " total differs: Java=" + actual.distance()
                            + ", Lean=" + expected.total);
            check(actual.temporalDistance() == expected.temporal,
                    name + " temporal component differs");
            check(actual.quantifierDistance() == expected.quantifier,
                    name + " quantifier component differs");
            check(actual.matrixDistance() == expected.matrix,
                    name + " matrix component differs");
            check(actual.binderAlignments() > 0,
                    name + " evaluated no admissible alpha alignment");
            check(actual.distance() == actual.temporalDistance()
                            + actual.quantifierDistance() + actual.matrixDistance(),
                    name + " violates additive decomposition in Java");
            check(actual.kernelAuthority()
                            == QuotientRepairDistance.KernelAuthority
                                    .TEST_ONLY_CLAIMED_PRODUCER_CONSISTENCY,
                    name + " returned an unexpected kernel authority");
        } catch (IllegalArgumentException exception) {
            check(expected.outcome == Outcome.REJECT_PROFILE_MISMATCH,
                    name + " unexpectedly rejected a profile: " + exception.getMessage());
            check("Repair views from different semantic profiles cannot be compared"
                            .equals(exception.getMessage()),
                    name + " rejected for an unrelated argument error");
        } catch (IllegalStateException exception) {
            check(expected.outcome == Outcome.REJECT_KERNEL_MISMATCH,
                    name + " unexpectedly rejected the producer kernel: "
                            + exception.getMessage());
            check("Equal producer observations have nonzero repair distance"
                            .equals(exception.getMessage())
                            || "A zero repair distance lacks producer-observation equality"
                                    .equals(exception.getMessage()),
                    name + " rejected for an unrelated state error");
        }
    }

    private static Map<String, Expected> readVectors(Path path) throws IOException {
        Objects.requireNonNull(path, "vector path");
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Lean vector path is not a regular file: " + path);
        }
        long size = Files.size(path);
        if (size <= 0 || size > MAX_VECTOR_BYTES) {
            throw new IllegalArgumentException("Lean vector file has an invalid bounded size");
        }
        return parseVectors(Files.readAllLines(path, StandardCharsets.UTF_8));
    }

    private static Map<String, Expected> parseVectors(List<String> lines) {
        Objects.requireNonNull(lines, "vector lines");
        if (lines.size() < 2 || lines.size() > MAX_VECTOR_LINES) {
            throw new IllegalArgumentException("Lean vector line count is outside the bound");
        }
        if (!HEADER.equals(lines.get(0))) {
            throw new IllegalArgumentException("Lean vector header mismatch");
        }
        Set<String> allowed = new LinkedHashSet<>(EXPECTED_NAMES);
        Map<String, Expected> result = new LinkedHashMap<>();
        for (int lineNumber = 1; lineNumber < lines.size(); lineNumber++) {
            String line = lines.get(lineNumber);
            if (line.isEmpty()) {
                throw new IllegalArgumentException(
                        "Blank Lean vector row at line " + (lineNumber + 1));
            }
            String[] fields = line.split("\\t", -1);
            if (fields.length != 6) {
                throw new IllegalArgumentException(
                        "Lean vector field count mismatch at line " + (lineNumber + 1));
            }
            String name = fields[0];
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException("Unknown Lean vector: " + name);
            }
            if (result.containsKey(name)) {
                throw new IllegalArgumentException("Duplicate Lean vector: " + name);
            }
            result.put(name, Expected.parse(fields));
        }
        if (!result.keySet().equals(allowed)) {
            Set<String> missing = new LinkedHashSet<>(allowed);
            missing.removeAll(result.keySet());
            throw new IllegalArgumentException(
                    "Lean vector set mismatch; missing=" + missing);
        }
        if (!new ArrayList<>(result.keySet()).equals(EXPECTED_NAMES)) {
            throw new IllegalArgumentException("Lean vector row order mismatch");
        }
        return Collections.unmodifiableMap(result);
    }

    private static void exerciseStrictVectorRejection(List<String> original)
            throws IOException {
        List<String> duplicate = new ArrayList<>(original);
        duplicate.add(original.get(1));
        expectVectorRejection(duplicate, "duplicate vectors must be rejected");

        List<String> unknown = new ArrayList<>(original);
        String[] unknownFields = unknown.get(1).split("\\t", -1);
        unknownFields[0] = "untrusted_extra_case";
        unknown.set(1, String.join("\t", unknownFields));
        expectVectorRejection(unknown, "unknown vectors must be rejected");

        List<String> missing = new ArrayList<>(original);
        missing.remove(missing.size() - 1);
        expectVectorRejection(missing,
                "an inexact vector set with a missing case must be rejected");

        List<String> badHeader = new ArrayList<>(original);
        badHeader.set(0, HEADER + "\textra");
        expectVectorRejection(badHeader, "a changed vector schema must be rejected");

        List<String> reordered = new ArrayList<>(original);
        String first = reordered.get(1);
        reordered.set(1, reordered.get(2));
        reordered.set(2, first);
        expectVectorRejection(reordered,
                "a nondeterministically reordered vector set must be rejected");
    }

    private static void expectVectorRejection(List<String> lines, String message)
            throws IOException {
        Path path = Files.createTempFile("acgn-concrete-repair-invalid-", ".tsv");
        try {
            Files.write(path, lines, StandardCharsets.UTF_8);
            checks++;
            try {
                readVectors(path);
            } catch (IllegalArgumentException expected) {
                return;
            }
            throw new AssertionError(message);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static CasePair javaCase(String name) {
        switch (name) {
            case "profile_mismatch_precedes_kernel":
                return pair(
                        view(SemanticProfile.alloyOverflowForbidding(), temporal("NONE"),
                                List.of(), List.of(), atom("A"), 0),
                        view(SemanticProfile.alloyModular(), temporal("NONE"),
                                List.of(), List.of(), atom("C"), 0));
            case "additive_decomposition": {
                Declaration all = declaration("ALL", "S", "ONE", 0, 0);
                Declaration some = declaration("SOME", "S", "ONE", 0, 0);
                return pair(
                        view(defaultProfile(), temporal("NONE"), List.of(all),
                                List.of(binding(all, 0, List.of(0))),
                                atom("A"), 0),
                        view(defaultProfile(), temporal("NONE", temporal("AFTER")),
                                List.of(some),
                                List.of(binding(some, 0, List.of(0))),
                                atom("C"), 1));
            }
            case "quantifier_full_tuple_modify": {
                Declaration left = declaration("ALL", "S", "ONE", 0, 0);
                Declaration right = declaration("NO", "T", "SET", 7, 0);
                return pair(
                        view(defaultProfile(), temporal("NONE"),
                                List.of(left),
                                List.of(binding(left, 0, List.of(0))), atom("A"), 0),
                        view(defaultProfile(), temporal("NONE"),
                                List.of(right),
                                List.of(binding(right, 0, List.of(0))), atom("A"), 1));
            }
            case "ordered_sequence_swap":
                return pair(
                        matrixView(container("SEQ", ContainerKind.SEQUENCE,
                                atom("A"), atom("B")), 0),
                        matrixView(container("SEQ", ContainerKind.SEQUENCE,
                                atom("B"), atom("A")), 1));
            case "bag_multiplicity":
                return pair(
                        matrixView(container("BAG", ContainerKind.BAG,
                                atom("A"), atom("A")), 0),
                        matrixView(container("BAG", ContainerKind.BAG, atom("A")), 1));
            case "bag_minimum_assignment":
                return pair(
                        matrixView(container("BAG", ContainerKind.BAG,
                                atom("A"), atom("B")), 0),
                        matrixView(container("BAG", ContainerKind.BAG,
                                atom("B"), atom("A")), 0));
            case "set_idempotence":
                return pair(
                        matrixView(idempotentAtomSet("A", "A", "B"), 0),
                        matrixView(idempotentAtomSet("B", "A"), 0));
            case "set_minimum_assignment":
                return pair(
                        matrixView(idempotentAtomSet("A", "B"), 0),
                        matrixView(idempotentAtomSet("B", "C"), 1));
            case "alpha_maximum_cardinality":
                return alphaMaximumCardinalityCase();
            case "kernel_zero_different_observation":
                return pair(matrixView(atom("A"), 0), matrixView(atom("A"), 1));
            case "kernel_nonzero_same_observation":
                return pair(matrixView(atom("A"), 0), matrixView(atom("C"), 0));
            default:
                throw new IllegalArgumentException("No bounded Java case for " + name);
        }
    }

    private static CasePair alphaMaximumCardinalityCase() {
        Declaration all = declaration("ALL", "S", "ONE", 0, 0);
        Binding x0 = binding(all, 0, List.of(0, 1, 2));
        Binding x1 = binding(all, 1, List.of(0, 1, 2));
        Binding x2 = binding(all, 2, List.of(0, 1, 2));
        Binding y0 = binding(all, 0, List.of(0));
        Node left = container("ROOT", ContainerKind.SET,
                clause("P", variable("x0", 0)),
                clause("Q", variable("x1", 1)),
                clause("R", variable("x2", 2)));
        Node right = container("ROOT", ContainerKind.SET,
                clause("Q", variable("y0", 0)));
        return pair(
                view(defaultProfile(), temporal("NONE"), List.of(all, all, all),
                        List.of(x0, x1, x2), left, 0),
                view(defaultProfile(), temporal("NONE"), List.of(all),
                        List.of(y0), right, 1));
    }

    private static SemanticProfile defaultProfile() {
        return SemanticProfile.alloyOverflowForbidding();
    }

    private static RepairView matrixView(Node matrix, int observation) {
        return view(defaultProfile(), temporal("NONE"), List.of(), List.of(),
                matrix, observation);
    }

    private static RepairView view(
            SemanticProfile profile,
            TemporalNode temporal,
            List<Declaration> declarations,
            List<Binding> bindings,
            Node matrix,
            int observation) {
        return new RepairView(
                temporal,
                List.of(new Phase(declarations, bindings, matrix)),
                profile,
                StructuralKey.leaf("concrete-repair-vector", Integer.toString(observation)));
    }

    private static Declaration declaration(
            String quantifier,
            String type,
            String cardinality,
            int disjointnessClass,
            int exchangeClass) {
        return new Declaration(
                quantifier,
                type,
                cardinality,
                disjointnessClass,
                type,
                exchangeClass,
                List.of());
    }

    private static Binding binding(
            Declaration declaration,
            int coordinate,
            List<Integer> orbit) {
        return new Binding(
                BindingRole.MATRIX,
                coordinate,
                0,
                coordinate,
                declaration,
                "bounded-alpha-block",
                orbit,
                true);
    }

    private static TemporalNode temporal(String label, TemporalNode... children) {
        return new TemporalNode(label, List.of(children));
    }

    private static Node atom(String symbol) {
        return new Node(
                symbol,
                null,
                null,
                -1,
                ContainerKind.SEQUENCE,
                false,
                List.of());
    }

    private static Node variable(String lexicalName, int bindingIndex) {
        return new Node(
                "VARIABLE",
                null,
                lexicalName,
                bindingIndex,
                ContainerKind.SEQUENCE,
                false,
                List.of());
    }

    private static Node clause(String predicate, Node variable) {
        return container(predicate, ContainerKind.ONE, variable);
    }

    private static Node container(
            String operator,
            ContainerKind kind,
            Node... children) {
        return new Node(
                operator,
                null,
                null,
                -1,
                kind,
                kind == ContainerKind.BAG || kind == ContainerKind.SET,
                List.of(children));
    }

    /** Models the already-quotiented SET port presented to RepairView. */
    private static Node idempotentAtomSet(String... symbols) {
        Set<String> unique = new LinkedHashSet<>(List.of(symbols));
        List<Node> children = new ArrayList<>(unique.size());
        for (String symbol : unique) {
            children.add(atom(symbol));
        }
        return container("SET", ContainerKind.SET, children.toArray(new Node[0]));
    }

    private static CasePair pair(RepairView left, RepairView right) {
        return new CasePair(left, right);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private enum Outcome {
        ACCEPT,
        REJECT_PROFILE_MISMATCH,
        REJECT_KERNEL_MISMATCH
    }

    private static final class Expected {
        private final Outcome outcome;
        private final int total;
        private final int temporal;
        private final int quantifier;
        private final int matrix;

        private Expected(
                Outcome outcome,
                int total,
                int temporal,
                int quantifier,
                int matrix) {
            this.outcome = outcome;
            this.total = total;
            this.temporal = temporal;
            this.quantifier = quantifier;
            this.matrix = matrix;
        }

        private static Expected parse(String[] fields) {
            Outcome outcome;
            try {
                outcome = Outcome.valueOf(fields[1]);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "Unknown Lean vector outcome: " + fields[1], exception);
            }
            if (outcome != Outcome.ACCEPT) {
                for (int index = 2; index < fields.length; index++) {
                    if (!"-".equals(fields[index])) {
                        throw new IllegalArgumentException(
                                "Rejected Lean vectors must not contain distances");
                    }
                }
                return new Expected(outcome, -1, -1, -1, -1);
            }
            return new Expected(
                    outcome,
                    parseBoundedInt(fields[2]),
                    parseBoundedInt(fields[3]),
                    parseBoundedInt(fields[4]),
                    parseBoundedInt(fields[5]));
        }

        private static int parseBoundedInt(String value) {
            if (!value.matches("0|[1-9][0-9]*")) {
                throw new IllegalArgumentException(
                        "Lean vector integer is not canonical: " + value);
            }
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                        "Lean vector integer exceeds the Java bound: " + value,
                        exception);
            }
        }
    }

    private static final class CasePair {
        private final RepairView left;
        private final RepairView right;

        private CasePair(RepairView left, RepairView right) {
            this.left = Objects.requireNonNull(left, "left view");
            this.right = Objects.requireNonNull(right, "right view");
        }
    }
}
