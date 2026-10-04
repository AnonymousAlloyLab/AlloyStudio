package is.fivefivefive.CanDis.theory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import is.fivefivefive.ACGN.alloy.ExactAlloyType;

/** Independently fixed correlated-family theory for guarded JOIN and ARROW chains. */
public final class DependentChainTheory {
    public enum LeafTypeRule {
        EXACT_RELATION,
        PRIMITIVE_SET_SINGLETON,
        PARSER_AUTHENTICATED_SUBFAMILY
    }

    public static final String VERSION = "alloy-dependent-chain-theory-v11";
    public static final String SOURCE_TEXT = String.join("\n",
            "FAMILY:finite-union-of-correlated-ordered-products;normalized=subtype-antichain",
            "DAG:edges=specific-to-general;synthetic-union-and-common-ancestor-nodes-are-not-nominal-authority",
            "UNION:retain-correlation;deduplicate;absorb-only-authenticated-componentwise-subtypes",
            "INTERSECTION:pairwise-product-meet;omit-only-authenticated-disjoint-PrimSig-branches",
            "SET-DERIVATION:source UNION and INTERSECTION DAGs are recursively derived before parser-result equality",
            "JOIN:ordered;complete-alternative-pair-matrix;overlap=exact-or-one-endpoint-on-parser-derived-PrimSig-parent-path;result=init(left)++tail(right)",
            "ARROW:ordered;complete-cartesian-product;result=columns(left)++columns(right)",
            "SUBTYPE:path starts at exact AlloySig carrier, including parser-provided univ;edges are direct PrimSig parents;witness ends at opposite boundary",
            "SUBTYPE-HIERARCHY:single-parent;acyclic;univ-terminal;independent-verification-requires-external-source-hierarchy-authority",
            "DISJOINT:two distinct authenticated PrimSig branches with first common ancestor;univ-commonality-never-implies-overlap",
            "AUTHORITY:one-complete-nominal-path-per-top;one-live-parser-module-per-chain",
            "JOIN-FLAT-GUARD:every interior source operand has retained relation arity at least two, including typed-empty families",
            "LEAF:exact correlated relation family, Int/AlloyCarrier primitive singleton, or parser-authenticated same-arity subfamily;typed empty requires its live parser occurrence;no-name-based-parameter-authority",
            "UNIV:explicit parser-provided AlloySig:univ is an exact carrier;absent-or-unresolved-types-never-invent-univ",
            "EMPTY:positive-arity typed empty family has zero alternatives;all-disjoint JOIN retains complete evidence and ordered Seq",
            "CONTAINER:ordered-duplicate-preserving-Seq",
            "laws=guarded-associativity-only;no-commutativity;no-idempotency;no-unit");
    public static final String DIGEST = sha256(VERSION + "\n" + SOURCE_TEXT);

    private DependentChainTheory() {
    }

    /** Signals a valid expression whose source types do not license reassociation. */
    public static final class UnsupportedFlattening extends IllegalArgumentException {
        UnsupportedFlattening(String message) {
            super(message);
        }
    }

