package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import is.fivefivefive.ACGN.alloy.ExactAlloyType;

/**
 * Authenticated finite type DAG for Alloy relation families.
 *
 * <p>A relation family is a finite union of correlated ordered products. Each
 * product column retains its parser-authenticated nominal ancestry. Edges point
 * from a specific carrier toward a more general carrier; the synthetic family
 * and common-ancestor nodes are observations only and never become nominal
 * subtype or JOIN-overlap authority.</p>
 */
public final class DependentTypeDag {
    public enum CombinationDecision {
        ARROW_PRODUCT,
        JOIN_OVERLAP,
        JOIN_DISJOINT
    }

    /** One exhaustive pair in an ARROW product or JOIN boundary matrix. */
    public static final class CombinationCase {
        private final int leftAlternative;
        private final int rightAlternative;
        private final CombinationDecision decision;
        private final DependentBoundaryCorrespondence boundary;
        private final List<DependentColumnEvidence> resultAlternative;
        private final StructuralKey structuralKey;

        private CombinationCase(
                int leftAlternative,
                int rightAlternative,
                CombinationDecision decision,
                DependentBoundaryCorrespondence boundary,
                List<DependentColumnEvidence> resultAlternative) {
            if (leftAlternative < 0 || rightAlternative < 0) {
                throw new IllegalArgumentException(
                        "A dependent combination case has a negative alternative index");
            }
            this.leftAlternative = leftAlternative;
            this.rightAlternative = rightAlternative;
            this.decision = Objects.requireNonNull(decision, "decision");
            this.boundary = boundary;
            this.resultAlternative = resultAlternative == null
                    ? null : List.copyOf(resultAlternative);
            validate();
            this.structuralKey = StructuralKey.of(
                    "dependent-type-combination-case-v1",
                    List.of(
                            Integer.toString(leftAlternative),
                            Integer.toString(rightAlternative),
                            decision.name()),
                    List.of(
                            boundary == null
                                    ? StructuralKey.leaf(
                                            "dependent-type-no-boundary-v1", "arrow")
                                    : boundary.structuralKey(),
                            resultAlternative == null
                                    ? StructuralKey.leaf(
                                            "dependent-type-empty-result-v1", "disjoint")
                                    : productKey(
                                            "dependent-type-case-result-v1",
                                            this.resultAlternative)));
        }

        private void validate() {
            switch (decision) {
                case ARROW_PRODUCT -> {
                    if (boundary != null || resultAlternative == null) {
                        throw new IllegalArgumentException(
                                "An ARROW product case has invalid boundary evidence");
                    }
                }
                case JOIN_OVERLAP -> {
                    if (boundary == null || !boundary.overlaps()
                            || resultAlternative == null) {
                        throw new IllegalArgumentException(
                                "A JOIN overlap case has invalid boundary evidence");
                    }
                }
                case JOIN_DISJOINT -> {
                    if (boundary == null || boundary.overlaps()
                            || resultAlternative != null) {
                        throw new IllegalArgumentException(
                                "A JOIN disjoint case has invalid boundary evidence");
                    }
                }
                default -> throw new IllegalStateException(
                        "Unhandled dependent combination decision " + decision);
            }
        }

        public int leftAlternative() {
            return leftAlternative;
        }

        public int rightAlternative() {
            return rightAlternative;
        }

        public CombinationDecision decision() {
            return decision;
        }

        public Optional<DependentBoundaryCorrespondence> boundary() {
            return Optional.ofNullable(boundary);
        }

        public Optional<List<DependentColumnEvidence>> resultAlternative() {
            return Optional.ofNullable(resultAlternative);
        }

        public StructuralKey structuralKey() {
            return structuralKey;
        }
    }

    /** Result plus the complete row-major proof matrix for one binary chain step. */
    public static final class ChainCombination {
        private final DependentTypeDag result;
        private final List<CombinationCase> cases;

