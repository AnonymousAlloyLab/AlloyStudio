package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

import is.fivefivefive.ACGN.alloy.ExactAlloyType;

/** Total conversion from occurrence-level Alloy types into structural graph types. */
public final class AlloyTypeBridge {
    private static final String EMPTY_RELATION_PREFIX =
            "AlloyEmptyRelation$arity=";

    private AlloyTypeBridge() {
    }

    public static GraphType graphType(ExactAlloyType type) {
        Objects.requireNonNull(type, "type");
        switch (type.kind()) {
            case BOOL:
                return GraphType.BOOL;
            case INT:
                return GraphType.INT;
            case EMPTY_RELATION:
                return emptyRelation(type.relationArity());
            case RELATION:
                TreeSet<GraphType> sortedAlternatives = new TreeSet<>();
                for (int alternative : retainedAntichainAlternatives(type)) {
                    List<String> tuple = type.alternatives().get(alternative);
                    List<GraphType> columns = new ArrayList<>(tuple.size());
                    for (String column : tuple) {
                        columns.add(alloyColumn(column));
                    }
                    sortedAlternatives.add(GraphType.relation(columns));
                }
                List<GraphType> alternatives = List.copyOf(sortedAlternatives);
                if (alternatives.size() == 1) {
                    return alternatives.get(0);
                }
                return GraphType.constructor("AlloyRelationUnion", alternatives);
            case UNKNOWN:
            default:
                throw new IllegalStateException("An exact Alloy expression type is unavailable");
        }
    }

    /**
     * Removes only a parser-authenticated product that is componentwise below
     * another product already present in the same static union.  This changes
     * the representation, not the denotation: {@code A + P = P} when every
     * column of {@code A} is on the recorded subtype path to {@code P}.
     */
    private static List<Integer> retainedAntichainAlternatives(
            ExactAlloyType type) {
        List<Integer> retained = new ArrayList<>();
        List<List<String>> alternatives = type.alternatives();
        List<List<List<String>>> ancestries = type.ancestryAlternatives();
        for (int candidate = 0; candidate < alternatives.size(); candidate++) {
            boolean absorbed = false;
            if (type.hasParserAuthenticatedAncestry()) {
                for (int carrier = 0; carrier < alternatives.size(); carrier++) {
                    if (candidate != carrier
                            && parserProductSubtypeOf(
                                    alternatives.get(candidate),
                                    ancestries.get(candidate),
                                    alternatives.get(carrier))) {
                        absorbed = true;
                        break;
                    }
                }
            }
            if (!absorbed) {
                retained.add(candidate);
            }
        }
        if (retained.isEmpty()) {
            throw new IllegalStateException(
                    "A nonempty exact Alloy relation lost every antichain alternative");
        }
        return Collections.unmodifiableList(retained);
    }

    private static boolean parserProductSubtypeOf(
            List<String> candidate,
            List<List<String>> candidateAncestry,
            List<String> carrier) {
        if (candidate.size() != carrier.size()
                || candidate.size() != candidateAncestry.size()) {
            return false;
        }
        boolean strict = false;
        for (int column = 0; column < candidate.size(); column++) {
            String candidateColumn = candidate.get(column);
            String carrierColumn = carrier.get(column);
            if (!candidateAncestry.get(column).contains(carrierColumn)) {
                return false;
            }
            strict |= !candidateColumn.equals(carrierColumn);
        }
        return strict;
    }

    /** Proof-only column ancestry for one exact, non-union relation occurrence. */
    public static List<DependentColumnEvidence> dependentColumns(
            ExactAlloyType type) {
        Objects.requireNonNull(type, "type");
        if (type.kind() != ExactAlloyType.Kind.RELATION
                || type.alternatives().size() != 1
                || type.ancestryAlternatives().size() != 1) {
            throw new IllegalArgumentException(
                    "A dependent chain leaf requires one exact relation alternative");
        }
        List<String> columns = type.alternatives().get(0);
        List<List<String>> ancestries = type.ancestryAlternatives().get(0);
        if (columns.size() != ancestries.size()) {
            throw new IllegalArgumentException(
                    "Exact Alloy columns and ancestry proofs differ in arity");
        }
        if (!type.hasParserAuthenticatedAncestry()
                && ancestries.stream().anyMatch(path -> path.size() > 1)) {
            throw new DependentChainTheory.UnsupportedFlattening(
                    "Nontrivial dependent ancestry requires live parser authority");
        }
        List<DependentColumnEvidence> result = new ArrayList<>(columns.size());
        for (int index = 0; index < columns.size(); index++) {
            result.add(DependentColumnEvidence.fromExactAlloyType(type, index));
        }
        return Collections.unmodifiableList(result);
    }

    /** Authenticated correlated alternatives for one exact Alloy relation type. */
    public static DependentTypeDag dependentTypeDag(ExactAlloyType type) {
        return DependentTypeDag.fromExactAlloyType(type);
    }

    public static GraphType commutativeCarrier(List<GraphType> operandTypes) {
        Objects.requireNonNull(operandTypes, "operandTypes");
        if (operandTypes.isEmpty()) {
            throw new IllegalArgumentException("A commutative carrier requires operands");
        }
        TreeSet<GraphType> unique = new TreeSet<>();
        for (GraphType type : operandTypes) {
            unique.add(Objects.requireNonNull(type, "operand type"));
        }
        if (unique.size() == 1) {
            return unique.first();
        }
        return GraphType.constructor(
                "AlloyComparableCarrier", Collections.unmodifiableList(new ArrayList<>(unique)));
    }

