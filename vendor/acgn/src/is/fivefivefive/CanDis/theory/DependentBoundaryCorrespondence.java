package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Total proof for one pair of dependent JOIN boundary carriers.
 *
 * <p>Edges run from specific to general. A non-disjoint result names the
 * greatest lower bound witnessed by exact identity or one authenticated
 * subtype path. Divergent branches carry two paths to their first common
 * ancestor and prove that this alternative pair contributes no JOIN tuples.
 * Merely sharing {@code univ} never creates an overlapping boundary.</p>
 */
public final class DependentBoundaryCorrespondence {
    /** A valid source boundary for which this closed evidence family has no proof. */
    static final class UnsupportedCorrespondence extends IllegalArgumentException {
        private UnsupportedCorrespondence(String message) {
            super(message);
        }
    }

    public enum Rule {
        EXACT,
        LEFT_SUBTYPE_OF_RIGHT,
        RIGHT_SUBTYPE_OF_LEFT,
        DISJOINT_BRANCHES
    }

    private static final GraphType UNIV =
            GraphType.constructor("AlloySig:univ");

    private final Rule rule;
    private final GraphType leftBoundary;
    private final GraphType rightBoundary;
    private final GraphType meetBoundary;
    private final GraphType commonAncestor;
    private final List<GraphType> leftWitnessPath;
    private final List<GraphType> rightWitnessPath;
    private final StructuralKey structuralKey;

    public static DependentBoundaryCorrespondence derive(
            DependentColumnEvidence left,
            DependentColumnEvidence right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        GraphType leftBoundary = left.exactColumn();
        GraphType rightBoundary = right.exactColumn();
        if (leftBoundary.equals(rightBoundary)) {
            return new DependentBoundaryCorrespondence(
                    Rule.EXACT,
                    leftBoundary,
                    rightBoundary,
                    leftBoundary,
                    leftBoundary,
                    List.of(leftBoundary),
                    List.of(rightBoundary));
        }
        if (!isAlloySignature(leftBoundary)
                || !isAlloySignature(rightBoundary)) {
            throw new UnsupportedCorrespondence(
                    "Dependent boundary comparison requires exact Alloy carriers");
        }
        if (!left.sharesParserModuleAuthorityWith(right)) {
            throw new UnsupportedCorrespondence(
                    "Dependent boundary comparison requires one live parser module");
        }

        int rightInLeft = left.ancestorIndex(rightBoundary);
        int leftInRight = right.ancestorIndex(leftBoundary);
        if (rightInLeft > 0 && leftInRight > 0) {
            throw new IllegalArgumentException(
                    "Dependent boundary evidence contains a nominal ancestry cycle");
        }
        if (rightInLeft > 0) {
            return new DependentBoundaryCorrespondence(
                    Rule.LEFT_SUBTYPE_OF_RIGHT,
                    leftBoundary,
                    rightBoundary,
                    leftBoundary,
                    rightBoundary,
                    left.ancestry().subList(0, rightInLeft + 1),
                    List.of(rightBoundary));
        }
        if (leftInRight > 0) {
            return new DependentBoundaryCorrespondence(
                    Rule.RIGHT_SUBTYPE_OF_LEFT,
                    leftBoundary,
                    rightBoundary,
                    rightBoundary,
                    leftBoundary,
                    List.of(leftBoundary),
                    right.ancestry().subList(0, leftInRight + 1));
        }

        GraphType common = firstCommonAncestor(
                left.ancestry(), right.ancestry());
        if (common == null) {
            throw new UnsupportedCorrespondence(
                    "Dependent boundary has neither overlap nor authenticated disjointness: "
                            + leftBoundary + " versus " + rightBoundary);
        }
        int leftCommon = left.ancestorIndex(common);
        int rightCommon = right.ancestorIndex(common);
        if (leftCommon <= 0 || rightCommon <= 0) {
            throw new IllegalArgumentException(
                    "A divergent dependent boundary has an invalid common ancestor");
        }
        return new DependentBoundaryCorrespondence(
                Rule.DISJOINT_BRANCHES,
                leftBoundary,
                rightBoundary,
                null,
                common,
                left.ancestry().subList(0, leftCommon + 1),
                right.ancestry().subList(0, rightCommon + 1));
    }

    private DependentBoundaryCorrespondence(
            Rule rule,
            GraphType leftBoundary,
            GraphType rightBoundary,
            GraphType meetBoundary,
            GraphType commonAncestor,
            List<GraphType> leftWitnessPath,
            List<GraphType> rightWitnessPath) {
        this.rule = Objects.requireNonNull(rule, "rule");
        this.leftBoundary = Objects.requireNonNull(leftBoundary, "leftBoundary");
        this.rightBoundary = Objects.requireNonNull(rightBoundary, "rightBoundary");
        this.meetBoundary = meetBoundary;
        this.commonAncestor = Objects.requireNonNull(
                commonAncestor, "commonAncestor");
        this.leftWitnessPath = copyPath(
                leftWitnessPath, leftBoundary, commonAncestor, "left");
        this.rightWitnessPath = copyPath(
                rightWitnessPath, rightBoundary, commonAncestor, "right");
        validateRule();
        this.structuralKey = StructuralKey.of(
                "dependent-boundary-correspondence-v2",
                List.of(rule.name()),
                List.of(
                        TheoryKeys.type(leftBoundary),
                        TheoryKeys.type(rightBoundary),
                        meetBoundary == null
                                ? StructuralKey.leaf(
                                        "dependent-boundary-empty-meet-v1", "disjoint")
                                : TheoryKeys.type(meetBoundary),
                        TheoryKeys.type(commonAncestor),
                        pathKey("dependent-boundary-left-path-v1", this.leftWitnessPath),
                        pathKey("dependent-boundary-right-path-v1", this.rightWitnessPath)));
    }