        private ChainCombination(
                DependentTypeDag result,
                List<CombinationCase> cases,
                int leftAlternatives,
                int rightAlternatives) {
            this.result = Objects.requireNonNull(result, "result");
            this.cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
            int expected = Math.multiplyExact(leftAlternatives, rightAlternatives);
            if (this.cases.size() != expected) {
                throw new IllegalArgumentException(
                        "A dependent combination omitted an alternative pair");
            }
            int offset = 0;
            for (int left = 0; left < leftAlternatives; left++) {
                for (int right = 0; right < rightAlternatives; right++) {
                    CombinationCase proof = this.cases.get(offset++);
                    if (proof.leftAlternative() != left
                            || proof.rightAlternative() != right) {
                        throw new IllegalArgumentException(
                                "A dependent combination matrix is not complete row-major evidence");
                    }
                }
            }
        }

        public DependentTypeDag result() {
            return result;
        }

        public List<CombinationCase> cases() {
            return cases;
        }
    }

    private static final GraphType UNIV =
            GraphType.constructor("AlloySig:univ");

    private final List<List<DependentColumnEvidence>> alternatives;
    private final int arity;
    private final GraphType relationType;
    private final GraphType commonAncestorType;
    private final StructuralKey structuralKey;

    private DependentTypeDag(
            List<? extends List<DependentColumnEvidence>> sourceAlternatives) {
        this(sourceAlternatives, -1);
    }

    private DependentTypeDag(
            List<? extends List<DependentColumnEvidence>> sourceAlternatives,
            int emptyArity) {
        Objects.requireNonNull(sourceAlternatives, "alternatives");
        if (sourceAlternatives.isEmpty()) {
            if (emptyArity <= 0) {
                throw new IllegalArgumentException(
                        "A dependent empty relation family requires positive arity");
            }
            this.alternatives = List.of();
            this.arity = emptyArity;
            this.relationType = AlloyTypeBridge.emptyRelation(emptyArity);
            this.commonAncestorType = null;
        } else {
            this.alternatives = normalize(sourceAlternatives);
            this.arity = this.alternatives.get(0).size();
            this.relationType = relationFamilyType(this.alternatives);
            this.commonAncestorType = commonAncestorType(this.alternatives);
        }
        List<StructuralKey> products = this.alternatives.stream()
                .map(product -> productKey("dependent-type-product-v1", product))
                .toList();
        this.structuralKey = StructuralKey.of(
                "dependent-type-dag-v1",
                List.of(Integer.toString(arity())),
                List.of(
                        StructuralKey.branch(
                                "dependent-type-correlated-alternatives-v1", products),
                        TheoryKeys.type(relationType),
                        commonAncestorType == null
                                ? StructuralKey.leaf(
                                        "dependent-type-no-common-ancestor-v1", "none")
                                : TheoryKeys.type(commonAncestorType)));
    }

    public static DependentTypeDag fromExactAlloyType(ExactAlloyType type) {
        Objects.requireNonNull(type, "type");
        if (type.kind() == ExactAlloyType.Kind.EMPTY_RELATION) {
            return empty(type.relationArity());
        }
        if (type.kind() != ExactAlloyType.Kind.RELATION
                || type.alternatives().isEmpty()
                || type.alternatives().size()
                        != type.ancestryAlternatives().size()) {
            throw new IllegalArgumentException(
                    "A dependent type DAG requires exact nonempty relation alternatives");
        }
        List<List<DependentColumnEvidence>> alternatives = new ArrayList<>();
        for (int alternative = 0;
                alternative < type.alternatives().size();
                alternative++) {
            List<String> columns = type.alternatives().get(alternative);
            List<DependentColumnEvidence> product = new ArrayList<>(columns.size());
            for (int column = 0; column < columns.size(); column++) {
                product.add(DependentColumnEvidence.fromExactAlloyType(
                        type, alternative, column));
            }
            alternatives.add(product);
        }
        DependentTypeDag result = new DependentTypeDag(alternatives);
        GraphType parserType = AlloyTypeBridge.graphType(type);
        if (!parserType.equals(result.relationType())) {
            throw new IllegalArgumentException(
                    "The dependent DAG normalization disagrees with the exact Alloy type: "
                            + parserType + " versus " + result.relationType());
        }
        return result;
    }