    static GraphType alloyColumn(String name) {
        String checked = normalizeAlloyIdentity(name);
        return "Int".equals(checked)
                ? GraphType.INT
                : GraphType.constructor("AlloySig:" + checked);
    }

    static String normalizeAlloyIdentity(String value) {
        if (!isAdmittedIdentity(value)) {
            throw new IllegalArgumentException(
                    "An Alloy column must be a well-formed visible identity");
        }
        String normalized = value.startsWith("this/")
                ? value.substring("this/".length()) : value;
        if (normalized.isEmpty() || normalized.startsWith("this/")) {
            throw new IllegalArgumentException(
                    "An Alloy column must have one canonical identity after its module prefix");
        }
        return normalized;
    }

    public static boolean isAdmittedIdentity(String value) {
        return ExactAlloyType.isAdmittedIdentity(value);
    }

    /** True only for an exact relation or a validated same-arity relation family. */
    public static boolean isCommutativeRelationCarrier(GraphType type) {
        return relationArityOrNull(Objects.requireNonNull(type, "type")) != null;
    }

    /** True for an exact product, typed empty family, or correlated family. */
    public static boolean isRelationFamily(GraphType type) {
        Objects.requireNonNull(type, "type");
        if (type.kind() == GraphType.Kind.RELATION) {
            return true;
        }
        if (emptyRelationArity(type) != null) {
            return true;
        }
        if (type.kind() != GraphType.Kind.CONSTRUCTOR
                || !"AlloyRelationUnion".equals(type.symbol())
                || type.arguments().size() < 2) {
            return false;
        }
        Integer arity = null;
        GraphType previous = null;
        for (GraphType alternative : type.arguments()) {
            if (alternative.kind() != GraphType.Kind.RELATION
                    || (arity != null
                            && arity.intValue() != alternative.arguments().size())
                    || previous != null && previous.compareTo(alternative) >= 0) {
                return false;
            }
            arity = alternative.arguments().size();
            previous = alternative;
        }
        return true;
    }

    /** Ordered nonempty product alternatives; typed empty families have none. */
    public static List<GraphType> relationAlternatives(GraphType type) {
        Objects.requireNonNull(type, "type");
        if (type.kind() == GraphType.Kind.RELATION) {
            return List.of(type);
        }
        if (emptyRelationArity(type) != null) {
            return List.of();
        }
        if (!isRelationFamily(type)) {
            throw new IllegalArgumentException(
                    "Not an exact correlated relation family: " + type);
        }
        return type.arguments();
    }

    public static int relationArity(GraphType type) {
        Integer arity = relationArityOrNull(Objects.requireNonNull(type, "type"));
        if (arity == null) {
            throw new IllegalArgumentException(
                    "Not a same-arity relation family: " + type);
        }
        return arity;
    }

    /** True when every candidate relation alternative is admitted by one carrier. */
    public static boolean isRelationSubfamily(
            GraphType candidate,
            GraphType carrier) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(carrier, "carrier");
        if (!isRelationFamily(candidate) || !isRelationFamily(carrier)
                || relationArity(candidate) != relationArity(carrier)) {
            return false;
        }
        return relationAlternatives(carrier).containsAll(
                relationAlternatives(candidate));
    }

    public static GraphType emptyRelation(int arity) {
        if (arity <= 0) {
            throw new IllegalArgumentException(
                    "An empty relation requires positive arity");
        }
        return GraphType.constructor(EMPTY_RELATION_PREFIX + arity);
    }

    private static Integer relationArityOrNull(GraphType type) {
        if (type.kind() == GraphType.Kind.RELATION) {
            return type.arguments().size();
        }
        if (isPrimitiveUnaryCarrier(type)) {
            return 1;
        }
        Integer emptyArity = emptyRelationArity(type);
        if (emptyArity != null) {
            return emptyArity;
        }
        if (type.kind() != GraphType.Kind.CONSTRUCTOR
                || !("AlloyComparableCarrier".equals(type.symbol())
                        || "AlloyRelationUnion".equals(type.symbol()))
                || type.arguments().isEmpty()) {
            return null;
        }
        Integer arity = null;
        for (GraphType alternative : type.arguments()) {
            Integer next = relationArityOrNull(alternative);
            if (next == null || (arity != null && !arity.equals(next))) {
                return null;
            }
            arity = next;
        }
        return arity;
    }

    private static boolean isPrimitiveUnaryCarrier(GraphType type) {
        if (type.kind() != GraphType.Kind.CONSTRUCTOR
                || !"AlloyCarrier".equals(type.symbol())
                || type.arguments().size() != 1) {
            return false;
        }
        GraphType carrier = type.arguments().get(0);
        if (carrier.kind() != GraphType.Kind.CONSTRUCTOR
                || !carrier.arguments().isEmpty()) {
            return false;
        }
        String identity = carrier.symbol().startsWith("AlloySig:")
                ? carrier.symbol().substring("AlloySig:".length())
                : carrier.symbol();
        return isAdmittedIdentity(identity);
    }

    public static Integer emptyRelationArity(GraphType type) {
        Objects.requireNonNull(type, "type");
        if (type.kind() != GraphType.Kind.CONSTRUCTOR
                || !type.arguments().isEmpty()
                || !type.symbol().startsWith(EMPTY_RELATION_PREFIX)) {
            return null;
        }
        String encoded = type.symbol().substring(EMPTY_RELATION_PREFIX.length());
        try {
            int arity = Integer.parseInt(encoded);
            return arity > 0 && Integer.toString(arity).equals(encoded)
                    ? arity : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