    private void validateRule() {
        switch (rule) {
            case EXACT -> {
                if (!leftBoundary.equals(rightBoundary)
                        || !leftBoundary.equals(meetBoundary)
                        || !leftBoundary.equals(commonAncestor)
                        || leftWitnessPath.size() != 1
                        || rightWitnessPath.size() != 1) {
                    throw new IllegalArgumentException(
                            "An exact dependent boundary has inconsistent evidence");
                }
            }
            case LEFT_SUBTYPE_OF_RIGHT -> {
                if (leftBoundary.equals(rightBoundary)
                        || !leftBoundary.equals(meetBoundary)
                        || !rightBoundary.equals(commonAncestor)
                        || leftWitnessPath.size() < 2
                        || rightWitnessPath.size() != 1) {
                    throw new IllegalArgumentException(
                            "A left-subtype dependent boundary has inconsistent evidence");
                }
            }
            case RIGHT_SUBTYPE_OF_LEFT -> {
                if (leftBoundary.equals(rightBoundary)
                        || !rightBoundary.equals(meetBoundary)
                        || !leftBoundary.equals(commonAncestor)
                        || leftWitnessPath.size() != 1
                        || rightWitnessPath.size() < 2) {
                    throw new IllegalArgumentException(
                            "A right-subtype dependent boundary has inconsistent evidence");
                }
            }
            case DISJOINT_BRANCHES -> {
                if (meetBoundary != null
                        || leftBoundary.equals(rightBoundary)
                        || leftWitnessPath.size() < 2
                        || rightWitnessPath.size() < 2) {
                    throw new IllegalArgumentException(
                            "A disjoint dependent boundary has inconsistent evidence");
                }
            }
            default -> throw new IllegalStateException(
                    "Unhandled dependent boundary rule " + rule);
        }
    }

    private static List<GraphType> copyPath(
            List<GraphType> path,
            GraphType exact,
            GraphType common,
            String role) {
        Objects.requireNonNull(path, role + " witness path");
        if (path.isEmpty()
                || !exact.equals(path.get(0))
                || !common.equals(path.get(path.size() - 1))) {
            throw new IllegalArgumentException(
                    "A dependent " + role + " path has inconsistent endpoints");
        }
        List<GraphType> copied = new ArrayList<>(path.size());
        LinkedHashSet<GraphType> seen = new LinkedHashSet<>();
        for (GraphType step : path) {
            GraphType checked = Objects.requireNonNull(step, role + " path step");
            if (!seen.add(checked)) {
                throw new IllegalArgumentException(
                        "A dependent boundary witness path contains a cycle");
            }
            copied.add(checked);
        }
        int univ = copied.indexOf(UNIV);
        if (univ >= 0 && univ + 1 != copied.size()) {
            throw new IllegalArgumentException(
                    "AlloySig:univ must terminate a dependent boundary path");
        }
        return Collections.unmodifiableList(copied);
    }

    private static StructuralKey pathKey(
            String tag,
            List<GraphType> path) {
        return StructuralKey.branch(
                tag, path.stream().map(TheoryKeys::type).toList());
    }

    private static GraphType firstCommonAncestor(
            List<GraphType> left,
            List<GraphType> right) {
        LinkedHashSet<GraphType> rightSet = new LinkedHashSet<>(right);
        for (GraphType candidate : left) {
            if (rightSet.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isAlloySignature(GraphType type) {
        return DependentColumnEvidence.isAdmittedAtomicColumn(type);
    }

    public Rule rule() {
        return rule;
    }

    public boolean overlaps() {
        return rule != Rule.DISJOINT_BRANCHES;
    }

    public GraphType leftBoundary() {
        return leftBoundary;
    }

    public GraphType rightBoundary() {
        return rightBoundary;
    }

    public GraphType meetBoundary() {
        if (meetBoundary == null) {
            throw new IllegalStateException(
                    "Disjoint dependent branches have no meet carrier");
        }
        return meetBoundary;
    }

    public GraphType commonAncestor() {
        return commonAncestor;
    }

    public List<GraphType> leftWitnessPath() {
        return leftWitnessPath;
    }

    public List<GraphType> rightWitnessPath() {
        return rightWitnessPath;
    }

    /** Compatibility view retained for exact and one-sided subtype witnesses. */
    public List<GraphType> witnessPath() {
        return switch (rule) {
            case EXACT, LEFT_SUBTYPE_OF_RIGHT -> leftWitnessPath;
            case RIGHT_SUBTYPE_OF_LEFT -> rightWitnessPath;
            case DISJOINT_BRANCHES -> throw new IllegalStateException(
                    "A disjoint boundary requires both witness paths");
        };
    }

    public StructuralKey structuralKey() {
        return structuralKey;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DependentBoundaryCorrespondence
                && rule == ((DependentBoundaryCorrespondence) other).rule
                && leftBoundary.equals(
                        ((DependentBoundaryCorrespondence) other).leftBoundary)
                && rightBoundary.equals(
                        ((DependentBoundaryCorrespondence) other).rightBoundary)
                && Objects.equals(
                        meetBoundary,
                        ((DependentBoundaryCorrespondence) other).meetBoundary)
                && commonAncestor.equals(
                        ((DependentBoundaryCorrespondence) other).commonAncestor)
                && leftWitnessPath.equals(
                        ((DependentBoundaryCorrespondence) other).leftWitnessPath)
                && rightWitnessPath.equals(
                        ((DependentBoundaryCorrespondence) other).rightWitnessPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                rule,
                leftBoundary,
                rightBoundary,
                meetBoundary,
                commonAncestor,
                leftWitnessPath,
                rightWitnessPath);
    }
}