    public static DependentTypeDag exactAlternative(
            GraphType relationType,
            List<DependentColumnEvidence> columns) {
        Objects.requireNonNull(relationType, "relationType");
        Objects.requireNonNull(columns, "columns");
        if (relationType.kind() != GraphType.Kind.RELATION
                || relationType.arguments().size() != columns.size()) {
            throw new IllegalArgumentException(
                    "A dependent exact alternative must match one relation type");
        }
        for (int index = 0; index < columns.size(); index++) {
            if (!relationType.arguments().get(index).equals(
                    columns.get(index).exactColumn())) {
                throw new IllegalArgumentException(
                        "A dependent exact alternative names another column at " + index);
            }
        }
        return new DependentTypeDag(List.of(columns));
    }

    public static DependentTypeDag exactRelation(GraphType relationType) {
        Objects.requireNonNull(relationType, "relationType");
        if (relationType.kind() != GraphType.Kind.RELATION) {
            throw new IllegalArgumentException(
                    "A dependent exact relation requires one product type");
        }
        return exactAlternative(
                relationType,
                relationType.arguments().stream()
                        .map(DependentColumnEvidence::exact)
                        .toList());
    }

    public static DependentTypeDag fromRelationFamilyType(GraphType relationType) {
        Objects.requireNonNull(relationType, "relationType");
        Integer emptyArity = AlloyTypeBridge.emptyRelationArity(relationType);
        if (emptyArity != null) {
            return empty(emptyArity);
        }
        List<List<DependentColumnEvidence>> products = new ArrayList<>();
        for (GraphType alternative : AlloyTypeBridge.relationAlternatives(
                relationType)) {
            products.add(alternative.arguments().stream()
                    .map(DependentColumnEvidence::exact)
                    .toList());
        }
        DependentTypeDag result = new DependentTypeDag(products);
        if (!result.relationType().equals(relationType)) {
            throw new IllegalArgumentException(
                    "A relation-family type is not in normalized DAG order");
        }
        return result;
    }

    public static DependentTypeDag empty(int arity) {
        return new DependentTypeDag(List.of(), arity);
    }

    public static DependentTypeDag union(List<DependentTypeDag> operands) {
        Objects.requireNonNull(operands, "operands");
        if (operands.size() < 2) {
            throw new IllegalArgumentException(
                    "A dependent union requires at least two operands");
        }
        List<List<DependentColumnEvidence>> alternatives = new ArrayList<>();
        int arity = -1;
        for (DependentTypeDag operand : operands) {
            DependentTypeDag checked = Objects.requireNonNull(
                    operand, "union operand");
            if (arity < 0) {
                arity = checked.arity();
            } else if (arity != checked.arity()) {
                throw new IllegalArgumentException(
                        "A dependent union requires equal relation arities");
            }
            alternatives.addAll(checked.alternatives());
        }
        return alternatives.isEmpty()
                ? empty(arity) : new DependentTypeDag(alternatives);
    }

    public static DependentTypeDag intersection(List<DependentTypeDag> operands) {
        Objects.requireNonNull(operands, "operands");
        if (operands.size() < 2) {
            throw new IllegalArgumentException(
                    "A dependent intersection requires at least two operands");
        }
        DependentTypeDag result = Objects.requireNonNull(
                operands.get(0), "intersection operand");
        for (int index = 1; index < operands.size(); index++) {
            result = intersectPair(
                    result,
                    Objects.requireNonNull(
                            operands.get(index), "intersection operand"));
        }
        return result;
    }