    public static void requireSoundFlattening(
            DependentChainKind kind,
            List<GraphType> operandTypes) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(operandTypes, "operandTypes");
        if (operandTypes.size() < 2) {
            throw new IllegalArgumentException(
                    "A dependent chain requires at least two operands");
        }
        for (GraphType operand : operandTypes) {
            GraphType checked = Objects.requireNonNull(operand, "operand type");
            if (!AlloyTypeBridge.isRelationFamily(checked)) {
                throw new IllegalArgumentException(
                        "A dependent-chain operand is not a correlated relation family");
            }
        }
        if (kind == DependentChainKind.JOIN && operandTypes.size() > 2) {
            for (int index = 1; index + 1 < operandTypes.size(); index++) {
                if (AlloyTypeBridge.relationArity(
                        operandTypes.get(index)) < 2) {
                    throw new UnsupportedFlattening(
                            "JOIN reassociation requires every interior operand "
                                    + "to retain distinct left and right boundary columns");
                }
            }
        }
    }

    public static StructuralKey proofIndex(
            DependentChainKind kind,
            List<GraphType> operandTypes,
            GraphType resultType) {
        requireSoundFlattening(kind, operandTypes);
        List<DependentTypeDag> dags = new ArrayList<>();
        for (GraphType operand : operandTypes) {
            dags.add(DependentTypeDag.fromRelationFamilyType(operand));
        }
        return proofIndex(
                kind,
                dags,
                DependentTypeDag.fromRelationFamilyType(resultType));
    }

    public static StructuralKey proofIndex(
            DependentChainApplication source) {
        Objects.requireNonNull(source, "source");
        List<DependentChainLeaf> leaves = source.leafInputs();
        requireSoundFlatteningEvidence(source.kind(), leaves);
        return proofIndex(
                source.kind(),
                leaves.stream().map(DependentChainLeaf::outputTypeDag).toList(),
                source.outputTypeDag());
    }

    public static void requireSoundFlatteningEvidence(
            DependentChainKind kind,
            List<DependentChainLeaf> leaves) {
        Objects.requireNonNull(leaves, "leaves");
        List<GraphType> operandTypes = leaves.stream()
                .map(DependentChainLeaf::outputType)
                .toList();
        requireSoundFlattening(kind, operandTypes);
        List<DependentTypeDag> dags = leaves.stream()
                .map(DependentChainLeaf::outputTypeDag)
                .toList();
        DependentTypeDag.fold(kind, dags);
    }

    public static List<DependentBoundaryCorrespondence> boundaryCorrespondences(
            DependentChainKind kind,
            List<? extends List<DependentColumnEvidence>> operandColumns) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(operandColumns, "operandColumns");
        requireConsistentHierarchy(operandColumns);
        if (kind == DependentChainKind.ARROW) {
            return List.of();
        }
        List<DependentBoundaryCorrespondence> result = new ArrayList<>();
        for (int index = 1; index < operandColumns.size(); index++) {
            List<DependentColumnEvidence> left = Objects.requireNonNull(
                    operandColumns.get(index - 1), "left operand columns");
            List<DependentColumnEvidence> right = Objects.requireNonNull(
                    operandColumns.get(index), "right operand columns");
            if (left.isEmpty() || right.isEmpty()) {
                throw new IllegalArgumentException(
                        "A JOIN boundary requires nonempty relation operands");
            }
            result.add(DependentBoundaryCorrespondence.derive(
                    left.get(left.size() - 1), right.get(0)));
        }
        return List.copyOf(result);
    }

    static void requireConsistentHierarchy(
            List<? extends List<DependentColumnEvidence>> operandColumns) {
        Map<GraphType, List<GraphType>> pathsByExact = new LinkedHashMap<>();
        Map<GraphType, GraphType> directParents = new LinkedHashMap<>();
        for (List<DependentColumnEvidence> operand : operandColumns) {
            Objects.requireNonNull(operand, "operand columns");
            for (DependentColumnEvidence column : operand) {
                DependentColumnEvidence checked = Objects.requireNonNull(
                        column, "dependent column");
                List<GraphType> path = checked.ancestry();
                List<GraphType> previousPath = pathsByExact.putIfAbsent(
                        checked.exactColumn(), path);
                if (previousPath != null && !previousPath.equals(path)) {
                    throw new IllegalArgumentException(
                            "One exact dependent-chain carrier has conflicting subtype stacks: "
                                    + checked.exactColumn() + " -> "
                                    + previousPath + " and " + path);
                }
                for (int index = 1; index < path.size(); index++) {
                    GraphType child = path.get(index - 1);
                    GraphType parent = path.get(index);
                    GraphType previousParent = directParents.putIfAbsent(
                            child, parent);
                    if (previousParent != null && !previousParent.equals(parent)) {
                        throw new IllegalArgumentException(
                                "One JOIN subtype carrier has two direct parents");
                    }
                }
            }
        }
        for (GraphType start : directParents.keySet()) {
            java.util.Set<GraphType> seen = new LinkedHashSet<>();
            GraphType current = start;
            while (current != null) {
                if (!seen.add(current)) {
                    throw new IllegalArgumentException(
                            "JOIN subtype ancestry contains a cross-path cycle");
                }
                current = directParents.get(current);
            }
        }
    }

    private static StructuralKey proofIndex(
            DependentChainKind kind,
            List<DependentTypeDag> operandDags,
            DependentTypeDag resultDag) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(operandDags, "operandDags");
        Objects.requireNonNull(resultDag, "resultDag");
        requireSoundFlattening(
                kind,
                operandDags.stream()
                        .map(DependentTypeDag::relationType)
                        .toList());
        if (operandDags.size() < 2) {
            throw new IllegalArgumentException(
                    "A dependent-chain proof index requires at least two operands");
        }
        List<StructuralKey> operandKeys = operandDags.stream()
                .map(DependentTypeDag::structuralKey)
                .toList();
        List<StructuralKey> foldSteps = new ArrayList<>();
        DependentTypeDag folded = operandDags.get(0);
        for (int index = 1; index < operandDags.size(); index++) {
            DependentTypeDag right = Objects.requireNonNull(
                    operandDags.get(index), "operand DAG");
            DependentTypeDag.ChainCombination combination =
                    DependentTypeDag.combine(kind, folded, right);
            foldSteps.add(StructuralKey.of(
                    "dependent-chain-fold-step-v1",
                    List.of(Integer.toString(index)),
                    List.of(
                            folded.structuralKey(),
                            right.structuralKey(),
                            StructuralKey.branch(
                                    "dependent-chain-complete-case-matrix-v1",
                                    combination.cases().stream()
                                            .map(DependentTypeDag.CombinationCase::structuralKey)
                                            .toList()),
                            combination.result().structuralKey())));
            folded = combination.result();
        }
        if (!folded.equals(resultDag)) {
            throw new IllegalArgumentException(
                    "Dependent DAG fold names another correlated result family");
        }
        return StructuralKey.of(
                "dependent-chain-theory-index-v3",
                List.of(VERSION, DIGEST, kind.name()),
                List.of(
                        StructuralKey.branch(
                                "dependent-chain-operand-dags-v1", operandKeys),
                        StructuralKey.branch(
                                "dependent-chain-fold-steps-v1", foldSteps),
                        resultDag.structuralKey()));
    }

    public static LeafTypeRule requireLeafTypeProof(
            GraphType storedType,
            GraphType relationType) {
        if (storedType.equals(relationType)
                && AlloyTypeBridge.isCommutativeRelationCarrier(relationType)) {
            DependentTypeDag.fromRelationFamilyType(relationType);
            return LeafTypeRule.EXACT_RELATION;
        }
        if (!AlloyTypeBridge.isRelationFamily(relationType)) {
            throw new IllegalArgumentException(
                    "A dependent-chain relation view is not a correlated family: "
                            + relationType);
        }
        List<GraphType> alternatives = AlloyTypeBridge.relationAlternatives(
                relationType);
        if (alternatives.size() != 1
                || alternatives.get(0).arguments().size() != 1) {
            throw new IllegalArgumentException(
                    "A primitive set slot can justify only a unary relation view");
        }
        GraphType expectedColumn = primitiveRelationColumn(storedType);
        if (expectedColumn == null
                || !expectedColumn.equals(
                        alternatives.get(0).arguments().get(0))) {
            throw new IllegalArgumentException(
                    "Stored primitive type " + storedType
                            + " does not justify relation view " + relationType);
        }
        return LeafTypeRule.PRIMITIVE_SET_SINGLETON;
    }

    static LeafTypeRule requireLeafTypeProof(
            GraphType storedType,
            DependentTypeDag relationDag,
            ExactAlloyType sourceAuthority) {
        Objects.requireNonNull(storedType, "storedType");
        Objects.requireNonNull(relationDag, "relationDag");
        try {
            return requireLeafTypeProof(
                    storedType, relationDag.relationType());
        } catch (IllegalArgumentException exactRuleUnavailable) {
            GraphType carrier = AlloyTypeBridge.isRelationFamily(storedType)
                    ? storedType : relationViewFromStoredType(storedType);
            if (sourceAuthority != null
                    && relationDag.isParserAuthenticatedSubfamilyOf(
                    carrier, sourceAuthority)) {
                return LeafTypeRule.PARSER_AUTHENTICATED_SUBFAMILY;
            }
            throw new IllegalArgumentException(
                    "Stored type " + storedType
                            + " does not admit parser-derived dependent family "
                            + relationDag.relationType(),
                    exactRuleUnavailable);
        }
    }

    public static GraphType relationViewFromStoredType(GraphType storedType) {
        Objects.requireNonNull(storedType, "storedType");
        if (AlloyTypeBridge.isRelationFamily(storedType)) {
            return storedType;
        }
        GraphType column = primitiveRelationColumn(storedType);
        if (column == null) {
            throw new IllegalArgumentException(
                    "No fixed unary relation view for stored type " + storedType);
        }
        return GraphType.relation(column);
    }

    public static StructuralKey leafTypeProof(
            LeafTypeRule rule,
            GraphType storedType,
            GraphType relationType) {
        LeafTypeRule checked = requireLeafTypeProof(storedType, relationType);
        if (rule != checked) {
            throw new IllegalArgumentException(
                    "Dependent-chain leaf proof names another typing rule");
        }
        return StructuralKey.of(
                "dependent-chain-leaf-type-proof-v1",
                List.of(rule.name()),
                List.of(TheoryKeys.type(storedType), TheoryKeys.type(relationType)));
    }

    static StructuralKey leafTypeProof(
            LeafTypeRule rule,
            GraphType storedType,
            DependentTypeDag relationDag,
            ExactAlloyType sourceAuthority) {
        LeafTypeRule checked = requireLeafTypeProof(
                storedType, relationDag, sourceAuthority);
        if (rule != checked) {
            throw new IllegalArgumentException(
                    "Dependent-chain leaf proof names another typing rule");
        }
        return StructuralKey.of(
                "dependent-chain-leaf-type-proof-v2",
                List.of(
                        rule.name(),
                        sourceAuthority == null
                                ? "no-parser-source"
                                : sourceAuthority.stableString()),
                List.of(
                        TheoryKeys.type(storedType),
                        relationDag.structuralKey()));
    }

    private static GraphType primitiveRelationColumn(GraphType storedType) {
        if (storedType.equals(GraphType.INT)) {
            return GraphType.INT;
        }
        if (storedType.kind() != GraphType.Kind.CONSTRUCTOR
                || !"AlloyCarrier".equals(storedType.symbol())
                || storedType.arguments().size() != 1) {
            return null;
        }
        GraphType carrier = storedType.arguments().get(0);
        if (carrier.kind() != GraphType.Kind.CONSTRUCTOR
                || !carrier.arguments().isEmpty()) {
            return null;
        }
        String symbol = carrier.symbol();
        if (symbol.startsWith("AlloySig:")) {
            return DependentColumnEvidence.isAdmittedAtomicColumn(carrier)
                    ? carrier : null;
        }
        GraphType column = GraphType.constructor("AlloySig:" + symbol);
        return DependentColumnEvidence.isAdmittedAtomicColumn(column)
                ? column : null;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