    private static DependentTypeDag intersectPair(
            DependentTypeDag left,
            DependentTypeDag right) {
        if (left.arity() != right.arity()) {
            throw new IllegalArgumentException(
                    "A dependent intersection requires equal relation arities");
        }
        requireCompatibleAuthority(left.alternatives, right.alternatives);
        if (left.alternatives.isEmpty() || right.alternatives.isEmpty()) {
            return empty(left.arity());
        }
        List<List<DependentColumnEvidence>> products = new ArrayList<>();
        for (List<DependentColumnEvidence> leftProduct : left.alternatives) {
            for (List<DependentColumnEvidence> rightProduct : right.alternatives) {
                List<DependentColumnEvidence> product = new ArrayList<>(left.arity());
                boolean disjoint = false;
                for (int column = 0; column < left.arity(); column++) {
                    DependentBoundaryCorrespondence proof =
                            DependentBoundaryCorrespondence.derive(
                                    leftProduct.get(column),
                                    rightProduct.get(column));
                    if (!proof.overlaps()) {
                        disjoint = true;
                        break;
                    }
                    product.add(moreSpecific(
                            leftProduct.get(column),
                            rightProduct.get(column),
                            proof));
                }
                if (!disjoint) {
                    products.add(product);
                }
            }
        }
        return products.isEmpty()
                ? empty(left.arity()) : new DependentTypeDag(products);
    }

    public static ChainCombination combine(
            DependentChainKind kind,
            DependentTypeDag left,
            DependentTypeDag right) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        requireCompatibleAuthority(left.alternatives, right.alternatives);
        int resultArity = kind == DependentChainKind.ARROW
                ? Math.addExact(left.arity(), right.arity())
                : Math.subtractExact(
                        Math.addExact(left.arity(), right.arity()), 2);
        if (resultArity <= 0) {
            throw new DependentChainTheory.UnsupportedFlattening(
                    "The certified Alloy relation slice has no nullary relation type");
        }
        List<List<DependentColumnEvidence>> products = new ArrayList<>();
        List<CombinationCase> cases = new ArrayList<>();
        for (int leftIndex = 0;
                leftIndex < left.alternatives.size();
                leftIndex++) {
            List<DependentColumnEvidence> leftProduct =
                    left.alternatives.get(leftIndex);
            for (int rightIndex = 0;
                    rightIndex < right.alternatives.size();
                    rightIndex++) {
                List<DependentColumnEvidence> rightProduct =
                        right.alternatives.get(rightIndex);
                if (kind == DependentChainKind.ARROW) {
                    List<DependentColumnEvidence> product = new ArrayList<>(
                            leftProduct.size() + rightProduct.size());
                    product.addAll(leftProduct);
                    product.addAll(rightProduct);
                    products.add(product);
                    cases.add(new CombinationCase(
                            leftIndex,
                            rightIndex,
                            CombinationDecision.ARROW_PRODUCT,
                            null,
                            product));
                    continue;
                }
                DependentBoundaryCorrespondence boundary =
                        DependentBoundaryCorrespondence.derive(
                                leftProduct.get(leftProduct.size() - 1),
                                rightProduct.get(0));
                if (!boundary.overlaps()) {
                    cases.add(new CombinationCase(
                            leftIndex,
                            rightIndex,
                            CombinationDecision.JOIN_DISJOINT,
                            boundary,
                            null));
                    continue;
                }
                List<DependentColumnEvidence> product = new ArrayList<>(
                        leftProduct.size() + rightProduct.size() - 2);
                product.addAll(leftProduct.subList(0, leftProduct.size() - 1));
                product.addAll(rightProduct.subList(1, rightProduct.size()));
                if (product.isEmpty()) {
                    throw new IllegalStateException(
                            "A positive JOIN result arity produced a nullary alternative");
                }
                products.add(product);
                cases.add(new CombinationCase(
                        leftIndex,
                        rightIndex,
                        CombinationDecision.JOIN_OVERLAP,
                        boundary,
                        product));
            }
        }
        return new ChainCombination(
                products.isEmpty()
                        ? empty(resultArity) : new DependentTypeDag(products),
                cases,
                left.alternatives.size(),
                right.alternatives.size());
    }

    public static DependentTypeDag fold(
            DependentChainKind kind,
            List<DependentTypeDag> operands) {
        Objects.requireNonNull(operands, "operands");
        if (operands.size() < 2) {
            throw new IllegalArgumentException(
                    "A dependent chain requires at least two operands");
        }
        List<DependentTypeDag> checkedOperands = new ArrayList<>(
                operands.size());
        for (DependentTypeDag operand : operands) {
            checkedOperands.add(Objects.requireNonNull(
                    operand, "operand DAG"));
        }
        DependentChainTheory.requireSoundFlattening(
                kind,
                checkedOperands.stream()
                        .map(DependentTypeDag::relationType)
                        .toList());
        List<List<DependentColumnEvidence>> originalAlternatives =
                new ArrayList<>();
        for (DependentTypeDag operand : checkedOperands) {
            originalAlternatives.addAll(operand.alternatives);
        }
        requireCompatibleAuthority(originalAlternatives);
        DependentChainTheory.requireConsistentHierarchy(originalAlternatives);
        DependentTypeDag result = Objects.requireNonNull(
                checkedOperands.get(0), "operand DAG");
        for (int index = 1; index < checkedOperands.size(); index++) {
            result = combine(
                    kind,
                    result,
                    checkedOperands.get(index))
                    .result();
        }
        return result;
    }

    public List<List<DependentColumnEvidence>> alternatives() {
        return alternatives;
    }

    public GraphType relationType() {
        return relationType;
    }

    public Optional<GraphType> commonAncestorType() {
        return Optional.ofNullable(commonAncestorType);
    }

    public int arity() {
        return arity;
    }

    public StructuralKey structuralKey() {
        return structuralKey;
    }

    /**
     * Runtime proof that this normalized family is contained in a stored
     * carrier through the exact source occurrence's parser-owned parent paths.
     * The empty family is accepted only when that same live occurrence proves
     * its arity and emptiness.
     */
    boolean isParserAuthenticatedSubfamilyOf(
            GraphType carrier,
            ExactAlloyType sourceAuthority) {
        Objects.requireNonNull(carrier, "carrier");
        ExactAlloyType authority = Objects.requireNonNull(
                sourceAuthority, "sourceAuthority");
        if (!authority.hasParserAuthenticatedAncestry()
                || (authority.kind() != ExactAlloyType.Kind.RELATION
                        && authority.kind()
                                != ExactAlloyType.Kind.EMPTY_RELATION)) {
            return false;
        }
        DependentTypeDag authenticated = fromExactAlloyType(authority);
        if (!sameOccurrenceEvidenceAs(authenticated)
                || !AlloyTypeBridge.isRelationFamily(carrier)
                || AlloyTypeBridge.relationArity(carrier) != arity) {
            return false;
        }
        if (alternatives.isEmpty()) {
            return true;
        }
        List<GraphType> carrierAlternatives =
                AlloyTypeBridge.relationAlternatives(carrier);
        for (List<DependentColumnEvidence> candidate : alternatives) {
            boolean covered = false;
            for (GraphType carrierAlternative : carrierAlternatives) {
                if (productCoveredBy(candidate, carrierAlternative)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                return false;
            }
        }
        return true;
    }

    private static boolean productCoveredBy(
            List<DependentColumnEvidence> candidate,
            GraphType carrier) {
        if (carrier.kind() != GraphType.Kind.RELATION
                || candidate.size() != carrier.arguments().size()) {
            return false;
        }
        for (int column = 0; column < candidate.size(); column++) {
            DependentColumnEvidence evidence = candidate.get(column);
            if (!evidence.hasParserModuleAuthority()
                    || evidence.ancestorIndex(
                            carrier.arguments().get(column)) < 0) {
                return false;
            }
        }
        return true;
    }

    private static List<List<DependentColumnEvidence>> normalize(
            List<? extends List<DependentColumnEvidence>> sourceAlternatives) {
        Objects.requireNonNull(sourceAlternatives, "alternatives");
        if (sourceAlternatives.isEmpty()) {
            throw new IllegalArgumentException(
                    "A dependent relation family must not be empty");
        }
        List<List<DependentColumnEvidence>> copied = new ArrayList<>();
        int arity = -1;
        for (List<DependentColumnEvidence> source : sourceAlternatives) {
            List<DependentColumnEvidence> product = List.copyOf(
                    Objects.requireNonNull(source, "relation alternative"));
            if (product.isEmpty()) {
                throw new IllegalArgumentException(
                        "A dependent relation alternative must not be nullary");
            }
            if (arity < 0) {
                arity = product.size();
            } else if (product.size() != arity) {
                throw new IllegalArgumentException(
                        "A dependent relation family has mixed arity");
            }
            for (DependentColumnEvidence column : product) {
                Objects.requireNonNull(column, "dependent column");
            }
            copied.add(product);
        }
        requireCompatibleAuthority(copied);
        DependentChainTheory.requireConsistentHierarchy(copied);

        Map<GraphType, List<DependentColumnEvidence>> unique =
                new LinkedHashMap<>();
        for (List<DependentColumnEvidence> product : copied) {
            GraphType type = productType(product);
            List<DependentColumnEvidence> previous = unique.get(type);
            if (previous == null) {
                unique.put(type, product);
                continue;
            }
            if (!previous.equals(product)) {
                throw new IllegalArgumentException(
                        "One exact relation alternative has conflicting ancestry evidence");
            }
            unique.put(type, preferAuthenticated(previous, product));
        }

        List<List<DependentColumnEvidence>> candidates =
                new ArrayList<>(unique.values());
        List<List<DependentColumnEvidence>> antichain = new ArrayList<>();
        for (int candidateIndex = 0;
                candidateIndex < candidates.size();
                candidateIndex++) {
            List<DependentColumnEvidence> candidate =
                    candidates.get(candidateIndex);
            boolean absorbed = false;
            for (int otherIndex = 0;
                    otherIndex < candidates.size();
                    otherIndex++) {
                if (candidateIndex == otherIndex) {
                    continue;
                }
                if (productSubtypeOrEqual(
                        candidate, candidates.get(otherIndex))) {
                    absorbed = true;
                    break;
                }
            }
            if (!absorbed) {
                antichain.add(candidate);
            }
        }
        antichain.sort(Comparator.comparing(DependentTypeDag::productType));
        List<List<DependentColumnEvidence>> frozen = new ArrayList<>();
        for (List<DependentColumnEvidence> product : antichain) {
            frozen.add(Collections.unmodifiableList(new ArrayList<>(product)));
        }
        return Collections.unmodifiableList(frozen);
    }

    private static List<DependentColumnEvidence> preferAuthenticated(
            List<DependentColumnEvidence> left,
            List<DependentColumnEvidence> right) {
        boolean leftAuthenticated = left.stream().anyMatch(
                DependentColumnEvidence::hasParserModuleAuthority);
        boolean rightAuthenticated = right.stream().anyMatch(
                DependentColumnEvidence::hasParserModuleAuthority);
        return !leftAuthenticated && rightAuthenticated ? right : left;
    }

    private static boolean productSubtypeOrEqual(
            List<DependentColumnEvidence> specific,
            List<DependentColumnEvidence> general) {
        if (specific.size() != general.size()) {
            return false;
        }
        boolean strict = false;
        for (int index = 0; index < specific.size(); index++) {
            DependentColumnEvidence left = specific.get(index);
            DependentColumnEvidence right = general.get(index);
            if (left.exactColumn().equals(right.exactColumn())) {
                continue;
            }
            DependentBoundaryCorrespondence proof;
            try {
                proof = DependentBoundaryCorrespondence.derive(left, right);
            } catch (DependentBoundaryCorrespondence.UnsupportedCorrespondence exception) {
                return false;
            }
            if (proof.rule()
                    != DependentBoundaryCorrespondence.Rule.LEFT_SUBTYPE_OF_RIGHT) {
                return false;
            }
            strict = true;
        }
        return strict;
    }

    private static DependentColumnEvidence moreSpecific(
            DependentColumnEvidence left,
            DependentColumnEvidence right,
            DependentBoundaryCorrespondence proof) {
        return switch (proof.rule()) {
            case EXACT -> preferAuthenticated(List.of(left), List.of(right)).get(0);
            case LEFT_SUBTYPE_OF_RIGHT -> left;
            case RIGHT_SUBTYPE_OF_LEFT -> right;
            case DISJOINT_BRANCHES -> throw new IllegalArgumentException(
                    "Disjoint dependent columns have no intersection carrier");
        };
    }

    private static void requireCompatibleAuthority(
            List<? extends List<DependentColumnEvidence>> left,
            List<? extends List<DependentColumnEvidence>> right) {
        List<List<DependentColumnEvidence>> combined = new ArrayList<>();
        combined.addAll(left);
        combined.addAll(right);
        requireCompatibleAuthority(combined);
        // Validate the complete input ledger before JOIN or intersection can
        // consume the columns that carry a conflicting parent path.
        DependentChainTheory.requireConsistentHierarchy(combined);
    }

    private static void requireCompatibleAuthority(
            List<? extends List<DependentColumnEvidence>> alternatives) {
        DependentColumnEvidence authority = null;
        for (List<DependentColumnEvidence> product : alternatives) {
            for (DependentColumnEvidence column : product) {
                if (!column.hasParserModuleAuthority()) {
                    continue;
                }
                if (authority == null) {
                    authority = column;
                } else if (!authority.sharesParserModuleAuthorityWith(column)) {
                    throw new IllegalArgumentException(
                            "A dependent type DAG mixes parser-module authorities");
                }
            }
        }
    }

    private static GraphType relationFamilyType(
            List<? extends List<DependentColumnEvidence>> alternatives) {
        List<GraphType> products = alternatives.stream()
                .map(DependentTypeDag::productType)
                .sorted()
                .toList();
        return products.size() == 1
                ? products.get(0)
                : GraphType.constructor("AlloyRelationUnion", products);
    }

    private static GraphType productType(
            List<DependentColumnEvidence> product) {
        return GraphType.relation(product.stream()
                .map(DependentColumnEvidence::exactColumn)
                .toList());
    }

    private static GraphType commonAncestorType(
            List<? extends List<DependentColumnEvidence>> alternatives) {
        if (alternatives.size() == 1) {
            return productType(alternatives.get(0));
        }
        List<GraphType> commonColumns = new ArrayList<>();
        int arity = alternatives.get(0).size();
        for (int column = 0; column < arity; column++) {
            List<GraphType> firstPath = alternatives.get(0).get(column).ancestry();
            GraphType common = null;
            for (GraphType candidate : firstPath) {
                boolean present = true;
                for (int alternative = 1;
                        alternative < alternatives.size();
                        alternative++) {
                    if (!alternatives.get(alternative).get(column)
                            .ancestry().contains(candidate)) {
                        present = false;
                        break;
                    }
                }
                if (present) {
                    common = candidate;
                    break;
                }
            }
            if (common == null) {
                return null;
            }
            commonColumns.add(common);
        }
        return GraphType.relation(commonColumns);
    }

    private static StructuralKey productKey(
            String tag,
            List<DependentColumnEvidence> product) {
        return StructuralKey.branch(
                tag,
                product.stream()
                        .map(DependentColumnEvidence::structuralKey)
                        .toList());
    }

    boolean sameOccurrenceEvidenceAs(DependentTypeDag other) {
        if (other == null
                || alternatives.size() != other.alternatives.size()
                || !relationType.equals(other.relationType)
                || !Objects.equals(commonAncestorType, other.commonAncestorType)) {
            return false;
        }
        for (int alternative = 0; alternative < alternatives.size(); alternative++) {
            List<DependentColumnEvidence> left = alternatives.get(alternative);
            List<DependentColumnEvidence> right = other.alternatives.get(alternative);
            if (left.size() != right.size()) {
                return false;
            }
            for (int column = 0; column < left.size(); column++) {
                if (!left.get(column).sameOccurrenceEvidenceAs(right.get(column))) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DependentTypeDag
                && alternatives.equals(((DependentTypeDag) other).alternatives)
                && relationType.equals(((DependentTypeDag) other).relationType)
                && Objects.equals(
                        commonAncestorType,
                        ((DependentTypeDag) other).commonAncestorType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(alternatives, relationType, commonAncestorType);
    }

    @Override
    public String toString() {
        return structuralKey.stableString();
    }
}
